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
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class Addressing {
    public static String longToString(long input) {
        return longToHost(input) + ':' + String.valueOf(longToPort(input));
    }
    
    public static InetSocketAddress longToInetSocketAddress(long input) {
        return new InetSocketAddress(longToHost(input), longToPort(input));
    }
    
    public static long stringToLong(final String input) {
        long output = 0;
        for (String octet : input.substring(0, input.indexOf(':')).split(Pattern.quote("."))) {
            output = output << 8 | Integer.parseInt(octet);
        }
        return output << 16 | Short.parseShort(input.substring(input.indexOf(':') + 1));
    }

    public static String longToHost(long input) {
        final StringBuilder builder = new StringBuilder();
        input >>= 16;
        for (int index = 0; index < 4; index += 1) {
            builder.insert(0, input & 0b11111111);
            if (index != 3) {
                builder.insert(0, '.');
                input >>= 8;
            }
        }
        return builder.toString();
    }

    public static short longToPort(final long input) {
        return (short) input;
    }
}

class Logging {
    private static ConcurrentHashMap<String, Logger> nameToLogger = new ConcurrentHashMap<>();
    
    public static Logger of(final Object object) {
        return of(object.getClass());
    }
    
    public static Logger of(final Class clazz) {
        if (nameToLogger.size() == 0) System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");

        if (!nameToLogger.containsKey(clazz.getName())) {
            final Logger logger = Logger.getLogger(clazz.getName());
            logger.setLevel(Level.ALL);
            nameToLogger.put(clazz.getName(), logger);
        }
        return nameToLogger.get(clazz.getName());
    }
}

class Streaming {
    public static void writeLong(final OutputStream stream, long input) throws IOException {
        for (int index = 0; index <= 7; index += 1) {
            stream.write((byte) (input & 0xFF));
            input >>= 8;
        }
    }
    
    public static long readLong(final byte[] bytes, final int offset) {
        long output = 0;
        for (int index = offset + 7; index >= offset; index -= 1) {
            output <<= 8;
            output |= bytes[index] & 0xFF;
        }
        return output;
    }
}

class Packet extends UnsafeBuffer {
    public static final int SIZE_METADATA = BitUtil.align(1 + 4 + 8 + 8, 8);
    
    public static final byte TYPE_AUDIO      = (byte) 0b100000000;
    public static final byte TYPE_VIDEO      = (byte) 0b010000000;
    public static final byte TYPE_JOIN       = (byte) 0b001000000;
    public static final byte TYPE_LEAVE      = (byte) 0b000100000;
    
    public static Packet factory() { return new Packet(); };
    
    public byte type() { return getByte(capacity() - SIZE_METADATA); }
    public int length() { return getInt(capacity() - SIZE_METADATA + 1); }
    public long addressUDP() { return getLong(capacity() - SIZE_METADATA + 1 + 4); }
    public long time() { return getLong(capacity() - SIZE_METADATA + 1 + 4 + 8 + 2); }
    
    public Packet setType(final byte type) { putByte(capacity() - SIZE_METADATA, type); return this; }
    public Packet setLength(final int length) { putInt(capacity() - SIZE_METADATA + 1, length); return this; }
    public Packet setAddressUDP(final long addressUDP) { putLong(capacity() - SIZE_METADATA + 1 + 4, addressUDP); return this; }
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
        for (int index = 0; index < size; index += 1) array[index] = factory.factory();
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

    public interface Factory<T> { T factory(); }
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

    protected void stop() {
        if (timer != null) {
            timer.stop();
            return;
        }
        thread.stop();
    };
    
    abstract protected void run();
}

abstract class Producer extends Daemon {
    public final RingBuffer<Packet> buffer;
    
    Producer(final int delay) {
        super(delay);
        buffer = new RingBuffer<>(Packet::factory, 64);
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
    final long addressUDP;
    final byte[] bytesPadding = new byte[7 + Packet.SIZE_METADATA];
    final Dimension dimension;
    final VideoCapture videoCapture;
    
    public Camera(final Dimension dimension, final int framesPerSecond, final long addressUDP) throws VideoCaptureException {
        super(1000 / framesPerSecond);
        this.addressUDP = addressUDP;
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
        catch (Exception exception) { Logging.of(this).warning(exception.toString()); return; }
        stream.write(bytesPadding, 0, BitUtil.align(stream.size(), 8) - stream.size() + Packet.SIZE_METADATA);
        
        packet.wrap(stream.toByteArray());
        packet.setType(Packet.TYPE_VIDEO).setLength(packet.capacity()).setAddressUDP(addressUDP).setTime(time);
        buffer.commit();
    }
}

class Microphone extends Producer {
    final long addressUDP;
    final TargetDataLine targetDataLine;
    
    public Microphone(final AudioFormat audioFormat, final int framesPerSecond, final long addressUDP) throws LineUnavailableException {
        super(1000 / framesPerSecond);
        this.addressUDP = addressUDP;
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
        packet.setType(Packet.TYPE_AUDIO).setLength(length).setAddressUDP(addressUDP).setTime(time);
        targetDataLine.read(packet.byteArray(), 0, length);
        buffer.commit();
    }
}

class Speaker extends Consumer {
    private final AudioFormat audioFormat;
    private final Long2ObjectHashMap<Tuple<RingBuffer<Packet>, Line>> addressUDPToBufferAndLine = new Long2ObjectHashMap<>();
    
    public Speaker(final AudioFormat audioFormat) {
        super(0, Packet.TYPE_AUDIO);
        this.audioFormat = audioFormat;
        start();
    }
    
    @Override protected void consume(final Packet packet) {
        final byte[] bytes = new byte[packet.capacity()];
        final long address = packet.addressUDP();
        RingBuffer<Packet> buffer;
        
        if (!addressUDPToBufferAndLine.containsKey(address)) {
            final Line line;
            try { line = new Line(); } catch (LineUnavailableException exception) { exception.printStackTrace(); return; }
            buffer = new RingBuffer<>(Packet::factory, 4);
            line.subscribe(buffer);
            addressUDPToBufferAndLine.put(address, new Tuple<>(buffer, line));
        }
        
        packet.getBytes(0, bytes);
        buffer = addressUDPToBufferAndLine.get(address).first;
        buffer.claim().wrap(bytes);
        buffer.commit();
    }

    public void removeAddressUDP(final long addressUDP) {
        if (addressUDPToBufferAndLine.containsKey(addressUDP)) {
            final Tuple<RingBuffer<Packet>, Line> tuple = addressUDPToBufferAndLine.get(addressUDP);
            tuple.second.stop();
            addressUDPToBufferAndLine.remove(addressUDP);
        }
    }

    private class Line extends Consumer {
        private final SourceDataLine line;

        Line() throws LineUnavailableException {
            super(0, Packet.TYPE_AUDIO);
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            line.open(audioFormat);
            line.start();
            start();
        }
        
        @Override protected void consume(final Packet packet) {
            line.write(packet.byteArray(), 0, packet.length());
        }
        
        @Override protected void stop() {
            super.stop();
            line.stop();
        }
    }
}

class Window extends Consumer {
    private final Dimension dimension;
    private final JFrame jFrame = new JFrame();
    private final Long2ObjectHashMap<JLabel> addressUDPToJLabel = new Long2ObjectHashMap<>();

    public Window(final Dimension dimension, final long addressUDP) {
        super(0, Packet.TYPE_VIDEO);
        this.dimension = dimension;
        jFrame.setLayout(new GridLayout(1, 1));
        jFrame.setTitle(Addressing.longToString(addressUDP));
        jFrame.setVisible(true);
        start();
    }

    @Override protected void consume(final Packet packet) {
        final BufferedImage bufferedImage;
        try { bufferedImage = ImageIO.read(new DirectBufferInputStream(packet, 0, packet.length())); }
        catch (IOException exception) { Logging.of(this).warning(exception.toString()); return; }
        if (bufferedImage == null) { Logging.of(this).warning("bufferedImage == null"); return; }
        
        final long address = packet.addressUDP();
        if (!addressUDPToJLabel.containsKey(address)) {
            addressUDPToJLabel.put(address, new JLabel());
            addressUDPToJLabel.get(address).setIcon(new ImageIcon());
            jFrame.getContentPane().add(addressUDPToJLabel.get(address));
        }
        ((ImageIcon) addressUDPToJLabel.get(address).getIcon()).setImage(bufferedImage);
        repaint();
    }

    public void removeAddressUDP(final long addressUDP) {
        if (addressUDPToJLabel.containsKey(addressUDP)) {
            jFrame.getContentPane().remove(addressUDPToJLabel.get(addressUDP));
            addressUDPToJLabel.remove(addressUDP);
            repaint();
        }
    }
    
    private void repaint() {
        jFrame.setSize((int) dimension.getWidth() * addressUDPToJLabel.size(), (int) dimension.getHeight());
        jFrame.revalidate();
        jFrame.repaint();
    }
}

class Call {
    public final ConcurrentHashMap.KeySetView<Long, Boolean> addressUDPs = ConcurrentHashMap.newKeySet();
    public final long addressUDPHost;
    
    public Call(final long addressUDPHost) {
        this.addressUDPHost = addressUDPHost;
    }

    public void addAddressUDP(final long addressUDP) {
        addressUDPs.add(addressUDP);
    }

    public void removeAddressUDP(final long addressUDP) {
//        addressUDPs.remove(addressUDP);
    }
}

class TCP {
    public static void multicast(final Set<Long> addressTCPs, final Packet packet) throws IOException {
        for (final long addressTCP : addressTCPs) unicast(addressTCP, packet);
    }
    
    public static byte[] unicast(final long addressTCP, final Packet packet) throws IOException {
        Logging.of(TCP.class).info("destination=" + Addressing.longToString(addressTCP));
        final Socket socket = new Socket(Addressing.longToHost(addressTCP), Addressing.longToPort(addressTCP));
        final OutputStream outputStream = socket.getOutputStream();
        outputStream.write(packet.byteArray());
        outputStream.write('\n');
        
        final BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        final byte[] bytes = reader.readLine().getBytes();
        socket.close();
        return bytes;
    }
    
    static class Server {
        final Handler handler;
        final ServerSocket serverSocket;

        public Server(long addressTCP, Handler handler) throws IOException {
            this.handler = handler;
            serverSocket = new ServerSocket();
            serverSocket.bind(Addressing.longToInetSocketAddress(addressTCP));
            Logging.of(this).info("origin=" + Addressing.longToString(addressTCP));
            new Thread(() -> {
                while (true) {
                    try {
                        final Packet packet = new Packet();
                        final Socket socket = serverSocket.accept();
                        final BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        packet.wrap(reader.readLine().getBytes());
                        handler.handle(socket, packet);
                    } catch (Exception exception) {
                        Logging.of(TCP.Server.class).warning(exception.toString());
                    }
                }
            }).start();
        }
    }

    public interface Handler { void handle(final Socket socket, final Packet packet) throws IOException; }
}

class UDP {
    static class Broadcaster extends Consumer {
        private final long addressUDP;
        private final Publication publication;

        public Broadcaster(final Aeron aeron, final long addressUDP) {
            super(0, (byte) 0b11000000);
            this.addressUDP = addressUDP;
            publication = aeron.addPublication("aeron:udp?control-mode=manual", 1);
            start();
        }

        public void addAddressUDP(final long addressUDP) {
            Logging.of(this).info(
                "origin=" + Addressing.longToString(this.addressUDP)
                + " destination=" + Addressing.longToString(addressUDP)
            );
            publication.addDestination("aeron:udp?endpoint=" + Addressing.longToString(addressUDP));
        }

        @Override protected void consume(final Packet packet) {
            final long outcome = publication.offer(packet);
            // if (outcome < -1) Logging.SENDER.log(Level.WARNING, "publication.offer() = {0}", outcome);
        }
    }

    static class Receiver extends Producer {
        private final Aeron aeron;
        private final long address;
        private final FragmentAssembler fragmentAssembler;
        private Subscription subscription;

        public Receiver(final Aeron aeron, final long address) {
            super(0);
            this.aeron = aeron;
            this.address = address;
            fragmentAssembler = new FragmentAssembler(this::receive, 0, true);
            subscription = aeron.addSubscription("aeron:udp?endpoint=" + Addressing.longToString(address), 1);
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
            subscription = aeron.addSubscription("aeron:udp?endpoint=" + Addressing.longToString(address), 1);
            try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
        }
    }
}

public class Participant {
    private static final MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    private Call call;
    private final TCP.Server server;

    private final long addressTCP;
    private final long addressUDP;

    private final Camera camera;
    private final Microphone microphone;
    private final UDP.Broadcaster broadcaster;
    private final UDP.Receiver receiver;
    private final Speaker speaker;
    private final Window window;

    public Participant(final long addressTCP, final long addressUDP) throws Exception {
        this.addressTCP = addressTCP;
        this.addressUDP = addressUDP;
        server = new TCP.Server(addressTCP, this::handle);

        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        final Aeron aeron = Aeron.connect(context);
        final AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        final Dimension dimension = new Dimension(320, 240);
        final int framesPerSecond = 30;

        broadcaster = new UDP.Broadcaster(aeron, addressUDP);
        speaker = new Speaker(audioFormat);
        window = new Window(dimension, addressUDP);
        camera = new Camera(dimension, framesPerSecond, addressUDP);
        microphone = new Microphone(audioFormat, framesPerSecond, addressUDP);
        receiver = new UDP.Receiver(aeron, addressUDP);

        broadcaster.subscribe(camera);
        broadcaster.subscribe(microphone);
        speaker.subscribe(receiver);
        window.subscribe(camera);
        window.subscribe(receiver);
    }
    
    public void host() throws IOException {
        leave();
        call = new Call(addressUDP);
    }
    
    public void join(final long addressTCPHost) throws IOException {
        leave();
        
        final Packet packet = new Packet();
        packet.wrap(new byte[Packet.SIZE_METADATA]);
        packet.setType(Packet.TYPE_JOIN).setAddressUDP(this.addressUDP);
        
        final byte[] bytes = TCP.unicast(addressTCPHost, packet);
        for (int index = bytes.length - 1 - 7; index >= 0; index -= 8) {
            final long addressUDP = Streaming.readLong(bytes, index);
            broadcaster.addAddressUDP(addressUDP);
            if (index == bytes.length - 1 - 7) {
                call = new Call(addressUDP);
                continue;
            }
            call.addAddressUDP(addressUDP);
        }
    }

    public void leave() throws IOException {
        if (call != null) {
            final Packet packet = new Packet();
            packet.wrap(new byte[Packet.SIZE_METADATA]);
            packet.setType(Packet.TYPE_LEAVE).setAddressUDP(addressUDP).setTime(System.nanoTime());
            TCP.multicast(call.addressUDPs, packet);
            call = null;
        }
    }

    private void handle(final Socket socket, final Packet packet) throws IOException {
        switch (packet.type()) {
            case Packet.TYPE_JOIN: handleJoin(socket, packet);
            case Packet.TYPE_LEAVE: handleLeave(socket, packet);
        }
    }

    private void handleJoin(final Socket socket, final Packet packet) throws IOException {
        if (call != null) {
            final long addressUDPJoiner = packet.addressUDP();
            call.addAddressUDP(addressUDPJoiner);
            broadcaster.addAddressUDP(addressUDPJoiner);

            final OutputStream stream = socket.getOutputStream();
            if (call.addressUDPHost == this.addressUDP) {
                Streaming.writeLong(stream, call.addressUDPHost);
                for (long addressUDP : call.addressUDPs) {
                    if (addressUDP != addressUDPJoiner) Streaming.writeLong(stream, addressUDP);
                }
            }
            stream.write('\n');
        }
    }

    private void handleLeave(final Socket socket, final Packet packet) {
        if (call != null) {
            call.removeAddressUDP(packet.addressUDP());
//            speaker.removeAddress(packet.address());
//            window.removeAddress(packet.address());
        }
    }

    public static void main(final String[] arguments) throws Exception {
        final int SIZE_PARTICIPANTS = 3;
        final Participant[] participants = new Participant[SIZE_PARTICIPANTS];
        for (int index = 0; index < participants.length; index += 1) {
            participants[index] = new Participant(
                Addressing.stringToLong("127.0.0.1:" + (20000 + (index * 2))),
                Addressing.stringToLong("127.0.0.1:" + (20000 + ((index * 2) + 1)))
            );
        }
        participants[0].host();
        for (int index = 1; index < participants.length; index += 1) {
            participants[index].join(participants[0].addressTCP);
        }
    }
}