package subscribers;

import publishers.Publisher;

import java.util.HashMap;

public abstract class Subscriber<T> {
    HashMap<Publisher, Integer> publisherToInt;

    public abstract void receive(T t);

    public void subscribe(Publisher publisher) {

    }
}
