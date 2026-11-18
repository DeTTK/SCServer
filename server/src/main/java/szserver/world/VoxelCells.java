package szserver.world;

import java.util.*;

/** Owner-confined voxel table. Positive revisions mark occupied slots; no per-cell objects are retained.
 * Sparse chunks use open addressing, dense chunks switch to direct unsigned-16-bit cell indexing.
 * Frozen views own sorted, exact-size arrays and never share mutable storage with the owner. */
final class VoxelCells extends AbstractMap<Integer,BlockOverlays.Voxel> {
    private static final char[] EMPTY_CHARS=new char[0];
    private static final long[] EMPTY_LONGS=new long[0];
    private char[] keys=EMPTY_CHARS,packed=EMPTY_CHARS;
    private long[] revisions=EMPTY_LONGS;
    private int size,used,modCount;
    private boolean dense;

    private static int hash(int cell){int v=cell*0x9e3779b9;return v^(v>>>16);}
    private int find(int cell){
        if(dense)return revisions[cell]>0?cell:-1;
        if(revisions.length==0)return -1;
        int mask=revisions.length-1,slot=hash(cell)&mask;
        while(revisions[slot]!=0){if(revisions[slot]>0&&keys[slot]==cell)return slot;slot=(slot+1)&mask;}
        return -1;
    }
    private static int cell(Object key){return key instanceof Integer v&&v>=0&&v<=65535?v:-1;}
    private BlockOverlays.Voxel value(int slot){int v=packed[slot];return new BlockOverlays.Voxel(v>>>4,v&15,revisions[slot]);}
    @Override public int size(){return size;}
    @Override public boolean containsKey(Object key){int c=cell(key);return c>=0&&find(c)>=0;}
    @Override public BlockOverlays.Voxel get(Object key){int c=cell(key),slot=c<0?-1:find(c);return slot<0?null:value(slot);}
    /** Negative means absent; zero is a present air override. Hot collision reads allocate no voxel record. */
    int packed(int cell){int slot=cell<0||cell>65535?-1:find(cell);return slot<0?-1:packed[slot];}
    @Override public BlockOverlays.Voxel put(Integer key,BlockOverlays.Voxel value){
        Objects.requireNonNull(key);Objects.requireNonNull(value);
        if(key<0||key>65535||value.id()<0||value.id()>4095||value.metadata()<0||value.metadata()>15||value.revision()<1)throw new IllegalArgumentException("invalid_voxel_cell");
        int slot=find(key);BlockOverlays.Voxel old=slot<0?null:value(slot);
        if(slot<0){ensureInsert();slot=insertionSlot(key);if(revisions[slot]==0)used++;size++;modCount++;if(!dense)keys[slot]=(char)(int)key;}
        packed[slot]=(char)((value.id()<<4)|value.metadata());revisions[slot]=value.revision();return old;
    }
    private int insertionSlot(int cell){
        if(dense)return cell;
        int mask=revisions.length-1,slot=hash(cell)&mask,deleted=-1;
        while(revisions[slot]!=0){if(revisions[slot]<0&&deleted<0)deleted=slot;slot=(slot+1)&mask;}
        return deleted<0?slot:deleted;
    }
    private void ensureInsert(){
        if(dense)return;
        int capacity=revisions.length;
        if(capacity==0){resize(4);return;}
        if((long)(used+1)*4<=capacity*3L)return;
        if((long)(size+1)*4<=capacity*3L){resize(capacity);return;}
        if(capacity==65536){makeDense();return;}
        resize(capacity*2);
    }
    private void resize(int capacity){
        char[] previousKeys=keys,previousPacked=packed;long[] previousRevisions=revisions;
        keys=new char[capacity];packed=new char[capacity];revisions=new long[capacity];used=0;
        for(int i=0;i<previousRevisions.length;i++)if(previousRevisions[i]>0){int slot=insertionSlot(previousKeys[i]);keys[slot]=previousKeys[i];packed[slot]=previousPacked[i];revisions[slot]=previousRevisions[i];used++;}
    }
    private void makeDense(){
        char[] values=new char[65536];long[] versions=new long[65536];
        for(int i=0;i<revisions.length;i++)if(revisions[i]>0){int cell=keys[i];values[cell]=packed[i];versions[cell]=revisions[i];}
        keys=EMPTY_CHARS;packed=values;revisions=versions;used=size;dense=true;
    }
    @Override public BlockOverlays.Voxel remove(Object key){
        int c=cell(key),slot=c<0?-1:find(c);if(slot<0)return null;
        BlockOverlays.Voxel old=value(slot);revisions[slot]=dense?0:-1;packed[slot]=0;size--;modCount++;if(dense)used--;
        if(size==0){keys=packed=EMPTY_CHARS;revisions=EMPTY_LONGS;used=0;dense=false;}
        return old;
    }
    @Override public void clear(){if(size>0){size=used=0;keys=packed=EMPTY_CHARS;revisions=EMPTY_LONGS;dense=false;modCount++;}}
    void copyFrom(VoxelCells other){
        if(other==this)return;
        keys=other.keys.clone();packed=other.packed.clone();revisions=other.revisions.clone();size=other.size;used=other.used;dense=other.dense;modCount++;
    }
    Map<Integer,BlockOverlays.Voxel> freeze(){
        if(size==0)return Map.of();
        char[] cells=new char[size],values=new char[size];long[] versions=new long[size];int n=0;
        for(int i=0;i<revisions.length;i++)if(revisions[i]>0)cells[n++]=(char)(dense?i:keys[i]);
        if(!dense)Arrays.sort(cells);
        for(int i=0;i<cells.length;i++){int slot=find(cells[i]);values[i]=packed[slot];versions[i]=revisions[slot];}
        return Collections.unmodifiableMap(new Frozen(cells,values,versions));
    }
    /** Array payload only, for capacity regression tests; JVM object headers are additional. */
    long storageBytes(){return 2L*(keys.length+packed.length)+8L*revisions.length;}
    boolean dense(){return dense;}

    @Override public Set<Map.Entry<Integer,BlockOverlays.Voxel>> entrySet(){return new AbstractSet<>(){
        @Override public int size(){return VoxelCells.this.size;}
        @Override public void clear(){VoxelCells.this.clear();}
        @Override public Iterator<Map.Entry<Integer,BlockOverlays.Voxel>> iterator(){return new Iterator<>(){
            int expected=modCount,next=nextSlot(0),lastCell=-1;
            private int nextSlot(int from){while(from<revisions.length&&revisions[from]<=0)from++;return from;}
            private void current(){if(expected!=modCount)throw new ConcurrentModificationException();}
            @Override public boolean hasNext(){current();return next<revisions.length;}
            @Override public Map.Entry<Integer,BlockOverlays.Voxel> next(){
                current();if(next>=revisions.length)throw new NoSuchElementException();int slot=next;lastCell=dense?slot:keys[slot];next=nextSlot(slot+1);return new CellEntry(lastCell);
            }
            @Override public void remove(){current();if(lastCell<0)throw new IllegalStateException();VoxelCells.this.remove(lastCell);expected=modCount;lastCell=-1;if(size==0)next=0;}
        };}
    };}
    private final class CellEntry implements Map.Entry<Integer,BlockOverlays.Voxel> {
        private final int cell;
        CellEntry(int cell){this.cell=cell;}
        @Override public Integer getKey(){return cell;}
        @Override public BlockOverlays.Voxel getValue(){return VoxelCells.this.get(cell);}
        @Override public BlockOverlays.Voxel setValue(BlockOverlays.Voxel value){return VoxelCells.this.put(cell,value);}
        @Override public boolean equals(Object other){return other instanceof Map.Entry<?,?> e&&Objects.equals(cell,e.getKey())&&Objects.equals(getValue(),e.getValue());}
        @Override public int hashCode(){return Integer.hashCode(cell)^Objects.hashCode(getValue());}
    }
    private static final class Frozen extends AbstractMap<Integer,BlockOverlays.Voxel> {
        private final char[] cells,packed;private final long[] revisions;
        Frozen(char[] cells,char[] packed,long[] revisions){this.cells=cells;this.packed=packed;this.revisions=revisions;}
        private int find(Object key){int c=cell(key);return c<0?-1:Arrays.binarySearch(cells,(char)c);}
        private BlockOverlays.Voxel value(int i){int v=packed[i];return new BlockOverlays.Voxel(v>>>4,v&15,revisions[i]);}
        @Override public int size(){return cells.length;}
        @Override public boolean containsKey(Object key){return find(key)>=0;}
        @Override public BlockOverlays.Voxel get(Object key){int i=find(key);return i<0?null:value(i);}
        @Override public Set<Map.Entry<Integer,BlockOverlays.Voxel>> entrySet(){return new AbstractSet<>(){
            @Override public int size(){return cells.length;}
            @Override public Iterator<Map.Entry<Integer,BlockOverlays.Voxel>> iterator(){return new Iterator<>(){
                int index;
                @Override public boolean hasNext(){return index<cells.length;}
                @Override public Map.Entry<Integer,BlockOverlays.Voxel> next(){if(!hasNext())throw new NoSuchElementException();int i=index++;return new SimpleImmutableEntry<>((int)cells[i],value(i));}
            };}
        };}
    }
}
