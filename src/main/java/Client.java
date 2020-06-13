import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;

public class Client {
    AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
    Dimension dimension = new Dimension(320, 240);
    static Aeron.Context context = new Aeron.Context();
    static MediaDriver mediaDriver = MediaDriver.launch();

    public Client(InetSocketAddress source, InetSocketAddress destination) {
        try {
            Aeron aeron = Aeron.connect(context);
            String channel = "aeron:udp?endpoint=" + destination.getHostName() + ":" + destination.getPort();
            
            TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            targetDataLine.open(audioFormat);
            targetDataLine.start();

            JFrame jFrame = new JFrame();
            JLabel jLabel = new JLabel();
            ImageIcon imageIcon = new ImageIcon();
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            jLabel.setIcon(imageIcon);
            jFrame.setSize(dimension);
            jFrame.getContentPane().add(jLabel);
            jFrame.setVisible(true);
            jFrame.setTitle(Integer.toString(source.getPort()));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();

            Publication publication = aeron.addPublication(channel, 1001);
            Timer timerAudio = new Timer(1000 / 10, (ActionEvent actionEvent) -> {
                byte[] bytes = new byte[1000];
                targetDataLine.read(bytes, 0, Math.min(targetDataLine.available(), bytes.length));
                long outcome = publication.offer(new UnsafeBuffer(bytes));
                if (outcome < 0) System.out.println(source.getPort() + ".timerAudio.offer() = " + outcome);
            });
            Timer timerVideo = new Timer(1000 / 20, (ActionEvent actionEvent) -> {
                try {
                    BufferedImage bufferedImage = new BufferedImage(
                        (int) dimension.getWidth(),
                        (int) dimension.getHeight(),
                        BufferedImage.TYPE_INT_ARGB
                    );
                    ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
                    long outcome = publication.offer(new UnsafeBuffer(byteArrayOutputStream.toByteArray()));
                    if (outcome < 0) System.out.println(source.getPort() + ".timerVideo.offer() = " + outcome);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });
              
            Subscription subscription = aeron.addSubscription(channel, 1001);
            Thread thread = new Thread(() -> {
                FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                    try {
                        System.out.println(source.getPort() + ".poll().length = " + length);
                        byte[] bytes = new byte[length];
                        buffer.getBytes(offset, bytes);
                        if (length == 1000) {
                            sourceDataLine.write(bytes, 0, length);
                        } else {
                            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
                            if (bufferedImage != null) {
                                imageIcon.setImage(bufferedImage);
                                jLabel.repaint();
                            }
                        }
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                };
                FragmentAssembler fragmentAssembler = new FragmentAssembler(fragmentHandler);
                while (true) subscription.poll(fragmentAssembler, 10);
            });
            
            thread.start();
            timerAudio.start();
            timerVideo.start();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
    
    public static void main(String[] arguments) {
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        InetSocketAddress[] inetSocketAddresses = {
            new InetSocketAddress("localhost", 20000),
            new InetSocketAddress("localhost", 20001),
        };
        Client[] clients = {
            new Client(inetSocketAddresses[0], inetSocketAddresses[1]),
            new Client(inetSocketAddresses[1], inetSocketAddresses[0]),
        };
        while (true);
    }
}