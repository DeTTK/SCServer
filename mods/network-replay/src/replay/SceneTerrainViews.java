package replay;

import java.util.*;
import java.util.function.LongSupplier;

/** Native cells are already prepared. Compile every selection once, then keep
 * cell membership and culling/sort backings ready for an atomic switch. */
final class SceneTerrainViews {
    interface Cells extends SceneTerrainChanges.Cells {Object draw(List<Object> cells)throws Exception;}
    record View(SceneDomainMap<SceneTerrainCodec.Coordinate,Object> coordinates,
                SceneDomainMap<Object,Boolean> membership,Set<Object> selected,Object draw){}
    final IdentityHashMap<Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes>,View> views=new IdentityHashMap<>();
    Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> previous=Map.of();
    SceneDomainMap<SceneTerrainCodec.Coordinate,Object> coordinates=new SceneDomainMap<>();
    SceneDomainMap<Object,Boolean> membership=SceneDomainMap.identityKeys();
    SceneTerrainChanges pending;
    Object draw;
    long compiled,drawLists;
    boolean prepare(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> target,int size,Cells cells,long deadline,LongSupplier clock)throws Exception {
        if(views.containsKey(target))return true;
        if(pending==null||pending.target!=target)pending=new SceneTerrainChanges(previous,target,coordinates,membership);
        if(!pending.advance(size,cells,deadline,clock))return false;
        coordinates=pending.coordinates.finish();membership=pending.membership.finish();
        if(draw==null||pending.drawChanged){var drawable=new ArrayList<Object>();for(Object cell:coordinates.values())if(cells.drawable(cell))drawable.add(cell);draw=cells.draw(drawable);drawLists++;}
        var selectedMembership=membership;
        Set<Object> selected=Collections.unmodifiableSet(new AbstractSet<>(){public int size(){return selectedMembership.size();}public boolean contains(Object cell){return selectedMembership.containsKey(cell);}public Iterator<Object> iterator(){return selectedMembership.keySet().iterator();}});
        views.put(target,new View(coordinates,membership,selected,draw));previous=target;pending=null;compiled++;return true;
    }
    View select(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> target){View value=views.get(target);if(value==null)throw new IllegalStateException("Terrain selection not compiled");return value;}
    void clear(){views.clear();pending=null;previous=Map.of();coordinates=new SceneDomainMap<>();membership=SceneDomainMap.identityKeys();draw=null;}
}
