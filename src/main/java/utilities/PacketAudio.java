package utilities;

public class PacketAudio extends Packet {
    public byte[] bytes = new byte[508];
    public static Factory<PacketAudio> factory = new FactoryPacketAudio();
    
    public static class FactoryPacketAudio implements Factory<PacketAudio> {
        @Override public PacketAudio get() {
            return new PacketAudio();
        }
    }
}