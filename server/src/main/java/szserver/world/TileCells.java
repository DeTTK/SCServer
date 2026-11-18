package szserver.world;

import java.util.*;

/** Compact clear markers and block IDs; only actual immutable tile payloads use a map. */
final class TileCells extends AbstractMap<Integer,TilePayload.Tile> {
    private static final char[] EMPTY=new char[0];private static final char DELETED=65535;
    private char[] keys=EMPTY,ids=EMPTY;private int size,used,mod;private boolean dense;
    private final Map<Integer,TilePayload.Tile> payloads=new HashMap<>();
    private static int key(Object value){return value instanceof Integer n&&n>=0&&n<=65535?n:-1;}
    private static int hash(int cell){int n=cell*0x9e3779b9;return n^(n>>>16);}
    private int find(int cell){if(dense)return ids[cell]!=0?cell:-1;if(ids.length==0)return -1;int slot=hash(cell)&(ids.length-1);while(ids[slot]!=0){if(ids[slot]!=DELETED&&keys[slot]==cell)return slot;slot=(slot+1)&(ids.length-1);}return -1;}
    private int insertion(int cell){if(dense)return cell;int slot=hash(cell)&(ids.length-1),deleted=-1;while(ids[slot]!=0){if(ids[slot]==DELETED&&deleted<0)deleted=slot;slot=(slot+1)&(ids.length-1);}return deleted<0?slot:deleted;}
    private void resize(int capacity){char[] oldKeys=keys,oldIds=ids;keys=new char[capacity];ids=new char[capacity];used=0;for(int n=0;n<oldIds.length;n++)if(oldIds[n]!=0&&oldIds[n]!=DELETED){int slot=insertion(oldKeys[n]);keys[slot]=oldKeys[n];ids[slot]=oldIds[n];used++;}}
    private void ensure(){if(dense)return;if(ids.length==0){resize(4);return;}if((long)(used+1)*4<=ids.length*3L)return;if((long)(size+1)*4<=ids.length*3L){resize(ids.length);return;}if(ids.length==16384){char[] values=new char[65536];for(int n=0;n<ids.length;n++)if(ids[n]!=0&&ids[n]!=DELETED)values[keys[n]]=ids[n];keys=EMPTY;ids=values;used=size;dense=true;}else resize(ids.length*2);}
    private void setId(int cell,int id){if(cell<0||cell>65535||id<0||id>4095)throw new IllegalArgumentException("tile_cell_or_id");int slot=find(cell);if(slot<0){ensure();slot=insertion(cell);if(ids[slot]==0)used++;size++;mod++;if(!dense)keys[slot]=(char)cell;}ids[slot]=(char)(id+1);}
    int id(int cell){if(cell<0||cell>65535)return 0;int slot=find(cell);return slot<0?0:ids[slot]-1;}
    int payloadCount(){return payloads.size();}
    long storageBytes(){return 2L*(keys.length+ids.length);}
    void copyFrom(TileCells other){keys=other.keys.clone();ids=other.ids.clone();size=other.size;used=other.used;dense=other.dense;payloads.clear();payloads.putAll(other.payloads);mod++;}
    @Override public int size(){return size;}
    @Override public boolean containsKey(Object value){int cell=key(value);return cell>=0&&find(cell)>=0;}
    @Override public TilePayload.Tile get(Object value){return payloads.get(value);}
    @Override public TilePayload.Tile put(Integer cell,TilePayload.Tile value){Objects.requireNonNull(cell);setId(cell,id(cell));return value==null?payloads.remove(cell):payloads.put(cell,value);}
    @Override public TilePayload.Tile remove(Object value){int cell=key(value),slot=cell<0?-1:find(cell);if(slot<0)return null;TilePayload.Tile old=payloads.remove(cell);ids[slot]=dense?0:DELETED;size--;if(dense)used--;mod++;if(size==0){keys=ids=EMPTY;used=0;dense=false;}return old;}
    @Override public void clear(){if(size!=0){keys=ids=EMPTY;payloads.clear();size=used=0;dense=false;mod++;}}
    private int next(int slot){while(slot<ids.length&&(ids[slot]==0||ids[slot]==DELETED))slot++;return slot;}
    @Override public Set<Entry<Integer,TilePayload.Tile>> entrySet(){return new AbstractSet<>(){
        public int size(){return TileCells.this.size;}
        public Iterator<Entry<Integer,TilePayload.Tile>> iterator(){return new Iterator<>(){final int expected=mod;int slot=TileCells.this.next(0);private void valid(){if(expected!=mod)throw new ConcurrentModificationException();}public boolean hasNext(){valid();return slot<ids.length;}public Entry<Integer,TilePayload.Tile> next(){valid();if(slot>=ids.length)throw new NoSuchElementException();int cell=dense?slot:keys[slot];slot=TileCells.this.next(slot+1);return new SimpleImmutableEntry<>(cell,payloads.get(cell));}};}
    };}
    Map<Integer,Integer> idView(){return new AbstractMap<>(){
        public Integer put(Integer cell,Integer id){Objects.requireNonNull(cell);Objects.requireNonNull(id);Integer previous=containsKey(cell)?TileCells.this.id(cell):null;setId(cell,id);return previous;}
        public boolean containsKey(Object cell){return TileCells.this.containsKey(cell);}
        public Integer get(Object value){int cell=key(value);return cell<0||find(cell)<0?null:TileCells.this.id(cell);}
        public Set<Entry<Integer,Integer>> entrySet(){return new AbstractSet<>(){public int size(){return TileCells.this.size;}public Iterator<Entry<Integer,Integer>> iterator(){var source=TileCells.this.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return source.hasNext();}public Entry<Integer,Integer> next(){int cell=source.next().getKey();return new SimpleImmutableEntry<>(cell,TileCells.this.id(cell));}};}};}
    };}
    record Frozen(Map<Integer,TilePayload.Tile> tiles,Map<Integer,Integer> ids){}
    Frozen freeze(){if(size==0)return new Frozen(Map.of(),Map.of());char[] cells=new char[size],values=new char[size];int at=0;for(int slot=next(0);slot<ids.length;slot=next(slot+1))cells[at++]=(char)(dense?slot:keys[slot]);if(!dense)Arrays.sort(cells);for(int n=0;n<cells.length;n++)values[n]=(char)id(cells[n]);Map<Integer,TilePayload.Tile> immutable=Map.copyOf(payloads);
        Map<Integer,TilePayload.Tile> tileView=new AbstractMap<>(){
            public int size(){return cells.length;}
            public boolean containsKey(Object value){int cell=key(value);return cell>=0&&Arrays.binarySearch(cells,(char)cell)>=0;}
            public TilePayload.Tile get(Object cell){return key(cell)<0?null:immutable.get(cell);}
            public Set<Entry<Integer,TilePayload.Tile>> entrySet(){return new AbstractSet<>(){public int size(){return cells.length;}public Iterator<Entry<Integer,TilePayload.Tile>> iterator(){return new Iterator<>(){int at;public boolean hasNext(){return at<cells.length;}public Entry<Integer,TilePayload.Tile> next(){if(!hasNext())throw new NoSuchElementException();int cell=cells[at++];return new SimpleImmutableEntry<>(cell,immutable.get(cell));}};}};}
        };
        Map<Integer,Integer> idView=new AbstractMap<>(){
            public int size(){return cells.length;}
            public Integer get(Object value){int cell=key(value),at=cell<0?-1:Arrays.binarySearch(cells,(char)cell);return at<0?null:(int)values[at];}
            public Set<Entry<Integer,Integer>> entrySet(){return new AbstractSet<>(){public int size(){return cells.length;}public Iterator<Entry<Integer,Integer>> iterator(){return new Iterator<>(){int at;public boolean hasNext(){return at<cells.length;}public Entry<Integer,Integer> next(){if(!hasNext())throw new NoSuchElementException();int n=at++;return new SimpleImmutableEntry<>((int)cells[n],(int)values[n]);}};}};}
        };return new Frozen(Collections.unmodifiableMap(tileView),Collections.unmodifiableMap(idView));
    }
}
