package publishers;

import utilities.BufferCircular;

public abstract class Publisher<T> {
    protected BufferCircular<T> bufferCircular;
    
    Publisher() {
        bufferCircular = new BufferCircular<>(16);
    }
    
    public int addSubscriber() {
        return bufferCircular.addSubscriber();
    }
    
    public T get(int id) {
        return bufferCircular.get(id);
    }
}
