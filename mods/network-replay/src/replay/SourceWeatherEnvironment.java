package replay;

import java.util.*;
import java.lang.reflect.*;
import com.google.gson.*;

/** Source event weights on the canonical preset selector, curves and environment data. */
final class SourceWeatherEnvironment implements ReplayRender.WeatherWeights {
    final SourceAdapters adapters;
    volatile ReplayRender.WeatherBlend blend=ReplayRender.WeatherBlend.EMPTY;
    long updates;
    boolean synthetic;volatile boolean manual;
    int manualMode;volatile ReplayRender.WeatherBlend manualBlend=ReplayRender.WeatherBlend.EMPTY;
    SourceWeatherEnvironment(SourceAdapters adapters){this.adapters=adapters;}
    void bind()throws Exception {
        blend=ReplayRender.WeatherBlend.EMPTY;manual=false;manualMode=0;manualBlend=ReplayRender.WeatherBlend.EMPTY;ReplayRender.weatherWeights=this;ReplayRender.weatherZoneCalls=ReplayRender.weatherBlendCalls=0;ReplayRender.weatherBlendStrength=0;
        String test=System.getProperty("sz.replay.qa.weather","");
        if(ReplayRender.testInput()&&!test.isBlank()){
            String[] values=test.split(":",2);if(values.length!=2)throw new IllegalArgumentException("Invalid synthetic weather fixture");
            update(0,Map.of(values[0],Float.parseFloat(values[1])));synthetic=true;
        }
    }
    void update(float electric,Map<String,Float> timeline)throws Exception {
        for(String name:List.of("1049587979","680337400","1914600486","1442200882","648055939","1275877375"))adapters.verify(name);
        Map<Object,Float> weights=new LinkedHashMap<>();for(var entry:timeline.entrySet())weights.put(phenomenon(entry.getKey()),entry.getValue());
        blend=new ReplayRender.WeatherBlend(electric,phenomenon("ElectricStorm"),weights);updates++;
    }
    void updateElectric(float electric)throws Exception {
        ReplayRender.WeatherBlend previous=blend;
        if(previous.electric()==electric&&previous.electricKind()!=null)return;
        for(String name:List.of("1049587979","680337400","1914600486","1442200882","648055939","1275877375"))adapters.verify(name);
        blend=new ReplayRender.WeatherBlend(electric,phenomenon("ElectricStorm"),previous.timeline());updates++;
    }
    Object phenomenon(String name)throws Exception {
        // The installed enum's identifier differs; its ordinal/preset remains 14.
        String nativeName=name.equals("ElectricStorm")?"Electricstorm":name;
        try{return Native.field(Native.type("680337400"),nativeName);}
        catch(NoSuchFieldException missing){throw new IllegalArgumentException("Source weather phenomenon needs native preset port: "+name,missing);}
    }
    void filmingWeather(int mode)throws Exception {
        if(mode<0||mode>3)throw new IllegalArgumentException("Invalid filming weather");
        if(mode!=manualMode){
            if(mode==0)manualBlend=ReplayRender.WeatherBlend.EMPTY;
            else {if(adapters!=null)adapters.verify("680337400");Object preset=phenomenon(switch(mode){case 1->"ClearWeather";case 2->"HeavyRain";case 3->"Thunderstorm";default->throw new AssertionError();});manualBlend=new ReplayRender.WeatherBlend(0,null,Map.of(preset,1f),true);}
            manualMode=mode;
        }
        manual=mode!=0;
    }
    public ReplayRender.WeatherBlend snapshot(){return manual?manualBlend:blend;}
    JsonObject renderedState()throws Exception {
        adapters.verify("569545768");adapters.verify("648055939");
        return numeric(Native.call(Native.type("569545768"),"final","648055939"),3,new IdentityHashMap<>()).getAsJsonObject();
    }
    static JsonElement numeric(Object value,int depth,IdentityHashMap<Object,Boolean> seen)throws Exception {
        if(value==null)return JsonNull.INSTANCE;
        if(value instanceof Number n)return (n instanceof Float||n instanceof Double)&&!Double.isFinite(n.doubleValue())?JsonNull.INSTANCE:new JsonPrimitive(n);if(value instanceof Boolean b)return new JsonPrimitive(b);
        Class<?> type=value.getClass();if(depth<0||seen.put(value,true)!=null)return JsonNull.INSTANCE;
        if(type.isArray()){JsonArray result=new JsonArray();for(int n=0;n<Math.min(256,Array.getLength(value));n++)result.add(numeric(Array.get(value,n),depth-1,seen));return result;}
        JsonObject result=new JsonObject();if(!type.getName().matches("[0-9]+"))return result;
        for(Field field:type.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!field.isSynthetic()){
            field.setAccessible(true);JsonElement element=numeric(field.get(value),depth-1,seen);if(!element.isJsonNull())result.add(field.getName(),element);
        }return result;
    }
    void unbind(){if(ReplayRender.weatherWeights==this)ReplayRender.weatherWeights=null;blend=ReplayRender.WeatherBlend.EMPTY;manualBlend=ReplayRender.WeatherBlend.EMPTY;manual=false;manualMode=0;}
}
