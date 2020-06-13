import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Client {
    AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
    Dimension dimension = new Dimension(320, 240);

    public Client(InetSocketAddress source, Set<InetSocketAddress> destinations) {
        try {
            DatagramSocket datagramSocket = new DatagramSocket(source);
            TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            VideoCapture videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            targetDataLine.open(audioFormat);
            targetDataLine.start();

            Timer timer = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
                try {
                    BufferedImage bufferedImage = new BufferedImage(
                        (int) dimension.getWidth(),
                        (int) dimension.getHeight(),
                        BufferedImage.TYPE_INT_ARGB
                    );
                    ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    ImageIO.write(bufferedImage, "png", stream);
                    byte[] bytes = stream.toByteArray();
                    
                    int index = 0;
                    int offset = 0;
                    while (offset < bytes.length) {
                        int bytesRead = Math.min(507, stream.size() - offset);
                        stream.reset();
                        stream.write((byte) index);
                        stream.write(bytes, offset, offset + bytesRead);
                        index += 1;
                        offset += bytesRead;
                        DatagramPacket datagramPacket = new DatagramPacket(stream.toByteArray(), stream.size());
                        for (InetSocketAddress destination : destinations) {
                            datagramPacket.setSocketAddress(destination);
                            datagramSocket.send(datagramPacket);
                        }
                    }
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
            Thread thread = new Thread(() -> {
                try {
                    byte[] bytes = new byte[508];
                    DatagramPacket datagramPacket = new DatagramPacket(bytes, bytes.length);
                    while (true) {
                        datagramSocket.receive(datagramPacket);
                        datagramPacket.getData();
//                        BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(datagramPacket.getData()));
//                        if (bufferedImage != null) {
//                            imageIcon.setImage(bufferedImage);
//                            jLabel.repaint();
//                        }
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });
            thread.start();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        
        
    }
    
    public static void main(String[] arguments) {
        InetSocketAddress[] inetSocketAddresses = {
            new InetSocketAddress("localhost", 20000),
            new InetSocketAddress("localhost", 20001),
            // new InetSocketAddress("localhost", 20002),
        };

        Client[] clients = {
            // new Client(inetSocketAddresses[0], new HashSet<>(Arrays.asList(inetSocketAddresses[1], inetSocketAddresses[2]))),
            // new Client(inetSocketAddresses[1], new HashSet<>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[2]))),
            // new Client(inetSocketAddresses[2], new HashSet<>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[1])))
            new Client(inetSocketAddresses[0], new HashSet<>(Arrays.asList(inetSocketAddresses[1]))),
            new Client(inetSocketAddresses[1], new HashSet<>(Arrays.asList(inetSocketAddresses[0]))),
        };
        while (true);
    }
}