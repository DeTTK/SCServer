package replay;

import java.io.*;
import java.util.*;

/** Decode recorded animation sync on an unregistered actor, never on a displayed actor. */
final class SceneFpMovement implements AutoCloseable {
    final NativeScenePlayback playback;final SceneTracks.ObjectTrack actor;final SceneTracks.ObjectState initial;
    final TreeMap<Long,SceneTracks.Value> rows=new TreeMap<>();
    Object decoder,sync;int cursor;boolean ready;SceneTracks.Track track;
    long beginNs;
    SceneFpMovement(NativeScenePlayback playback,SceneTracks.ObjectTrack actor,SceneTracks.ObjectState initial){this.playback=playback;this.actor=actor;this.initial=initial;}
    static SceneTracks.Numbers capture(Object entity)throws Exception {
        if(Native.type("378545641").isInstance(entity)){
            Object controller=Native.call(Native.type("1704556748"),"return","1704556748",entity),state=Native.call(controller,"if","1943200621");
            Object gait=Native.call(Native.type("496896492"),"valueOf","496896492",((Enum<?>)Native.field(state,"l")).name());
            float scale=((Number)Native.call(Native.type("873944585"),"extends","float",entity,Native.field(state,"l"))).floatValue();
            return new SceneTracks.Numbers(((Enum<?>)Native.call(state,"throw","430277821")).ordinal(),(boolean)Native.field(state,"n")?1:0,((Enum<?>)gait).ordinal(),((Number)Native.call(state,"this","float")).floatValue(),scale);
        }
        Object sync=Native.call(entity,"static","563585906",Native.type("1507399958"));return sync==null?null:sync(sync);
    }
    static SceneTracks.Numbers sync(Object sync)throws Exception {
        return new SceneTracks.Numbers(((Enum<?>)Native.call(sync,"extends","430277821")).ordinal(),(boolean)Native.call(sync,"assert","boolean")?1:0,((Enum<?>)Native.call(sync,"break","496896492")).ordinal(),((Number)Native.call(sync,"super","float")).floatValue(),((Number)Native.call(sync,"abstract","float")).floatValue());
    }
    boolean prepare(long deadline)throws Exception {
        if(ready)return true;
        if(actor.channels().containsKey("fp-movement")){track=actor.channels().get("fp-movement");ready=true;return true;}
        // Local intent was recorded independently of incoming remote animation sync.
        if(Native.type("378545641").isAssignableFrom(Native.type(actor.type()))){
            for(var event:playback.world.packets.demo.events){if(event.incoming()||!actor.alive(event.timeNs()))continue;var map=playback.world.packets.demo.mapping(event);if(map==null||!map.has("adapter")||!map.get("adapter").getAsString().equals("source_local_controls_v1"))continue;
                var frame=SelfMotion.read(event.payload());if(frame.mode()==null)continue;Object[] modes=(Object[])Native.call(Native.type("1785380642"),"values","[L1785380642;");String name=((Enum<?>)modes[frame.mode()]).name();Object gait=Native.call(Native.type("496896492"),"valueOf","496896492",name);
                rows.put(event.timeNs(),new SceneTracks.Numbers(((Enum<?>)Native.field(Native.type("430277821"),"STAND")).ordinal(),1,((Enum<?>)gait).ordinal(),0,0));
            }finish();return true;
        }
        if(decoder==null){beginNs=initial.channels().get("spawn-event") instanceof SceneTracks.Bytes b?SceneSpawnRecipe.decode(b).timeNs():actor.bornNs();decoder=new NativeSceneEntityFactory(playback.world.packets).create(playback.actors.world,initial,playback.fpRuntimeId());sync=Native.call(decoder,"static","563585906",Native.type("1507399958"));if(sync==null)throw new IOException("Recorded actor lacks animation sync: "+actor.id());rows.put(actor.bornNs(),sync(sync));}
        var demo=playback.world.packets.demo;
        while(cursor<demo.events.size()&&System.nanoTime()<deadline){var event=demo.events.get(cursor++);if(!event.incoming()||event.timeNs()<beginNs||actor.deadNs()>=0&&event.timeNs()>=actor.deadNs())continue;var map=demo.mapping(event);if(map==null||!map.has("adapter")||!map.get("adapter").getAsString().equals("source_motion_v1"))continue;
            var motion=Motion.read(event.payload());for(var update:motion.updates())if(update.entity()==actor.id().networkId()){
                ReplayDecode.begin(playback.world.packets.registryMaps);ReplayDecode.use(playback.world.packets.adapters);ReplayClock.beginDecode();
                try(var input=new DataInputStream(new ByteArrayInputStream(update.tail()))){playback.world.packets.adapters.tail(decoder,input,update.flags());if(input.available()!=0)throw new IOException("Unread FP movement sync");}finally{ReplayClock.endDecode();ReplayDecode.end();}
                var value=sync(sync);if(rows.isEmpty()||!rows.lastEntry().getValue().equals(value))rows.put(Math.max(actor.bornNs(),event.timeNs()),value);
            }
        }
        if(cursor==demo.events.size()){finish();close();}return ready;
    }
    void finish(){if(!rows.isEmpty()){var keys=new ArrayList<SceneTracks.Key>();rows.forEach((ns,value)->keys.add(new SceneTracks.Key(ns,value,false)));track=new SceneTracks.Track(SceneTracks.Domain.TRANSFORMS,SceneTracks.Blend.HOLD,keys);}ready=true;}
    SceneTracks.Numbers sample(long ns)throws IOException {if(track==null)return null;if(!track.prepare(ns,0,false))return null;return (SceneTracks.Numbers)track.sample(ns);}
    static String fpGait(String gait){return gait.equals("SUPER_SPRINT")?"SPRINT":gait;}
    static void apply(Object state,SceneTracks.Numbers value)throws Exception {
        if(value==null)return;double[] v=value.data();Object[] stances=(Object[])Native.call(Native.type("430277821"),"values","[L430277821;"),gaits=(Object[])Native.call(Native.type("496896492"),"values","[L496896492;");
        if(v.length!=5||v[0]<0||v[0]>=stances.length||v[2]<0||v[2]>=gaits.length)throw new IOException("FP movement schema changed");
        SourceAdapters.put(state,"ar",stances[(int)v[0]]);SourceAdapters.put(state,"try",stances[(int)v[0]]);SourceAdapters.put(state,"n",v[1]!=0);
        String name=fpGait(((Enum<?>)gaits[(int)v[2]]).name());SourceAdapters.put(state,"l",Native.call(Native.type("1785380642"),"switch","1785380642",name));
    }
    public void close()throws Exception {if(decoder!=null){Object previous=decoder;decoder=null;Native.call(playback.actors.world,"default","void",List.of(previous),new HashSet<>());}sync=null;}
}
