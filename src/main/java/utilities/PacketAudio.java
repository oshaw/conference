package utilities;

public class PacketAudio extends Packet implements Factory {
    public byte[] bytes = new byte[508];
    
    @Override public Object get() {
        return new PacketAudio();
    }
}