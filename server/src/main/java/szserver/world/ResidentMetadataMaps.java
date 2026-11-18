package szserver.world;

import java.util.*;
import java.util.function.ToLongBiFunction;

/** Owner-mutated, immutable-published metadata tables. Old snapshots keep their exact shard roots. */
final class ResidentMetadataMaps {
    private static final int PARTS=256;
    private ResidentMetadataMaps(){}
    static <K,V> Map<K,V> mutable(Map<K,V> source){return mutable(source,(key,value)->0);}
    @SuppressWarnings("unchecked") static <K,V> Map<K,V> mutable(Map<K,V> source,ToLongBiFunction<K,V> weight){
        if(source instanceof Table<K,V> table)return new Table<>(table.snapshot(),table.weight);
        if(source instanceof Frozen<K,V> frozen)return new Table<>(frozen,weight);
        Map<K,V>[] parts=new Map[PARTS];Map<Integer,Map<K,V>> building=new HashMap<>();for(var row:source.entrySet())building.computeIfAbsent(part(row.getKey()),ignored->new HashMap<>()).put(row.getKey(),row.getValue());
        long bytes=0;for(var row:source.entrySet())bytes=Math.addExact(bytes,weight.applyAsLong(row.getKey(),row.getValue()));for(int p=0;p<PARTS;p++)parts[p]=building.containsKey(p)?Map.copyOf(building.get(p)):Map.of();return new Table<>(new Frozen<>(parts,source.size(),bytes),weight);
    }
    static <K,V> Map<K,V> snapshot(Map<K,V> source){return source instanceof Table<K,V> table?table.snapshot():Collections.unmodifiableMap(source);}
    static <K> Set<K> mutable(Set<K> source){Map<K,Boolean> rows=new HashMap<>();for(K key:source)rows.put(key,true);return new Values<>(mutable(rows));}
    static <K> Set<K> snapshot(Set<K> source){return source instanceof Values<K> set?Collections.unmodifiableSet(snapshot(set.rows).keySet()):Collections.unmodifiableSet(source);}
    static long weight(Map<?,?> source){if(source instanceof Table<?,?> table)return table.snapshot().bytes;if(source instanceof Frozen<?,?> frozen)return frozen.bytes;throw new IllegalStateException("resident_metadata_snapshot_required");}
    private static int part(Object key){int h=key.hashCode();h^=h>>>16;return h&(PARTS-1);}
    private static final class Table<K,V> extends AbstractMap<K,V>{
        private volatile Frozen<K,V> view;
        private final ToLongBiFunction<K,V> weight;
        Table(Frozen<K,V> view,ToLongBiFunction<K,V> weight){this.view=view;this.weight=weight;}
        Frozen<K,V> snapshot(){return view;}
        @Override public V get(Object key){return view.get(key);}
        @Override public boolean containsKey(Object key){return view.containsKey(key);}
        @Override public int size(){return view.size();}
        @Override public Set<Entry<K,V>> entrySet(){return view.entrySet();}
        @Override public V put(K key,V value){Objects.requireNonNull(key);Objects.requireNonNull(value);Frozen<K,V> before=view;int p=part(key);V old=before.parts[p].get(key);if(Objects.equals(old,value))return old;long bytes=Math.addExact(before.bytes-(old==null?0:weight.applyAsLong(key,old)),weight.applyAsLong(key,value));Map<K,V> next=new HashMap<>(before.parts[p]);next.put(key,value);Map<K,V>[] parts=before.parts.clone();parts[p]=Map.copyOf(next);view=new Frozen<>(parts,before.size+(old==null?1:0),bytes);return old;}
        @SuppressWarnings("unchecked") @Override public V remove(Object key){Frozen<K,V> before=view;if(key==null)return null;int p=part(key);V old=before.parts[p].get(key);if(old==null)return null;long bytes=before.bytes-weight.applyAsLong((K)key,old);Map<K,V> next=new HashMap<>(before.parts[p]);next.remove(key);Map<K,V>[] parts=before.parts.clone();parts[p]=Map.copyOf(next);view=new Frozen<>(parts,before.size-1,bytes);return old;}
        @Override public void clear(){if(view.size==0)return;view=((Table<K,V>)ResidentMetadataMaps.<K,V>mutable(Map.of())).view;}
    }
    private static final class Frozen<K,V> extends AbstractMap<K,V>{
        private final Map<K,V>[] parts;private final int size;private final long bytes;
        Frozen(Map<K,V>[] parts,int size,long bytes){this.parts=parts;this.size=size;this.bytes=bytes;}
        @Override public V get(Object key){return key==null?null:parts[part(key)].get(key);}
        @Override public boolean containsKey(Object key){return key!=null&&parts[part(key)].containsKey(key);}
        @Override public int size(){return size;}
        @Override public Set<Entry<K,V>> entrySet(){return new AbstractSet<>(){
            public int size(){return size;}
            public Iterator<Entry<K,V>> iterator(){return new Iterator<>(){int part;Iterator<Entry<K,V>> current=Collections.emptyIterator();private void advance(){while(!current.hasNext()&&part<PARTS)current=parts[part++].entrySet().iterator();}public boolean hasNext(){advance();return current.hasNext();}public Entry<K,V> next(){advance();return current.next();}};}
        };}
    }
    private static final class Values<K> extends AbstractSet<K>{
        private final Map<K,Boolean> rows;Values(Map<K,Boolean> rows){this.rows=rows;}
        @Override public int size(){return rows.size();}
        @Override public boolean contains(Object key){return rows.containsKey(key);}
        @Override public boolean add(K key){return rows.put(key,true)==null;}
        @Override public boolean remove(Object key){return rows.remove(key)!=null;}
        @Override public void clear(){rows.clear();}
        @Override public Iterator<K> iterator(){return snapshot(rows).keySet().iterator();}
    }
}
