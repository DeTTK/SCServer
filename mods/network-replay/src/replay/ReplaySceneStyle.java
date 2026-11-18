package replay;

/** Local filming overrides. Recorded actors and the offline transport remain untouched. */
final class ReplaySceneStyle {
    int weather;boolean overrideTime;float hour=12;
    Object bound,info,recordedWeather;long timeOrigin;double secondsOrigin;
    boolean hadTime,hadWeather;Object appliedWeather;long timeRevision=-1;
    void reset(){bound=info=recordedWeather=null;hadTime=hadWeather=false;appliedWeather=null;timeRevision=-1;}
    void apply(NativePackets.World replay,long position)throws Exception {
        if(!replay.bootstrapped)return;
        Object world=Native.call(replay.connection,"instanceof","1054393445");
        applyNative(world,replay.environment,position);
    }
    void applyNative(Object world,SourceWeatherEnvironment environment,long position)throws Exception {
        if(world!=bound){
            bound=world;info=Native.call(world,"private","249504617");recordedWeather=Native.field(info,"boolean");
            timeOrigin=((Number)Native.call(world,"if","long")).longValue();secondsOrigin=position/1e9;hadTime=hadWeather=false;appliedWeather=null;timeRevision=-1;
        }
        environment.filmingWeather(weather);
        Object current=Native.field(info,"boolean");
        if(weather!=0&&(!hadWeather||current!=appliedWeather))recordedWeather=current;
        Object wanted=weather==0?recordedWeather:Native.field(Native.type("1719648191"),switch(weather){case 1->"CLEAR";case 2->"RAIN";case 3->"RAIN_THUNDER";default->throw new IllegalArgumentException("Invalid weather override");});
        if((weather!=0||hadWeather)&&current!=wanted)Native.call(info,"this","void",wanted);
        hadWeather=weather!=0;appliedWeather=weather==0?null:wanted;
        // A world can be created before its first time packet. Capture the
        // baseline when entering the override, and retain later packet updates.
        ReplayClock.WorldTime sample=ReplayClock.worldTime(info);
        if(!hadTime){timeOrigin=((Number)Native.call(world,"if","long")).longValue();secondsOrigin=position/1e9;if(sample!=null)timeRevision=sample.revision();}
        else if(sample!=null&&sample.revision()!=timeRevision){timeOrigin=sample.ticks();secondsOrigin=sample.seconds();timeRevision=sample.revision();}
        if(!overrideTime&&!hadTime)return;
        long recorded=timeOrigin+(long)Math.floor((position/1e9-secondsOrigin)*20);
        long ticks=overrideTime?Math.floorDiv(recorded,160000)*160000+(long)(Math.max(0,Math.min(24,hour))*160000/24):recorded;
        // World.public(long) also changes the day-cycle flag. A visual edit
        // writes only WorldInfo's day time, preserving native lifecycle state.
        Native.call(info,"throw","void",ticks);hadTime=overrideTime;
    }
    void draw(){
        if(!imgui.ImGui.collapsingHeader("Погода и время суток"))return;
        String[] modes={"Из записи","Ясно","Дождь","Гроза"};
        for(int i=0;i<modes.length;i++){if(i>0)imgui.ImGui.sameLine();if(imgui.ImGui.button(modes[i]))weather=i;}
        if(imgui.ImGui.checkbox("Свое время суток",overrideTime))overrideTime=!overrideTime;
        if(overrideTime){float[] value={hour};if(imgui.ImGui.sliderFloat("Часы",value,0,24))hour=value[0];}
    }
}
