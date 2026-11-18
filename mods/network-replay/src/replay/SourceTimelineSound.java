package replay;

import java.util.*;
import com.google.gson.*;

/** Source 1469809766: authored event positions, parameters and region entry/exit. */
final class SourceTimelineSound implements SourceTimelineRunner.Handler {
    interface Access {
        boolean inside()throws Exception;
        float skylight()throws Exception;
        int prepare(String event,float x,float y,float z)throws Exception;
        void range(int voice,float min,float max)throws Exception;
        void seek(int voice,int millis)throws Exception;
        void parameter(int voice,String name,float value)throws Exception;
        void start(int voice)throws Exception;
        void stop(int voice)throws Exception;
    }
    record Parameter(String source,float from,float to,float halfLife) {
        Parameter {if(!Set.of("SKYLIGHT","PROGRESS").contains(source))throw new IllegalArgumentException("Unknown source sound parameter: "+source);}
        static Parameter read(JsonObject json){String source=json.get("source").getAsString().toUpperCase(Locale.ROOT);return new Parameter(source,number(json,"from",0),number(json,"to",1),number(json,"smoothingHalfLife",source.equals("SKYLIGHT")?0.2f:0));}
    }
    final Access access;final boolean continuous;final String event;final float min,max;
    final List<float[]> positions=new ArrayList<>();final Map<String,Parameter> parameters=new LinkedHashMap<>();
    final List<Integer> voices=new ArrayList<>();final Map<String,Float> values=new HashMap<>();
    SourceTimelineSound(SourceWeatherProgram.Element element,Access access){
        this.access=access;continuous=element.continuous();JsonObject action=element.action();event=action.get("event").getAsString();min=number(action,"minDistance",-1);max=number(action,"maxDistance",-1);
        if(action.has("positions"))for(JsonElement json:action.getAsJsonArray("positions")){JsonArray p=json.getAsJsonArray();positions.add(new float[]{p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat()});}
        if(action.has("parameters"))for(var entry:action.getAsJsonObject("parameters").entrySet())parameters.put(entry.getKey(),Parameter.read(entry.getValue().getAsJsonObject()));
    }
    static float number(JsonObject json,String name,float fallback){return json.has(name)?json.get(name).getAsFloat():fallback;}
    static float smooth(float previous,float target,float halfLife){return (float)((double)target+(double)(previous-target)*Math.pow(2.0,-0.05f/halfLife));}
    static float remap(Parameter parameter,float value){return parameter.from()+(parameter.to()-parameter.from())*Math.max(0,Math.min(1,value));}
    float sample(Parameter parameter,float progress)throws Exception{return parameter.source().equals("SKYLIGHT")?access.skylight():progress;}
    void parameters(int voice)throws Exception {for(var entry:parameters.entrySet()){Float value=values.get(entry.getKey());if(value!=null)access.parameter(voice,entry.getKey(),remap(entry.getValue(),value));}}
    void prepare(float x,float y,float z,long elapsed)throws Exception {
        int voice=access.prepare(event,x,y,z);
        if(min>=0||max>=0)access.range(voice,min,max);
        int millis=(int)elapsed;if(millis>0)access.seek(voice,millis);
        parameters(voice);access.start(voice);voices.add(voice);
    }
    void begin(long elapsed,float progress)throws Exception {
        values.clear();for(var entry:parameters.entrySet())values.put(entry.getKey(),sample(entry.getValue(),progress));
        if(positions.isEmpty())prepare(0,0,0,elapsed);else for(float[] p:positions)prepare(p[0],p[1],p[2],elapsed);
    }
    void stop()throws Exception {try{for(int voice:voices)access.stop(voice);}finally{voices.clear();}}
    public void enter(long elapsed,float progress)throws Exception {if(access.inside())begin(elapsed,progress);}
    public void update(long elapsed,float progress)throws Exception {
        if(!continuous)return;
        boolean inside=access.inside();
        if(inside&&voices.isEmpty())begin(elapsed,progress);
        else if(!inside&&!voices.isEmpty())stop();
        else if(inside){for(var entry:parameters.entrySet()){float target=sample(entry.getValue(),progress);Float previous=values.get(entry.getKey());values.put(entry.getKey(),previous==null?target:smooth(previous,target,entry.getValue().halfLife()));}for(int voice:voices)parameters(voice);}
    }
    public void exit(long elapsed,float progress)throws Exception {try{if(continuous)stop();}finally{voices.clear();values.clear();}}
}
