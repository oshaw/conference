package publishers;

import utilities.BufferCircular;
import utilities.ObjectPool;

public abstract class Publisher<T> {
    protected BufferCircular<T> bufferCircular;
    
    Publisher(ObjectPool<T> objectPool) {
        bufferCircular = new BufferCircular<>(objectPool, 16);
    }
    
    public int addSubscriber() {
        return bufferCircular.addSubscriber();
    }
    
    public T get(int id) {
        return bufferCircular.get(id);
    }
}
