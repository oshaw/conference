package utilities;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferCircular<T extends Factory> {
    T[] array;
    int mask;
    int size;

    AtomicInteger atomicInteger;
    ConcurrentHashMap<Integer, Integer> subscriberIdToIndex = new ConcurrentHashMap<>();
    int publisherIndex;

    public BufferCircular(int size) {
        this.size = size;
        T.get();
        array = (T[]) new Object[size];
        mask = (byte) size;
    }
    
    public T allocate() {
        while (getLowestSubscriberIndex() == publisherIndex - size);
        return array[publisherIndex % mask];
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
