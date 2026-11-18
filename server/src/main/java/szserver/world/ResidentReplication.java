package szserver.world;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded latest-cell history for active native chunks. A missing range requires an authoritative snapshot. */
public final class ResidentReplication {
    public record Point(int cell,int packed,TilePayload.Tile tile,long revision){}
    public record View(long revision,long floor,Map<Integer,Point> cells,long bytes){public View{cells=Map.copyOf(cells);}}
    private final Thread owner=Thread.currentThread();private final Map<ResidentChunks.Key,View> published=new ConcurrentHashMap<>();
    private final LinkedHashMap<ResidentChunks.Key,View> recent=new LinkedHashMap<>();private long bytes;
    private static final int MAX_CELLS=128;private static final long CHUNK_BYTES=4L*1024*1024,TOTAL_BYTES=32L*1024*1024;
    private void owner(){if(owner!=Thread.currentThread())throw new IllegalStateException("resident_replication_wrong_owner");}
    public View view(ResidentChunks.Key key){return published.get(key);}
    public void loaded(ResidentChunks.Key key,long revision){owner();if(published.containsKey(key))throw new IllegalStateException("resident_replication_duplicate_chunk");published.put(key,new View(revision,revision,Map.of(),0));}
    public void unloaded(ResidentChunks.Key key){owner();published.remove(key);var old=recent.remove(key);if(old!=null)bytes-=old.bytes();}
    public void refresh(ResidentChunks.Key key,long revision){owner();var before=published.get(key);if(before==null||revision<=before.revision())throw new IllegalStateException("resident_replication_revision");publish(key,new View(revision,revision,Map.of(),0));}
    public void blocks(ResidentBlockOperations.Change change){
        owner();Map<ResidentChunks.Key,List<ResidentBlockOperations.Delta>> groups=new LinkedHashMap<>();for(var delta:change.changes()){var p=delta.position();groups.computeIfAbsent(new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)),ignored->new ArrayList<>()).add(delta);}
        for(var row:groups.entrySet()){
            var old=published.get(row.getKey());Long revision=change.revisions().get(row.getKey());if(old==null||revision==null||revision<=old.revision())throw new IllegalStateException("resident_replication_revision");Map<Integer,Point> cells=new HashMap<>(old.cells());for(var delta:row.getValue()){var p=delta.position();int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);cells.put(cell,new Point(cell,delta.packed(),delta.tile(),revision));}
            long floor=old.floor();if(cells.size()>MAX_CELLS){cells.clear();floor=old.revision();for(var delta:row.getValue()){var p=delta.position();int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);cells.put(cell,new Point(cell,delta.packed(),delta.tile(),revision));}}
            long retained=0;for(var point:cells.values())retained+=160L+(point.tile()==null?0:point.tile().recordSize()+point.tile().completeStateSize());if(retained>CHUNK_BYTES){cells.clear();floor=revision;retained=0;}publish(row.getKey(),new View(revision,floor,cells,retained));
        }
    }
    private void publish(ResidentChunks.Key key,View view){var old=recent.remove(key);if(old!=null)bytes-=old.bytes();published.put(key,view);if(view.bytes()>0){recent.put(key,view);bytes+=view.bytes();}while(bytes>TOTAL_BYTES){var iterator=recent.entrySet().iterator();var row=iterator.next();iterator.remove();bytes-=row.getValue().bytes();published.put(row.getKey(),new View(row.getValue().revision(),row.getValue().revision(),Map.of(),0));}}
}
