import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
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

interface Factory<T> {
    T create();
}

class Packet {
    public static final byte SIZE_HEAD = 1 + 4 + 4;
    public static final byte TYPE_ALL = 0;
    public static final byte TYPE_AUDIO = 1;
    public static final byte TYPE_VIDEO = 2;

    public UnsafeBuffer head;
    public UnsafeBuffer body;
    private int bodyLoadedLength;

    public Packet() {
        head = new UnsafeBuffer(new byte[SIZE_HEAD]);
        setStreamId(0);
        setSessionId(0);
        
        body = new UnsafeBuffer();
        bodyLoadedLength = -1;
    }
    
    public void load(DirectBuffer directBuffer, int offset, int length, Header header) {
        directBuffer.getBytes(offset, head, 0, SIZE_HEAD);
        setStreamId(header.streamId());
        setSessionId(header.sessionId());

        bodyLoadedLength = length - SIZE_HEAD;
        body = new UnsafeBuffer(new byte[bodyLoadedLength]);
        directBuffer.getBytes(SIZE_HEAD, body, 0, bodyLoadedLength);
    }
    
    public int bodyLength() { return (bodyLoadedLength != -1) ? bodyLoadedLength : body.capacity(); }
    public byte type() { return head.getByte(0); }
    public int streamId() { return head.getInt(1); }
    public int sessionId() { return head.getInt(1 + 4); }
    
    public void setType(byte type) { head.putByte(0, type); }
    public void setStreamId(int streamId) { head.putInt(1, streamId); }
    public void setSessionId(int sessionId) { head.putInt(1 + 4, sessionId); }

    public static FactoryPacket factoryPacket = new FactoryPacket();
    static class FactoryPacket implements Factory<Packet> {
        @Override public Packet create() { return new Packet(); }
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
        receiverTicketToCursor.put(ticket, calculateNextCursor(receiverTicketToCursor.get(ticket)));
    }
    
    @Override public void transmit(int messageTypeId, DirectBuffer sourceBuffer, int sourceIndex, int length) {
        while (cursor.get() + length + 8 >= minimumReceiverCursor() + capacity());
        super.transmit(messageTypeId, sourceBuffer, sourceIndex, length);
        cursor.set(calculateCursor());
    }
    
    private long calculateCursor() {
        return buffer.getLongVolatile(capacity() + BroadcastBufferDescriptor.LATEST_COUNTER_OFFSET);
    }
    
    private long calculateNextCursor(long cursor) {
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

abstract class Publisher {
    public final BlockingBroadcastTransmitter transmitter = new BlockingBroadcastTransmitter(new UnsafeBuffer());
}

abstract class Subscriber {
    private final Set<BlockingBroadcastReceiver> receivers = ConcurrentHashMap.newKeySet();

    abstract byte packetType();

    abstract void take(Packet packet);

    public void subscribe(Publisher publisher) {
        receivers.add(new BlockingBroadcastReceiver(publisher.transmitter));
    }

    protected void start() {
        new Thread(() -> {
            while (true) {
                for (BlockingBroadcastReceiver receiver : receivers) {
                    if (receiver.receiveNext()) take(receiver.buffer(), receiver.offset(), receiver.length());
                }
            }
        }).start();
    }
}

class Camera extends Publisher {
    public Camera(Dimension dimension) throws VideoCaptureException {
        VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            Packet packet = ringBuffer.claim();
            packet.setType(Packet.TYPE_VIDEO);
            
            BufferedImage bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
            ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
            catch (Exception exception) { exception.printStackTrace(); }
            
//            byte metadata = (byte) 0b10000000;
//            byteArrayOutputStream.write(metadata);
//            UnsafeBuffer unsafeBuffer = new UnsafeBuffer(byteArrayOutputStream.toByteArray());
//            BufferedImage bufferedImageOutput = null;
//            try { bufferedImageOutput = ImageIO.read(new DirectBufferInputStream(unsafeBuffer, 0, unsafeBuffer.capacity() - 1)); }
//            catch (IOException exception) { exception.printStackTrace(); }
            
            packet.body.wrap(byteArrayOutputStream.toByteArray());
            
            ringBuffer.commit();
        }).start();
    }
}

class Microphone extends Publisher {
    public Microphone(AudioFormat audioFormat) throws LineUnavailableException {
        TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            byte[] bytes = new byte[targetDataLine.available() + 1];
            targetDataLine.read(bytes, 0, bytes.length - 1);
            transmitter.transmit(1, new UnsafeBuffer(bytes), 0, bytes.length);
        }).start();
    }
}

class Receiver extends Publisher {
    private final String address;
    private final Aeron aeron;
    private Subscription subscription;

    public Receiver(Aeron aeron, String address) {
        this.aeron = aeron;
        this.address = address;
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        new Thread(() -> {
            FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                ringBuffer.claim().load(buffer, offset, length, header);
                ringBuffer.commit();
            };
            FragmentAssembler fragmentAssembler = new FragmentAssembler(fragmentHandler, 0, true);
            while (true) {
                subscription.poll(fragmentAssembler, 100);
                if (subscription.hasNoImages()) reconnect();
            }
        }).start();
    }

    private void reconnect() {
        subscription.close();
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        try {
            Thread.sleep(1000);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}

class Sender extends Subscriber {
    private final Publication publication;

    public Sender(Aeron aeron) {
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
        start();
    }

    @Override byte packetType() { return Packet.TYPE_ALL; }

    @Override public void take(Packet packet) {
        long outcome = publication.offer(packet.head, 0, packet.head.capacity(), packet.body, 0, packet.body.capacity());
        // if (outcome < 0) System.out.println(outcome);
    }

    public void addDestination(String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }
}

class Speaker extends Subscriber {
    private final SourceDataLine sourceDataLine;

    public Speaker(AudioFormat audioFormat) throws LineUnavailableException {
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
        start();
    }

    @Override byte packetType() { return Packet.TYPE_AUDIO; }

    @Override public void take(Packet packet) {
        byte[] bytes = new byte[packet.bodyLength()];
        packet.body.getBytes(0, bytes);
        sourceDataLine.write(bytes, 0, bytes.length);
    }
}

class Window extends Subscriber {
    private final JFrame jFrame = new JFrame();
    private JLabel jLabel;
    private long id;
    private final Long2ObjectHashMap<JLabel> idToJLabel = new Long2ObjectHashMap<>();

    public Window(Dimension dimension, String address) {
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setTitle(address);
        jFrame.setVisible(true);
        start();
    }

    @Override byte packetType() { return Packet.TYPE_VIDEO; }

    @Override public void take(Packet packet) {
        BufferedImage bufferedImage = null;
        try {
            bufferedImage = ImageIO.read(new DirectBufferInputStream(packet.body));
            if (bufferedImage == null) return;
        } catch (IOException exception) {
            // exception.printStackTrace();
            return;
        }
        id = ((long) packet.streamId()) << 32 + (long) packet.sessionId();
        if (!idToJLabel.containsKey(id)) {
            jLabel = new JLabel();
            jLabel.setIcon(new ImageIcon());
            jFrame.getContentPane().add(jLabel);
            idToJLabel.put(id, jLabel);
        }
        jLabel = idToJLabel.get(id);
        ((ImageIcon) jLabel.getIcon()).setImage(bufferedImage);
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