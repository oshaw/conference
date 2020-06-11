package utilities;

public class ObjectPoolPacketAudio {
    private static final ObjectPool<PacketAudio> singleton = new ObjectPool<>(16);
    
    public static ObjectPool<PacketAudio> getSingleton() {
        return singleton;
    }
}
