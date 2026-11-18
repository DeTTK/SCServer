package szserver.net;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

/** Account-state worker's directory view. Values are detached; changes require explicit put. */
final class AccountIndexMap<K,V> extends AbstractMap<K,V> {
    private AccountStoreSnapshot.Index<K,V> root;
    AccountIndexMap(DiskAccountTree disk,Type type,Function<String,K> decode){this(disk,type,decode,null);}
    AccountIndexMap(DiskAccountTree disk,Type type,Function<String,K> decode,String table){root=AccountStoreSnapshot.Index.disk(disk,type,decode,table);}
    long offset(){return root.offset();}
    Runnable relocation(DiskAccountTree next,long offset){var prepared=root.relocate(next,offset);return ()->root=prepared;}
    void relocate(DiskAccountTree next,long offset){relocation(next,offset).run();}
    @SuppressWarnings("unchecked") @Override public V get(Object key){return root.get((K)key);}
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public int size(){return Math.toIntExact(root.size());}
    @Override public V put(K key,V value){V old=get(key);root=root.put(key,Objects.requireNonNull(value));return old;}
    @SuppressWarnings("unchecked") @Override public V remove(Object key){V old=get(key);if(old!=null)root=root.put((K)key,null);return old;}
    @Override public Set<Entry<K,V>> entrySet(){var captured=root;return new AbstractSet<>(){
        public int size(){return Math.toIntExact(captured.size());}
        public Iterator<Entry<K,V>> iterator(){return captured.entries();}
    };}
    static <K> Set<K> set(AccountIndexMap<K,Boolean> map){return new AbstractSet<>(){
        public boolean contains(Object key){return map.containsKey(key);}
        public boolean add(K key){return map.put(key,true)==null;}
        public boolean remove(Object key){return map.remove(key)!=null;}
        public int size(){return map.size();}
        public Iterator<K> iterator(){return map.keySet().iterator();}
    };}
}
