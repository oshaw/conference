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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

abstract class Factory<T> { abstract T create(); }

class Addressing {
    public static int hostToAddress(final String host) {
        int address = 0;
        for (String octet : host.substring(0, host.indexOf(':')).split(Pattern.quote("."))) {
            address = address << 8 | Integer.parseInt(octet);
        }
        return address;
    }
    
    public static short hostToPort(final String host) {
        return Short.parseShort(host.substring(host.indexOf(':') + 1, host.length()));
    }
}

class Packet extends UnsafeBuffer {
    public static final byte SIZE_METADATA = 1 + 4 + 4 + 2 + 8 + 5;
    public static final byte TYPE_ALL = -1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;
    
    public static Factory<Packet> factory = new Factory<Packet>() {@Override Packet create() { return new Packet(); }};

    public byte type() { return getByte(capacity() - SIZE_METADATA); }
    public int length() { return getInt(capacity() - SIZE_METADATA + 1); }
    public int address() { return getInt(capacity() - SIZE_METADATA + 1 + 4); }
    public short port() { return getShort(capacity() - SIZE_METADATA + 1 + 4 + 4); }
    public long time() { return getLong(capacity() - SIZE_METADATA + 1 + 4 + 4 + 2); }
    
    public void setType(final byte type) { putByte(capacity() - SIZE_METADATA, type); }
    public void setLength(final int length) { putInt(capacity() - SIZE_METADATA + 1, length); }
    public void setAddress(final int address) { putInt(capacity() - SIZE_METADATA + 1 + 4, address); }
    public void setPort(final short port) { putShort(capacity() - SIZE_METADATA + 1 + 4 + 4, port); }
    public void setTime(final long time) { putLong(capacity() - SIZE_METADATA + 1 + 4 + 4 + 2, time); }
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
    private final byte type;

    Consumer(final int delay, final byte type) {
        super(delay);
        this.type = type;
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
            packet = (Packet) tuple.first.acquire(tuple.second);
            if (packet != null) {
                if (type == Packet.TYPE_ALL || type == packet.type()) consume(packet);
                tuple.first.release(tuple.second);
            }
        }
    }

    protected abstract void consume(final Packet packet);
}

class Camera extends Producer {
    final int address;
    final byte[] bytesPadding = new byte[7 + Packet.SIZE_METADATA];
    final Dimension dimension;
    final short port;
    final VideoCapture videoCapture;
    
    public Camera(final Dimension dimension, final int framesPerSecond, final String host) throws VideoCaptureException {
        super(1000 / framesPerSecond);
        this.address = Addressing.hostToAddress(host);
        this.dimension = dimension;
        this.port = Addressing.hostToPort(host);
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
        catch (Exception exception) { exception.printStackTrace(); }
        stream.write(bytesPadding, 0, BitUtil.align(stream.size(), 8) - stream.size() + Packet.SIZE_METADATA);
        packet.wrap(stream.toByteArray());
        
        packet.setType(Packet.TYPE_VIDEO);
        packet.setLength(packet.capacity());
        packet.setAddress(address);
        packet.setPort(port);
        packet.setTime(time);
        buffer.commit();
    }
}

class Microphone extends Producer {
    final int address;
    final short port;
    final TargetDataLine targetDataLine;
    
    public Microphone(final AudioFormat audioFormat, final int framesPerSecond, final String host) throws LineUnavailableException {
        super(1000 / framesPerSecond);
        this.address = Addressing.hostToAddress(host);
        this.port = Addressing.hostToPort(host);
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
        packet.setType(Packet.TYPE_AUDIO);
        packet.setLength(length);
        packet.setAddress(address);
        packet.setPort(port);
        packet.setTime(time);
        
        targetDataLine.read(packet.byteArray(), 0, length);
        buffer.commit();
    }
}

class Sender extends Consumer {
    private final Publication publication;

    public Sender(final Aeron aeron) {
        super(0, Packet.TYPE_ALL);
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
        start();
    }

    public void addDestination(final String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }

    @Override protected void consume(final Packet packet) {
        final long outcome = publication.offer(packet);
        if (outcome < -1) System.out.println(outcome);
    }
}

class Receiver extends Producer {
    private final Aeron aeron;
    private final String address;
    private final FragmentAssembler fragmentAssembler;
    private Subscription subscription;
    
    public Receiver(final Aeron aeron, final String host) {
        super(Packet.TYPE_ALL);
        this.aeron = aeron;
        this.address = host;
        fragmentAssembler = new FragmentAssembler(this::receive, 0, true);
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + host, 1);
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
    private final Long2ObjectHashMap<RingBuffer<Packet>> hostToBuffer = new Long2ObjectHashMap<>();
    
    public Speaker(final AudioFormat audioFormat) {
        super(0, Packet.TYPE_AUDIO);
        this.audioFormat = audioFormat;
        start();
    }
    
    @Override protected void consume(final Packet packet) {
        final byte[] bytes = new byte[packet.capacity()];
        final long host = packet.address() << 16 | packet.port();
        RingBuffer<Packet> buffer;
        
        if (!hostToBuffer.containsKey(host)) {
            final Line line;
            try { line = new Line(); } catch (LineUnavailableException exception) { exception.printStackTrace(); return; }
            buffer = new RingBuffer<>(Packet.factory, 4);
            line.subscribe(buffer);
            hostToBuffer.put(host, buffer);
        }
        
        packet.getBytes(0, bytes);
        buffer = hostToBuffer.get(host);
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
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> hostToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final String host) {
        super(0, Packet.TYPE_VIDEO);
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(host);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final Packet packet) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(packet, 0, packet.length())); }
        catch (IOException exception) {
            // exception.printStackTrace();
            return;
        }
        if (bufferedImage == null) {
            // System.out.println("Window: bufferedImage == null");
            return;
        }
        
        final long host = packet.address() << 16 | packet.port();
        if (!hostToJLabel.containsKey(host)) {
            hostToJLabel.put(host, new JLabel());
            hostToJLabel.get(host).setIcon(new ImageIcon());
            jFrame.getContentPane().add(hostToJLabel.get(host));
        }
        ((ImageIcon) hostToJLabel.get(host).getIcon()).setImage(bufferedImage);
        jFrame.revalidate();
        jFrame.repaint();
    }
}

public class Client {
    private static final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    private final Sender sender;

    public Client(final String host) throws LineUnavailableException, VideoCaptureException {
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        final Aeron aeron = Aeron.connect(context);
        final AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        final Dimension dimension = new Dimension(320, 240);
        final int framesPerSecond = 30;

        sender = new Sender(aeron);
        final Speaker speaker = new Speaker(audioFormat);
        final Window window = new Window(dimension, host);

        final Camera camera = new Camera(dimension, framesPerSecond, host);
        final Microphone microphone = new Microphone(audioFormat, framesPerSecond, host);
        final Receiver receiver = new Receiver(aeron, host);

        sender.subscribe(camera);
        sender.subscribe(microphone);
        speaker.subscribe(receiver);
        window.subscribe(camera);
        window.subscribe(receiver);
    }

    public void addDestination(final String address) {
        sender.addDestination(address);
    }

    public static void main(final String[] arguments) throws InterruptedException, LineUnavailableException, VideoCaptureException {
        final String[] hosts = {"127.0.0.1:20000", "127.0.0.1:20001", "127.0.0.1:20002",};
        final Client[] clients = {new Client(hosts[0]), new Client(hosts[1]), new Client(hosts[2]),};
        clients[0].addDestination(hosts[1]);
        clients[0].addDestination(hosts[2]);
        clients[1].addDestination(hosts[0]);
        clients[1].addDestination(hosts[2]);
        clients[2].addDestination(hosts[0]);
        clients[2].addDestination(hosts[1]);
    }
}