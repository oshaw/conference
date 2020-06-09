package subscribers;

public abstract class Subscriber<T> {
    abstract void receive(T t);
}
