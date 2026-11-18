package replay;

import java.util.*;
import com.google.gson.*;

/** Hash-bound adapters to installed location, polygon, shake and FMOD implementations. */
final class SourceTimelineNative implements SourceTimelineRunner.Access {
    final Object game;final SourceAdapters adapters;
    final IdentityHashMap<SourceWeatherProgram.Element,Target> targets=new IdentityHashMap<>();
    final Map<Integer,Integer> preparedOffsets=new HashMap<>();
    final IdentityHashMap<Object,Object> ownedShakes=new IdentityHashMap<>();
    long soundPrepared,soundStarted,soundStopped,soundParameters,soundSeeked,shakeStarted,shakeStopped;
    SourceTimelineNative(Object game,SourceAdapters adapters){this.game=game;this.adapters=adapters;}
    final class Target {
        final Object locations;final List<List<Object>> polygons;final float min,max;
        Target(JsonObject json)throws Exception {
            for(String name:List.of("1067276875","1971526348","1721734686","1737125061","148537415"))adapters.verify(name);
            String selector=json!=null&&json.has("locations")?json.get("locations").getAsString():"<ALL>";
            locations=Native.call(Native.type("1067276875"),"void","1067276875",selector);
            min=json==null?-Float.MAX_VALUE:SourceTimelineSound.number(json,"minY",-Float.MAX_VALUE);
            max=json==null?Float.MAX_VALUE:SourceTimelineSound.number(json,"maxY",Float.MAX_VALUE);
            if(json==null||!json.has("polygons"))polygons=null;
            else{
                adapters.verify("1751472043");adapters.verify("921590923");polygons=new ArrayList<>();
                for(JsonElement polygon:json.getAsJsonArray("polygons")){List<Object> points=new ArrayList<>();for(JsonElement point:polygon.getAsJsonArray()){JsonArray p=point.getAsJsonArray();points.add(Native.type("921590923").getConstructor(float.class,float.class).newInstance(p.get(0).getAsFloat(),p.get(1).getAsFloat()));}polygons.add(List.copyOf(points));}
            }
        }
        boolean location()throws Exception {Object current=Native.call(Native.type("1737125061"),"native","148537415");return current==null||(boolean)Native.call(locations,"instanceof","boolean",current);}
        boolean inside()throws Exception {
            double[] point=playerPosition();if(point==null)return false;
            // Source 730638937 returns before testing height when polygons are absent.
            if(polygons==null)return true;
            float x=(float)point[0],y=(float)point[1],z=(float)point[2];if(!heightMatches(y,min,max))return false;
            for(List<Object> polygon:polygons)if((boolean)Native.call(Native.type("1751472043"),"instanceof","boolean",x,z,polygon))return true;return false;
        }
    }
    static boolean heightMatches(float y,float min,float max){return !(y<min||y>max);}
    Target target(SourceWeatherProgram.Element event)throws Exception {Target target=targets.get(event);if(target==null){target=new Target(event.target());targets.put(event,target);}return target;}
    public boolean admit(SourceWeatherProgram.Element event)throws Exception {return target(event).location();}
    double[] playerPosition()throws Exception {
        Object player=Native.field(game,"e");if(player==null)return null;
        for(String name:List.of("961798934","576005245","153304200"))adapters.verify(name);
        Object point=Native.call(Native.type("576005245"),"char","153304200",player,1f);
        return new double[]{((Number)Native.field(point,"case")).doubleValue(),((Number)Native.field(point,"void")).doubleValue(),((Number)Native.field(point,"import")).doubleValue()};
    }
    float skylight()throws Exception {
        double[] p=playerPosition();if(p==null)return 0;
        adapters.verify("619606930");Object world=Native.call(game,"throws","1054393445");if(world==null)return 0;
        return ((Number)Native.call(Native.type("619606930"),"int","int",world,(int)Math.floor(p[0]),(int)Math.floor(p[1]),(int)Math.floor(p[2]))).floatValue()/15f;
    }
    Object manager()throws Exception {for(String name:List.of("1831969298","1335989370","269975446","1069936767"))adapters.verify(name);return Native.call(Native.type("1831969298"),"extends","1831969298");}
    long handle(int voice)throws Exception {Object registry=Native.field(Native.field(manager(),"else"),"const");return ((Number)Native.call(registry,"boolean","long",voice)).longValue();}
    public SourceTimelineRunner.Handler create(SourceWeatherProgram.Element event,UUID instance)throws Exception {
        Target target=target(event);
        if(event.type.equals("set_weather_phenomenon_weight"))return null;
        if(event.type.equals("perlin_shake"))return new SourceTimelineShake(event,new SourceTimelineShake.Access(){
            public boolean inside()throws Exception{return target.inside();}
            public Object start(JsonObject definition)throws Exception {
                for(String name:List.of("1427337337","42554018","1925488649","1962380521","1077040037"))adapters.verify(name);
                Object config=Native.call(Native.type("42554018"),"void","42554018",definition);
                Object manager=Native.field(Native.type("1427337337"),"byte");Object effect=Native.call(manager,"try","877660917",config);ownedShakes.put(effect,manager);shakeStarted++;return effect;
            }
            public void stop(Object effect)throws Exception {if(Native.type("1962380521").isInstance(effect))Native.call(Native.call(effect,"assert","1077040037"),"catch","void");shakeStopped++;}
        });
        if(event.type.equals("play_sound"))return new SourceTimelineSound(event,new SourceTimelineSound.Access(){
            public boolean inside()throws Exception{return target.inside();}
            public float skylight()throws Exception{return SourceTimelineNative.this.skylight();}
            public int prepare(String name,float x,float y,float z)throws Exception {int voice=((Number)Native.call(manager(),"strictfp","int",name,1f,x,y,z,true)).intValue();preparedOffsets.remove(voice);soundPrepared++;return voice;}
            public void range(int voice,float min,float max)throws Exception {long handle=handle(voice);if(handle==0)return;if(min>=0)Native.call(Native.type("1069936767"),"public","int",handle,3,min);if(max>=0)Native.call(Native.type("1069936767"),"public","int",handle,4,max);}
            public void seek(int voice,int millis)throws Exception {long handle=handle(voice);if(handle!=0&&((Number)Native.call(Native.type("1069936767"),"float","int",handle,millis)).intValue()==0){preparedOffsets.put(voice,millis);soundSeeked++;}}
            public void parameter(int voice,String name,float value)throws Exception {Native.call(manager(),"implements","void",voice,name,value);soundParameters++;}
            public void start(int voice)throws Exception {Native.call(manager(),"package","void",voice);Integer offset=preparedOffsets.remove(voice);long handle=handle(voice);if(event.continuous())ReplayRender.timelineSoundStarted(handle,offset==null?0:offset);else if(offset!=null&&handle!=0)ReplayRender.audioPositioned(0,true,handle,offset,1);soundStarted++;}
            public void stop(int voice)throws Exception {long handle=handle(voice);Native.call(manager(),"long","void",voice,true);ReplayRender.timelineSoundStopped(handle);preparedOffsets.remove(voice);soundStopped++;}
        });
        throw new IllegalArgumentException("Unknown client timeline action: "+event.type);
    }
    void clear()throws Exception {try{for(var entry:ownedShakes.entrySet())((List<?>)Native.field(entry.getValue(),"for")).remove(entry.getKey());}finally{ownedShakes.clear();targets.clear();preparedOffsets.clear();}}
}
