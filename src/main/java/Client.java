import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
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
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

abstract class Factory<T> { abstract T create(); }

class Addressing {
    public static int stringToAddress(final String addressPort) {
        int address = 0;
        for (String octet : addressPort.substring(0, addressPort.indexOf(':')).split(Pattern.quote("."))) {
            address = address << 8 | Integer.parseInt(octet);
        }
        return address;
    }
    
    public static short stringToPort(final String addressPort) {
        return Short.parseShort(addressPort.substring(addressPort.indexOf(':') + 1, addressPort.length()));
    }
}

class Packet extends UnsafeBuffer {
    public static final byte SIZE_METADATA = 1 + 4 + 2 + 8 + 1;
    public static final byte TYPE_ALL = -1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;
    
    public static Factory<Packet> factory = new Factory<Packet>() {@Override Packet create() { return new Packet(); }};

    public byte type() { return getByte(capacity() - SIZE_METADATA); }
    public int address() { return getInt(capacity() - SIZE_METADATA + 1); }
    public short port() { return getShort(capacity() - SIZE_METADATA + 1 + 4); }
    public long time() { return getLong(capacity() - SIZE_METADATA + 1 + 4 + 2); }
    
    public int dataLength() { return capacity() - SIZE_METADATA; }
    
    public void setType(final byte type) { putByte(capacity() - SIZE_METADATA, type); }
    public void setAddress(final int address) { putInt(capacity() - SIZE_METADATA + 1, address); }
    public void setPort(final short port) { putShort(capacity() - SIZE_METADATA + 1 + 4, port); }
    public void setTime(final long time) { putLong(capacity() - SIZE_METADATA + 1 + 4 + 2, time); }
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
    public final RingBuffer<Packet> ringBuffer;
    
    Producer(final int delay) {
        super(delay);
        ringBuffer = new RingBuffer<>(Packet.factory, 64);
    }
    
    @Override protected void run() { produce(); }
    
    protected abstract void produce();
}

abstract class Consumer extends Daemon {
    private final Set<Tuple<Producer, Integer>> tuplesPublisherTicket = ConcurrentHashMap.newKeySet();
    private final byte type;

    Consumer(final int delay, final byte type) {
        super(delay);
        this.type = type;
    }
    
    public void subscribe(final Producer producer) {
        tuplesPublisherTicket.add(new Tuple<Producer, Integer>(producer, producer.ringBuffer.subscribe()));
    }
    
    @Override protected void run() {
        int ticket;
        Packet packet;
        Producer producer;
        for (final Tuple<Producer, Integer> tuple : tuplesPublisherTicket) {
            producer = tuple.first;
            ticket = tuple.second;
            packet = producer.ringBuffer.acquire(ticket);
            if (packet != null) {
                if (type == Packet.TYPE_ALL || type == packet.type()) consume(packet);
                producer.ringBuffer.release(ticket);
            }
        }
    }

    protected abstract void consume(final Packet metadata);
}

class Camera extends Producer {
    final int address;
    final Dimension dimension;
    final short port;
    final VideoCapture videoCapture;
    
    public Camera(final Dimension dimension, final int framesPerSecond, final String addressPort) throws VideoCaptureException {
        super(1000 / framesPerSecond);
        this.address = Addressing.stringToAddress(addressPort);
        this.dimension = dimension;
        this.port = Addressing.stringToPort(addressPort);
        videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        start();
    }

    @Override protected void produce() {
        final long time = System.nanoTime();
        final BufferedImage bufferedImage;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final Packet packet = ringBuffer.claim();
        
        bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
        try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
        catch (Exception exception) { exception.printStackTrace(); }
        packet.wrap(byteArrayOutputStream.toByteArray());
        
        packet.setType(Packet.TYPE_VIDEO);
        packet.setAddress(address);
        packet.setPort(port);
        packet.setTime(time);
        ringBuffer.commit();
    }
}

class Microphone extends Producer {
    final int address;
    final short port;
    final TargetDataLine targetDataLine;
    
    public Microphone(final AudioFormat audioFormat, final int framesPerSecond, final String addressPort) throws LineUnavailableException {
        super(1000 / framesPerSecond);
        this.address = Addressing.stringToAddress(addressPort);
        this.port = Addressing.stringToPort(addressPort);
        targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        start();
    }
    
    @Override protected void produce() {
        final long time = System.nanoTime();
        final Packet packet = ringBuffer.claim();
        packet.wrap(new byte[targetDataLine.available() + Packet.SIZE_METADATA]);
        packet.setType(Packet.TYPE_AUDIO);
        packet.setAddress(address);
        packet.setPort(port);
        packet.setTime(time);
        targetDataLine.read(packet.byteArray(), 0, packet.dataLength());
        ringBuffer.commit();
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
    
    public Receiver(final Aeron aeron, final String addressPort) {
        super(Packet.TYPE_ALL);
        this.aeron = aeron;
        this.address = addressPort;
        fragmentAssembler = new FragmentAssembler(this::receive, 0, true);
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + addressPort, 1);
        start();
    }
    
    @Override protected void produce() {
        subscription.poll(fragmentAssembler, 100);
        if (subscription.hasNoImages()) reconnect();
    }
    
    private void receive(final DirectBuffer buffer, final int offset, final int length, final Header header) {
        final Packet packet = ringBuffer.claim();
        packet.wrap(new byte[length]);
        buffer.getBytes(offset, packet, 0, length);
        ringBuffer.commit();
    };
    
    private void reconnect() {
        subscription.close();
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
    }
}

class Speaker extends Consumer {
    private final SourceDataLine sourceDataLine;
    
    public Speaker(final AudioFormat audioFormat) throws LineUnavailableException {
        super(0, Packet.TYPE_AUDIO);
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
        start();
    }
    
    @Override protected void consume(final Packet packet) {
        sourceDataLine.write(packet.byteArray(), 0, packet.dataLength());
    }
}

class Window extends Consumer {
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> addressPortToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final String addressPort) {
        super(0, Packet.TYPE_VIDEO);
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(addressPort);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final Packet packet) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(packet, 0, packet.dataLength())); }
        catch (IOException exception) { return; }
        if (bufferedImage == null) return;
        
        final long addressPort = packet.address() << 16 | packet.port();
        if (!addressPortToJLabel.containsKey(addressPort)) {
            addressPortToJLabel.put(addressPort, new JLabel());
            addressPortToJLabel.get(addressPort).setIcon(new ImageIcon());
            jFrame.getContentPane().add(addressPortToJLabel.get(addressPort));
        }
        ((ImageIcon) addressPortToJLabel.get(addressPort).getIcon()).setImage(bufferedImage);
        jFrame.revalidate();
        jFrame.repaint();
    }
}

public class Client {
    private static final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    private final Sender sender;

    public Client(final String addressPort) throws LineUnavailableException, VideoCaptureException {
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        final Aeron aeron = Aeron.connect(context);
        final AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        final Dimension dimension = new Dimension(320, 240);
        final int framesPerSecond = 30;

        sender = new Sender(aeron);
        final Speaker speaker = new Speaker(audioFormat);
        final Window window = new Window(dimension, addressPort);

        final Camera camera = new Camera(dimension, framesPerSecond, addressPort);
        final Microphone microphone = new Microphone(audioFormat, framesPerSecond, addressPort);
        final Receiver receiver = new Receiver(aeron, addressPort);

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
        final String[] addressPorts = {"127.0.0.1:20000", "127.0.0.1:20001", "127.0.0.1:20002",};
        final Client[] clients = {new Client(addressPorts[0]), new Client(addressPorts[1]), new Client(addressPorts[2]),};
        clients[0].addDestination(addressPorts[1]);
        clients[0].addDestination(addressPorts[2]);
        clients[1].addDestination(addressPorts[0]);
        clients[1].addDestination(addressPorts[2]);
        clients[2].addDestination(addressPorts[0]);
        clients[2].addDestination(addressPorts[1]);
    }
}