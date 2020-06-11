package publishers;

import utilities.BufferCircular;

public abstract class Publisher<T> {
    BufferCircular<T> bufferCircular = new BufferCircular<>(16);
     
    public int addSubscriber() {
        return 0;
    }
    
    public T get(int id) {
        return bufferCircular.get(id);
    }
}
