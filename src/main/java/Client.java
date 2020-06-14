import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
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
import java.util.HashMap;
import java.util.Map;

class Packet {
    public static final byte SIZE_HEAD = 1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;
    
    public UnsafeBuffer head;
    public UnsafeBuffer body;
    boolean fromSingleBuffer;
    
    public Packet() {
        head = new UnsafeBuffer(new byte[SIZE_HEAD]);
        body = new UnsafeBuffer();
        fromSingleBuffer = false;
    }
    public Packet(DirectBuffer directBuffer) {
        head = new UnsafeBuffer(directBuffer, 0, SIZE_HEAD);
        body = new UnsafeBuffer(directBuffer, SIZE_HEAD, directBuffer.capacity() - SIZE_HEAD);
        fromSingleBuffer = true;
    }
    public int getBodyLength() { return fromSingleBuffer ? body.capacity() - SIZE_HEAD : body.capacity(); }
    public byte getType() { return head.getByte(0); }
    public void setType(byte type) { head.putByte(0, type); }
    // public void setTimeTriggered(long timeTriggered) { head.putLong(1, timeTriggered); }
}

interface Subscriber {
    void take(Packet packet);
}

class Camera {
    public Camera(Dimension dimension, Sender sender, Window window) throws VideoCaptureException {
        VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            Packet packet = new Packet();
            packet.setType(Packet.TYPE_VIDEO);
            // packet.setTimeTriggered(actionEvent.getWhen());
            
            BufferedImage bufferedImage = new BufferedImage((int) dimension.getWidth(), (int) dimension.getHeight(), BufferedImage.TYPE_INT_ARGB);
            ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try { ImageIO.write(bufferedImage, "png", byteArrayOutputStream); }
            catch (Exception exception) { exception.printStackTrace(); }
            packet.body.wrap(byteArrayOutputStream.toByteArray());
            
            sender.take(packet);
            window.take(packet);
        }).start();
    }
}

class Microphone {
    public Microphone(AudioFormat audioFormat, Sender sender, Speaker speaker) throws LineUnavailableException {
        TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            Packet packet = new Packet();
            packet.setType(Packet.TYPE_AUDIO);
            // packet.setTimeTriggered(actionEvent.getWhen());

            packet.body.wrap(new byte[targetDataLine.available()]);
            targetDataLine.read(packet.body.byteArray(), 0, packet.body.capacity());

//            sender.take(packet);
            speaker.take(packet);
        }).start();
    }
}

class Receiver {
    Aeron aeron;
    String address;
    Subscription subscription;

    public Receiver(Aeron aeron, String address, Speaker speaker, Window window) {
        this.aeron = aeron;
        this.address = address;
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 0);
        new Thread(() -> {
            FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                Packet packet = new Packet(buffer);
                if (packet.getType() == Packet.TYPE_AUDIO) {
                    speaker.take(packet);
                    return;
                }
                window.take(packet);
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
        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 0);
        try {
            Thread.sleep(1000);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}

class Sender implements Subscriber {
    Publication publication;

    public Sender(Aeron aeron) {
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 0);
    }

    @Override
    public void take(Packet packet) {
        long outcome = publication.offer(packet.head, 0, packet.head.capacity(), packet.body, 0, packet.body.capacity());
        if (outcome < 0) System.out.print(outcome);
    }
    
    public void addDestination(String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }
}

class Speaker implements Subscriber {
    SourceDataLine sourceDataLine;

    public Speaker(AudioFormat audioFormat) throws LineUnavailableException {
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
    }
    
    @Override
    public void take(Packet packet) {
        byte[] bytes = new byte[packet.getBodyLength()];
        packet.body.getBytes(0, bytes);
        sourceDataLine.write(bytes, 0, bytes.length);
    }
}

class Window implements Subscriber {
    JFrame jFrame = new JFrame();
    JLabel jLabel;
    long id;
    Map<Long, JLabel> idToJLabel = new HashMap<>();

    public Window(Dimension dimension) {
        jFrame.setLayout(new GridLayout(1, 3));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setVisible(true);
    }

    @Override
    public void take(Packet packet) {
        BufferedImage bufferedImage = null;
        try {
            bufferedImage = ImageIO.read(new DirectBufferInputStream(packet.body));
            if (bufferedImage == null) return;
        } catch (IOException exception) {
            exception.printStackTrace();
            return;
        }
        id = 0L; // ((long) streamId << 32) + (long) sessionId;
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
    static MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    Sender sender;

    public Client(String address) throws LineUnavailableException, VideoCaptureException {
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        Aeron aeron = Aeron.connect(context);
        AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
        Dimension dimension = new Dimension(320, 240);

        sender = new Sender(aeron);
        Speaker speaker = new Speaker(audioFormat);
        Window window = new Window(dimension);

        Camera camera = new Camera(dimension, sender, window);
        Microphone microphone = new Microphone(audioFormat, sender, speaker);
        Receiver receiver = new Receiver(aeron, address, speaker, window);
    }

    public void addDestination(String address) {
        sender.addDestination(address);
    }

    public static void main(String[] arguments) throws InterruptedException, LineUnavailableException, VideoCaptureException {
        String[] addresses = {"localhost:20000", "localhost:20001", "localhost:20002",};
        Client[] clients = {new Client(addresses[0]), new Client(addresses[1]), new Client(addresses[2]),};
        clients[0].addDestination(addresses[1]);
        clients[0].addDestination(addresses[2]);
        clients[1].addDestination(addresses[0]);
        clients[1].addDestination(addresses[2]);
        clients[2].addDestination(addresses[0]);
        clients[2].addDestination(addresses[1]);
        while (true);
    }
}