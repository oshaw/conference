package subscribers;

import publishers.Publisher;

public abstract class Subscriber<T> {
    public void subscribe(Publisher publisher) {
        new Thread(() -> {
            int id = publisher.addSubscriber();
            while (true) {
                publisher.get(id);
            }
        });
    }
}
