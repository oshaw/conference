package utilities;

public class ObjectPoolPacketVideo {
    private static ObjectPool<PacketVideo> singleton;
    
    public static ObjectPool<PacketVideo> getSingleton() {
        if (singleton == null) singleton = new ObjectPool<>(PacketVideo.factory, 16);
        return singleton;
    }
}
