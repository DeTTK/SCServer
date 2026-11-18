package replay;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import com.google.gson.*;

/** Explicit synthetic scene, available only to the hidden local QA launcher. */
final class ExtractorSceneQa {
    final boolean enabled=ReplayRender.testInput()&&Boolean.getBoolean("sz.replay.qa.extractor.scene");
    Object world,manager,entity,component;Map<String,Object> attributes;
    long generations;String phase="";boolean admitted,viewed;
    void reset()throws Exception {
        try{if(manager!=null&&entity!=null)Native.call(manager,"for","void",entity,false);}
        finally{world=manager=entity=component=null;attributes=null;phase="";admitted=viewed=false;}
    }
    void step(Object game,NativePackets.World replay)throws Exception {
        if(!enabled||!replay.bootstrapped||ReplayClock.seconds<5)return;
        Object nativeWorld=Native.call(replay.connection,"instanceof","1054393445");if(nativeWorld==null)return;
        if(world!=null&&world!=nativeWorld)reset();
        if(manager!=null&&Native.call(manager,"throws","1886271463",Native.call(entity,"const","java.util.UUID"))!=entity)reset();
        if(entity==null){
            double[] point=replay.sourceTimeline.effects.playerPosition();if(point==null)return;
            Object source=Native.call(Native.type("1814125595"),"throws","1814125595","extractor_small_inside");
            if(source==null)throw new IllegalStateException("QA extractor prefab is unavailable");
            Object clone=Native.type("1814125595").getConstructor(String.class,Native.type("1669874697"),boolean.class).newInstance(Native.field(source,"while"),Native.field(source,"goto"),Native.field(source,"super"));
            for(Field field:Native.type("1814125595").getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(clone,field.get(source));}
            Object original=Native.field(source,"void");JsonObject json=((JsonObject)Native.field(original,"strictfp")).deepCopy();
            json.addProperty("extraction_vfx","effects:particles/anomalies/artefact_extractor/working_effect.efkefc");json.addProperty("extraction_sfx","anomalies:resources.device_idle_inside");json.addProperty("progress_animation_name","work");
            Object parameters=Native.type("1422486348").getConstructor(String.class,JsonObject.class,int.class,String.class,Integer.class,String.class).newInstance(Native.field(original,"void"),json,Native.field(original,"instanceof"),Native.field(original,"const"),Native.field(original,"case"),Native.field(original,"goto"));
            SourceAdapters.put(clone,"void",parameters);
            entity=Native.call(clone,"new","1886271463",UUID.nameUUIDFromBytes(("extractor-scene-qa:"+(++generations)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Object transform=Native.field(entity,"m");Native.call(transform,"catch","447252483",(float)(point[0]+3));Native.call(transform,"native","447252483",(float)point[1]);Native.call(transform,"default","447252483",(float)point[2]);
            Native.call(entity,"break","void");component=Native.field(entity,"byte");
            Object container=Native.call(entity,"float","1536302723");attributes=new LinkedHashMap<>();replay.packets.adapters.extras.put(container,attributes);update();
            Object configuration=Native.type("98008865").getConstructor().newInstance();
            Native.call(configuration,"strictfp","void","EnableHud",true);Native.call(configuration,"super","void","HudRadius",100f);Native.call(configuration,"super","void","health",100f);Native.call(configuration,"super","void","maxHealth",100f);Native.call(configuration,"super","void","personalRewardRange",3f);
            Native.call(component,"char","void",configuration);
            world=nativeWorld;manager=Native.call(Native.type("493563411"),"try","493563411",world);Native.call(manager,"enum","void",null,entity,false);
            Native.call(Native.field(Native.type("1651476251"),"if"),"double","void",component);
        }
        update();admitted=Native.call(manager,"throws","1886271463",Native.call(entity,"const","java.util.UUID"))==entity;
    }
    void update(){
        SourceExtractorState.Phase next=ReplayClock.seconds<10?SourceExtractorState.Phase.LOADING:ReplayClock.seconds<40?SourceExtractorState.Phase.EXTRACTING:SourceExtractorState.Phase.FINISHED;
        double changed=next==SourceExtractorState.Phase.LOADING?5:next==SourceExtractorState.Phase.EXTRACTING?10:40;
        attributes.put("GOResourceExtractor_state",new SourceExtractorState.State(next,ReplayClock.wallMillis()-(long)((ReplayClock.seconds-changed)*1000),changed));
        attributes.put("GOResourceExtractor_stateDuration",Duration.ofSeconds(next==SourceExtractorState.Phase.LOADING?5:30));phase=next.name();
        attributes.put("GOResourceExtractor_needActivation",true);
    }
    void view(FreeCamera camera)throws Exception {
        if(!enabled||viewed||entity==null||camera.pose==null)return;
        double x=((Number)Native.call(entity,"case","float")).doubleValue(),y=((Number)Native.call(entity,"try","float")).doubleValue(),z=((Number)Native.call(entity,"throw","float")).doubleValue();
        camera.yaw=camera.pitch=0;camera.pose=new Pose(new Pose.V(x,y+2,z+4),Pose.Q.euler(0,0,0));camera.following=-1;viewed=true;
    }
    JsonObject snapshot(NativePackets.World replay)throws Exception {
        JsonObject result=new JsonObject();result.addProperty("enabled",enabled);result.addProperty("synthetic_fixture",true);result.addProperty("capture_evidence",false);result.addProperty("generations",generations);result.addProperty("created",entity!=null);result.addProperty("admitted",admitted);result.addProperty("phase",phase);
        if(entity!=null){result.addProperty("renderer",Native.field(entity,"final")!=null);result.addProperty("world_attached",Native.field(entity,"q")!=null);result.addProperty("x",((Number)Native.call(entity,"case","float")).floatValue());result.addProperty("y",((Number)Native.call(entity,"try","float")).floatValue());result.addProperty("z",((Number)Native.call(entity,"throw","float")).floatValue());result.addProperty("activation_prompt",(String)Native.call(component,"catch","java.lang.String"));
            SourceExtractorController.Entry entry=replay.sourceExtractors.entries.get(component);
            if(entry!=null&&entry.voice!=-1){
                Object engine=Native.field(Native.call(Native.type("1831969298"),"extends","1831969298"),"else"),events=Native.field(engine,"const");
                boolean event=(boolean)Native.call(events,"int","boolean",entry.voice);Object registry=event?events:Native.field(engine,"continue");
                long handle=((Number)Native.call(registry,"boolean","long",entry.voice)).longValue();result.addProperty("voice_handle",handle);result.addProperty("voice_is_event",event);
                if(handle!=0){ReplayAudio.Key voice=new ReplayAudio.Key(event,handle);result.add("native_audio",new Gson().toJsonTree(replay.audio.driver.read(voice)));if(event)result.addProperty("native_playback_state",replay.audio.driver.playback(voice));}
            }
        }
        return result;
    }
}
