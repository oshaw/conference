package utilities;

import java.net.SocketAddress;

public class PacketAudio extends Packet {
    public byte[] bytes;

    public static Packet[] factory(int size, SocketAddress socketAddress) {
        Packet[] packets = new PacketAudio[size];
        for (int index = 0; index < size; index += 1) {
            packets[index] = new PacketAudio();
            packets[index].socketAddress = socketAddress;
        }
        return packets;
    }
}