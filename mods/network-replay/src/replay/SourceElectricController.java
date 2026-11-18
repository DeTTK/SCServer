package replay;

import java.io.IOException;
import java.time.*;
import java.util.*;
import com.google.gson.*;

/** Source 1612976154 -> shared native screen FX, with source 190767737 nullable sound ownership. */
final class SourceElectricController implements ReplayRender.WeatherSound {
    static final String BACKGROUND="stalker:electricstorm.background";
    static final List<String> CLASSES=List.of("569545768","1683205074","253290967","1995609889","1909010511","1795492378","1067276875","1971526348","1721734686","1737125061","148537415");
    record Profile(Object selector,SourceWeatherProgram.ElectricProfile settings){}
    final NativePackets.World owner;final List<Profile> profiles=new ArrayList<>();
    Object shader;boolean applying;long ticks,generation;float weight,scaled;
    final SourceElectricSound sound=new SourceElectricSound(this::createSound);
    SourceWeatherProgram.ElectricProfile selected;
    SourceElectricController(NativePackets.World owner){this.owner=owner;}
    void bind()throws Exception {
        ticks=0;generation++;weight=scaled=0;selected=null;profiles.clear();
        if(owner.packets.adapters==null||!owner.packets.demo.hashes.has("253290967"))return;
        for(String name:CLASSES)owner.packets.adapters.verify(name);
        if(owner.weatherProgram.electric==null)throw new IOException("Electric storm settings are not bound; reconvert the capture");
        shader=Native.field(Native.type("569545768"),"assert");
        for(var profile:owner.weatherProgram.electric.profiles())profiles.add(new Profile(Native.call(Native.type("1067276875"),"void","1067276875",profile.selector()),profile));
        ReplayRender.weatherSound=this;
    }
    void require(SourceWeather.Electric state)throws IOException {
        if(state.active()&&ReplayRender.weatherSound!=this)throw new IOException("Electric storm client classes are not bound; reconvert the capture");
    }
    void tick(Instant now)throws Exception {
        if(ReplayRender.weatherSound!=this)return;
        // Source 296075862 substitutes the generic context; the last matching authored selector wins.
        Object context=Native.call(Native.type("1737125061"),"native","148537415");
        if(context==null)context=Native.type("148537415").getConstructor(String.class,Map.class).newInstance("generic",Map.of());
        selected=null;for(Profile profile:profiles)if((boolean)Native.call(profile.selector(),"instanceof","boolean",context))selected=profile.settings();
        if(selected==null)throw new IOException("No electric storm profile matches the recorded location");
        SourceWeather.Update update=owner.weather.get(SourceWeather.Kind.ELECTRIC_STORM);
        SourceWeather.Electric state=update==null?new SourceWeather.Electric(null,now,null,null,null,false):(SourceWeather.Electric)update.current();
        weight=owner.weatherProgram.electric.weight(state,now);scaled=weight*selected.modifier();
        owner.environment.updateElectric(weight);
        applying=true;try{Native.call(shader,"strictfp","void",state.active(),scaled,selected.wind());ticks++;}finally{applying=false;}
    }
    public Object runner(){return sound.runner;}
    public void enabledChange(Object target,boolean enabled)throws Exception {
        // Debug/editor calls share this shader. Only the source controller supplies a new sound profile.
        if(!applying||target!=shader)return;
        sound.enabled(enabled,true);
    }
    Object createSound()throws Exception {
        Object entry=Native.type("1909010511").getConstructor(Duration.class,String.class).newInstance(Duration.ZERO,BACKGROUND);
        Object profile=Native.type("1995609889").getConstructor(List.class).newInstance(List.of(entry));
        return Native.type("253290967").getConstructor(Native.type("1995609889")).newInstance(profile);
    }
    public void stopped(Object runner){sound.stopped(runner);}
    JsonObject snapshot()throws Exception {
        JsonObject result=new JsonObject();result.addProperty("bound",ReplayRender.weatherSound==this);result.addProperty("ticks",ticks);result.addProperty("generation",generation);result.addProperty("weight",weight);result.addProperty("scaled_weight",scaled);
        result.addProperty("profile",selected==null?null:selected.selector());result.addProperty("background_event",BACKGROUND);result.addProperty("runner_created",sound.created);result.addProperty("runner_released",sound.released);result.addProperty("runner_present",sound.runner!=null);
        if(shader!=null){result.addProperty("native_enabled",(boolean)Native.field(shader,"import"));result.addProperty("native_scaled_weight",((Number)Native.field(shader,"enum")).floatValue());result.addProperty("native_level",((Number)Native.field(shader,"long")).floatValue());result.addProperty("native_started_tick",((Number)Native.field(shader,"return")).intValue());result.addProperty("native_ended_tick",((Number)Native.field(shader,"char")).intValue());}
        if(sound.runner!=null){result.addProperty("native_voice",((Number)Native.field(sound.runner,"if")).intValue());result.addProperty("native_transition_voice",((Number)Native.field(sound.runner,"interface")).intValue());Object entry=Native.field(sound.runner,"throw");result.addProperty("native_sound_event",(String)Native.field(entry,"enum"));}
        return result;
    }
    void unbind()throws Exception {
        try{if(sound.runner!=null){Object runner=sound.runner;Native.call(runner,"byte","void");stopped(runner);}}
        finally{if(ReplayRender.weatherSound==this)ReplayRender.weatherSound=null;sound.runner=null;shader=null;profiles.clear();selected=null;applying=false;}
    }
}
