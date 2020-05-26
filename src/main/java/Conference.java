import com.github.sarxos.webcam.Webcam;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.Timer;

public class Conference {
    public static void main(String[] arguments) {
        Webcam.setAutoOpenMode(true);
        Webcam webcam = Webcam.getDefault();
        webcam.setViewSize(new Dimension(320, 240));
        Set<InetSocketAddress> addresses = new HashSet<>();
        for (int index = 0; index < 3; index += 1) {
            addresses.add(new InetSocketAddress("localhost", 20000 + index));
        }
        Object[] addressesArray = addresses.toArray();
        for (int index = 0; index < 3; index += 1) {
            InetSocketAddress source = (InetSocketAddress) addressesArray[index];
            Set<InetSocketAddress> destinations = new HashSet<>(addresses);
            destinations.remove(source);
            new Client(webcam, source, destinations);
        }
    }
}

class Client {
    DatagramSocket datagramSocket;
    JFrame jFrame;
    Set<InetSocketAddress> destinations;
    Webcam webcam;
    public Client(Webcam webcam, InetSocketAddress source, Set<InetSocketAddress> destinations) {
        try {
            // this.jFrame = new JFrame();
            this.datagramSocket = new DatagramSocket(source);
            this.destinations = destinations;
            this.webcam = webcam;
            sender();
            receiver();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
    private void sender() {
        new Timer().schedule(new TimerTask() {
            @Override public void run() {
                try {
                    // ImageIO.write(webcam.getImage(), "jpg", byteArrayOutputStream);
                    // byte[] byteArray = byteArrayOutputStream.toByteArray();
                    // System.out.println(byteArray.length);
                    byte[] byteArray = {1};
                    for (InetSocketAddress destination : destinations) {
                        datagramSocket.send(new DatagramPacket(byteArray, byteArray.length, destination));
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }
        }, 0, 1000/30);
    }
    private void receiver() {
        new Thread(() -> {
            try {
                byte[] byteArray = new byte[8];
                DatagramPacket datagramPacket = new DatagramPacket(byteArray, byteArray.length);
                while (true) {
                    datagramSocket.receive(datagramPacket);
                    System.out.println(datagramPacket.getPort());
                }
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }).start();
    }
}