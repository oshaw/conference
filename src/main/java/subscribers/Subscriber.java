package subscribers;

import publishers.Publisher;

public abstract class Subscriber<T> {
    public void subscribeTo(Publisher publisher) {
        new Thread(() -> {
            int id = publisher.addSubscriber();
            while (true) {
                receive((T) publisher.get(id));
            }
        });
    }
    
    abstract void receive(T t);
}
