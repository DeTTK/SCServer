package szserver.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Worker-owned cache of declared native AABB assets. Missing/corrupt declared data is explicit. */
public final class CollisionBoxes {
    public static final long DEFAULT_BYTES=32L*1024*1024;
    private record Cached(List<SceneCollision.Box> boxes,long bytes){}
    private final Path root;private final long limit;private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(16,.75f,true);private long retained,loads,evictions,failures,caseFallbacks,caseIndexBytes;
    private Map<String,Path> caseIndex;private Set<String> ambiguousCase;
    private static final int MAX_CASE_ASSETS=65536;private static final long MAX_CASE_BYTES=16L*1024*1024;
    public CollisionBoxes(Path root){this(root,DEFAULT_BYTES);}
    private ActorMotion feedbackMotion;
    ActorContacts.Obstacle livingObstacle(SceneObject object,SceneRegistry.Definition definition){return feedbackMotion!=null&&feedbackMotion.mapObjectsEnabled()?feedbackMotion.livingObstacle(definition,object):null;}
    public CollisionBoxes(Path root,ActorMotion motion){this(root);feedbackMotion=motion;}
    public SceneCollision.Shape feedbackShape(SceneObject object,SceneRegistry.Definition definition)throws IOException{
        return feedbackShape(object,feedback(definition));
    }
    SceneCollision.Shape feedbackShape(SceneObject object,List<SceneCollision.Box> asset){
        if(asset.isEmpty())return null;
        if(feedbackMotion==null)throw new IllegalStateException("native_feedback_geometry_unavailable");
        return SceneCollision.feedbackShape(object.identity(),feedbackMotion.feedbackBounds(object.affine(),asset));
    }
    SceneCollision.Shape anomalyGeometry(SceneObject object,SceneRegistry.Definition definition)throws IOException{return feedbackMotion==null?SceneCollision.shape(object,feedback(definition)):feedbackShape(object,definition);}
    void mapObject(CollisionTree.Builder builder,int x,int z,boolean container,int occurrence,SceneObject object,SceneRegistry.Definition definition){
        if(!ActorSceneObject.supported(definition)||!object.feedbackActive()||object.behaviour(definition.behaviour()).equals("ladder"))return;
        boolean anomaly=szserver.client.AnomalySettings.supported(definition.behaviour());
        if(!anomaly&&(feedbackMotion==null||!feedbackMotion.mapObjectsEnabled()))return;
        ActorSceneObject descriptor;
        if(anomaly&&(!definition.behaviour().equals("carousel")||feedbackMotion==null||!feedbackMotion.mapObjectsEnabled())){
            var bounds=AnomalyZone.indexBounds(definition,object);
            if(Set.of("simple_anomaly","puh","steam","lighter","mirror").contains(definition.behaviour()))try{var feedback=anomalyGeometry(object,definition);if(feedback!=null)bounds=feedback.bounds();}catch(IOException missing){return;}
            descriptor=new ActorSceneObject(definition,object,bounds);
        }else descriptor=feedbackMotion.describeMapObject(definition,object);if(descriptor==null)return;var shape=SceneCollision.feedbackShape(object.identity(),List.of(descriptor.bounds()));
        builder.add(new CollisionTree.Item(x,z,container,occurrence,shape,null,false,descriptor));
    }
    boolean ladderSource(SceneObject object,SceneRegistry.Definition definition){return definition!=null&&object.behaviour(definition.behaviour()).equals("ladder")&&szserver.client.SceneSettings.ladderSource(object);}
    void ladder(CollisionTree.Builder target,int x,int z,boolean container,int occurrence,SceneObject object,SceneRegistry.Definition definition)throws IOException{
        if(!ladderSource(object,definition))return;
        var shape=SceneCollision.shape(object,get(definition,true));if(shape!=null)target.add(new CollisionTree.Item(x,z,container,occurrence,shape,null,true));
    }
    public CollisionBoxes(Path root,long limit){if(limit<256||limit>128L*1024*1024)throw new IllegalArgumentException("collision_cache_limit");this.root=root.toAbsolutePath().normalize();this.limit=limit;}
    public List<SceneCollision.Box> get(SceneRegistry.Definition asset)throws IOException{
        return get(asset,false);
    }
    /** Native switchable_collision keeps frame geometry and selects closed_/open_ mesh groups. */
    public List<SceneCollision.Box> get(SceneRegistry.Definition asset,SceneObject object)throws IOException{
        String behavior=asset==null?"":object.behaviour(asset.behaviour());if(!behavior.equals("switchable_collision"))return get(asset,false);
        String fallback=asset.definition().has("default_state")?asset.definition().get("default_state").getAsString():"closed";
        boolean closed=szserver.client.SceneAttributes.objectState(object,behavior,fallback).equals("closed");return get(asset,false,closed?"closed":"open");
    }
    /** Feedback uses the declared AABB even when physical collision is disabled. */
    public List<SceneCollision.Box> feedback(SceneRegistry.Definition asset)throws IOException{return get(asset,true);}
    private List<SceneCollision.Box> get(SceneRegistry.Definition asset,boolean feedback)throws IOException{return get(asset,feedback,"");}
    private List<SceneCollision.Box> get(SceneRegistry.Definition asset,boolean feedback,String state)throws IOException{
        SceneCollision.worker();if(asset==null)throw new IllegalArgumentException("collision_asset_unknown");var definition=asset.definition();
        if(asset.behaviour().equals("culling_volume")||!feedback&&definition.has("collision")&&definition.get("collision").isJsonPrimitive()&&definition.get("collision").getAsString().equals("none")||!definition.has("aabb")||!definition.get("aabb").isJsonPrimitive()||!definition.get("aabb").getAsJsonPrimitive().isString())return List.of();
        String name=definition.get("aabb").getAsString();String cacheKey=state.isEmpty()?name:name+"#door="+state;Cached found=cache.get(cacheKey);if(found!=null)return found.boxes();
        int colon=name.indexOf(':');String relative=colon<0?name:name.substring(0,colon)+"/"+name.substring(colon+1);Path path=root.resolve("assets").resolve(relative).normalize();if(!path.startsWith(root)||!path.startsWith(root.resolve("assets")))throw new IllegalArgumentException("invalid_collision_asset_path");
        try{Path real;try{real=path.toRealPath();}catch(NoSuchFileException missing){Path candidate=caseInsensitive(root.resolve("assets"),relative);if(candidate==null)throw missing;real=candidate.toRealPath();caseFallbacks++;}Path base=root.toRealPath();if(!real.startsWith(base))throw new IllegalArgumentException("invalid_collision_asset_path");long size=Files.size(real);if(size>SceneCollision.MAX_ASSET_BYTES)throw new IllegalArgumentException("collision_asset_size_limit");byte[] data;try(InputStream in=Files.newInputStream(real)){data=in.readNBytes(SceneCollision.MAX_ASSET_BYTES+1);}if(data.length>SceneCollision.MAX_ASSET_BYTES)throw new IllegalArgumentException("collision_asset_size_limit");List<SceneCollision.Box> boxes=state.isEmpty()?SceneCollision.decode(data):SceneCollision.decode(data,part->part.startsWith("closed_")?state.equals("closed"):!part.startsWith("open_")||state.equals("open"));long bytes=96L+cacheKey.length()*2L+boxes.size()*80L;loads++;
            if(bytes<=limit){while(!cache.isEmpty()&&(retained+bytes>limit||cache.size()>=4096)){var it=cache.entrySet().iterator();retained-=it.next().getValue().bytes();it.remove();evictions++;}cache.put(cacheKey,new Cached(boxes,bytes));retained+=bytes;}return boxes;
        }catch(IOException|IllegalArgumentException error){failures++;throw error;}
    }
    /** This worker-owned resource catalog is immutable for the lifetime of the cache. Only AABBs are indexed. */
    private Path caseInsensitive(Path assets,String relative)throws IOException{
        if(caseIndex==null){
            Map<String,Path> built=new HashMap<>();Set<String> ambiguous=new HashSet<>();long bytes=0;int count=0;
            if(Files.isDirectory(assets))try(var walk=Files.walk(assets)){
                var files=walk.iterator();while(files.hasNext()){
                    Path file=files.next();if(!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".aabb")||!Files.isRegularFile(file))continue;
                    String key=assets.relativize(file).toString().replace('\\','/').toLowerCase(Locale.ROOT);bytes+=192L+key.length()*4L;
                    if(++count>MAX_CASE_ASSETS||bytes>MAX_CASE_BYTES)throw new IOException("collision_case_index_limit");
                    Path previous=built.putIfAbsent(key,file);if(previous!=null&&!previous.equals(file))ambiguous.add(key);
                }
            }catch(UncheckedIOException error){throw error.getCause();}
            caseIndex=Map.copyOf(built);ambiguousCase=Set.copyOf(ambiguous);caseIndexBytes=bytes;
        }
        String key=relative.replace('\\','/').toLowerCase(Locale.ROOT);if(ambiguousCase.contains(key))throw new IOException("ambiguous_collision_asset_case:"+key);return caseIndex.get(key);
    }
    public Map<String,Object> metrics(){return Map.of("cached",cache.size(),"retainedBytes",retained,"limitBytes",limit,"loads",loads,"evictions",evictions,"failures",failures,"caseFallbacks",caseFallbacks,"caseIndexEntries",caseIndex==null?0:caseIndex.size(),"caseIndexBytes",caseIndexBytes);}
}
