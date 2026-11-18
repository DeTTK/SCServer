package replay;
import java.util.*;
import java.util.concurrent.*;

/** Complete map data is decoded on one CPU worker. No native world or GPU objects. */
final class SceneMapPreparation implements AutoCloseable {
    record Request(long time,Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows,SceneTracks.Frame legacy){}
    record Prepared(SceneMapIndex.Selection selection,Map<SceneTracks.Bytes,byte[]> prefabPayloads){
        Prepared at(long time){return selection.timeNs()==time?this:new Prepared(selection.at(time),prefabPayloads);}
    }
    final SceneMapIndex index=new SceneMapIndex();
    final IdentityHashMap<Object,Long> keys=new IdentityHashMap<>();
    final ConcurrentMap<Long,Request> inputs=new ConcurrentHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,byte[]> payloads=new IdentityHashMap<>();
    Map<UUID,SceneTracks.Bytes> previousPrefabs;
    Map<SceneTracks.Bytes,byte[]> selectedPayloads=Map.of();
    volatile boolean closed;
    volatile Map<Object,Prepared> completeSelections;
    volatile Exception completeFailure;
    volatile int completePrepared;
    Thread completeWorker;
    /** Map track roots share immutable pages. Compile these derived domain
     * selections before viewing; no native world, checkpoint or timeline replay. */
    boolean completeIndex(SceneTracks tracks)throws Exception {
        if(closed)throw new IllegalStateException("Closed map preparation");
        if(completeFailure!=null)throw completeFailure;
        if(completeSelections!=null)return true;
        if(completeWorker==null){
            var rows=tracks.mapIndexRows();
            completeWorker=new Thread(()->{try{
                var results=new IdentityHashMap<Object,Prepared>();
                // The native map catalog has already seeded all payloads and
                // chunk identities. One read-only table serves every selection.
                Map<SceneTracks.Bytes,byte[]> sharedPayloads=Collections.unmodifiableMap(payloads);
                for(int i=0;i<rows.size();i++){
                    if(closed)return;var source=rows.get(i);var selected=index.indexed(tracks.mapIndexTime(i),source);
                    for(var change:SceneDomainMap.changes(previousPrefabs==null?Map.of():previousPrefabs,selected.prefabs()))if(change.after()!=null&&!payloads.containsKey(change.after()))payloads.put(change.after(),change.after().data());
                    previousPrefabs=selected.prefabs();results.put(source,new Prepared(selected,sharedPayloads));completePrepared++;
                }
                completeSelections=Collections.unmodifiableMap(results);
            }catch(Exception error){if(!closed)completeFailure=error;}},"Replay complete map index");
            completeWorker.setDaemon(true);completeWorker.start();
        }
        return false;
    }
    final SceneAsyncFrames<Prepared> frames=new SceneAsyncFrames<>("Replay map data preparation",128,this::decode,value->{});
    Prepared decode(long key)throws Exception {
        Request request=inputs.get(key);if(request==null)throw new IllegalStateException("Missing map preparation input");
        SceneMapIndex.Selection selection=request.rows()==null?index.select(request.legacy()):index.indexed(request.time(),request.rows());
        if(previousPrefabs!=selection.prefabs()){
            var selected=new IdentityHashMap<SceneTracks.Bytes,byte[]>();
            for(var bytes:selection.prefabs().values()){
                byte[] value=payloads.get(bytes);if(value==null){value=bytes.data();payloads.put(bytes,value);}selected.put(bytes,value);
            }
            selectedPayloads=Collections.unmodifiableMap(selected);previousPrefabs=selection.prefabs();
        }
        return new Prepared(selection,selectedPayloads);
    }
    Prepared request(SceneTracks.Frame frame,boolean urgent)throws Exception {
        var rows=frame.indexedMapObjects();return request(new Request(frame.timeNs(),rows,rows==null?frame:null),urgent);
    }
    Prepared request(long time,Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows,boolean urgent)throws Exception {
        return rows==null?null:request(new Request(time,rows,null),urgent);
    }
    private Prepared request(Request request,boolean urgent)throws Exception {
        if(closed)throw new IllegalStateException("Closed map preparation");
        Object identity=request.rows()==null?request.legacy().objects():request.rows();
        Map<Object,Prepared> compiled=completeSelections;
        if(compiled!=null&&request.rows()!=null){Prepared selected=compiled.get(identity);if(selected==null)throw new IllegalStateException("Map selection absent from complete index");return selected.at(request.time());}
        if(completeWorker!=null&&compiled==null){if(completeFailure!=null)throw completeFailure;return null;}
        Long key=keys.get(identity);if(key==null){key=(long)keys.size();keys.put(identity,key);inputs.put(key,request);}
        Prepared selected=frames.request(key,urgent);return selected==null?null:selected.at(request.time());
    }
    public void close()throws Exception {if(closed)return;closed=true;try{if(completeWorker!=null){completeWorker.interrupt();completeWorker.join();}frames.close();}finally{completeSelections=null;keys.clear();inputs.clear();payloads.clear();previousPrefabs=null;selectedPayloads=Map.of();index.previous=Map.of();index.current=null;index.indexedRows=null;index.decoded.clear();index.coordinates.clear();index.terrainCoordinates.clear();index.ids.clear();index.selectedRows.clear();index.rowOrder.clear();index.rowValues.clear();}}
}
