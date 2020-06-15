import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.ObjectHashSet;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

interface Factory<T> {
    T create();
}

abstract class Publisher {
    public RingBuffer<Packet> ringBuffer;
    
    protected Publisher() {
        ringBuffer = new RingBuffer<>(Packet.factoryPacket, 64);
    }

    static class RingBuffer<T> {
        private final T[] array;
        private final AtomicInteger idNext = new AtomicInteger(0);
        private final int mask;
        private final int size;

        private final AtomicInteger producerIndex = new AtomicInteger(0);
        private final Map<Integer, AtomicInteger> subscriberTicketToIndex = new ConcurrentHashMap<>();
        
        @SuppressWarnings("unchecked")
        public RingBuffer(Factory<T> factory, int size) {
            array = (T[]) new Object[size];
            mask = size - 1;
            this.size = size;
            for (int index = 0; index < size; index += 1) array[index] = factory.create();
        }

        public void commit() {
            while (producerIndex.get() == subscribersMinimumIndex() - size);
            producerIndex.incrementAndGet();
        }

        public T current() {
            return array[producerIndex.get() & mask];
        }

        public int subscribe() {
            int id = idNext.getAndIncrement();
            subscriberTicketToIndex.put(id, new AtomicInteger(Math.max(0, producerIndex.get() - size)));
            return id;
        }

        public T take(int ticket) {
            if (subscriberTicketToIndex.get(ticket).get() == producerIndex.get()) return null;
            return array[subscriberTicketToIndex.get(ticket).getAndIncrement() & mask];
        }

        public boolean valid(int id) {
            return producerIndex.get() < subscriberTicketToIndex.get(id).get() - 1;
        }
        
        private int subscribersMinimumIndex() {
            int index = Integer.MAX_VALUE;
            for (AtomicInteger atomicInteger : subscriberTicketToIndex.values()) index = Math.min(index, atomicInteger.get());
            return index;
        }
    }
}

abstract class Subscriber {
    private final Set<Tuple<Publisher, Integer>> tuplesPublisherTicket = new ObjectHashSet<>();

    abstract byte packetType();
    
    abstract void take(Packet packet);

    public void subscribe(Publisher publisher) {
        tuplesPublisherTicket.add(new Tuple<>(publisher, publisher.ringBuffer.subscribe()));
    }

    protected void start() {
        new Thread(() -> {
            Iterator<Tuple<Publisher, Integer>> iterator;
            Packet packet;
            Tuple<Publisher, Integer> tuple;
            while (true) {
                iterator = tuplesPublisherTicket.iterator();
                while (iterator.hasNext()) {
                    tuple = iterator.next();
                    packet = tuple.first.ringBuffer.take(tuple.second);
                    if (packet != null && (packetType() == Packet.TYPE_ALL || packetType() == packet.type())) take(packet);
                }
            }
        }).start();
    }

    static class Tuple<A, B> {
        A first;
        B second;

        public Tuple(A a, B b) {
            first = a;
            second = b;
        }
    }
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
        head.wrap(directBuffer, offset, SIZE_HEAD);
        setStreamId(header.streamId());
        setSessionId(header.sessionId());
        
        body.wrap(directBuffer, offset + SIZE_HEAD, length - SIZE_HEAD);
        bodyLoadedLength = length - SIZE_HEAD;
        if (type() == TYPE_AUDIO && (bodyLoadedLength % 2 == 1)) {
            bodyLoadedLength = bodyLoadedLength;
        }
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

class Camera extends Publisher {
    public Camera(Dimension dimension) throws VideoCaptureException {
        VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            Packet packet = ringBuffer.current();
            packet.setType(Packet.TYPE_VIDEO);
            
            BufferedImage bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
            ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
            catch (Exception exception) { exception.printStackTrace(); }
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
            Packet packet = ringBuffer.current();
            packet.setType(Packet.TYPE_AUDIO);
            packet.body.wrap(new byte[targetDataLine.available()]);
            targetDataLine.read(packet.body.byteArray(), 0, packet.body.capacity());
            ringBuffer.commit();
        }).start();
    }
}

class Receiver extends Publisher {
    private final Aeron aeron;
    private final String address;
    private Subscription subscription;
    private IntHashSet sessionIds = new IntHashSet();

    public Receiver(Aeron aeron, String address) {
        this.aeron = aeron;
        this.address = address;
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 1);
        new Thread(() -> {
            FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                if (!sessionIds.contains(header.sessionId())) sessionIds.add(header.sessionId());
                System.out.println(address + " has " + sessionIds.size() + " sessionIds and " + subscription.images().size() + " Images");
                ringBuffer.current().load(buffer, offset, length, header);
                ringBuffer.commit();
            };
            FragmentAssembler fragmentAssembler = new FragmentAssembler(fragmentHandler);
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
        jFrame.setLayout(new GridLayout(1, 4));
        jFrame.setSize((int) dimension.getWidth() * 4, (int) dimension.getHeight());
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
        // sender.subscribe(microphone);
        // speaker.subscribe(receiver);
        // window.subscribe(camera);
        window.subscribe(receiver);
    }

    public void addDestination(String address) {
        sender.addDestination(address);
    }

    public static void main(String[] arguments) throws InterruptedException, LineUnavailableException, VideoCaptureException {
        String[] addresses = {"localhost:20000", "localhost:20001", "localhost:20002", "localhost:20003", "localhost:20004",};
        Client[] clients = {
            new Client(addresses[0]),
            new Client(addresses[1]),
            new Client(addresses[2]),
            new Client(addresses[3]),
            new Client(addresses[4]),
        };
        clients[0].addDestination(addresses[1]);
        clients[0].addDestination(addresses[2]);
        clients[0].addDestination(addresses[3]);
        clients[0].addDestination(addresses[4]);
        clients[1].addDestination(addresses[0]);
        clients[1].addDestination(addresses[2]);
        clients[1].addDestination(addresses[3]);
        clients[1].addDestination(addresses[4]);
        clients[2].addDestination(addresses[0]);
        clients[2].addDestination(addresses[1]);
        clients[2].addDestination(addresses[3]);
        clients[2].addDestination(addresses[4]);
        clients[3].addDestination(addresses[0]);
        clients[3].addDestination(addresses[1]);
        clients[3].addDestination(addresses[2]);
        clients[3].addDestination(addresses[4]);
        clients[4].addDestination(addresses[0]);
        clients[4].addDestination(addresses[1]);
        clients[4].addDestination(addresses[2]);
        clients[4].addDestination(addresses[3]);
    }
}