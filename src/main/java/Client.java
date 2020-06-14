import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

class Type {
    final public static byte AUDIO = 0;
    final public static byte VIDEO = 1;
}

class Camera {
    public Camera(Dimension dimension, Sender sender, Window window) throws VideoCaptureException {
        VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            long timeTriggered = System.nanoTime();
            BufferedImage bufferedImage = new BufferedImage(
                (int) dimension.getWidth(),
                (int) dimension.getHeight(),
                BufferedImage.TYPE_INT_ARGB
            );
            ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try {
                ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
            byte[] bytes = byteArrayOutputStream.toByteArray();
            sender.take(Type.VIDEO, timeTriggered, bytes);
            window.take(0, 0, bytes);
        }).start();
    }
}

class Microphone {
    public Microphone(AudioFormat audioFormat, Sender sender) throws LineUnavailableException {
        TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            long timeTriggered = System.nanoTime();
            byte[] bytes = new byte[targetDataLine.available()];
            targetDataLine.read(bytes, 0, bytes.length);
            sender.take(Type.AUDIO, timeTriggered, bytes);
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
                long timeReceived = System.nanoTime();
                byte[] bytes = new byte[length - (1 + 8 + 8)];
                buffer.getBytes(offset + (1 + 8 + 8), bytes);
                switch (buffer.getByte(offset)) {
                    case Type.AUDIO: {
                        speaker.take(bytes);
                        break;
                    }
                    case Type.VIDEO: {
                        window.take(header.streamId(), header.sessionId(), bytes);
                        break;
                    }
                }
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

class Sender {
    Publication publication;

    public Sender(Aeron aeron) {
        publication = aeron.addPublication("aeron:udp?control-mode=manual", 0);
    }

    public void take(byte type, long timeProduction, byte[] bytes) {
        UnsafeBuffer unsafeBuffer = new UnsafeBuffer(ByteBuffer.allocate(1 + 8 + 8 + bytes.length));
        unsafeBuffer.putByte(0, type);
        unsafeBuffer.putLong(1, timeProduction);
        unsafeBuffer.putBytes(1 + 8 + 8, bytes);
        unsafeBuffer.putLong(1 + 8, System.nanoTime());
        long outcome = publication.offer(unsafeBuffer);
        if (outcome < 0) System.out.print(outcome);
    }

    public void addDestination(String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }
}

class Speaker {
    SourceDataLine sourceDataLine;

    public Speaker(AudioFormat audioFormat) throws LineUnavailableException {
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
        sourceDataLine.open(audioFormat);
        sourceDataLine.start();
    }

    public void take(byte[] bytes) {
        sourceDataLine.write(bytes, 0, bytes.length);
    }
}

class Window {
    JFrame jFrame = new JFrame();
    JLabel jLabel;
    long id;
    Map<Long, JLabel> idToJLabel = new HashMap<>();

    public Window(Dimension dimension) {
        jFrame.setLayout(new GridLayout(1, 3));
        jFrame.setSize((int) dimension.getWidth() * 3, (int) dimension.getHeight());
        jFrame.setVisible(true);
    }

    public void take(int streamId, int sessionId, byte[] bytes) {
        BufferedImage bufferedImage = null;
        try {
            bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bufferedImage == null) return;
        } catch (IOException exception) {
            exception.printStackTrace();
            return;
        }
        id = ((long) streamId << 32) + (long) sessionId;
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
        Microphone microphone = new Microphone(audioFormat, sender);
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