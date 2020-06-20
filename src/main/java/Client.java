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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.agrona.concurrent.broadcast.BroadcastBufferDescriptor.TRAILER_LENGTH;

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
    
    public static byte type(DirectBuffer buffer, int offset, int length) { return buffer.getByte(offset + length - SIZE); }
    public static int host(DirectBuffer buffer, int offset, int length) { return buffer.getInt(offset + length - SIZE + 1); }
    public static short port(DirectBuffer buffer, int offset, int length) { return buffer.getShort(offset + length - SIZE + 1 + 4); }
    public static long time(DirectBuffer buffer, int offset, int length) { return buffer.getLong(offset + length - SIZE + 1 + 4 + 2); }
    
    public static void setType(MutableDirectBuffer buffer, int length, byte type) { buffer.putByte(length - SIZE, type); }
    public static void setHost(MutableDirectBuffer buffer, int length, int address) { buffer.putInt(length - SIZE + 1, address); }
    public static void setPort(MutableDirectBuffer buffer, int length, short port) { buffer.putShort(length - SIZE + 1 + 4, port); }
    public static void setTime(MutableDirectBuffer buffer, int length, long time) { buffer.putLong(length - SIZE + 1 + 4 + 2, time); }
}

abstract class Producer {
    public final BlockingBroadcastTransmitter transmitter = new BlockingBroadcastTransmitter(
        new UnsafeBuffer(new byte[(int) (Math.pow(2, 22) + TRAILER_LENGTH)]));
    public final byte type;
    private final Timer timer;
    private final Thread thread;
    
    Producer(final byte type, final int delay) {
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
    private final Set<BlockingBroadcastReceiver> receivers = ConcurrentHashMap.newKeySet();
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
        receivers.add(new BlockingBroadcastReceiver(producer.transmitter));
    }
    
    private void run() {
        for (final BlockingBroadcastReceiver receiver : receivers) {
            if (receiver.receiveNext()
                    && (type == Metadata.TYPE_ALL || type == Metadata.type(receiver.buffer(), receiver.offset(), receiver.length()))) {
                consume(receiver.buffer(), receiver.offset(), receiver.length());
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

    protected abstract void consume(final DirectBuffer buffer, final int offset, final int length);
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
        final UnsafeBuffer buffer;
        
        bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
        try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
        catch (Exception exception) { exception.printStackTrace(); }
        byteArrayOutputStream.write(0);
        buffer = new UnsafeBuffer(byteArrayOutputStream.toByteArray());
        
        Metadata.setType(buffer, buffer.capacity(), type);
        Metadata.setHost(buffer, buffer.capacity(), host);
        Metadata.setPort(buffer, buffer.capacity(), port);
        Metadata.setTime(buffer, buffer.capacity(), time);
        transmitter.transmit(1, buffer, 0, buffer.capacity());
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
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[targetDataLine.available() + Metadata.SIZE]);
        
        targetDataLine.read(buffer.byteArray(), 0, buffer.capacity() - Metadata.SIZE);
        
        Metadata.setType(buffer, buffer.capacity(), type);
        Metadata.setHost(buffer, buffer.capacity(), host);
        Metadata.setPort(buffer, buffer.capacity(), port);
        Metadata.setTime(buffer, buffer.capacity(), time);
        transmitter.transmit(1, buffer, 0, buffer.capacity());
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

    @Override protected void consume(final DirectBuffer buffer, final int offset, final int length) {
        final long outcome = publication.offer(buffer, offset, length);
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
            transmitter.transmit(1, buffer, offset, length);
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
    
    @Override protected void consume(final DirectBuffer buffer, final int offset, final int length) {
        sourceDataLine.write(buffer.byteArray(), offset, length - Metadata.SIZE);
    }
}

class Window extends Consumer {
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> idToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final String address) {
        super(Metadata.TYPE_VIDEO, 0);
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(address);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final DirectBuffer buffer, final int offset, final int length) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(buffer, offset, length - Metadata.SIZE)); }
        catch (IOException exception) { return; }
        if (bufferedImage == null) return;

        final long id = Metadata.host(buffer, offset, length) << 16 | Metadata.port(buffer, offset, length);
        if (!idToJLabel.containsKey(id)) {
            idToJLabel.put(id, new JLabel());
            idToJLabel.get(id).setIcon(new ImageIcon());
            jFrame.getContentPane().add(idToJLabel.get(id));
        }
        ((ImageIcon) idToJLabel.get(id).getIcon()).setImage(bufferedImage);
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