import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.sound.sampled.*;
import javax.swing.*;

class Camera {
    VideoCapture videoCapture;

    public Camera(Dimension dimension) {
        try {
            videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        } catch (VideoCaptureException exception) {
            exception.printStackTrace();
        }
    }

    public void read(BufferedImage bufferedImage) {
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
    }
}

class Microphone {
    TargetDataLine targetDataLine;

    public Microphone(AudioFormat audioFormat) {
        try {
            targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            targetDataLine.open(audioFormat);
            targetDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }

    public int getBufferSize() {
        return targetDataLine.getBufferSize();
    }

    public void read(byte[] bytes) {
        targetDataLine.read(bytes, 0, 1024);
    }

    public void stop() {
        targetDataLine.close();
    }
}

class Display {
    JFrame jFrame = new JFrame();
    JLabel jLabel = new JLabel();
    ImageIcon imageIcon = new ImageIcon();

    public Display(Dimension dimension) {
        jLabel.setIcon(imageIcon);
        jFrame.setSize(dimension);
        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        jFrame.getContentPane().add(jLabel);
        jFrame.setVisible(true);
    }

    public void write(BufferedImage bufferedImage) {
        imageIcon.setImage(bufferedImage);
        jLabel.repaint();
    }
}

class Speaker {
    SourceDataLine sourceDataLine;

    public Speaker(AudioFormat audioFormat) {
        try {
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }

    public void write(byte[] bytes) {
        sourceDataLine.write(bytes, 0, 1024);
    }

    public void stop() {
        sourceDataLine.drain();
        sourceDataLine.close();
    }
}

public class Client {
    static AudioFormat AUDIO_FORMAT = new AudioFormat(8000.0f, 16, 1, true, true);
    static Dimension DIMENSION = new Dimension(320, 240);
    static int SIZE_UDP_PACKET_MAX = 508;

    Camera camera = new Camera(DIMENSION);
    Display display = new Display(DIMENSION);
    Microphone microphone = new Microphone(AUDIO_FORMAT);
    Speaker speaker = new Speaker(AUDIO_FORMAT);

    DatagramSocket datagramSocket;
    Set<InetSocketAddress> destinations;

    Thread threadReceiver = new Thread(new Receiver());
    Thread threadSender = new Thread(new Sender());

    public Client(InetSocketAddress source, Set<InetSocketAddress> destinations) {
        try {
            datagramSocket = new DatagramSocket(source);
            this.destinations = destinations;
        } catch (SocketException exception) {
            exception.printStackTrace();
        }
        threadReceiver.start();
        threadSender.start();
    }

    class Sender implements Runnable {
        BufferedImage bufferedImage = new BufferedImage((int) DIMENSION.getWidth(), (int) DIMENSION.getHeight(), BufferedImage.TYPE_INT_ARGB);
        byte[] bytes = new byte[SIZE_UDP_PACKET_MAX];
        byte[] bytesMicrophone = new byte[microphone.getBufferSize() / 5];
        DatagramPacket datagramPacket = new DatagramPacket(bytes, SIZE_UDP_PACKET_MAX);

        @Override public void run() {
            Timer timer = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
                camera.read(bufferedImage);
                display.write(bufferedImage);
                microphone.read(bytesMicrophone);
                speaker.write(bytesMicrophone);
                datagramPacket.setData(bytesMicrophone, 0, bytesMicrophone.length);
                try {
                    for (InetSocketAddress destination : destinations) {
                        datagramPacket.setSocketAddress(destination);
                        datagramSocket.send(datagramPacket);
                    }
                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            });
            timer.start();
        }
    }

    class Receiver implements Runnable {
        byte[] bytes = new byte[SIZE_UDP_PACKET_MAX];
        DatagramPacket datagramPacket = new DatagramPacket(bytes, SIZE_UDP_PACKET_MAX);

        @Override public void run() {
            try {
                while (true) {
                    datagramSocket.receive(datagramPacket);
                    System.out.println(datagramPacket.getLength());
                }
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        }
    }

    public static void main(String[] arguments) {
        InetSocketAddress[] inetSocketAddresses = {
            new InetSocketAddress("localhost", 20000),
            new InetSocketAddress("localhost", 20001),
            new InetSocketAddress("localhost", 20002),
        };
        Client[] clients = {
            new Client(inetSocketAddresses[0], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[1], inetSocketAddresses[2]))),
            new Client(inetSocketAddresses[1], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[2]))),
            new Client(inetSocketAddresses[2], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[1])))
        };
        while (true);
    }
}