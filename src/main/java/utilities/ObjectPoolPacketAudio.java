package utilities;

public class ObjectPoolPacketAudio {
    private static ObjectPool<PacketAudio> singleton;
    
    public static ObjectPool<PacketAudio> getSingleton() {
        if (singleton == null) singleton = new ObjectPool<>(PacketAudio.factory, 16);
        return singleton;
    }
}
