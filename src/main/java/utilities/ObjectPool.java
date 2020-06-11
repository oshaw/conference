package utilities;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectPool<T> {
    Set<T> set = ConcurrentHashMap.newKeySet();
    T t;
    
    ObjectPool(Factory<T> factory, int size) {
        for (int index = 0; index < size; index += 1) set.add(factory.get());
    }
    
    public T allocate() {
        synchronized (this) {
            if (set.isEmpty()) System.out.println("ObjectPool empty");
            t = set.iterator().next();
            set.remove(t);
            return t;
        }
    }
    
    public void deallocate(T t) {
        set.add(t);
    }
}
