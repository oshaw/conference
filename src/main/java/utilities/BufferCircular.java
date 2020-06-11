package utilities;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferCircular<T> {
    T[] array;
    int mask;

    ConcurrentHashMap<Integer, Integer> subscriberIdToIndex = new ConcurrentHashMap<>();
    AtomicInteger atomicInteger;
    int publisherIndex;

    public BufferCircular(int size) {
        array = (T[]) new Object[size];
        mask = (byte) size;
    }
    
    public void put(T t) {
        
    }
    
    public T get(int id) {
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
