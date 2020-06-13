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

public class Client {
    static MediaDriver mediaDriver = MediaDriver.launchEmbedded();
    
    AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
    Dimension dimension = new Dimension(320, 240);
    Publication publication;
    Subscription subscription;
    
    Thread thread;
    Timer timerAudio;
    Timer timerVideo;
    
    public Client(String address) {
        try {
            Aeron.Context context = new Aeron.Context();
            context.aeronDirectoryName(mediaDriver.aeronDirectoryName());
            Aeron aeron = Aeron.connect(context);
            
            JFrame jFrame = new JFrame();
            JLabel jLabel = new JLabel();
            ImageIcon imageIcon = new ImageIcon();
            jLabel.setIcon(imageIcon);
            jFrame.setSize(dimension);
            jFrame.getContentPane().add(jLabel);
            jFrame.setVisible(true);
            jFrame.setTitle(address);
            VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());

            TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            targetDataLine.open(audioFormat);
            targetDataLine.start();
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
            
            publication = aeron.addPublication("aeron:udp?control-mode=manual", 0);
            timerAudio = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
                byte[] bytes = new byte[1000];
                targetDataLine.read(bytes, 0, Math.min(targetDataLine.available(), bytes.length));
                long outcome = publication.offer(new UnsafeBuffer(bytes));
                // if (outcome < 0) System.out.println(address + ".timerAudio.offer() = " + outcome);
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
                    // if (outcome < 0) System.out.println(address + ".timerVideo.offer() = " + outcome);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });

            subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 0);
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
                    subscription.poll(fragmentAssembler, 100);
                    if (subscription.hasNoImages()) {
                        subscription.close();
                        subscription = aeron.addSubscription("aeron:udp?endpoint=" + address, 0);
                        try { Thread.sleep(1000); } catch (Exception exception) { exception.printStackTrace(); }
                    }
                }
            });
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
    
    public void start() {
        thread.start();
        timerAudio.start();
        timerVideo.start();
    }
    
    public void addDestination(String address) {
        publication.addDestination("aeron:udp?endpoint=" + address);
    }
    
    public static void main(String[] arguments) throws InterruptedException {
        String[] addresses = {"localhost:20000", "localhost:20001", "localhost:20002",};
        Client[] clients = {new Client(addresses[0]), new Client(addresses[1]), new Client(addresses[2]),};
        clients[0].addDestination(addresses[1]);
        clients[0].addDestination(addresses[2]);
        clients[1].addDestination(addresses[0]);
        clients[1].addDestination(addresses[2]);
        clients[2].addDestination(addresses[0]);
        clients[2].addDestination(addresses[1]);
        clients[0].start();
        clients[1].start();
        clients[2].start();
        while (true);
    }
}