import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

abstract class Factory<T> { abstract T create(); }

class Address {
    public static int stringToHost(final String address) {
        int host = 0;
        for (String octet : address.substring(0, address.indexOf(':')).split(Pattern.quote("."))) {
            host = host << 8 | Integer.parseInt(octet);
        }
        return host;
    }
    
    public static short stringToPort(final String address) {
        return Short.parseShort(address.substring(address.indexOf(':') + 1, address.length()));
    }
}

class Metadata {
    public static final byte SIZE = 1 + 4 + 2 + 8 + 1;
    public static final byte TYPE_ALL = -1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;
    
    public static byte type(final DirectBuffer buffer) { return buffer.getByte(buffer.capacity() - SIZE); }
    public static int host(final DirectBuffer buffer) { return buffer.getInt(buffer.capacity() - SIZE + 1); }
    public static short port(final DirectBuffer buffer) { return buffer.getShort(buffer.capacity() - SIZE + 1 + 4); }
    public static long time(final DirectBuffer buffer) { return buffer.getLong(buffer.capacity() - SIZE + 1 + 4 + 2); }
    
    public static void setType(final MutableDirectBuffer buffer, final byte type) { buffer.putByte(buffer.capacity() - SIZE, type); }
    public static void setHost(final MutableDirectBuffer buffer, final int address) { buffer.putInt(buffer.capacity() - SIZE + 1, address); }
    public static void setPort(final MutableDirectBuffer buffer, final short port) { buffer.putShort(buffer.capacity() - SIZE + 1 + 4, port); }
    public static void setTime(final MutableDirectBuffer buffer, final long time) { buffer.putLong(buffer.capacity() - SIZE + 1 + 4 + 2, time); }
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

abstract class Producer {
    public final byte type;
    public final RingBuffer<UnsafeBuffer> ringBuffer;
    private final Timer timer;
    private final Thread thread;
    
    Producer(final byte type, final int delay) {
        ringBuffer = new RingBuffer<>(new Factory<>() {@Override UnsafeBuffer create() { return new UnsafeBuffer(); }}, 64);
        this.type = type;
        if (delay > 0) {
            timer = new Timer(delay, (ActionEvent actionEvent) -> produce());
            thread = null;
            return;
        }
        timer = null;
        thread = new Thread(() -> { while (true) produce(); });
    }
    
    protected void start() {
        if (timer != null) {
            timer.start();
            return;
        }
        thread.start();
    }
    
    protected abstract void produce();
}

abstract class Consumer {
    private final Set<Tuple<Producer, Integer>> tuplesPublisherTicket = ConcurrentHashMap.newKeySet();
    private final byte type;
    private final Timer timer;
    private final Thread thread;

    Consumer(final byte type, final int delay) {
        this.type = type;
        if (delay != 0) {
            timer = new Timer(delay, (ActionEvent actionEvent) -> run());
            thread = null;
            return;
        }
        timer = null;
        thread = new Thread(() -> { while (true) run(); });
    }
    
    public void subscribe(final Producer producer) {
        tuplesPublisherTicket.add(new Tuple<Producer, Integer>(producer, producer.ringBuffer.subscribe()));
    }
    
    private void run() {
        Producer producer;
        int ticket;
        for (final Tuple<Producer, Integer> tuple : tuplesPublisherTicket) {
            producer = tuple.first;
            ticket = tuple.second;
            DirectBuffer buffer = producer.ringBuffer.acquire(ticket);
            if (buffer != null) {
                if (type == Metadata.TYPE_ALL || type == Metadata.type(buffer)) consume(buffer);
                producer.ringBuffer.release(ticket);
            }
        }
    }

    protected void start() {
        if (timer != null) {
            timer.start();
            return;
        }
        thread.start();
    }

    protected abstract void consume(final DirectBuffer buffer);
}

class Camera extends Producer {
    final Dimension dimension;
    final int host;
    final short port;
    final VideoCapture videoCapture;
    
    public Camera(final Dimension dimension, final String address) throws VideoCaptureException {
        super(Metadata.TYPE_VIDEO, 1000 / 30);
        this.host = Address.stringToHost(address);
        this.dimension = dimension;
        this.port = Address.stringToPort(address);
        videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        start();
    }

    @Override protected void produce() {
        final long time = System.nanoTime();
        final BufferedImage bufferedImage;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final MutableDirectBuffer buffer = ringBuffer.claim();
        
        bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
        try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
        catch (Exception exception) { exception.printStackTrace(); }
        byteArrayOutputStream.write(0);
        buffer.wrap(byteArrayOutputStream.toByteArray());
        
        Metadata.setType(buffer, type);
        Metadata.setHost(buffer, host);
        Metadata.setPort(buffer, port);
        Metadata.setTime(buffer, time);
        ringBuffer.commit();
    }
}

class Microphone extends Producer {
    final int host;
    final short port;
    final TargetDataLine targetDataLine;
    
    public Microphone(final AudioFormat audioFormat, final String address) throws LineUnavailableException {
        super(Metadata.TYPE_AUDIO, 1000 / 30);
        this.host = Address.stringToHost(address);
        this.port = Address.stringToPort(address);
        targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        start();
    }
    
    @Override protected void produce() {
        final long time = System.nanoTime();
        final UnsafeBuffer buffer = ringBuffer.claim();
        
        buffer.wrap(new byte[targetDataLine.available() + Metadata.SIZE]);
        targetDataLine.read(buffer.byteArray(), 0, buffer.capacity() - Metadata.SIZE);
        
        Metadata.setType(buffer, type);
        Metadata.setHost(buffer, host);
        Metadata.setPort(buffer, port);
        Metadata.setTime(buffer, time);
        ringBuffer.commit();
    }
}

class Sender extends Consumer {
    private final Publication publication;

    public Sender(final Aeron aeron) {
        super(Metadata.TYPE_ALL, 0);
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
        start();
    }

    public void addDestination(final String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }

    @Override protected void consume(final DirectBuffer buffer) {
        final long outcome = publication.offer(buffer);
        if (outcome < 0) System.out.println("Sender: " + outcome);
    }
}

class Receiver extends Producer {
    private final Aeron aeron;
    private final String address;
    private final FragmentAssembler fragmentAssembler;
    private Subscription subscription;
    
    public Receiver(final Aeron aeron, final String address) {
        super(Metadata.TYPE_ALL, 0);
        this.aeron = aeron;
        this.address = address;
        final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
            byte[] bytes = new byte[length];
            buffer.getBytes(0, bytes);
            ringBuffer.claim().wrap(bytes);
            ringBuffer.commit();
        };
        fragmentAssembler = new FragmentAssembler(fragmentHandler, 0, true);
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        start();
    }
    
    private void reconnect() {
        subscription.close();
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
    }
    
    @Override protected void produce() {
        subscription.poll(fragmentAssembler, 100);
        if (subscription.hasNoImages()) reconnect();
    }
}

class Speaker extends Consumer {
    private final SourceDataLine sourceDataLine;
    
    public Speaker(final AudioFormat audioFormat) throws LineUnavailableException {
        super(Metadata.TYPE_AUDIO, 0);
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
        start();
    }
    
    @Override protected void consume(final DirectBuffer buffer) {
        sourceDataLine.write(buffer.byteArray(), 0, buffer.capacity() - Metadata.SIZE);
    }
}

class Window extends Consumer {
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> addressToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final String address) {
        super(Metadata.TYPE_VIDEO, 0);
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(address);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final DirectBuffer buffer) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(buffer, 0, buffer.capacity() - Metadata.SIZE)); }
        catch (IOException exception) { return; }
        if (bufferedImage == null) return;

        final long address = Metadata.host(buffer) << 16 | Metadata.port(buffer);
        if (!addressToJLabel.containsKey(address)) {
            addressToJLabel.put(address, new JLabel());
            addressToJLabel.get(address).setIcon(new ImageIcon());
            jFrame.getContentPane().add(addressToJLabel.get(address));
        }
        ((ImageIcon) addressToJLabel.get(address).getIcon()).setImage(bufferedImage);
        jFrame.revalidate();
        jFrame.repaint();
    }
}

public class Client {
    private static final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    private final Sender sender;

    public Client(final String address) throws LineUnavailableException, VideoCaptureException {
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        final Aeron aeron = Aeron.connect(context);
        final AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        final Dimension dimension = new Dimension(320, 240);

        sender = new Sender(aeron);
        final Speaker speaker = new Speaker(audioFormat);
        final Window window = new Window(dimension, address);

        final Camera camera = new Camera(dimension, address);
        final Microphone microphone = new Microphone(audioFormat, address);
        final Receiver receiver = new Receiver(aeron, address);

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
        final String[] addresses = {"127.0.0.1:20000", "127.0.0.1:20001", "127.0.0.1:20002",};
        final Client[] clients = {new Client(addresses[0]), new Client(addresses[1]), new Client(addresses[2]),};
        clients[0].addDestination(addresses[1]);
        clients[0].addDestination(addresses[2]);
        clients[1].addDestination(addresses[0]);
        clients[1].addDestination(addresses[2]);
        clients[2].addDestination(addresses[0]);
        clients[2].addDestination(addresses[1]);
    }
}