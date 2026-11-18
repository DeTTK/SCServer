package szserver.world;

import szserver.util.Workers;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Packed global forest. Only changed native-home partitions are replaced on query workers. */
public final class SceneCollisionCatalog implements AutoCloseable {
    private static final int PARTS=64;
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.szr");
    private record Forest(NativeCommitIndex index,List<CollisionTree> parts,CollisionTree tree){}
    private final ThreadPoolExecutor loader=Workers.pool("scene-collision-catalog",1,1);
    private final CompletableFuture<Boolean> loaded=new CompletableFuture<>();private volatile boolean stopped,failed;
    private volatile Map<String,Object> metrics=Map.of("ready",false,"error","");private volatile Forest current;
    private final Path root;private final SceneRegistry registry;private final CollisionBoxes boxes;private final long limit;private final java.util.function.Supplier<NativeCommitIndex> nativeBase;
    private long sourceChunks,rebuilds,assetBytes;private final Map<String,List<SceneCollision.Box>> retainedAssets=new HashMap<>();
    public SceneCollisionCatalog(Path root,SceneRegistry registry,Path assets,long bytes){this(root,registry,assets,bytes,NativeCommitIndex::empty);}
    public SceneCollisionCatalog(Path root,SceneRegistry registry,Path assets,long bytes,java.util.function.Supplier<NativeCommitIndex> nativeBase){
        this(root,registry,assets,bytes,nativeBase,null);
    }
    public SceneCollisionCatalog(Path root,SceneRegistry registry,Path assets,long bytes,java.util.function.Supplier<NativeCommitIndex> nativeBase,ActorMotion motion){
        this.root=root;this.registry=registry==null?null:registry.fork();this.boxes=new CollisionBoxes(assets,motion);this.limit=bytes;this.nativeBase=nativeBase;
        new CollisionTree.Builder(bytes);
        if(registry==null){var empty=emptyParts();current=new Forest(NativeCommitIndex.empty(),empty,CollisionTree.forest(empty));loaded.complete(true);publish();return;}
        NativeCommitIndex captured=nativeBase.get();loader.execute(()->scan(captured));
    }
    private static List<CollisionTree> emptyParts(){List<CollisionTree> parts=new ArrayList<>(PARTS);for(int n=0;n<PARTS;n++)parts.add(CollisionTree.empty());return List.copyOf(parts);}
    private static int part(long key){int x=Math.floorDiv((int)(key>>32),32),z=Math.floorDiv((int)key,32);long mixed=BlockOverlays.key(x,z);mixed^=mixed>>>33;mixed*=0xff51afd7ed558ccdL;mixed^=mixed>>>33;return (int)mixed&(PARTS-1);}
    private void scan(NativeCommitIndex captured){
        try{SceneCollision.worker();List<CollisionTree.Builder> builders=new ArrayList<>();for(int n=0;n<PARTS;n++)builders.add(new CollisionTree.Builder(limit,true));Set<Long> nativeSeen=new HashSet<>();List<Path> files=new ArrayList<>();
            try(var stream=Files.newDirectoryStream(root,"r.*.*.szr")){for(Path path:stream){if(files.size()>=SceneCatalog.MAX_REGIONS)throw new IOException("scene_catalog_region_limit");files.add(path);}}files.sort(Comparator.comparing(p->p.getFileName().toString()));
            for(Path path:files){Matcher name=REGION.matcher(path.getFileName().toString());if(!name.matches())throw new IOException("scene_catalog_region_name");int rx=Integer.parseInt(name.group(1)),rz=Integer.parseInt(name.group(2));if(rx<MapBounds.REGION_MIN||rx>MapBounds.REGION_MAX||rz<MapBounds.REGION_MIN||rz>MapBounds.REGION_MAX)throw new IOException("scene_catalog_region_bounds");
                try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)){RegionTerrain.regionHeader(file,rx,rz);for(int cell=0;cell<1024;cell++){
                    if(stopped||Thread.currentThread().isInterrupted())throw new InterruptedException("scene_collision_stopped");byte[] record=RegionTerrain.regionRecord(file,cell);if(record==null)continue;int x=rx*32+cell%32,z=rz*32+cell/32;ByteBuffer h=ByteBuffer.wrap(record);if(h.getInt()!=x||h.getInt()!=z)throw new IOException("scene_catalog_chunk_coordinate");long key=BlockOverlays.key(x,z);
                    if(captured.revision(x,z)>0)nativeSeen.add(key);add(builders.get(part(key)),x,z,entries(captured,x,z,record));sourceChunks++;budget(builders);
                }}
            }
            for(long key:captured.revisions().keySet())if(!nativeSeen.contains(key)){int x=(int)(key>>32),z=(int)key;add(builders.get(part(key)),x,z,entries(captured,x,z,null));sourceChunks++;budget(builders);}
            List<CollisionTree> parts=new ArrayList<>();for(var builder:builders)parts.add(builder.finish());current=new Forest(captured.differenceView(),List.copyOf(parts),CollisionTree.forest(parts));publish();loaded.complete(true);
        }catch(Throwable error){failed=true;Throwable cause=error;for(int n=0;n<12&&cause.getCause()!=null&&cause.getCause()!=cause;n++)cause=cause.getCause();String detail=cause.getClass().getSimpleName()+":"+Objects.toString(cause.getMessage(),"");metrics=Map.of("ready",false,"chunks",sourceChunks,"error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()),"cause",detail.substring(0,Math.min(detail.length(),512)));loaded.completeExceptionally(error);}
    }
    private void budget(List<CollisionTree.Builder> builders){long total=assetBytes;for(var builder:builders)total+=builder.retainedBytes();if(total>limit)throw new IllegalArgumentException("scene_collision_memory_limit");}
    private Map<UUID,ScenePayload.Entry> entries(NativeCommitIndex index,int x,int z,byte[] original)throws IOException{
        var nativeChunk=index.readChunk(x,z);if(nativeChunk!=null)return ScenePayload.index(ScenePayload.parse(nativeChunk.record()).channels(),registry.target());
        if(original==null)try(FileChannel file=FileChannel.open(root.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".szr"),StandardOpenOption.READ)){RegionTerrain.regionHeader(file,Math.floorDiv(x,32),Math.floorDiv(z,32));original=RegionTerrain.regionRecord(file,Math.floorMod(z,32)*32+Math.floorMod(x,32));}catch(NoSuchFileException missing){return Map.of();}
        if(original==null)return Map.of();try{return ScenePayload.index(registry.project(original,RegionTerrain.capturedSceneChannels(original)),registry.target());}catch(Exception error){throw new IOException("scene_projection_failed",error);}
    }
    private void add(CollisionTree.Builder builder,int x,int z,Map<UUID,ScenePayload.Entry> entries)throws IOException{
        for(var entry:entries.values()){int occurrence=0;for(var physical:entry.occurrences()){
            var object=physical.object();var definition=registry.target().names().get(object.asset());
            var shape=SceneCollision.shape(object,asset(definition,false));if(shape!=null)builder.add(new CollisionTree.Item(x,z,physical.container(),occurrence,shape));
            if(boxes.ladderSource(object,definition)){var ladder=SceneCollision.shape(object,asset(definition,true));if(ladder!=null)builder.add(new CollisionTree.Item(x,z,physical.container(),occurrence,ladder,null,true));}
            boxes.mapObject(builder,x,z,physical.container(),occurrence,object,definition);
            var effect=SceneFeedback.effect(object,definition,boxes);
            if(effect!=null){var feedback=boxes.feedbackShape(object,asset(definition,true));if(feedback!=null){builder.reserve(80L*feedback.assetBoxes().size());builder.add(new CollisionTree.Item(x,z,physical.container(),occurrence,feedback,effect));}}
            occurrence++;
        }}
    }
    private List<SceneCollision.Box> asset(SceneRegistry.Definition definition,boolean feedback)throws IOException{
        if(definition==null)throw new IllegalArgumentException("collision_asset_unknown");
        String key=(feedback?"feedback:":"solid:")+definition.name();var value=retainedAssets.get(key);
        if(value==null){value=feedback?boxes.feedback(definition):boxes.get(definition);long charge=128L+key.length()*2L+80L*value.size();if(charge>limit-assetBytes)throw new IllegalArgumentException("scene_collision_memory_limit");assetBytes+=charge;retainedAssets.put(key,value);}return value;
    }
    /** Exact base captured with the voxel view. A later publication invalidates that view's guard. */
    public CollisionTree current(NativeCommitIndex index)throws IOException{
        SceneCollision.worker();if(!loaded.isDone())throw new IllegalArgumentException("terrain_not_ready_retry");try{loaded.join();}catch(CompletionException error){throw new IllegalArgumentException("scene_collision_catalog_failed",error.getCause());}
        if(index.generation().equals(current.index.generation()))return current.tree;
        Set<Long> changed=index.changedChunksSince(current.index);Set<Integer> changedParts=new HashSet<>();for(long key:changed)changedParts.add(part(key));List<CollisionTree> parts=new ArrayList<>(current.parts);
        for(int n:changedParts){CollisionTree.Builder builder=new CollisionTree.Builder(limit,true);current.parts.get(n).forEachItem(item->{if(!changed.contains(BlockOverlays.key(item.homeX(),item.homeZ()))){if(item.feedback()!=null)builder.reserve(80L*item.shape().assetBoxes().size());builder.add(item);}});
            for(long key:changed)if(part(key)==n){int x=(int)(key>>32),z=(int)key;add(builder,x,z,entries(index,x,z,null));}parts.set(n,builder.finish());}
        long bytes=parts.stream().mapToLong(CollisionTree::retainedBytes).sum();if(bytes+assetBytes>limit)throw new IllegalArgumentException("scene_collision_memory_limit");
        current=new Forest(index.differenceView(),List.copyOf(parts),CollisionTree.forest(parts));rebuilds++;publish();return current.tree;
    }
    public CollisionTree current(){try{return current(nativeBase.get());}catch(IOException error){throw new IllegalArgumentException("scene_collision_native_load_failed",error);}}
    private void publish(){metrics=Map.ofEntries(Map.entry("ready",true),Map.entry("shapes",current.tree.size()),Map.entry("chunks",sourceChunks),Map.entry("retainedBytes",current.tree.retainedBytes()+assetBytes),Map.entry("packedBytes",current.tree.packedBytes()),Map.entry("nativeGeneration",current.index.generation()),Map.entry("rebuilds",rebuilds),Map.entry("partitions",PARTS),Map.entry("assetCache",boxes.metrics()),Map.entry("error",""));}
    public Map<String,Object> metrics(){return metrics;}public boolean healthy(){return !stopped&&!failed&&!loaded.isCompletedExceptionally();}public boolean ready(){return healthy()&&loaded.isDone();}
    @Override public void close()throws InterruptedException{stopped=true;Workers.stop(loader);if(!loaded.isDone())loaded.completeExceptionally(new IllegalStateException("scene_collision_stopped"));}
}
