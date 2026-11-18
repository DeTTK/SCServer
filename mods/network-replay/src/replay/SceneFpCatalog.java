package replay;
import java.io.*;import java.util.*;

/** Prepare every supported player incarnation and native FP variant before admission. */
final class SceneFpCatalog implements AutoCloseable {
    final NativeScenePlayback playback;final List<SceneTracks.ObjectTrack> actors;
    final Map<SceneTracks.ObjectId,NativeReplayFp> contexts=new LinkedHashMap<>();
    final long budget=Math.min(1024L*1024*1024,Runtime.getRuntime().maxMemory()/4);
    int cursor,variantCursor,variants;long bytes;boolean ready,closed;List<Long> variantTimes;
    SceneFpCatalog(NativeScenePlayback playback)throws Exception {
        this.playback=playback;var actors=new ArrayList<SceneTracks.ObjectTrack>();for(var actor:playback.tracks.objects)if(!actor.type().startsWith("world-")&&Native.type("1253930690").isAssignableFrom(Native.type(actor.type())))actors.add(actor);this.actors=List.copyOf(actors);
    }
    static long first(SceneTracks.ObjectTrack actor){long first=actor.bornNs();for(String name:List.of("position","look","spawn-event")){var track=actor.channels().get(name);if(track!=null)first=Math.max(first,track.timeAt(0));}return first;}
    static List<Long> variants(SceneTracks.ObjectTrack actor,SceneFpPoses poses){var times=new TreeSet<Long>();times.add(first(actor));for(var entry:actor.channels().entrySet())if(SceneActorCatalog.boundary(entry.getKey()))for(var key:entry.getValue().keys)if(actor.alive(key.timeNs())&&key.timeNs()>=first(actor))times.add(key.timeNs());for(var key:poses.itemKeys)if(actor.alive(key.timeNs()))times.add(key.timeNs());return List.copyOf(times);}
    boolean step(long deadline)throws Exception {
        if(closed)throw new IllegalStateException("Closed FP catalog");if(ready)return true;
        while(cursor<actors.size()&&System.nanoTime()<deadline){
            var actor=actors.get(cursor);NativeReplayFp fp=contexts.get(actor.id());
            if(fp==null){long first=first(actor);var initial=playback.tracks.fpResources(actor,first);if(initial==null)return false;var nativeActor=playback.actors.pool.objects.get(playback.actors.pool.resources.cacheKey(initial));if(nativeActor==null)throw new IOException("FP catalog has no native actor: "+actor.id());fp=new NativeReplayFp(playback);contexts.put(actor.id(),fp);fp.initialize(nativeActor.entity,initial,first);fp.catalogued=true;}
            if(!fp.prepareIndex(deadline)||!fp.saved())return false;
            if(variantTimes==null){bytes=Math.addExact(bytes,fp.poses.bytes);if(bytes>budget)throw new IOException("FP histories exceed resident budget");variantTimes=variants(actor,fp.poses);variantCursor=0;}
            while(variantCursor<variantTimes.size()&&System.nanoTime()<deadline){long ns=variantTimes.get(variantCursor);var state=playback.tracks.fpResources(actor,ns);if(state==null||!fp.prepareVariant(state,ns,deadline))return false;variantCursor++;variants++;}
            if(variantCursor<variantTimes.size())return false;
            cursor++;variantTimes=null;
        }
        ready=cursor==actors.size();return ready;
    }
    Map<String,Object> diagnostic(){return Map.of("ready",ready,"prepared_actors",cursor,"total_actors",actors.size(),"prepared_variants",variants,"pose_bytes",bytes,"budget_bytes",budget,"current_actor",cursor<actors.size()?actors.get(cursor).id().toString():"");}
    public void close()throws Exception {if(closed)return;closed=true;Exception failure=null;for(var fp:contexts.values())try{fp.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}contexts.clear();if(failure!=null)throw failure;}
}
