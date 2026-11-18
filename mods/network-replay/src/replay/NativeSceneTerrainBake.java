package replay;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Uses the world's existing terrain worker, including its serialized render-pass state.
 * Independent cells never enter that renderer's spatial grid. */
final class NativeSceneTerrainBake implements AutoCloseable {
    record Inputs(List<SceneTracks.Bytes> chunks,Map<String,SceneTracks.Bytes> prefabs) {
        Inputs {chunks=Collections.unmodifiableList(new ArrayList<>(chunks));}
        // prepare interns equal complete prefab maps once. Comparing that identity
        // avoids repeating thousands of prefab comparisons for every vertical cell.
        public boolean equals(Object value){
            if(!(value instanceof Inputs other)||prefabs!=other.prefabs||chunks.size()!=other.chunks.size())return false;
            // Capture retains each exact immutable chunk value after comparing an export
            // once. A different identity conservatively rebuilds; it never accepts a
            // hash collision or repeatedly compares megabytes per vertical cell.
            for(int n=0;n<chunks.size();n++)if(chunks.get(n)!=other.chunks.get(n))return false;
            return true;
        }
        public int hashCode(){int hash=System.identityHashCode(prefabs);for(Object chunk:chunks)hash=31*hash+System.identityHashCode(chunk);return hash;}
    }
    record Output(Inputs inputs,SceneTracks.Bytes mesh) {}
    record Job(Inputs inputs,Object cell,CompletableFuture<?> future) {}
    final Object world,grid;final String layout;
    final Map<SceneTerrainCodec.Coordinate,Output> outputs=new LinkedHashMap<>();
    final Map<SceneTerrainCodec.Coordinate,Job> jobs=new LinkedHashMap<>();
    Map<SceneTerrainCodec.Coordinate,Inputs> required=Map.of();
    Map<String,SceneTracks.Bytes> prefabVersion=Map.of();
    final Map<SceneTerrainCodec.Coordinate,Map<String,SceneTracks.Bytes>> prefabVersions=new HashMap<>();
    long builds,emptyBuilds,discarded,bytes;boolean closed;
    NativeSceneTerrainBake(Object world,String layout)throws Exception {
        this.world=world;this.layout=layout;grid=Native.field(Native.call(world,"throw","1376962110"),"strictfp");
        if(((Number)Native.field(grid,"transient")).intValue()!=16)throw new IOException("Complete recorded terrain preparation requires canonical 16-block cells");
    }
    static Map<SceneTerrainCodec.Coordinate,Inputs> dependencies(Map<SceneMapIndex.Coordinate,SceneTracks.Bytes> chunks,Map<String,SceneTracks.Bytes> prefabs){
        Map<SceneTerrainCodec.Coordinate,Inputs> result=new LinkedHashMap<>();
        for(var coordinate:new TreeSet<>(chunks.keySet())){
            List<SceneTracks.Bytes> neighbours=new ArrayList<>();
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)neighbours.add(chunks.get(new SceneMapIndex.Coordinate(coordinate.x()+x,coordinate.z()+z)));
            Inputs input=new Inputs(neighbours,prefabs);
            for(int y=0;y<256;y+=16)result.put(new SceneTerrainCodec.Coordinate(coordinate.x()*16,y,coordinate.z()*16,16),input);
        }
        return result;
    }
    boolean prepare(Map<SceneMapIndex.Coordinate,SceneTracks.Bytes> chunks,Map<String,SceneTracks.Bytes> prefabs)throws Exception {
        if(closed)throw new IllegalStateException("Closed terrain preparation");
        if(!prefabVersion.equals(prefabs))prefabVersion=Map.copyOf(prefabs);
        required=dependencies(chunks,prefabVersion);
        return pump();
    }
    boolean prepareLocal(Map<SceneMapIndex.Coordinate,SceneTracks.Bytes> chunks,Map<SceneTerrainCodec.Coordinate,Map<String,SceneTracks.Bytes>> prefabs)throws Exception {
        if(closed)throw new IllegalStateException("Closed terrain preparation");
        required=localDependencies(chunks,prefabs,prefabVersions);return pump();
    }
    static Map<SceneTerrainCodec.Coordinate,Inputs> localDependencies(Map<SceneMapIndex.Coordinate,SceneTracks.Bytes> chunks,Map<SceneTerrainCodec.Coordinate,Map<String,SceneTracks.Bytes>> prefabs,Map<SceneTerrainCodec.Coordinate,Map<String,SceneTracks.Bytes>> prefabVersions){
        Map<SceneTerrainCodec.Coordinate,Inputs> columns=dependencies(chunks,Map.of());
        Map<SceneTerrainCodec.Coordinate,Inputs> selected=new LinkedHashMap<>();
        for(var entry:columns.entrySet()){
            Map<String,SceneTracks.Bytes> next=prefabs.getOrDefault(entry.getKey(),Map.of()),previous=prefabVersions.get(entry.getKey());
            if(previous==null||!previous.equals(next))previous=Map.copyOf(next);
            prefabVersions.put(entry.getKey(),previous);selected.put(entry.getKey(),new Inputs(entry.getValue().chunks,previous));
        }
        prefabVersions.keySet().retainAll(columns.keySet());return selected;
    }
    boolean pump()throws Exception {
        // Drain only this preparation's completed outputs; the native draw loop pumps its GPU executor.
        for(var iterator=jobs.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();Job job=entry.getValue();if(!job.future.isDone())continue;
            iterator.remove();Object mesh=job.future.join();if(mesh==null)throw new IOException("Native terrain builder returned no result at "+entry.getKey().key());
            SourceAdapters.put(job.cell,"boolean",mesh);
            try{
                Inputs current=required.get(entry.getKey());if(!job.inputs.equals(current)){discarded++;continue;}
                SceneTracks.Bytes value;
                if(mesh==Native.field(Native.type("1488007653"),"protected")){
                    var coordinate=entry.getKey();int stride=((Number)Native.field(Native.type("32347481"),"import")).intValue();
                    value=SceneTerrainCodec.encode(new SceneTerrainCodec.Mesh(coordinate,layout,stride,0,new double[]{0,0,0,16,16,16},new byte[0],Arrays.asList(null,null,null)));emptyBuilds++;
                }else value=SceneTerrainCodec.capture(job.cell,layout);
                outputs.put(entry.getKey(),new Output(job.inputs,value));builds++;bytes+=value.size();
            }finally{SceneTerrainCodec.dispose(job.cell);}
        }
        outputs.keySet().retainAll(required.keySet());
        boolean ready=true;
        for(var entry:required.entrySet()){
            Output previous=outputs.get(entry.getKey());if(previous!=null&&previous.inputs.equals(entry.getValue()))continue;
            ready=false;if(jobs.size()>=16||jobs.containsKey(entry.getKey()))continue;
            var coordinate=entry.getKey();Object query=Native.type("581461543").getConstructor(int.class).newInstance(0);
            Object cell=Native.type("949691181").getConstructor(Native.type("581461543"),int.class,int.class,int.class,int.class).newInstance(query,16,coordinate.x()/16,coordinate.y()/16,coordinate.z()/16);
            Object job=Native.type("1913868594").getConstructor(Native.type("1102540382"),Native.type("949691181"),int.class).newInstance(grid,cell,0);
            CompletableFuture<?> future=(CompletableFuture<?>)Native.field(job,"implements");
            // The single native worker owns native meshing state; do not create a competing worker.
            Object worker=Native.field(grid,"short");
            enqueue(Native.field(grid,"super"),(java.util.concurrent.locks.ReentrantLock)Native.field(worker,"default"),(java.util.concurrent.locks.Condition)Native.field(worker,"assert"),job);
            jobs.put(coordinate,new Job(entry.getValue(),cell,future));
        }
        return ready&&jobs.isEmpty();
    }
    /** The canonical ring queue is not thread-safe. Use its worker's lock and wakeup. */
    static void enqueue(Object queue,java.util.concurrent.locks.ReentrantLock lock,java.util.concurrent.locks.Condition wake,Object job)throws Exception {
        lock.lock();
        try{Native.call(queue,"implements","void",job);wake.signalAll();}
        finally{lock.unlock();}
    }
    Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> selected()throws IOException {
        Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> result=new LinkedHashMap<>();
        for(var entry:required.entrySet()){
            Output output=outputs.get(entry.getKey());if(output==null||!output.inputs.equals(entry.getValue()))throw new IOException("Unprepared terrain coordinate "+entry.getKey().key());
            result.put(entry.getKey(),output.mesh);
        }
        return result;
    }
    Map<String,Object> diagnostic(){return Map.of("built_cells",builds,"empty_cells",emptyBuilds,"pending_cells",jobs.size(),"required_cells",required.size(),"invalidated_jobs",discarded,"portable_bytes",bytes);}
    public void close(){
        if(closed)return;closed=true;
        // Native futures still run during cancellation. Their owned buffers must be released
        // on the same GPU executor even if the user closes preparation between frames.
        for(Job job:jobs.values())job.future.whenCompleteAsync((mesh,failure)->{
            if(mesh==null)return;
            try{SourceAdapters.put(job.cell,"boolean",mesh);SceneTerrainCodec.dispose(job.cell);}
            catch(Exception cleanup){throw new IllegalStateException("Cannot dispose abandoned prepared terrain",cleanup);}
        },(java.util.concurrent.Executor)grid);
        jobs.clear();outputs.clear();prefabVersions.clear();required=Map.of();
    }
}
