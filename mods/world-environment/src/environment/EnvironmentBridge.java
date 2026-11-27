package environment;

import com.google.gson.*;
import com.github.luben.zstd.Zstd;
import environment.protocol.EnvironmentWire;
import szmods.api.ModContext;
import imgui.ImGui;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Native objects are prepared off-thread, then published and edited only by the game owner. */
public final class EnvironmentBridge {
    public static ModContext context;
    private record Received(Object connection,byte[] body){}
    private record Outgoing(Object connection,JsonObject message){}
    private record Prepared(Object connection,long revision,String hash,String json,Object manager,Object editorManager){}
    private static final ArrayBlockingQueue<Received> incoming=new ArrayBlockingQueue<>(256);
    private static final Queue<Outgoing> outgoing=new ConcurrentLinkedQueue<>();
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"Native weather configuration");t.setDaemon(true);return t;});
    private static final Map<Object,Long> editorVersions=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Object connection,game;
    private static volatile boolean active,editable;
    private static volatile JsonObject state=new JsonObject();
    private static volatile long receivedAt,appliedRevision;
    private static volatile String status="Ожидание сервера",failure="",appliedHash="",savedJson,authoredJson;
    private static volatile Object savedEditor,lastEditor,originalManager;
    private static volatile Future<Prepared> preparing;
    private static volatile boolean saving;
    private static long helloAt;
    private static ByteArrayOutputStream download;
    private static int downloadSize,downloadPage;
    private static long downloadRevision;
    private static String downloadHash;
    private static String choice="auto";
    private static Object snowState;
    private static String snowKey="";
    private static long uiFrames;
    private static final imgui.type.ImInt duration=new imgui.type.ImInt(600);
    private static final String[][] modes={{"auto","Автоматически по регионам и циклам"},{"clear","Ясно"},{"light_rain","Небольшой дождь"},{"rain","Сильный дождь"},{"downpour","Ливень"},{"thunder","Гроза"},{"snow","Метель / снег"},{"fog","Густой туман"},{"light_fog","Лёгкий туман"},{"cloudy","Облачно"},{"cloudy_rainy","Облачно с дождём"},{"drizzle","Морось"},{"storm","Шторм"},{"ejection","Профиль выброса"},{"psy_ejection","Профиль пси-выброса"},{"electric_storm","Профиль электрической бури"}};
    private EnvironmentBridge(){}
    public static byte[] read(DataInput input)throws IOException{return EnvironmentWire.read(input);}
    public static void receive(Object owner,byte[] bytes){if(!incoming.offer(new Received(owner,bytes)))throw new IllegalStateException("environment_receive_overflow");}
    public static boolean packet(Object value){return value!=null&&(value.getClass().getName().equals(EnvironmentWire.INPUT)||value.getClass().getName().equals(EnvironmentWire.SNAPSHOT));}
    private static Class<?> type(String name)throws Exception{return Class.forName(name);}
    private static Object field(Object value,String name)throws Exception{Class<?> owner=value instanceof Class<?> c?c:value.getClass();for(Class<?> cursor=owner;cursor!=null;cursor=cursor.getSuperclass())try{Field field=cursor.getDeclaredField(name);field.setAccessible(true);return field.get(value instanceof Class<?>?null:value);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(owner.getName()+"."+name);}
    private static void set(Object value,String name,Object next)throws Exception{Class<?> owner=value instanceof Class<?> c?c:value.getClass();for(Class<?> cursor=owner;cursor!=null;cursor=cursor.getSuperclass())try{Field field=cursor.getDeclaredField(name);field.setAccessible(true);field.set(value instanceof Class<?>?null:value,next);return;}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(owner.getName()+"."+name);}
    private static Object call(Object value,String name,String result,Object...args)throws Exception {
        Class<?> owner=value instanceof Class<?> c?c:value.getClass();
        search:for(Method m:owner.getMethods())if(m.getName().equals(name)&&m.getReturnType().getName().equals(result)&&m.getParameterCount()==args.length){
            Class<?>[] types=m.getParameterTypes();for(int i=0;i<args.length;i++)if(args[i]!=null&&!boxed(types[i]).isInstance(args[i]))continue search;
            try{return m.invoke(value instanceof Class<?>?null:value,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception cause)throw cause;throw e;}
        }throw new NoSuchMethodException(owner.getName()+"."+name+" -> "+result);
    }
    private static Class<?> boxed(Class<?> c){if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==int.class)return Integer.class;if(c==boolean.class)return Boolean.class;return c;}
    private static void error(Throwable error){failure=Objects.toString(error.getMessage(),error.toString());status="Ошибка: "+failure;if(context!=null)context.log(status);}
    private static boolean replay(){try{return type("replay.ReplayClock").getField("active").getBoolean(null);}catch(Exception absent){return false;}}
    private static boolean owns(){return active&&!replay();}
    private static void send(JsonObject message)throws Exception {
        if(connection==null)return;Object packet=type(EnvironmentWire.INPUT).getConstructor(byte[].class).newInstance((Object)EnvironmentWire.encode(message));
        Object envelope=type("385693344").getConstructor(type("499058674")).newInstance(packet);call(connection,"else","void",envelope);
    }
    public static void frame(Object currentGame) {
        try {
            game=currentGame;Object next=call(currentGame,"transient","1219970978");
            if(next!=connection){if(originalManager!=null){set(type("1049587979"),"package",originalManager);originalManager=null;}connection=next;active=false;editable=false;helloAt=0;incoming.clear();outgoing.clear();download=null;preparing=null;saving=false;appliedRevision=0;appliedHash="";authoredJson=null;editorVersions.clear();state=new JsonObject();snowState=null;snowKey="";WeatherChunkOverlay.reset();}
            if(next==null||field(currentGame,"e")==null||field(currentGame,"g")==null||replay()){active=false;return;}
            long now=System.nanoTime();if(!active&&now-helloAt>1_000_000_000L){helloAt=now;send(EnvironmentWire.message("hello"));}
            for(int i=0;i<8;i++){Received nextMessage=incoming.poll();if(nextMessage==null)break;if(nextMessage.connection()==connection)accept(EnvironmentWire.decode(nextMessage.body()));}
            Future<Prepared> pending=preparing;if(pending!=null&&pending.isDone()){
                preparing=null;Prepared prepared=pending.get();if(prepared.connection()==connection){
                    Object editor=lastEditor;boolean clean=editor!=null&&!editorDirty(editor);
                    if(originalManager==null)originalManager=call(type("1049587979"),"switch","1049587979");set(type("1049587979"),"package",prepared.manager());appliedRevision=prepared.revision();appliedHash=prepared.hash();authoredJson=prepared.json();
                    if(clean)reloadEditor(editor,prepared.editorManager(),prepared.json());
                    status="Погода, регионы и циклы синхронизированы: версия "+appliedRevision;
                }
            }
            for(int i=0;i<2;i++){Outgoing message=outgoing.poll();if(message==null)break;if(message.connection()==connection)send(message.message());}
        }catch(Throwable error){error(error);}
    }
    private static void accept(JsonObject message)throws Exception {
        switch(message.get("op").getAsString()){
            case "chunkCoverage"->WeatherChunkOverlay.receive(message);
            case "state"->{state=message;receivedAt=System.nanoTime();active=true;editable=message.get("editable").getAsBoolean();syncUtc();syncSnow();}
            case "ack"->{if(message.has("ok")&&message.get("ok").getAsBoolean()){
                if(message.has("operation")&&message.get("operation").getAsString().equals("saveConfig")&&savedEditor!=null&&savedJson!=null){set(savedEditor,"enum",savedJson);editorVersions.put(savedEditor,message.has("configRevision")?message.get("configRevision").getAsLong():appliedRevision+1);savedEditor=null;savedJson=null;}
                saving=false;failure="";status="Изменение сохранено сервером";
            }else{saving=false;failure=message.has("error")?message.get("error").getAsString():"environment_request_rejected";status="Сервер отклонил изменение: "+failure;}}
            case "configBegin"->{downloadSize=message.get("bytes").getAsInt();if(downloadSize<0||downloadSize>EnvironmentWire.MAX_CONFIG)throw new IOException("weather_download_limit");downloadRevision=message.get("revision").getAsLong();downloadHash=message.get("sha256").getAsString();downloadPage=0;download=new ByteArrayOutputStream(downloadSize);}
            case "configPart"->{
                if(download==null||message.get("index").getAsInt()!=downloadPage++)throw new IOException("weather_download_sequence");byte[] bytes=Base64.getDecoder().decode(message.get("data").getAsString());if(bytes.length>EnvironmentWire.PAGE_BYTES||download.size()+bytes.length>downloadSize)throw new IOException("weather_download_limit");download.write(bytes);
                if(download.size()==downloadSize){byte[] compressed=download.toByteArray();download=null;if(!hash(compressed).equals(downloadHash))throw new IOException("weather_download_checksum");
                    if(downloadRevision!=appliedRevision||!downloadHash.equals(appliedHash)){
                        final Object owner=connection;final long revision=downloadRevision;final String hash=downloadHash;
                        preparing=worker.submit(()->{long size=Zstd.decompressedSize(compressed);if(size<2||size>32L*1024*1024)throw new IOException("weather_config_raw_limit");String json=new String(Zstd.decompress(compressed,(int)size),StandardCharsets.UTF_8);Object manager=type("szmods.bridge.EnvironmentWeatherManager").getConstructor(boolean.class).newInstance(true);call(manager,"break","void",json,false);Object editorManager=type("szmods.bridge.EnvironmentWeatherManager").getConstructor(boolean.class).newInstance(true);call(editorManager,"break","void",json,true);call(editorManager,"long","void");copyFallback(manager,editorManager);String base=(String)field(type("1049587979"),"byte");for(Object style:((Map<?,?>)field(editorManager,"do")).values())if(base.equals(field(style,"void"))){set(manager,"fallbackStyle",style);break;}return new Prepared(owner,revision,hash,json,manager,editorManager);});status="Подготовка серверных регионов и погодных циклов…";
                    }
                }
            }
            default->throw new IOException("unknown_environment_reply");
        }
    }
    private static void syncUtc()throws Exception {
        long server=state.get("serverMillis").getAsLong()+(System.nanoTime()-receivedAt)/1_000_000L;
        call(type("1553688601"),"throws","void",server,1f);
    }
    /** Feed the native phenomenon controller: particles, snow cover, audio and region policy stay native. */
    private static void syncSnow()throws Exception {
        long now=state.get("serverMillis").getAsLong(),start=state.get("weatherStarted").getAsLong(),end=state.get("weatherUntil").getAsLong();
        boolean enabled=state.get("weather").getAsString().equals("snow")&&end>now;
        String phase=null;long phaseStart=now;
        if(enabled){
            Object preparation=field(type("682686664"),"PREPARATION"),ending=field(type("682686664"),"ENDING");
            long nativePreparation=((java.time.Duration)call(type("1683205074"),"strictfp","java.time.Duration",preparation)).toMillis();
            long nativeEnding=((java.time.Duration)call(type("1683205074"),"strictfp","java.time.Duration",ending)).toMillis();
            long length=Math.max(1,end-start),prepare=Math.min(nativePreparation,length/4),finish=Math.min(nativeEnding,length/4);
            if(prepare>0&&now<start+prepare){phase="PREPARATION";phaseStart=now-(long)((double)(now-start)/prepare*nativePreparation);}
            else if(finish>0&&now>=end-finish){phase="ENDING";phaseStart=now-(long)((double)(now-end+finish)/finish*nativeEnding);}
            else{phase="ACTIVE";phaseStart=start+prepare;}
        }
        String key=enabled?start+":"+end+":"+phase:"inactive";if(key.equals(snowKey)){if(enabled&&!phase.equals("ACTIVE"))set(snowState,"this",java.time.Instant.ofEpochMilli(phaseStart));return;}
        Object next=enabled?type("1380868477").getConstructor(type("682686664"),java.time.Instant.class,java.time.Instant.class,UUID.class,java.time.Instant.class).newInstance(field(type("682686664"),phase),java.time.Instant.ofEpochMilli(start),java.time.Instant.ofEpochMilli(phaseStart),UUID.nameUUIDFromBytes((start+":"+end).getBytes(StandardCharsets.UTF_8)),java.time.Instant.ofEpochMilli(end)):type("1380868477").getConstructor().newInstance();
        Object update=type("217636303").getConstructor(type("1384066014"),type("746015971"),type("746015971")).newInstance(field(type("1384066014"),"SNOWSTORM"),next,snowState==null?next:snowState);
        call(field(type("962844222"),"else"),"const","void",List.of(update));snowState=next;snowKey=key;
    }
    /** Lighting authority never changes weapon/action clock speed or the editor's explicit preview. */
    public static boolean clock(Object currentGame) {
        if(!owns())return false;
        try {
            if(!((Enum<?>)field(type("1461113355"),"default")).name().equals("NORMAL"))return true;
            Object world=field(currentGame,"g");if(world==null)return true;
            double ticks=state.get("nativeTime").getAsDouble()+(System.nanoTime()-receivedAt)/1e9*20*state.get("timeSpeed").getAsDouble();
            Object info=call(world,"private","249504617");long target=(long)Math.floor(ticks);
            set(info,"new",target);set(info,"extends",target);return true;
        }catch(Exception error){error(error);return true;}
    }
    public static boolean nativeDayTick(Object unused){
        if(!owns())return false;
        try{if(!((Enum<?>)field(type("1461113355"),"default")).name().equals("NORMAL"))return false;}catch(Exception error){error(error);return false;}
        return clock(game);
    }
    static boolean chunkCoverageAvailable(){return owns()&&editable&&state.has("chunkCoverage")&&state.get("chunkCoverage").getAsBoolean();}
    static void requestChunkCoverage(){outgoing.add(new Outgoing(connection,EnvironmentWire.message("getChunkCoverage")));}
    public static boolean weather(Object game){if(context==null)frame(game);return owns();}
    public static boolean clockPacket(Object packet) {
        if(!owns())return false;
        try{set(packet,"catch",state.get("serverMillis").getAsLong()+(System.nanoTime()-receivedAt)/1_000_000L);set(packet,"switch",1f);return true;}catch(Exception error){error(error);return false;}
    }
    /** All preset interpolation still runs in the game's own region/style evaluator. */
    public static Map<?,?> weights(Map<?,?> original,float scale) {
        if(!owns())return original;
        try {
            Object editor=field(type("1730472284"),"transient");if((Boolean)call(editor,"public","boolean"))return original;
            if(!state.has("phenomenon")||state.get("phenomenon").isJsonNull())return original;
            if(state.get("weather").getAsString().equals("snow"))return original;
            // Native short(double,float) consumes its float argument while filling the map.
            // At return that local contains the unused remainder, often zero, not the input budget.
            // The completed weights retain the full budget, including native event attenuation.
            float budget=0;for(Object weight:original.values())budget+=((Number)weight).floatValue();
            if(!Float.isFinite(budget)||budget<0)return original;
            Object phenomenon=field(type("680337400"),state.get("phenomenon").getAsString());Map<Object,Float> weights=new LinkedHashMap<>();weights.put(phenomenon,budget);return weights;
        }catch(Exception error){error(error);return original;}
    }
    public static void editorOpened(Object editor){lastEditor=editor;editorVersions.put(editor,appliedRevision);if(owns()&&appliedRevision>0&&authoredJson!=null)try{Object copy=type("szmods.bridge.EnvironmentWeatherManager").getConstructor(boolean.class).newInstance(true);call(copy,"break","void",authoredJson,true);call(copy,"long","void");reloadEditor(editor,copy,authoredJson);}catch(Exception error){error(error);}}
    private static void copyFallback(Object target,Object source)throws Exception{Object fallback=field(target,"public");call(fallback,"finally","void");call(fallback,"throw","void",field(source,"public"),1f);}
    /** Preserve native interpolation for the base style when a teleport leaves all weather regions. */
    public static Object defaultProfile(Object original,Object manager){
        if(!owns()||!manager.getClass().getName().equals("szmods.bridge.EnvironmentWeatherManager"))return original;
        try{Object style=field(manager,"fallbackStyle");return style==null?original:call(style,"implements","1275877375",field(type("680337400"),"ClearWeather"));}catch(Exception error){error(error);return original;}
    }
    private static void flushEditor(Object editor)throws Exception{for(Object tab:(List<?>)field(editor,"transient"))call(tab,"if","void");}
    private static boolean editorDirty(Object editor)throws Exception {flushEditor(editor);return !Objects.equals(call(field(editor,"this"),"throw","java.lang.String"),field(editor,"enum"));}
    private static void reloadEditor(Object editor,Object manager,String json)throws Exception {
        Object target=field(editor,"this");for(String name:List.of("do","return","native")){Map<Object,Object> to=(Map<Object,Object>)field(target,name);to.clear();to.putAll((Map<?,?>)field(manager,name));}
        List<Object> regions=(List<Object>)field(target,"instanceof");regions.clear();regions.addAll((List<?>)field(manager,"instanceof"));set(target,"extends",field(manager,"extends"));copyFallback(target,manager);
        for(Object tab:(List<?>)field(editor,"transient"))call(tab,"synchronized","void");set(editor,"enum",call(target,"throw","java.lang.String"));editorVersions.put(editor,appliedRevision);
    }
    public static boolean saveEditor(Object editor) {
        if(!owns())return false;
        try {
            if(!editable)throw new IllegalStateException("environment_requires_world_editor");if(saving)throw new IllegalStateException("Дождитесь завершения сохранения");
            if(preparing!=null||download!=null||appliedRevision!=state.get("configRevision").getAsLong())throw new IllegalStateException("Сначала дождитесь загрузки серверной версии");
            flushEditor(editor);String json=(String)call(field(editor,"this"),"throw","java.lang.String");long expected=editorVersions.getOrDefault(editor,appliedRevision);
            final Object owner=connection;saving=true;savedEditor=editor;savedJson=json;status="Сохранение регионов, стилей, палитр и циклов на сервер…";
            worker.submit(()->{try {
                byte[] compressed=Zstd.compress(EnvironmentWire.compactConfiguration(json).getBytes(StandardCharsets.UTF_8),3);if(compressed.length>EnvironmentWire.MAX_CONFIG)throw new IOException("weather_config_byte_limit");
                JsonObject begin=EnvironmentWire.message("configBegin");begin.addProperty("bytes",compressed.length);begin.addProperty("sha256",hash(compressed));begin.addProperty("expectedRevision",expected);outgoing.add(new Outgoing(owner,begin));
                for(int at=0,index=0;at<compressed.length;at+=EnvironmentWire.PAGE_BYTES,index++){JsonObject part=EnvironmentWire.message("configPart");part.addProperty("index",index);part.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(compressed,at,Math.min(compressed.length,at+EnvironmentWire.PAGE_BYTES))));outgoing.add(new Outgoing(owner,part));}
            }catch(Throwable error){if(owner==connection){saving=false;error(error);}}});return true;
        }catch(Exception error){error(error);return true;}
    }
    public static boolean cloudBakeAllowed(){if(owns())return editable;try{return (Boolean)call(type("220710426"),"final","boolean");}catch(Exception error){return false;}}
    public static void controls(Object editorWindow) {
        if(!owns())return;
        uiFrames++;
        try {
            ImGui.separator();ImGui.text("Серверная погода и время");ImGui.textWrapped(status);
            String label=Arrays.stream(modes).filter(mode->mode[0].equals(choice)).map(mode->mode[1]).findFirst().orElse(choice);
            if(ImGui.beginCombo("Погодное явление",label)){for(String[] mode:modes)if(ImGui.selectable(mode[1],mode[0].equals(choice)))choice=mode[0];ImGui.endCombo();}
            ImGui.inputInt("Длительность, секунд",duration);duration.set(Math.max(1,Math.min(86400,duration.get())));
            if(ImGui.button("Применить погоду всем")){JsonObject request=EnvironmentWire.message("update");request.addProperty("weather",choice);request.addProperty("durationSeconds",duration.get());outgoing.add(new Outgoing(connection,request));}
            ImGui.sameLine();if(ImGui.button("Включить снег")){JsonObject request=EnvironmentWire.message("update");request.addProperty("weather","snow");request.addProperty("durationSeconds",duration.get());outgoing.add(new Outgoing(connection,request));}
            if(ImGui.button("Применить время всем")){
                Object world=field(game,"g");long nativeTime=((Number)call(world,"if","long")).longValue();double ticks=(nativeTime+160000d/24)*24000d/160000;
                Object controller=field(type("1461113355"),"interface");String mode=((Enum<?>)call(controller,"class","366749492")).name();double speed=mode.equals("FIXED")?0:mode.equals("SPEDUP")?((Number)call(controller,"volatile","float")).doubleValue():1;
                JsonObject request=EnvironmentWire.message("update");request.addProperty("time",(long)Math.floor(ticks));request.addProperty("timeSpeed",speed);outgoing.add(new Outgoing(connection,request));
                call(controller,"public","void",field(type("366749492"),"NORMAL"));set(editorWindow,"private",false);
            }
            ImGui.sameLine();if(ImGui.button("Возобновить ход времени")){JsonObject request=EnvironmentWire.message("update");request.addProperty("timeSpeed",1);outgoing.add(new Outgoing(connection,request));}
            ImGui.textWrapped("Ползунки выше — локальный предпросмотр. «Применить» меняет общий мир. «Файл → Сохранить» сохраняет регионы, стили, палитры и расписания на сервере.");
            if(ImGui.button("Загрузить с сервера — сбросить черновик")){
                if(saving||preparing!=null||download!=null)throw new IllegalStateException("Дождитесь текущей передачи конфигурации");
                Object editor=field(type("1730472284"),"transient");flushEditor(editor);set(editor,"enum",call(field(editor,"this"),"throw","java.lang.String"));
                appliedRevision=-1;appliedHash="";savedEditor=null;savedJson=null;failure="";status="Загрузка актуальной серверной версии…";
                outgoing.add(new Outgoing(connection,EnvironmentWire.message("getConfig")));
            }
        }catch(Exception error){error(error);}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static JsonObject qaState()throws Exception {
        JsonObject out=new JsonObject();out.addProperty("active",active);out.addProperty("editable",editable);out.addProperty("appliedRevision",appliedRevision);out.addProperty("appliedHash",appliedHash);out.addProperty("status",status);out.addProperty("failure",failure);out.addProperty("saving",saving);out.addProperty("editorFrames",uiFrames);out.add("server",state.deepCopy());
        if(active){Object manager=call(type("1049587979"),"switch","1049587979");Object rendered=field(field(type("569545768"),"assert"),"synchronized");out.addProperty("renderedExposure",(Number)field(field(rendered,"goto"),"if"));out.addProperty("renderedAmbient",(Number)field(field(rendered,"goto"),"boolean"));out.addProperty("renderedSky",(Number)field(field(rendered,"catch"),"double"));out.addProperty("fallbackExposure",(Number)field(field(field(manager,"public"),"goto"),"if"));Object editor=field(type("1730472284"),"transient"),model=field(editor,"this");out.addProperty("editorStyles",model==null?0:((Map<?,?>)field(model,"do")).size());}
        if(active){Object world=field(game,"g");out.addProperty("nativeTime",((Number)call(world,"if","long")).longValue());Object manager=call(type("1049587979"),"switch","1049587979");for(String[] part:new String[][]{{"styles","do"},{"schedules","return"},{"palettes","native"}})out.addProperty(part[0],((Map<?,?>)field(manager,part[1])).size());out.addProperty("regions",((List<?>)field(manager,"instanceof")).size());Object snowManager=field(field(type("962844222"),"else"),"try");Object snow=call(snowManager,"default","1683205074");out.addProperty("snowWeight",(Number)call(snow,"throw","float"));out.addProperty("snowPhase",snowKey);out.addProperty("nativeClockSpeed",(Number)field(type("1553688601"),"throw"));}
        if(active){Object events=field(field(type("962844222"),"else"),"try");Object ejection=call(events,"break","1449646185");out.addProperty("ejectionWeight",(Number)call(ejection,"package","float"));out.addProperty("dayFraction",(Number)call(type("765374079"),"for","double"));}
        JsonObject hooks=new JsonObject();System.getProperties().forEach((k,v)->{if(k.toString().startsWith("sz.environment.transform."))hooks.addProperty(k.toString(),v.toString());});out.add("hooks",hooks);return out;
    }
    static void qaAction(JsonObject action)throws Exception {
        String op=action.get("op").getAsString();
        if(op.equals("exportDefault")){Object manager=originalManager==null?call(type("1049587979"),"switch","1049587979"):originalManager;String json=(String)call(manager,"throw","java.lang.String");java.nio.file.Path target=java.nio.file.Path.of(System.getProperty("sz.render.qa.directory"),"native-weather-default.json");java.nio.file.Files.writeString(target,json,StandardCharsets.UTF_8);return;}
        if(op.equals("update")){action.addProperty("version",EnvironmentWire.VERSION);outgoing.add(new Outgoing(connection,action));return;}
        Object editor=field(type("1730472284"),"transient");
        if(op.equals("editorOpen")){Object window=type("1730472284").getConstructor().newInstance();call(call(type("1402131154"),"try","1402131154"),"break","void",window);return;}
        if(op.equals("editorSave")){call(editor,"transient","void");return;}
        if(op.equals("renameRegion")){
            flushEditor(editor);Object manager=field(editor,"this");JsonObject json=JsonParser.parseString((String)call(manager,"throw","java.lang.String")).getAsJsonObject();json.getAsJsonArray("regions").get(0).getAsJsonObject().addProperty("name",action.get("name").getAsString());call(manager,"break","void",json.toString(),true);for(Object tab:(List<?>)field(editor,"transient"))call(tab,"synchronized","void");return;
        }
        throw new IllegalArgumentException("unknown_qa_action");
    }
}

