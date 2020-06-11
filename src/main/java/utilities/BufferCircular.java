package utilities;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferCircular<T> {
    T[] array;
    int mask;
    int size;

    AtomicInteger atomicInteger;
    ConcurrentHashMap<Integer, Integer> subscriberIdToIndex = new ConcurrentHashMap<>();
    ObjectPool<T> objectPool;
    int publisherIndex;

    public BufferCircular(ObjectPool<T> objectPool, int size) {
        this.objectPool = objectPool;
        this.size = size;
        array = (T[]) new Object[size];
        mask = (byte) size;
    }
    
    public void put(T t) {
        while (getLowestSubscriberIndex() == publisherIndex - size);
        synchronized (this) {
            array[publisherIndex++] = t;
            objectPool.deallocate(array[publisher]);
        }
        
    }
    
    public T get(int id) {
        while (subscriberIdToIndex.get(id) == publisherIndex);
        return array[subscriberIdToIndex.get(id)];
    }

    public int addSubscriber() {
        int id = atomicInteger.getAndIncrement();
        subscriberIdToIndex.put(id, 0);
        return id;
    }

    private int getLowestSubscriberIndex() {
        return subscriberIdToIndex.isEmpty() ? 0 : Collections.min(subscriberIdToIndex.values());
    }
}
