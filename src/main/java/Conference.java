import com.github.sarxos.webcam.Webcam;
import handler.H264StreamDecoder;
import handler.H264StreamEncoder;
import handler.StreamFrameListener;
import org.jboss.netty.buffer.ChannelBuffer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
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
    H264StreamDecoder h264StreamDecoder;
    H264StreamEncoder h264StreamEncoder;
    Set<InetSocketAddress> destinations;
    Webcam webcam;
    public Client(Webcam webcam, InetSocketAddress source, Set<InetSocketAddress> destinations) {
        try {
            // this.jFrame = new JFrame();
            this.datagramSocket = new DatagramSocket(source);
            this.destinations = destinations;
            this.h264StreamDecoder = new H264StreamDecoder(null, webcam.getViewSize(), false, false);
            this.h264StreamEncoder = new H264StreamEncoder(webcam.getViewSize(), false);
            this.webcam = webcam;
            sender();
            receiver();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
    private void sender() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    ChannelBuffer channelBuffer = (ChannelBuffer) h264StreamEncoder.encode(webcam.getImage());
                    byte[] byteArray = channelBuffer.toByteBuffer().array();
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
                    System.out.println(datagramPacket.getLength());
                }
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }).start();
    }
    private class StreamFrameListenerImplementation implements StreamFrameListener {
        @Override
        public void onFrameReceived(BufferedImage image) {
            System.out.println(datagramSocket.getPort());
        }
    }
}