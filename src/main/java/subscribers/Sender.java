package subscribers;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Set;

public class Sender extends Subscriber<Object> {
    DatagramPacket datagramPacket = new DatagramPacket(new byte[]{}, 0);
    DatagramSocket datagramSocket;
    Set<InetSocketAddress> destinations;

    public Sender(DatagramSocket datagramSocket, Set<InetSocketAddress> destinations) {
        this.datagramSocket = datagramSocket;
        this.destinations = destinations;
    }

    void send(byte[] bytes) {
        try {
            datagramPacket.setData(bytes, 0, 508);
            for (InetSocketAddress destination : destinations) {
                datagramPacket.setSocketAddress(destination);
                datagramSocket.send(datagramPacket);
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    void sendAudio(byte[] bytes) {
        send(bytes);
    }

    void sendVideo(BufferedImage bufferedImage) {
        send(((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData());
    }

    @Override void receive(Object object) {
        if (object instanceof byte[]) sendAudio((byte[]) object);
        else sendVideo((BufferedImage) object);
    }
}
