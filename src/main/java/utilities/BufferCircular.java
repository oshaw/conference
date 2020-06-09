package utilities;

import subscribers.Subscriber;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class BufferCircular<T> {
    T[] array;
    int mask;

    ConcurrentHashMap<Subscriber<T>, Integer> subscriberToIndex;
    int lowestSubscriberIndex;
    int producerIndex;

    public BufferCircular(T[] array) {
        this.array = array;
        mask = (byte) array.length;
    }

    public void subscribe(Subscriber<T> subscriber) {
        subscriberToIndex.put(subscriber, producerIndex);
        while (subscriberToIndex.containsKey(subscriber)) {
            while (subscriberToIndex.get(subscriber) < producerIndex) {
                subscriber.receive(array[subscriberToIndex.get(subscriber) & mask]);
                subscriberToIndex.put(subscriber, subscriberToIndex.get(subscriber));
            }
        }
    }

    public T getAvailableSlot() {
        while (getLowestSubscriberIndex() <= producerIndex - array.length);
        return array[producerIndex & mask];
    }

    public void markSlotFilled() {
        producerIndex += 1;
    }

    private int getLowestSubscriberIndex() {
        return Collections.min(subscriberToIndex.values());
    }
}
