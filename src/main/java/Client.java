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
import javax.sound.sampled.AudioFormat;
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
            
            VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            Publication publication = aeron.addPublication(channel, 1001);
            Timer timer = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
                try {
                    BufferedImage bufferedImage = new BufferedImage(
                        (int) dimension.getWidth(),
                        (int) dimension.getHeight(),
                        BufferedImage.TYPE_INT_ARGB
                    );
                    ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
                    publication.offer(new UnsafeBuffer(byteArrayOutputStream.toByteArray()));
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });
            timer.start();

            JFrame jFrame = new JFrame();
            JLabel jLabel = new JLabel();
            ImageIcon imageIcon = new ImageIcon();
            jLabel.setIcon(imageIcon);
            jFrame.setSize(dimension);
            jFrame.getContentPane().add(jLabel);
            jFrame.setVisible(true);
            jFrame.setTitle(Integer.toString(source.getPort()));
            Subscription subscription = aeron.addSubscription(channel, 1001);
            Thread thread = new Thread(() -> {
                FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                    try {
                        BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(buffer.byteArray()));
                        if (bufferedImage != null) {
                            imageIcon.setImage(bufferedImage);
                            jLabel.repaint();
                        }
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                };
                FragmentAssembler fragmentAssembler = new FragmentAssembler(fragmentHandler);
                while (true) subscription.poll(fragmentAssembler, 100);
            });
            thread.start();
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