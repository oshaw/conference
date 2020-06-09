package publishers;

import subscribers.Subscriber;
import utilities.BufferCircular;

public abstract class Publisher<T> {
    BufferCircular<T> buffer;

    public void addSubscriber(Subscriber subscriber) {
        buffer.addSubscriber(subscriber);
    }

    abstract void publish();
}
