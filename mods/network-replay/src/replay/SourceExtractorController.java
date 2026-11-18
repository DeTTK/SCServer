package replay;

import java.time.*;
import java.util.*;
import com.google.gson.*;

/** Reuses the installed prefab renderer, animation layers and native effects/audio. */
final class SourceExtractorController implements ReplayRender.Extractors {
    final SourceAdapters adapters;final Map<Object,Entry> entries=new IdentityHashMap<>();
    final SourceExtractorHud hud=new SourceExtractorHud(this);
    long ticks,progressUpdates,effectStarts,effectStops,soundStarts,soundStops;boolean bound;
    SourceExtractorController(SourceAdapters adapters){this.adapters=adapters;}
    void bind()throws Exception {
        if(adapters==null||!adapters.demo.hashes.has("1212469801"))return;
        for(String name:List.of("1212469801","1079929180","1562480994","1886271463","1814125595","1822632555","1422486348","690713104","98008865","292512748","576239596","1254265885","510119352","881819494","125158170","1809954121","1391506621","432112703","1366534706","1054393445","1831969298","1651476251","351318308","485110855","964379188","1857703917","1100551740","61438557","1625524896","1973624787","747722393","186969209","1074092844","566165616"))adapters.verify(name);
        bound=true;ReplayRender.extractors=this;
    }
    public boolean accepts(Object component){return bound&&component.getClass().getName().equals("1212469801");}
    Entry entry(Object component)throws Exception {
        Entry entry=entries.get(component);if(entry==null){entry=new Entry(component);entries.put(component,entry);}return entry;
    }
    public void tick(Object component)throws Exception {Entry entry=entry(component);entry.sync();SourceExtractor.tick(entry.state.phase(),entry);ticks++;}
    public void progress(Object component)throws Exception {Entry entry=entry(component);entry.sync();entry.progress();}
    public void configured(Object component,Object configuration)throws Exception {
        Entry entry=entry(component);entry.range=(boolean)Native.call(configuration,"instanceof","boolean","personalRewardRange")?((Number)Native.call(configuration,"strictfp","float","personalRewardRange")).floatValue():0f;
        SourceAdapters.put(component,"import",(boolean)Native.call(configuration,"instanceof","boolean","OwnerName")?Native.call(configuration,"final","java.lang.String","OwnerName"):null);
        for(String[] property:List.of(new String[]{"final","CanParyMemberOpen"},new String[]{"assert","CanOpenByNonOwner"},new String[]{"throws","CanLootEveryone"},new String[]{"try","EnableHud"}))SourceAdapters.put(component,property[0],Native.call(configuration,"new","boolean",property[1]));
        SourceAdapters.put(component,"native",Native.call(configuration,"strictfp","float","HudRadius"));
        // Source int(config) has no legacy state/date fields or inventory block.
        // It invokes the client progress update immediately.
        entry.sync();entry.progress();
    }
    public void removed(Object component)throws Exception {Entry entry=entries.remove(component);if(entry!=null)entry.finish();}
    public void hudUpdated(Object value)throws Exception {hud.updated(value);}
    public void hudDraw(Object value,int width,float partial)throws Exception {hud.draw(value,width,partial);}
    public String activationPrompt(Object component)throws Exception {
        Object entity=Native.call(component,"try","1886271463");Map<String,Object> extra=adapters.extras.get(Native.call(entity,"float","1536302723"));
        if(extra==null||!Boolean.TRUE.equals(extra.get("GOResourceExtractor_needActivation")))return null;
        return (String)Native.call(Native.call(Native.type("1074092844"),"char","566165616","gameobjects.action_tip.incident_terminal.activate"),"throws","java.lang.String");
    }
    void unbind()throws Exception {
        if(ReplayRender.extractors==this)ReplayRender.extractors=null;boolean usedHud=bound&&!hud.timings.isEmpty();bound=false;
        Exception failure=null;for(Entry entry:entries.values())try{entry.finish();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}entries.clear();
        if(usedHud)try{hud.clearSelection();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}hud.timings.clear();if(failure!=null)throw failure;
    }
    JsonObject snapshot(){JsonObject result=new JsonObject();result.addProperty("bound",bound);result.addProperty("hud_updates",hud.updates);result.addProperty("hud_draws",hud.draws);result.addProperty("hud_timed_draws",hud.timedDraws);result.addProperty("ticks",ticks);result.addProperty("progress_updates",progressUpdates);result.addProperty("effect_starts",effectStarts);result.addProperty("effect_stops",effectStops);result.addProperty("sound_starts",soundStarts);result.addProperty("sound_stops",soundStops);JsonArray active=new JsonArray();for(Entry entry:entries.values()){JsonObject value=new JsonObject();value.addProperty("prefab",entry.prefab);value.add("state",new Gson().toJsonTree(entry.state));if(Float.isFinite(entry.lastProgress))value.addProperty("progress",entry.lastProgress);else value.addProperty("progress",Float.toString(entry.lastProgress));value.addProperty("effect",entry.effect!=null);value.addProperty("voice",entry.voice);value.addProperty("animation",entry.animation);value.addProperty("sound",entry.sound);active.add(value);}result.add("components",active);return result;}
    final class Entry implements SourceExtractor.Access {
        final Object component,entity;final String prefab,animation,sound;final Object effectResource;
        SourceExtractorState.State state=new SourceExtractorState.State(SourceExtractorState.Phase.INITIAL,0,0);
        Duration duration=Duration.ZERO;float range,lastProgress=Float.NaN;Object effect;int voice=-1;
        Entry(Object component)throws Exception {
            this.component=component;entity=Native.call(component,"try","1886271463");
            Object definition=Native.field(entity,"class"),parameters=Native.field(definition,"void");prefab=(String)Native.field(definition,"while");
            animation=(String)Native.call(parameters,"float","java.lang.String","progress_animation_name","work");
            effectResource=(boolean)Native.call(parameters,"class","boolean","extraction_vfx")?Native.type("779455578").getConstructor(String.class).newInstance(Native.call(parameters,"catch","java.lang.String","extraction_vfx")):null;
            sound=(boolean)Native.call(parameters,"class","boolean","extraction_sfx")?(String)Native.call(parameters,"catch","java.lang.String","extraction_sfx"):null;
        }
        void sync()throws Exception {
            Map<String,Object> extra=adapters.extras.get(Native.call(entity,"float","1536302723"));
            Object value=extra==null?null:extra.get("GOResourceExtractor_state");state=value instanceof SourceExtractorState.State recorded?recorded:new SourceExtractorState.State(SourceExtractorState.Phase.INITIAL,0,0);
            duration=extra!=null&&extra.containsKey("GOResourceExtractor_stateDuration")?(Duration)extra.get("GOResourceExtractor_stateDuration"):Duration.ZERO;
            SourceAdapters.put(component,"super",state.phase()==null?null:Enum.valueOf((Class)Native.type("1822632555"),state.phase().name()));
            // Preserve old component getters for native selection/update hooks.
            // The source HUD draws from its own cached elapsed-clock interval.
            long age=(long)((ReplayClock.seconds-state.changedSeconds())*1000);Instant start=Instant.ofEpochMilli(ReplayClock.wallMillis()-age);
            SourceAdapters.put(component,"strictfp",start);SourceAdapters.put(component,"interface",duration==null?null:start.plusSeconds(duration.getSeconds()));SourceAdapters.put(component,"return",duration==null?null:start.plusSeconds(duration.getSeconds()));
        }
        public void progress()throws Exception {
            Object renderer=Native.field(entity,"final");if(renderer==null)return;
            Object controller=Native.call(renderer,"case","576239596");
            float progress=state.phase()==SourceExtractorState.Phase.EXTRACTING?state.progress(duration,ReplayClock.seconds):1f;
            if(SourceExtractorAnimation.apply(controller,animation,state.phase(),progress)){lastProgress=progress;progressUpdates++;}
        }
        public boolean hasEffect(){return effect!=null;}
        public float rewardRange(){return range;}
        public void parameter(int index,float value)throws Exception {Native.call(effect,"return","void",index,value);}
        public void start()throws Exception {
            if(effectResource==null&&(sound==null||voice!=-1))return;
            float x=((Number)Native.call(entity,"case","float")).floatValue(),y=((Number)Native.call(entity,"try","float")).floatValue(),z=((Number)Native.call(entity,"throw","float")).floatValue();
            if(effectResource!=null&&effect==null){Object world=Native.call(component,"while","1500514786");Object manager=Native.call(world,"transient","432112703");effect=Native.call(manager,"break","1366534706",effectResource,1,x,y,z);if(effect!=null)effectStarts++;}
            if(sound!=null&&voice==-1){Object manager=Native.call(Native.type("1831969298"),"extends","1831969298");voice=((Number)Native.call(manager,"do","int",sound,18,1f,1f,x,y,z,0f,64f,Native.call(manager,"abstract","1651937268"))).intValue();if(voice!=-1)soundStarts++;}
        }
        public void finish()throws Exception {
            try{if(effect!=null){Native.call(effect,"void","void");effect=null;effectStops++;}}
            finally{if(voice!=-1){Object manager=Native.call(Native.type("1831969298"),"extends","1831969298");Native.call(manager,"package","void",voice,100L);voice=-1;soundStops++;}}
        }
    }
}
