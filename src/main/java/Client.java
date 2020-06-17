import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.broadcast.BroadcastBufferDescriptor;
import org.agrona.concurrent.broadcast.BroadcastReceiver;
import org.agrona.concurrent.broadcast.BroadcastTransmitter;
import org.agrona.concurrent.broadcast.RecordDescriptor;
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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class Metadata {
    public static final byte SIZE = 8;
    public static final byte TYPE_ALL = -1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;
    public static final byte ORIGIN_LOCAL = 0;
    public static final byte ORIGIN_FOREIGN = 1;

    private static final long MASK_TYPE = (long) 1 << (SIZE * 8 - 1);
    private static final long MASK_ORIGIN = (long) 1 << (SIZE * 8) - 1;
    private static final long MASK_SESSION_ID = (long) Integer.MAX_VALUE << (SIZE * 8) - 3;
    
    public static void setType(UnsafeBuffer buffer, byte type) {
        byte metadata = buffer.getByte(buffer.capacity() - 1);
        if (type == TYPE_AUDIO) {
            buffer.putByte(buffer.capacity() - 1, (byte) (metadata & ~MASK_TYPE));
            return;
        }
        buffer.putByte(buffer.capacity() - 1, (byte) (metadata | MASK_TYPE));
    }
    
    public static void setOrigin(UnsafeBuffer buffer, byte origin) {
        
    }

    public static byte type(DirectBuffer buffer) {
        return (byte) (buffer.getByte(buffer.capacity() - 1) & MASK_TYPE >> 7);
    }
}

class BlockingBroadcastTransmitter extends BroadcastTransmitter {
    private final AtomicBuffer buffer;
    private final AtomicLong cursor;
    private final AtomicLong ticketNext = new AtomicLong(0);
    private final ConcurrentHashMap<Long, Long> receiverTicketToCursor = new ConcurrentHashMap<>();
    
    public BlockingBroadcastTransmitter(AtomicBuffer buffer) {
        super(buffer);
        this.buffer = buffer;
        cursor = new AtomicLong(calculateCursor());
    }
    
    public long addSubscriber() {
        final long ticket = ticketNext.getAndIncrement();
        receiverTicketToCursor.put(ticket, cursor.get());
        return ticket;
    }
    
    public AtomicBuffer buffer() { return buffer; }
    
    public void reportReceiveNext(long ticket) {
        receiverTicketToCursor.put(ticket, calculateCursorNext(receiverTicketToCursor.get(ticket)));
    }
    
    @Override public void transmit(int messageTypeId, DirectBuffer sourceBuffer, int sourceIndex, int length) {
        while (cursor.get() + length + 8 >= minimumReceiverCursor() + capacity());
        super.transmit(messageTypeId, sourceBuffer, sourceIndex, length);
        cursor.set(calculateCursor());
    }
    
    private long calculateCursor() {
        return buffer.getLongVolatile(capacity() + BroadcastBufferDescriptor.LATEST_COUNTER_OFFSET);
    }
    
    private long calculateCursorNext(long cursor) {
        return (long) BitUtil.align(buffer.getInt(RecordDescriptor.lengthOffset((int) cursor & capacity() - 1)), 8);
    }
    
    private long minimumReceiverCursor() {
        return Collections.min(receiverTicketToCursor.values());
    }
}

class BlockingBroadcastReceiver extends BroadcastReceiver {
    private final BlockingBroadcastTransmitter transmitter;
    private final long ticket;
    
    public BlockingBroadcastReceiver(BlockingBroadcastTransmitter transmitter) {
        super(transmitter.buffer());
        this.transmitter = transmitter;
        ticket = transmitter.addSubscriber();
    }

    @Override
    public boolean receiveNext() {
        boolean available = super.receiveNext();
        if (available) transmitter.reportReceiveNext(ticket);
        return available;
    }
}

abstract class Producer {
    public final BlockingBroadcastTransmitter transmitter = new BlockingBroadcastTransmitter(new UnsafeBuffer());
    public final byte type;
    private final Timer timer;
    private final Thread thread;
    
    Producer(byte type, int delay) {
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

    Consumer(byte type, int delay) {
        this.type = type;
        if (delay != 0) {
            timer = new Timer(delay, (ActionEvent actionEvent) -> run());
            thread = null;
            return;
        }
        timer = null;
        thread = new Thread(() -> { while (true) run(); });
    }
    
    public void subscribe(Producer producer) {
        receivers.add(new BlockingBroadcastReceiver(producer.transmitter));
    }
    
    private void run() {
        for (BlockingBroadcastReceiver receiver : receivers) {
            if (receiver.receiveNext() && (type == Metadata.TYPE_ALL || Metadata.type(receiver.buffer()) == type)) {
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

    protected abstract void consume(DirectBuffer buffer, int offset, int length);
}

class Camera extends Producer {
    Dimension dimension;
    VideoCapture videoCapture;
    
    public Camera(Dimension dimension) throws VideoCaptureException {
        super(Metadata.TYPE_VIDEO, 1000 / 30);
        this.dimension = dimension;
        videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        start();
    }

    @Override protected void produce() {
        final BufferedImage bufferedImage;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        
        bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
        try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
        catch (Exception exception) { exception.printStackTrace(); }
        byteArrayOutputStream.write(0);
        
        final UnsafeBuffer buffer = new UnsafeBuffer(byteArrayOutputStream.toByteArray());
        Metadata.setType(buffer, type);
        Metadata.setOrigin(buffer, Metadata.ORIGIN_LOCAL);
        transmitter.transmit(0, buffer, 0, buffer.capacity());
    }
}

class Microphone extends Producer {
    TargetDataLine targetDataLine;
    
    public Microphone(AudioFormat audioFormat) throws LineUnavailableException {
        super(Metadata.TYPE_AUDIO, 1000 / 30);
        targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
    }

    @Override protected void produce() {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[targetDataLine.available() + 1]);
        targetDataLine.read(buffer.byteArray(), 0, buffer.capacity() - 1);
        Metadata.setType(buffer, type);
        Metadata.setOrigin(buffer, Metadata.ORIGIN_LOCAL);
        transmitter.transmit(0, buffer, 0, buffer.capacity());
    }
}

class Sender extends Consumer {
    private final Publication publication;

    public Sender(Aeron aeron) {
        super(Metadata.TYPE_ALL, 0);
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
        start();
    }

    public void addDestination(String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }

    @Override protected void consume(DirectBuffer buffer, int offset, int length) {
        final long outcome = publication.offer(buffer, offset, length);
        // if (outcome < 0) System.out.println(outcome);
    }
}

class Receiver extends Producer {
    private final Aeron aeron;
    private final String address;
    private final FragmentAssembler fragmentAssembler;
    private Subscription subscription;
    
    public Receiver(Aeron aeron, String address) {
        super(Metadata.TYPE_ALL, 0);
        this.aeron = aeron;
        this.address = address;
        FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
            Metadata.setOrigin(buffer, Metadata.ORIGIN_FOREIGN);
            Metadata.setSessionId(buffer, header.sessionId());
            transmitter.transmit(Metadata.type(buffer), buffer, offset, length);
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
    
    public Speaker(AudioFormat audioFormat) throws LineUnavailableException {
        super(Metadata.TYPE_AUDIO, 0);
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
        start();
    }
    
    @Override protected void consume(DirectBuffer buffer, int offset, int length) {
        sourceDataLine.write(buffer.byteArray(), offset, length - Metadata.SIZE);
    }
}

class Window extends Consumer {
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> idToJLabel = new Long2ObjectHashMap<>();

    public Window(Dimension dimension, String address) {
        super(Metadata.TYPE_VIDEO, 0);
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(address);
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(DirectBuffer buffer, int offset, int length) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(buffer, offset, length - Metadata.SIZE)); }
        catch (IOException exception) { return; }
        if (bufferedImage == null) return;

        final long id = Metadata.sessionId(buffer) << 1 | Metadata.origin(buffer);
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

    public Client(String address) throws LineUnavailableException, VideoCaptureException {
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        Aeron aeron = Aeron.connect(context);
        AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        Dimension dimension = new Dimension(320, 240);

        sender = new Sender(aeron);
        Speaker speaker = new Speaker(audioFormat);
        Window window = new Window(dimension, address);

        Camera camera = new Camera(dimension);
        Microphone microphone = new Microphone(audioFormat);
        Receiver receiver = new Receiver(aeron, address);

        sender.subscribe(camera);
        sender.subscribe(microphone);
        speaker.subscribe(receiver);
        window.subscribe(camera);
        window.subscribe(receiver);
    }

    public void addDestination(String address) {
        sender.addDestination(address);
    }

    public static void main(String[] arguments) throws InterruptedException, LineUnavailableException, VideoCaptureException {
        String[] addresses = {"localhost:20000", "localhost:20001", "localhost:20002",};
        Client[] clients = {
            new Client(addresses[0]),
            new Client(addresses[1]),
            new Client(addresses[2]),
        };
        clients[0].addDestination(addresses[1]);
        clients[0].addDestination(addresses[2]);
        clients[1].addDestination(addresses[0]);
        clients[1].addDestination(addresses[2]);
        clients[2].addDestination(addresses[0]);
        clients[2].addDestination(addresses[1]);
    }
}