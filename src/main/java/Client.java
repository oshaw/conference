import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.Header;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.io.DirectBufferInputStream;
import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import java.util.regex.Pattern;

class Address {
    public static long stringToLong(final String input) {
        long output = 0;
        for (String octet : ip(input).split(Pattern.quote("."))) {
            output = output << 8 | Integer.parseInt(octet);
        }
        return output << 16 + port(input);
    }

    public static InetSocketAddress stringToInetSocketAddress(final String input) {
        return new InetSocketAddress(ip(input), port(input));
    }
    
    private static String ip(final String input) { return input.substring(0, input.indexOf(':')); }
    
    private static short port(final String input) { return Short.parseShort(input.substring(input.indexOf(':') + 1)); }
}

class Logging {
    public static final Logger CLIENT = logger(Client.class, Level.ALL);
    public static final Logger CAMERA = logger(Camera.class, Level.ALL);
    public static final Logger MICROPHONE = logger(Microphone.class, Level.ALL);
    public static final Logger SENDER = logger(Sender.class, Level.ALL);
    public static final Logger RECEIVER = logger(Receiver.class, Level.ALL);
    public static final Logger SPEAKER = logger(Speaker.class, Level.ALL);
    public static final Logger WINDOW = logger(Window.class, Level.ALL);
    
    private static Handler handler;
    
    private static Logger logger(final Class clazz, final Level level) {
        if (handler == null) {
            handler = new ConsoleHandler();
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.ALL);
        }
        Logger logger = Logger.getLogger(clazz.getName());
        logger.setLevel(level);
        logger.addHandler(handler);
        return logger;
    }
}

class Packet extends UnsafeBuffer {
    public static final byte SIZE_METADATA = 1 + 4 + 8 + 8 + 3;
    public static final byte TYPE_AUDIO      = (byte) 0b100000000;
    public static final byte TYPE_VIDEO      = (byte) 0b010000000;
    public static final byte TYPE_JOIN       = (byte) 0b001000000;
    public static final byte TYPE_JOIN_REPLY = (byte) 0b000100000;
    public static final byte TYPE_LEAVE      = (byte) 0b000010000;
    
    public static Factory<Packet> factory = new Factory<Packet>() {@Override Packet create() { return new Packet(); }};

    public byte type() { return getByte(capacity() - SIZE_METADATA); }
    public int length() { return getInt(capacity() - SIZE_METADATA + 1); }
    public long address() { return getLong(capacity() - SIZE_METADATA + 1 + 4); }
    public long time() { return getLong(capacity() - SIZE_METADATA + 1 + 4 + 8 + 2); }
    
    public Packet setType(final byte type) { putByte(capacity() - SIZE_METADATA, type); return this; }
    public Packet setLength(final int length) { putInt(capacity() - SIZE_METADATA + 1, length); return this; }
    public Packet setAddress(final long address) { putLong(capacity() - SIZE_METADATA + 1 + 4, address); return this; }
    public Packet setTime(final long time) { putLong(capacity() - SIZE_METADATA + 1 + 4 + 8 + 2, time); return this; }
}

class Tuple<A, B> { A first; B second; public Tuple(A a, B b) { first = a; second = b; }}

class RingBuffer<T> {
    private final T[] array;
    private final AtomicInteger ticketNext = new AtomicInteger(0);
    private final int mask;
    private final int size;

    private final AtomicInteger producerIndex = new AtomicInteger(0);
    private final Map<Integer, AtomicInteger> consumerTicketToIndex = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    protected RingBuffer(final Factory<T> factory, final int size) {
        array = (T[]) new Object[size];
        mask = size - 1;
        this.size = size;
        for (int index = 0; index < size; index += 1) array[index] = factory.create();
    }

    private int consumersMinimumIndex() {
        int index = Integer.MAX_VALUE;
        for (AtomicInteger atomicInteger : consumerTicketToIndex.values()) index = Math.min(index, atomicInteger.get());
        return index;
    }

    public T claim() {
        while (producerIndex.get() == consumersMinimumIndex() + size);
        return array[producerIndex.get() & mask];
    }

    public void commit() {
        producerIndex.incrementAndGet();
    }

    public T acquire(final int ticket) {
        if (consumerTicketToIndex.get(ticket).get() == producerIndex.get()) return null;
        return array[consumerTicketToIndex.get(ticket).get() & mask];
    }

    public void release(final int ticket) {
        consumerTicketToIndex.get(ticket).incrementAndGet();
    }

    public int subscribe() {
        int ticket = ticketNext.getAndIncrement();
        consumerTicketToIndex.put(ticket, new AtomicInteger(Math.max(0, producerIndex.get() - size)));
        return ticket;
    }
}

abstract class Factory<T> { abstract T create(); }

abstract class Daemon {
    private final Timer timer;
    private final Thread thread;
    
    Daemon(final int delay) {
        if (delay > 0) {
            timer = new Timer(delay, (ActionEvent actionEvent) -> run());
            thread = null;
            return;
        }
        timer = null;
        thread = new Thread(() -> { while (true) run(); });
    }

    protected void start() {
        if (timer != null) {
            timer.start();
            return;
        }
        thread.start();
    }
    
    abstract protected void run();
}

abstract class Producer extends Daemon {
    public final RingBuffer<Packet> buffer;
    
    Producer(final int delay) {
        super(delay);
        buffer = new RingBuffer<>(Packet.factory, 64);
    }
    
    @Override protected void run() { produce(); }
    
    protected abstract void produce();
}

abstract class Consumer extends Daemon {
    private final Set<Tuple<RingBuffer<Packet>, Integer>> tuplesBufferTicket = ConcurrentHashMap.newKeySet();
    private final byte mask;

    Consumer(final int delay, final byte mask) {
        super(delay);
        this.mask = mask;
    }

    public void subscribe(final Producer producer) {
        tuplesBufferTicket.add(new Tuple<RingBuffer<Packet>, Integer>(producer.buffer, producer.buffer.subscribe()));
    }
    
    public void subscribe(final RingBuffer<Packet> buffer) {
        tuplesBufferTicket.add(new Tuple<RingBuffer<Packet>, Integer>(buffer, buffer.subscribe()));
    }
    
    @Override protected void run() {
        Packet packet;
        for (final Tuple<RingBuffer<Packet>, Integer> tuple : tuplesBufferTicket) {
            packet = tuple.first.acquire(tuple.second);
            if (packet != null) {
                if ((packet.type() & mask) != 0) consume(packet);
                tuple.first.release(tuple.second);
            }
        }
    }

    protected abstract void consume(final Packet packet);
}

class Camera extends Producer {
    final long address;
    final byte[] bytesPadding = new byte[7 + Packet.SIZE_METADATA];
    final Dimension dimension;
    final VideoCapture videoCapture;
    
    public Camera(final Dimension dimension, final int framesPerSecond, final String address) throws VideoCaptureException {
        super(1000 / framesPerSecond);
        this.address = Address.stringToLong(address);
        this.dimension = dimension;
        videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        start();
    }

    @Override protected void produce() {
        final long time = System.nanoTime();
        final BufferedImage bufferedImage;
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final Packet packet = buffer.claim();
        
        bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
        try { ImageIO.write(bufferedImage, "png", stream); }
        catch (Exception exception) { Logging.CAMERA.log(Level.WARNING, exception.toString(), exception); return; }
        stream.write(bytesPadding, 0, BitUtil.align(stream.size(), 8) - stream.size() + Packet.SIZE_METADATA);
        
        packet.wrap(stream.toByteArray());
        packet.setType(Packet.TYPE_VIDEO).setLength(packet.capacity()).setAddress(address).setTime(time);
        buffer.commit();
    }
}

class Microphone extends Producer {
    final long address;
    final TargetDataLine targetDataLine;
    
    public Microphone(final AudioFormat audioFormat, final int framesPerSecond, final String address) throws LineUnavailableException {
        super(1000 / framesPerSecond);
        this.address = Address.stringToLong(address);
        targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        start();
    }
    
    @Override protected void produce() {
        final long time = System.nanoTime();
        final Packet packet = buffer.claim();
        final int length = targetDataLine.available();
        
        packet.wrap(new byte[BitUtil.align(length, 8) + Packet.SIZE_METADATA]);
        packet.setType(Packet.TYPE_AUDIO).setLength(length).setAddress(address).setTime(time);
        targetDataLine.read(packet.byteArray(), 0, length);
        buffer.commit();
    }
}

class Sender extends Consumer {
    private final DatagramSocket datagramSocket;
    private final Publication publication;

    public Sender(final Aeron aeron, final String address) throws SocketException {
        super(0, (byte) 0b11000000);
        datagramSocket = new DatagramSocket(Address.stringToInetSocketAddress(address));
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
        start();
    }

    public void addDestination(final String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }
    
    @Override protected void consume(final Packet packet) {
        final long outcome = multicast(packet);
        // if (outcome < -1) Logging.SENDER.log(Level.WARNING, "publication.offer() = {0}", outcome);
    }
    
    public long unicast(final String address, final Packet packet) {
        return 0L;
    }
    
    public long multicast(final Packet packet) {
        return publication.offer(packet);
    }
}

class Receiver extends Producer {
    private final Aeron aeron;
    private final String address;
    private final FragmentAssembler fragmentAssembler;
    private Subscription subscription;
    
    public Receiver(final Aeron aeron, final String address) {
        super(0);
        this.aeron = aeron;
        this.address = address;
        fragmentAssembler = new FragmentAssembler(this::receive, 0, true);
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        start();
    }
    
    @Override protected void produce() {
        subscription.poll(fragmentAssembler, 100);
        if (subscription.hasNoImages()) reconnect();
    }
    
    private void receive(final DirectBuffer buffer, final int offset, final int length, final Header header) {
        final Packet packet = this.buffer.claim();
        packet.wrap(new byte[length]);
        buffer.getBytes(offset, packet, 0, length);
        this.buffer.commit();
    };
    
    private void reconnect() {
        subscription.close();
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
    }
}

class Speaker extends Consumer {
    private final AudioFormat audioFormat;
    private final Long2ObjectHashMap<RingBuffer<Packet>> addressToBuffer = new Long2ObjectHashMap<>();
    
    public Speaker(final AudioFormat audioFormat) {
        super(0, Packet.TYPE_AUDIO);
        this.audioFormat = audioFormat;
        start();
    }
    
    @Override protected void consume(final Packet packet) {
        final byte[] bytes = new byte[packet.capacity()];
        final long address = packet.address();
        RingBuffer<Packet> buffer;
        
        if (!addressToBuffer.containsKey(address)) {
            final Line line;
            try { line = new Line(); } catch (LineUnavailableException exception) { exception.printStackTrace(); return; }
            buffer = new RingBuffer<>(Packet.factory, 4);
            line.subscribe(buffer);
            addressToBuffer.put(address, buffer);
        }
        
        packet.getBytes(0, bytes);
        buffer = addressToBuffer.get(address);
        buffer.claim().wrap(bytes);
        buffer.commit();
    }
    
    class Line extends Consumer {
        private final SourceDataLine line;

        Line() throws LineUnavailableException {
            super(0, Packet.TYPE_AUDIO);
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            line.open(audioFormat);
            line.start();
            start();
        }
        
        @Override protected void consume(Packet packet) {
            line.write(packet.byteArray(), 0, packet.length());
        }
    }
}

class Window extends Consumer {
    private final Dimension dimension;
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> addressToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final String address) {
        super(0, Packet.TYPE_VIDEO);
        this.dimension = dimension;
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setTitle(address);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final Packet packet) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(packet, 0, packet.length())); }
        catch (IOException exception) { Logging.WINDOW.log(Level.WARNING, exception.toString(), exception); return; }
        if (bufferedImage == null) { Logging.WINDOW.log(Level.WARNING, "bufferedImage == null"); return; }
        
        final long address = packet.address();
        if (!addressToJLabel.containsKey(address)) {
            addressToJLabel.put(address, new JLabel());
            addressToJLabel.get(address).setIcon(new ImageIcon());
            jFrame.setSize((int) dimension.getWidth() * addressToJLabel.size(), (int) dimension.getHeight());
            jFrame.getContentPane().add(addressToJLabel.get(address));
        }
        ((ImageIcon) addressToJLabel.get(address).getIcon()).setImage(bufferedImage);
        jFrame.revalidate();
        jFrame.repaint();
    }
}

class Call {
    private final ConcurrentHashMap.KeySetView<String, Boolean> participants = ConcurrentHashMap.newKeySet();
    public final String host;
    
    public Call(final String host) {
        this.host = host;
    }

    private void addParticipant(final String address) {
        participants.add(address);
    }

    private void removeParticipant(final String address) {
        participants.remove(address);
    }
}

public class Client {
    private static final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    private Call call;
    private final String address;
    private final Sender sender;
    
    public Client(final String address) throws LineUnavailableException, VideoCaptureException {
        this.address = address;
        
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        final Aeron aeron = Aeron.connect(context);
        final AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        final Dimension dimension = new Dimension(320, 240);
        final int framesPerSecond = 30;

        sender = new Sender(aeron);
        final Speaker speaker = new Speaker(audioFormat);
        final Window window = new Window(dimension, address);

        final Camera camera = new Camera(dimension, framesPerSecond, address);
        final Microphone microphone = new Microphone(audioFormat, framesPerSecond, address);
        final Receiver receiver = new Receiver(aeron, address);

        sender.subscribe(camera);
        sender.subscribe(microphone);
        speaker.subscribe(receiver);
        window.subscribe(camera);
        window.subscribe(receiver);
    }
    
    public void host() {
        call = new Call(address);
    }
    
    public void join(String address) {
        Packet packet = Packet.factory.create()
            .setType(Packet.TYPE_JOIN)
            .setAddress(Address.stringToLong(this.address));
        sender.unicast(address, );
        call = new Call(address);
    }
    
    public void leave() {
        
    }
    
    public static void main(final String[] arguments) throws LineUnavailableException, VideoCaptureException {
        final String[] addresss = {"127.0.0.1:20000", "127.0.0.1:20001", "127.0.0.1:20002",};
        final Client[] clients = {new Client(addresss[0]), new Client(addresss[1]), new Client(addresss[2]),};
        clients[0].address();
        clients[1].join(clients[0].address);
        clients[2].join(clients[0].address);
    }
}