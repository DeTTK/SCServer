package replay;

import java.util.*;

/** Bounded identity aliases after an exact content cache has certified a value. */
final class SceneIdentityAliases<T> {
    record Key(Object value) {
        public int hashCode(){return System.identityHashCode(value);}
        public boolean equals(Object other){return other instanceof Key key&&value==key.value;}
    }
    final int limit;final LinkedHashMap<Key,T> entries=new LinkedHashMap<>(16,.75f,true);
    final IdentityHashMap<T,Set<Key>> targets=new IdentityHashMap<>();
    SceneIdentityAliases(int limit){if(limit<1)throw new IllegalArgumentException("Invalid identity alias limit");this.limit=limit;}
    T get(Object key){return entries.get(new Key(key));}
    void put(Object key,T value){
        Objects.requireNonNull(key);Objects.requireNonNull(value);Key identity=new Key(key);T previous=entries.put(identity,value);
        if(previous!=null&&previous!=value)unlink(previous,identity);
        targets.computeIfAbsent(value,ignored->new HashSet<>()).add(identity);
        while(entries.size()>limit){var oldest=entries.entrySet().iterator();var entry=oldest.next();oldest.remove();unlink(entry.getValue(),entry.getKey());}
    }
    void unlink(T value,Key key){Set<Key> keys=targets.get(value);if(keys!=null){keys.remove(key);if(keys.isEmpty())targets.remove(value);}}
    void removeTarget(T value){Set<Key> keys=targets.remove(value);if(keys!=null)for(Key key:keys)entries.remove(key);}
    void clear(){entries.clear();targets.clear();}
}
