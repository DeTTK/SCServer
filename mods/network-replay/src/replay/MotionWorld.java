package replay;

import java.io.*;
import java.util.*;

/** Feed source fixed32 poses into native snapshot interpolation, retaining tails. */
final class MotionWorld {
    final SourceAdapters adapters;
    int updates,skippedUnloaded,skippedMissing,skippedNonActor,queued,immediate;
    final Map<Integer,Integer> missingEntities=new TreeMap<>();
    final Map<Integer,Map<String,Object>> missingEntityDetails=new TreeMap<>();
    final Map<String,Integer> nonActorClasses=new TreeMap<>();
    long receivedTailBytes,missingTailBytes,nonActorTailBytes;int pendingEntityUpdates;
    int activatedPendingEntities,failedPendingActivations;
    int hiddenRemainsUpdates,missingChunkUpdates;
    MotionWorld(SourceAdapters adapters){this.adapters=adapters;}
    void reset(){updates=skippedUnloaded=skippedMissing=skippedNonActor=queued=immediate=pendingEntityUpdates=activatedPendingEntities=failedPendingActivations=hiddenRemainsUpdates=missingChunkUpdates=0;receivedTailBytes=missingTailBytes=nonActorTailBytes=0;missingEntities.clear();missingEntityDetails.clear();nonActorClasses.clear();}
    static int nativeFlags(int source){return ((source&2)!=0?64:0)|((source&64)!=0?32:0)|((source&4)!=0?8:0)|((source&32)!=0?4:0)|((source&1)!=0?16:0)|((source&8)!=0?1:0)|((source&16)!=0?2:0);}
    void apply(Object connection,Motion.Frame frame)throws Exception {
        for(String name:List.of("488132318","834790994","1754157392","456314467","752186780","1054393445","1500514786","576005245","153304200","514314713"))adapters.verify(name);
        Object world=Native.call(connection,"instanceof","1054393445"),manager=Native.call(world,"synchronized","488132318");SourceAdapters.put(manager,"int",frame.tick());
        Object registry=Native.call(world,"package","865047575",Native.type("752186780"));
        for(Motion.Update update:frame.updates()){
            receivedTailBytes+=update.tail().length;
            Object entity=Native.call(world,"continue","1217489680",update.entity());
            if(entity==null){
                Object pending=null;for(Object candidate:(Set<?>)Native.call(world,"throws","java.util.Set"))if(((Number)Native.call(candidate,"for","int")).intValue()==update.entity()){pending=candidate;break;}
                Map<String,Object> detail=missingEntityDetails.computeIfAbsent(update.entity(),id->{Map<String,Object> d=new LinkedHashMap<>();d.put("first_time_seconds",ReplayClock.seconds);return d;});
                detail.put("last_time_seconds",ReplayClock.seconds);detail.put("pending",pending!=null);
                if(pending!=null){
                    // Use the game's decoded current position, not its encoded
                    // coordinate getters, to identify the actual admission gate.
                    Object p=Native.call(Native.type("576005245"),"char","153304200",pending,1f);
                    double x=((Number)Native.field(p,"case")).doubleValue(),z=((Number)Native.field(p,"import")).doubleValue();
                    int cx=((Number)Native.call(Native.type("514314713"),"break","int",x/16.0)).intValue(),cz=((Number)Native.call(Native.type("514314713"),"break","int",z/16.0)).intValue();
                    boolean chunkPresent=(boolean)Native.call(world,"instanceof","boolean",cx,cz);
                    detail.put("class",pending.getClass().getName());detail.put("x",x);detail.put("z",z);detail.put("chunk_x",cx);detail.put("chunk_z",cz);detail.put("chunk_present",chunkPresent);
                    boolean hiddenRemains=false;
                    if(Native.type("544082630").isInstance(pending)){
                        adapters.verify("544082630");
                        hiddenRemains=(boolean)Native.call(pending,"i","boolean");
                        int owner=((Number)Native.field(pending,"case")).intValue();
                        detail.put("native_hidden_remains",hiddenRemains);detail.put("remains_kind",String.valueOf(Native.field(pending,"import")));detail.put("discard_without_pose",Native.call(pending,"h","boolean"));detail.put("remains_owner_id",owner);detail.put("owner_registered",Native.call(world,"continue","1217489680",owner)!=null);
                    }
                    // Retry the game's own chunk/event admission. Packet batches
                    // must not outrun its ten-entities-per-tick retry queue when
                    // the required chunk is already available. No world tick or
                    // gameplay time is advanced here; a missing chunk still fails.
                    if(chunkPresent&&!hiddenRemains&&(boolean)Native.call(world,"assert","boolean",pending)){
                        entity=Native.call(world,"continue","1217489680",update.entity());
                        if(entity==null)throw new IOException("Native actor admission succeeded without registration: "+update.entity());
                        activatedPendingEntities++;
                    }else {failedPendingActivations++;if(hiddenRemains)hiddenRemainsUpdates++;if(!chunkPresent)missingChunkUpdates++;}
                }
            }
            // The source dispatcher has the same actor guard before consuming
            // a record; distinguish absent entities from loaded non-actors.
            if(entity==null){skippedUnloaded++;skippedMissing++;missingTailBytes+=update.tail().length;missingEntities.merge(update.entity(),1,Integer::sum);
                for(Object pending:(Set<?>)Native.call(world,"throws","java.util.Set"))if(((Number)Native.call(pending,"for","int")).intValue()==update.entity()){pendingEntityUpdates++;break;}
                continue;}
            if(!Native.type("820497227").isInstance(entity)){skippedUnloaded++;skippedNonActor++;nonActorTailBytes+=update.tail().length;nonActorClasses.merge(entity.getClass().getName(),1,Integer::sum);continue;}
            updates++;Object component=Native.call(registry,"instanceof","563585906",update.entity());
            if(component==null){
                Object input=Native.type("1754157392").getConstructor(byte[].class).newInstance((Object)update.tail());
                ReplayDecode.defer(input,update.flags(),update.tail().length);ReplayDecode.tail(entity,input);immediate++;continue;
            }
            int[] position=update.absolute();boolean changed=position!=null;
            if(position!=null)Native.call(manager,"char","void",component,position[0],position[1],position[2]);
            if(update.delta()!=null){position=new int[]{((Number)Native.call(manager,"interface","int",component)).intValue()+update.delta()[0],((Number)Native.call(manager,"abstract","int",component)).intValue()+update.delta()[1],((Number)Native.call(manager,"catch","int",component)).intValue()+update.delta()[2]};Native.call(manager,"char","void",component,position[0],position[1],position[2]);changed=true;}
            int[] look=update.look();Object snapshot=Native.call(manager,"static","834790994",component);
            Native.call(manager,"void","void",component,snapshot,changed?position[0]/32.0:0.0,changed?position[1]/32.0:0.0,changed?position[2]/32.0:0.0,look==null?0f:look[0]*360f/256f,look==null?0f:look[1]*360f/256f,changed,look!=null,(update.flags()&8)!=0);
            SourceAdapters.put(snapshot,"int",(byte)nativeFlags(update.flags()));Object input=Native.field(snapshot,"if");Native.call(input,"char","1754157392");Native.call(input,"write","void",update.tail());Native.call(input,"char","1754157392");ReplayDecode.defer(input,update.flags(),update.tail().length,update.entity(),component,snapshot);queued++;
        }
    }
}
