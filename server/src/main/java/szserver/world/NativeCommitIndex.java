package szserver.world;

import szserver.persistence.Journal;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

/** Immutable disk-backed native base view. A whole durable wave publishes one root.
 * Older readers keep their exact generation across newer edits, without retaining chunk bodies. */
public final class NativeCommitIndex {
    private static final int SHARDS=64;
    private record Pointer(Path generation,long revision){}
    public static final class Chunk {
        private final byte[] record;private final List<TilePayload.Tile> complete;private final long revision;
        private Chunk(byte[] record,List<TilePayload.Tile> complete,long revision){this.record=record;this.complete=List.copyOf(complete);this.revision=revision;}
        public byte[] record(){return record.clone();}
        public List<TilePayload.Tile> completeTiles(){return complete;}
        public long revision(){return revision;}
    }
    private final List<Map<Long,Pointer>> shards;private final int size;private final String generation;
    private final Journal.Prefix prefix;private Object lease;
    private NativeCommitIndex(List<Map<Long,Pointer>> shards,int size,String generation,Journal.Prefix prefix){this.shards=List.copyOf(shards);this.size=size;this.generation=generation;this.prefix=prefix;}
    void lease(Object lease){if(this.lease!=null&&this.lease!=lease)throw new IllegalStateException("native_index_lease_changed");this.lease=lease;}
    record Stored(long key,long revision,Path generation){}
    @FunctionalInterface interface Rows{void accept(Stored row)throws IOException;}
    void stored(Rows rows)throws IOException{NativeTileStates.worker();for(var part:shards)for(var row:part.entrySet())rows.accept(new Stored(row.getKey(),row.getValue().revision(),row.getValue().generation()));}
    Set<Path> paths(){NativeTileStates.worker();Set<Path> paths=new HashSet<>();for(var part:shards)for(var row:part.values())paths.add(row.generation());return Set.copyOf(paths);}
    /** Metadata/packed geometry compares pointers but never reads this predecessor's files. */
    NativeCommitIndex differenceView(){return new NativeCommitIndex(shards,size,generation,prefix);}
    static NativeCommitIndex restored(Collection<Stored> rows,String generation,Journal.Prefix prefix)throws IOException{
        List<Map<Long,Pointer>> parts=new ArrayList<>();for(int n=0;n<SHARDS;n++)parts.add(new HashMap<>());int count=0;
        for(var row:rows){if(row.revision<1||parts.get(shard(row.key)).putIfAbsent(row.key,new Pointer(row.generation,row.revision))!=null)throw new IOException("native_index_duplicate_or_revision");count++;}
        for(int n=0;n<SHARDS;n++)parts.set(n,Map.copyOf(parts.get(n)));return new NativeCommitIndex(parts,count,generation,prefix);
    }
    public static NativeCommitIndex empty(){List<Map<Long,Pointer>> shards=new ArrayList<>(SHARDS);for(int n=0;n<SHARDS;n++)shards.add(Map.of());return new NativeCommitIndex(shards,0,"",null);}
    private static int shard(long key){key^=key>>>33;key*=0xff51afd7ed558ccdL;key^=key>>>33;key*=0xc4ceb9fe1a85ec53L;key^=key>>>33;return (int)key&(SHARDS-1);}
    private Pointer pointer(long key){return shards.get(shard(key)).get(key);}
    /** Allocate the next root before ledger fsync; publication afterwards is constant work. */
    NativeCommitIndex updated(Path generation,Map<Long,Long> revisions,Journal.Prefix prefix)throws IOException{
        NativeTileStates.worker();List<Map<Long,Pointer>> next=new ArrayList<>(shards);Map<Integer,Map<Long,Pointer>> changed=new HashMap<>();int count=size;
        for(var entry:revisions.entrySet()){
            long key=entry.getKey(),revision=entry.getValue();Pointer old=pointer(key);if(revision<1||old!=null&&old.revision()>revision)throw new IOException("native_commit_revision_regressed");
            if(old==null)count=Math.addExact(count,1);int shard=shard(key);Map<Long,Pointer> partition=changed.computeIfAbsent(shard,n->new HashMap<>(shards.get(n)));partition.put(key,new Pointer(generation,revision));
        }
        changed.forEach((key,value)->next.set(key,Map.copyOf(value)));return new NativeCommitIndex(next,count,generation.getFileName().toString(),prefix);
    }
    public int size(){return size;}
    public String generation(){return generation;}
    public Journal.Prefix prefix(){return prefix;}
    /** Worker diff skips unchanged immutable partitions, including reset to the original base. */
    public Set<Long> changedChunksSince(NativeCommitIndex before){
        NativeTileStates.worker();Set<Long> changed=new HashSet<>();for(int n=0;n<SHARDS;n++){Map<Long,Pointer> now=shards.get(n),old=before.shards.get(n);if(now==old)continue;for(var row:now.entrySet())if(!row.getValue().equals(old.get(row.getKey())))changed.add(row.getKey());for(long key:old.keySet())if(!now.containsKey(key))changed.add(key);}return Set.copyOf(changed);
    }
    public long revision(int x,int z){Pointer found=pointer(BlockOverlays.key(x,z));return found==null?0:found.revision();}
    /** Loader/backend worker only. Null means use the original base; corrupt/missing committed data is an error. */
    public Chunk readChunk(int x,int z)throws IOException{
        try{NativeTileStates.worker();Pointer found=pointer(BlockOverlays.key(x,z));if(found==null)return null;
        byte[] record=NativeMapArchive.record(found.generation(),x,z);if(record==null||ByteBuffer.wrap(record).getInt(0)!=x||ByteBuffer.wrap(record).getInt(4)!=z)throw new IOException("native_commit_chunk_missing_or_misplaced");
        List<TilePayload.Tile> full=NativeTileStates.attach(found.generation().resolve("region"),x,z,TilePayload.fromRecord(record)).stream().filter(tile->tile.completeStateSize()>0).toList();
        return new Chunk(record,full,found.revision());}finally{java.lang.ref.Reference.reachabilityFence(this);}
    }
    public Map<Long,Long> revisions(){NativeCommitIndex captured=this;return Collections.unmodifiableMap(new AbstractMap<>(){
        @Override public int size(){return captured.size;}
        @Override public Long get(Object key){if(!(key instanceof Long number))return null;Pointer found=captured.pointer(number);return found==null?null:found.revision();}
        @Override public Set<Map.Entry<Long,Long>> entrySet(){return new AbstractSet<>(){
            @Override public int size(){return captured.size;}
            @Override public Iterator<Map.Entry<Long,Long>> iterator(){Iterator<Map<Long,Pointer>> parts=captured.shards.iterator();return new Iterator<>(){
                Iterator<Map.Entry<Long,Pointer>> rows=Collections.emptyIterator();
                public boolean hasNext(){while(!rows.hasNext()&&parts.hasNext())rows=parts.next().entrySet().iterator();return rows.hasNext();}
                public Map.Entry<Long,Long> next(){if(!hasNext())throw new NoSuchElementException();var row=rows.next();return new SimpleImmutableEntry<>(row.getKey(),row.getValue().revision());}
            };}
        };}
    });}
}
