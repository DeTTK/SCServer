package replay;

import java.util.*;

/** Immutable domain values with shared pages. A changed mesh copies one page,
 * while any older time selection still owns its original entries. */
final class SceneDomainMap<K,V> extends AbstractMap<K,V> {
    final Map<K,Integer> ordinals;
    final Object[][] pages;
    final int count;
    SceneDomainMap(){this(new HashMap<>(),new Object[0][],0);}
    static <K,V> SceneDomainMap<K,V> identityKeys(){return new SceneDomainMap<>(new IdentityHashMap<>(),new Object[0][],0);}
    private SceneDomainMap(Map<K,Integer> ordinals,Object[][] pages,int count){this.ordinals=ordinals;this.pages=pages;this.count=count;}
    @SuppressWarnings("unchecked") Entry<K,V> entry(int ordinal){return ordinal>>>6>=pages.length||pages[ordinal>>>6]==null?null:(Entry<K,V>)pages[ordinal>>>6][ordinal&63];}
    public V get(Object key){Integer n=ordinals.get(key);Entry<K,V> e=n==null?null:entry(n);return e==null?null:e.getValue();}
    public boolean containsKey(Object key){return get(key)!=null;}
    public int size(){return count;}
    public Set<Entry<K,V>> entrySet(){return new AbstractSet<>(){
        public int size(){return count;}
        public Iterator<Entry<K,V>> iterator(){return new Iterator<>(){
            int next=advance(0);
            private int advance(int n){while(n<pages.length*64&&entry(n)==null)n++;return n;}
            public boolean hasNext(){return next<pages.length*64;}
            public Entry<K,V> next(){if(!hasNext())throw new NoSuchElementException();Entry<K,V> result=entry(next);next=advance(next+1);return result;}
        };}
    };}
    Builder<K,V> edit(){return new Builder<>(this);}
    record Change<K,V>(K key,V before,V after){}
    static <K,V> List<Change<K,V>> changes(Map<K,V> before,Map<K,V> after){
        if(before==after)return List.of();
        List<Change<K,V>> result=new ArrayList<>();
        if(before instanceof SceneDomainMap<K,V> a&&after instanceof SceneDomainMap<K,V> b&&a.ordinals==b.ordinals){
            for(int page=0;page<Math.max(a.pages.length,b.pages.length);page++){
                Object[] ap=page<a.pages.length?a.pages[page]:null,bp=page<b.pages.length?b.pages[page]:null;if(ap==bp)continue;
                for(int slot=0;slot<64;slot++){
                    Entry<K,V> old=a.entry(page*64+slot),next=b.entry(page*64+slot);
                    V av=old==null?null:old.getValue(),bv=next==null?null:next.getValue();
                    if(av!=bv)result.add(new Change<>(next==null?old.getKey():next.getKey(),av,bv));
                }
            }
        }else{
            for(var e:before.entrySet()){V next=after.get(e.getKey());if(e.getValue()!=next)result.add(new Change<>(e.getKey(),e.getValue(),next));}
            for(var e:after.entrySet())if(!before.containsKey(e.getKey()))result.add(new Change<>(e.getKey(),null,e.getValue()));
        }
        return result;
    }
    static final class Builder<K,V> {
        final SceneDomainMap<K,V> base;Object[][] pages;final BitSet copied=new BitSet();int count;
        Builder(SceneDomainMap<K,V> base){this.base=base;pages=base.pages.clone();count=base.count;}
        @SuppressWarnings("unchecked") Entry<K,V> entry(int n){return n>>>6>=pages.length||pages[n>>>6]==null?null:(Entry<K,V>)pages[n>>>6][n&63];}
        void writable(int n){int page=n>>>6;if(page>=pages.length)pages=Arrays.copyOf(pages,page+1);if(!copied.get(page)){pages[page]=pages[page]==null?new Object[64]:pages[page].clone();copied.set(page);}}
        V remove(Object key){Integer n=base.ordinals.get(key);Entry<K,V> old=n==null?null:entry(n);if(old==null)return null;writable(n);pages[n>>>6][n&63]=null;count--;return old.getValue();}
        V put(K key,V value){Objects.requireNonNull(key);Objects.requireNonNull(value);int n=base.ordinals.computeIfAbsent(key,unused->base.ordinals.size());Entry<K,V> old=entry(n);writable(n);pages[n>>>6][n&63]=Map.entry(key,value);if(old==null)count++;return old==null?null:old.getValue();}
        SceneDomainMap<K,V> finish(){
            boolean changed=false;
            for(int page=copied.nextSetBit(0);page>=0;page=copied.nextSetBit(page+1)){
                Object[] before=page<base.pages.length?base.pages[page]:null;boolean equal=true;
                for(int slot=0;slot<64;slot++)if(!Objects.equals(before==null?null:before[slot],pages[page][slot])){equal=false;break;}
                if(equal)pages[page]=before;else changed=true;
            }
            return changed?new SceneDomainMap<>(base.ordinals,pages,count):base;
        }
    }
}
