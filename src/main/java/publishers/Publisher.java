package publishers;

import utilities.BufferCircular;

public abstract class Publisher<T> {
    BufferCircular<T> buffer;

    abstract void publish();
}
