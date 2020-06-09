package utilities;

import subscribers.Subscriber;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class BufferCircular<T> {
    T[] array;
    int mask;

    int producerIndex;
    ConcurrentHashMap<Subscriber<T>, Integer> consumerToIndex;

    public BufferCircular(T[] array) {
        this.array = array;
        mask = (byte) array.length;
    }

    public void addSubscriber(Subscriber<T> subscriber) {
        consumerToIndex.put(subscriber, producerIndex);
    }

    private int getLowestSubscriberIndex() {
        return Collections.min(consumerToIndex.values());
    }

    public T getAvailableSlot() {
        while ((producerIndex % mask) == getLowestSubscriberIndex());
        return array[producerIndex];
    }

    public void receive() {
        producerIndex += 1;
    }
}
