package utilities;

import java.awt.image.BufferedImage;

public class PacketVideo extends Packet {
    public BufferedImage bufferedImage;
    public static Factory<PacketVideo> factory = new PacketVideo.FactoryPacketVideo();
    
    public static class FactoryPacketVideo implements Factory<PacketVideo> {
        @Override public PacketVideo get() {
            return new PacketVideo();
        }
    }
}