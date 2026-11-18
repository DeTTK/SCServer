package replay;

import java.util.*;

/** Vertex buffers are immutable; each native cell keeps its own visibility and bounds. */
final class SceneTerrainBuffers<T> {
    interface Access<T>{T create(SceneTerrainCodec.Pass pass)throws Exception;void dispose(T buffer)throws Exception;}
    record Key(String layout,int stride,String category,int usage,int length,String hash){}
    final class Entry {final Key key;final T value;int references;Entry(Key key,T value){this.key=key;this.value=value;}}
    final class Lease {
        final Entry entry;boolean closed;Lease(Entry entry){this.entry=entry;}
        T value(){return entry.value;}
        void close()throws Exception {if(closed)return;closed=true;if(--entry.references==0){entries.remove(entry.key);residentBytes-=entry.key.length;access.dispose(entry.value);}}
    }
    final Access<T> access;final Map<Key,Entry> entries=new HashMap<>();
    final IdentityHashMap<Object,List<Lease>> owners=new IdentityHashMap<>();
    long residentBytes,created,hits;
    SceneTerrainBuffers(Access<T> access){this.access=access;}
    Lease acquire(String layout,int stride,SceneTerrainCodec.Pass pass)throws Exception {
        Key key=new Key(layout,stride,pass.category(),pass.usage(),pass.vertexByteSize(),pass.hash());Entry entry=entries.get(key);
        if(entry==null){T value=Objects.requireNonNull(access.create(pass));entry=new Entry(key,value);entries.put(key,entry);residentBytes+=key.length;created++;}else hits++;
        entry.references++;return new Lease(entry);
    }
    void bind(Object owner,List<Lease> leases){if(owners.containsKey(owner))throw new IllegalStateException("Terrain cell already owns buffers");owners.put(owner,List.copyOf(leases));}
    boolean owns(Object owner){return owners.containsKey(owner);}
    void release(Object owner)throws Exception {List<Lease> leases=owners.remove(owner);if(leases==null)return;release(leases);}
    void release(List<Lease> leases)throws Exception {Exception failure=null;for(Lease lease:leases)try{lease.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;}
}
