package subscribers;

import utilities.Packet;
import utilities.PacketAudio;
import utilities.PacketVideo;

import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Set;

public class Sender extends Subscriber<Packet> {
    DatagramPacket datagramPacket = new DatagramPacket(new byte[]{}, 0);
    DatagramSocket datagramSocket;
    Set<InetSocketAddress> destinations;

    public Sender(DatagramSocket datagramSocket, Set<InetSocketAddress> destinations) {
        this.datagramSocket = datagramSocket;
        this.destinations = destinations;
    }

    @Override
    public void receive(Packet packet) {
        if (packet instanceof PacketAudio) sendAudio((PacketAudio) packet);
        else sendVideo((PacketVideo) packet);
    }

    void sendAudio(PacketAudio packetAudio) {
        datagramPacket.setData(packetAudio.bytes);
        send(datagramPacket);
    }

    void sendVideo(PacketVideo packetVideo) {
        datagramPacket.setData(((DataBufferByte) packetVideo.bufferedImage.getRaster().getDataBuffer()).getData());
        send(datagramPacket);
    }

    void send(DatagramPacket datagramPacket) {
        try {
            for (InetSocketAddress destination : destinations) {
                datagramPacket.setSocketAddress(destination);
                datagramSocket.send(datagramPacket);
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}
