import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.samples.SamplesUtil;
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
import java.util.concurrent.atomic.AtomicInteger;

public class Client {
    static Aeron.Context context = new Aeron.Context()
            .availableImageHandler(SamplesUtil::printAvailableImage)
            .unavailableImageHandler(SamplesUtil::printUnavailableImage);;
    static MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    
    Aeron aeron;
    AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
    Dimension dimension = new Dimension(320, 240);
    JLabel jLabel;
    InetSocketAddress source;
    ImageIcon imageIcon;
    String channel;
    Subscription subscription;
    Timer timerAudio;
    Timer timerVideo;
    Thread thread;

    SourceDataLine sourceDataLine;
    
    public AtomicInteger atomicIntegerReceived = new AtomicInteger(0);
    
    public Client(InetSocketAddress source, InetSocketAddress destination) {
        try {
            aeron = Aeron.connect(context);
            channel = "aeron:udp?endpoint=" + destination.getHostName() + ":" + destination.getPort();
            this.source = source;
            
            TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            targetDataLine.open(audioFormat);
            targetDataLine.start();

            JFrame jFrame = new JFrame();
            jLabel = new JLabel();
            imageIcon = new ImageIcon();
            jLabel.setIcon(imageIcon);
            jFrame.setSize(dimension);
            jFrame.getContentPane().add(jLabel);
            jFrame.setVisible(true);
            jFrame.setTitle(Integer.toString(source.getPort()));
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();

            Publication publication = aeron.addPublication(channel, 1003);
            timerAudio = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
                byte[] bytes = new byte[1000];
                targetDataLine.read(bytes, 0, Math.min(targetDataLine.available(), bytes.length));
                long outcome = publication.offer(new UnsafeBuffer(bytes));
                // if (outcome < 0) System.out.println(source.getPort() + ".timerAudio.offer() = " + outcome);
            });
            timerVideo = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
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
                    // if (outcome < 0) System.out.println(source.getPort() + ".timerVideo.offer() = " + outcome);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });
            
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        subscription = aeron.addSubscription(channel, 1003);
        thread = new Thread(() -> {
            FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
                try {
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
            while (true) {
                atomicIntegerReceived.addAndGet(subscription.poll(fragmentAssembler, 1000));
                if (subscription.hasNoImages()) {
                    subscription.close();
                    subscription = aeron.addSubscription(channel, 1003);
                    try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
                }
            }
        });
        thread.start();
        timerAudio.start();
        timerVideo.start();
    }
    
    public static void main(String[] arguments) throws InterruptedException {
        context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        context.driverTimeoutMs(10000);
        context.keepAliveInterval(10000);
        context.interServiceTimeout(10000);
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