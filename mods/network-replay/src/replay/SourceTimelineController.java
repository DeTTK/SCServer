package replay;

import java.io.IOException;
import java.time.*;
import java.util.*;
import com.google.gson.*;

/** Runs alongside the native client weather tick, under the recorded world clock. */
final class SourceTimelineController implements ReplayRender.WeatherTick {
    final NativePackets.World owner;final SourceTimelineNative effects;final SourceTimelineRunner runner;
    long ticks,generation;boolean synthetic,syntheticTargets,hadActive;
    SourceWeather.WorldTimeline fixture;
    SourceWeatherProgram.Program fixtureProgram;
    SourceTimelineController(NativePackets.World owner){this.owner=owner;effects=new SourceTimelineNative(owner.game,owner.packets.adapters);runner=new SourceTimelineRunner(effects);}
    void bind()throws Exception {
        generation++;ticks=0;synthetic=syntheticTargets=hadActive=false;fixture=null;fixtureProgram=null;
        if(owner.packets.adapters==null||!owner.packets.demo.hashes.has("962844222"))return;
        for(String name:List.of("962844222","1520717974","1427337337","1389510037","1102042093","447252483"))owner.packets.adapters.verify(name);ReplayRender.resetShakeClock(true);ReplayRender.weatherTick=this;
        syntheticTargets=ReplayRender.testInput()&&System.getProperty("sz.replay.qa.timeline.targets","").equals("unrestricted");
        String test=System.getProperty("sz.replay.qa.timeline","");
        if(ReplayRender.testInput()&&!test.isBlank()){
            String[] values=test.split(":",2);if(values.length!=2)throw new IOException("Invalid timeline fixture");
            SourceWeatherProgram.Program original=owner.weatherProgram.timelines.get(values[0]);if(original==null)throw new IOException("Unbound timeline fixture: "+values[0]);
            // Explicit QA fixture: retain authored actions/times, use an unrestricted target.
            fixtureProgram=new SourceWeatherProgram.Program(original.resource(),original.duration(),original.elements().stream().map(event->new SourceWeatherProgram.Element(event.id,event.at,event.duration,event.action(),null)).toList());
            fixture=new SourceWeather.WorldTimeline(original.resource(),Instant.ofEpochMilli(ReplayClock.wallMillis()).minusMillis((long)(Double.parseDouble(values[1])*1000)),original.duration().toMillis(),UUID.nameUUIDFromBytes("source-timeline-qa".getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,0);synthetic=true;
        }
    }
    void require(SourceWeather.WorldTimeline state)throws IOException {
        if(!state.active())return;
        if(ReplayRender.weatherTick!=this)throw new IOException("Source timeline classes are not bound; reconvert the capture");
        if(!owner.weatherProgram.timelines.containsKey(state.resource()))throw new IOException("Active timeline configuration is not bound: "+state.resource());
    }
    public void tick()throws Exception {
        ticks++;if(!owner.bootstrapped||owner.connection==null)return;
        Object world=Native.call(owner.game,"throws","1054393445");if(world==null)return;
        SourceWeather.Update update=owner.weather.get(SourceWeather.Kind.WORLD_TIMELINE);
        SourceWeather.WorldTimeline state=synthetic?fixture:update!=null?(SourceWeather.WorldTimeline)update.current():new SourceWeather.WorldTimeline(null,null,0,null,1,0);
        SourceWeatherProgram.Program program=synthetic?fixtureProgram:state.active()?owner.weatherProgram.timelines.get(state.resource()):null;
        if(state.active()&&program==null)throw new IOException("Active timeline configuration is not bound: "+state.resource());
        if(syntheticTargets&&program!=null){if(fixtureProgram==null||!fixtureProgram.resource().equals(program.resource()))fixtureProgram=new SourceWeatherProgram.Program(program.resource(),program.duration(),program.elements().stream().map(event->new SourceWeatherProgram.Element(event.id,event.at,event.duration,event.action(),null)).toList());program=fixtureProgram;}
        Instant now=Instant.ofEpochMilli(ReplayClock.wallMillis());
        owner.sourceElectric.tick(now);
        if(state.active()){
            Duration position=Duration.ofMillis(state.positionMillis(now));Map<SourceWeatherProgram.Element,Boolean> admission=new IdentityHashMap<>();
            for(SourceWeatherProgram.Element event:program.elements())if(event.type.equals("set_weather_phenomenon_weight")&&event.contains(position))admission.put(event,effects.admit(event));
            owner.environment.update(owner.environment.blend.electric(),program.weights(position,event->admission.getOrDefault(event,false)));hadActive=true;
        }else if(hadActive){owner.environment.update(owner.environment.blend.electric(),Map.of());hadActive=false;}
        runner.step(state,now,program,true);
    }
    JsonObject snapshot()throws Exception {
        JsonObject result=new JsonObject();result.addProperty("ticks",ticks);result.addProperty("generation",generation);result.addProperty("synthetic_fixture",synthetic);result.addProperty("synthetic_target_override",syntheticTargets);result.addProperty("enters",runner.enters);result.addProperty("updates",runner.updates);result.addProperty("exits",runner.exits);
        result.addProperty("sound_prepared",effects.soundPrepared);result.addProperty("sound_started",effects.soundStarted);result.addProperty("sound_stopped",effects.soundStopped);result.addProperty("sound_parameters",effects.soundParameters);result.addProperty("sound_seeked",effects.soundSeeked);result.addProperty("shake_started",effects.shakeStarted);result.addProperty("shake_stopped",effects.shakeStopped);result.addProperty("shake_clock_calls",ReplayRender.shakeClockCalls);
        JsonObject active=new JsonObject();for(var entry:runner.active.entrySet()){
            JsonObject value=new JsonObject();SourceTimelineRunner.Handler handler=entry.getValue().handler();value.addProperty("type",entry.getValue().event().type);
            if(handler instanceof SourceTimelineSound sound){value.add("voices",new Gson().toJsonTree(sound.voices));value.add("parameters",new Gson().toJsonTree(sound.values));JsonArray handles=new JsonArray();for(int voice:sound.voices)handles.add(effects.handle(voice));value.add("native_handles",handles);if(runner.previous!=null)value.addProperty("elapsed_ms",Math.max(0,runner.previous.toMillis()-entry.getValue().event().at.toMillis()));}
            if(handler instanceof SourceTimelineShake shake&&shake.effect!=null){value.addProperty("native_class",shake.effect.getClass().getName());if(Native.type("1962380521").isInstance(shake.effect))value.addProperty("native_age_seconds",((Number)Native.field(shake.effect,"native")).floatValue());}
            active.add(entry.getKey(),value);
        }result.add("active",active);result.addProperty("camera_shake_calls",ReplayRender.cameraShakeCalls);return result;
    }
    void unbind()throws Exception {if(ReplayRender.weatherTick==this){ReplayRender.weatherTick=null;ReplayRender.resetShakeClock(false);}try{runner.clear();}finally{effects.clear();fixture=null;fixtureProgram=null;hadActive=false;}}
}
