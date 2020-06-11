package utilities;

public class ObjectPoolPacketVideo {
    private static final ObjectPool<PacketVideo> singleton = new ObjectPool<>(16);
    
    public static ObjectPool<PacketVideo> getSingleton() {
        return singleton;
    }
}
