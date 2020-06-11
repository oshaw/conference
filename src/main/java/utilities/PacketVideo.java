package utilities;

import java.awt.image.BufferedImage;
import java.net.SocketAddress;

public class PacketVideo extends Packet {
    public BufferedImage bufferedImage;

    public static Packet[] factory(int size, SocketAddress socketAddress) {
        Packet[] packets = new PacketVideo[size];
        for (int index = 0; index < size; index += 1) {
            packets[index] = new PacketVideo();
            packets[index].socketAddress = socketAddress;
        }
        return packets;
    }
}