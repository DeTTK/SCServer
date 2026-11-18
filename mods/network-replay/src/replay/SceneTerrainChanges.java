package replay;
import java.util.*;import java.util.function.LongSupplier;
/** Private pending geometry edits survive resource waits; publication remains atomic. */
final class SceneTerrainChanges {
    interface Cells {default void reserve(Collection<SceneTracks.Bytes> values)throws Exception{}default void prefetch(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes bytes)throws Exception{}Object resolve(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes bytes)throws Exception;boolean drawable(Object cell);}
    final Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> target;
    final SceneDomainMap<SceneTerrainCodec.Coordinate,Object> base;
    final SceneDomainMap<Object,Boolean> baseMembership;
    final SceneDomainMap.Builder<SceneTerrainCodec.Coordinate,Object> coordinates;
    final SceneDomainMap.Builder<Object,Boolean> membership;
    final List<SceneDomainMap.Change<SceneTerrainCodec.Coordinate,SceneTracks.Bytes>> changes;
    int cursor;boolean drawChanged;
    SceneTerrainChanges(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> before,Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> target,SceneDomainMap<SceneTerrainCodec.Coordinate,Object> base,SceneDomainMap<Object,Boolean> membership){
        this.target=target;this.base=base;this.baseMembership=membership;coordinates=base.edit();this.membership=membership.edit();changes=SceneDomainMap.changes(before,target);
    }
    boolean matches(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> target,SceneDomainMap<SceneTerrainCodec.Coordinate,Object> base,SceneDomainMap<Object,Boolean> membership){return this.target==target&&this.base==base&&baseMembership==membership;}
    boolean advance(int size,Cells cells,long deadline,LongSupplier clock)throws Exception {
        long began=clock.getAsLong();if(began>=deadline)return false;
        long prefetchDeadline=began+(deadline-began)/2;
        List<SceneTracks.Bytes> required=new ArrayList<>(16);
        for(int n=cursor;n<Math.min(changes.size(),cursor+16);n++){var change=changes.get(n);if(change.after()!=null&&change.key().size()==size)required.add(change.after());}
        cells.reserve(required);
        // Queue a bounded consecutive group before waiting for its first mesh.
        // A single request per render frame otherwise turns 1000 CPU jobs into
        // ten seconds of wall time even when each decode takes a fraction of a millisecond.
        for(int n=cursor;n<Math.min(changes.size(),cursor+16);n++){
            if(clock.getAsLong()>=prefetchDeadline)break;
            var change=changes.get(n);if(change.after()!=null&&change.key().size()==size)cells.prefetch(change.key(),change.after());
        }
        while(cursor<changes.size()){
            if(clock.getAsLong()>=deadline)return false;
            var change=changes.get(cursor);if(change.key().size()!=size){cursor++;continue;}
            Object old=base.get(change.key());
            if(change.after()==null){coordinates.remove(change.key());if(old!=null){membership.remove(old);drawChanged|=cells.drawable(old);}}
            else {
                Object cell=cells.resolve(change.key(),change.after());if(cell==null)return false;
                coordinates.put(change.key(),cell);
                if(old!=cell){if(old!=null)membership.remove(old);membership.put(cell,Boolean.TRUE);drawChanged|=cells.drawable(cell)||old!=null&&cells.drawable(old);}
            }
            cursor++;
        }
        return true;
    }
}
