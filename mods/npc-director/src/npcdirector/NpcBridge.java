package npcdirector;

import com.google.gson.*;
import imgui.ImGui;
import imgui.type.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Authoring UI; all game/native object access stays on the client frame owner. */
public final class NpcBridge {
    private record Received(Object connection,byte[] bytes){}
    private static final ArrayBlockingQueue<Received> incoming=new ArrayBlockingQueue<>(64);
    private static final ArrayBlockingQueue<JsonObject> requests=new ArrayBlockingQueue<>(32);
    private static Object game,connection,window;
    private static JsonObject catalog=new JsonObject();
    private static String actor="",program="",status="Сервер ещё не ответил";
    private static final ImString name=new ImString("zombie_route",64),draft=new ImString(48000);
    private static final ImBoolean loop=new ImBoolean(true);
    private static final ImFloat seconds=new ImFloat(2),yaw=new ImFloat(90);
    private static String gait="WALK";
    private static final JsonArray steps=new JsonArray();
    private static volatile String qaState="{}";
    private static volatile long uiFrames;
    private static long sentAt;
    private static long editRevision=-1,nativeEditRevision=-1,nativeListRevision=-1;
    private static final Set<String> group=new LinkedHashSet<>();
    private static final Map<String,Long> nativeFrames=new TreeMap<>();
    private static final List<Object> fontConfigs=new ArrayList<>();
    private static final List<byte[]> fontBytes=new ArrayList<>();
    /** The native atlas keeps pointers from font inputs for later builds. Retain them for its lifetime. */
    public static void retainFontConfig(Object config){fontConfigs.add(config);}
    public static void retainFontBytes(byte[] bytes){fontBytes.add(bytes);}
    public static void releaseFontInputs(){for(Object config:fontConfigs)try{config.getClass().getMethod("destroy").invoke(config);}catch(Exception e){System.setProperty("sz.npc.fontCleanupError",e.toString());}fontConfigs.clear();fontBytes.clear();}
    public static void nativeFrame(Object window){nativeFrames.merge(window.getClass().getName(),1L,Long::sum);}
    private static final Set<String> nativeRequests=Set.of("536730109","1021019243","1785466534","1809022406","1495851331","923493987","1580019374","72832071","1459698367","648635307","1788955781","482715697","466145232","1450003595","1353667941","1045968216","1858175876","1879569808","366336598");
    public static boolean nativeSend(Object packet){if(packet==null||connection==null||!catalog.has("nativeRevision")||!nativeRequests.contains(packet.getClass().getName()))return false;try{var bytes=new ByteArrayOutputStream();packet.getClass().getMethod("switch",DataOutput.class).invoke(packet,new DataOutputStream(bytes));if(bytes.size()>32768)throw new IOException("npc_native_packet_limit");JsonObject request=request("native");request.addProperty("class",packet.getClass().getName());request.addProperty("body",Base64.getEncoder().encodeToString(bytes.toByteArray()));String cls=packet.getClass().getName();long base=cls.equals("466145232")?nativeEditRevision:nativeListRevision;if(base<0)base=catalog.get("nativeRevision").getAsLong();request.addProperty("nativeRevision",base);queue(request);}catch(Exception error){status="NPC: "+error;}return true;}
    public static boolean nativePermissions(){return connection!=null&&catalog.has("nativeRevision");}
    public static void nativePlacement(){if(Boolean.getBoolean("sz.client.isolatedTest")){ImGui.setNextWindowPos(15,15,1);ImGui.setNextWindowSize(930,500,1);}}
    public static void nativeStatus(){ImGui.separator();ImGui.textWrapped("Сервер: "+status);ImGui.textWrapped("Подключены внешность и хранение пресетов. AI, бой и роли ещё требуют серверных обработчиков; сценарии действий запускаются через NPC Director.");}
    public static byte[] read(DataInput input)throws IOException{int n=input.readInt();if(n<2||n>1024*1024)throw new IOException("npc_snapshot_limit");byte[] b=new byte[n];input.readFully(b);return b;}
    public static boolean packet(Object value){return value!=null&&(nativeRequests.contains(value.getClass().getName())||Set.of("szmods.bridge.NpcEditorInput","szmods.bridge.NpcEditorSnapshot","306881531","1295176231","868626947","610316810","1203574050","403066430").contains(value.getClass().getName()));}
    public static void receive(Object owner,byte[] bytes){if(!incoming.offer(new Received(owner,bytes)))throw new IllegalStateException("npc_snapshot_overflow");}
    static Class<?> type(String n)throws Exception{return Class.forName(n);}
    static Object field(Object o,String n)throws Exception{for(Class<?> c=o instanceof Class<?> t?t:o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    static Object call(Object o,String n,String result,Object...args)throws Exception{search:for(Method m:(o instanceof Class<?> c?c:o.getClass()).getMethods())if(m.getName().equals(n)&&m.getReturnType().getName().equals(result)&&m.getParameterCount()==args.length){for(int i=0;i<args.length;i++){Class<?> t=m.getParameterTypes()[i];if(t.isPrimitive())t=t==boolean.class?Boolean.class:t==int.class?Integer.class:t==float.class?Float.class:Double.class;if(args[i]!=null&&!t.isInstance(args[i]))continue search;}return m.invoke(o instanceof Class<?>?null:o,args);}throw new NoSuchMethodException(n+" -> "+result);}
    public static void frame(Object current){
        try{game=current;Object next=call(game,"transient","1219970978");if(next!=connection){connection=next;window=null;catalog=new JsonObject();actor=program="";editRevision=nativeEditRevision=nativeListRevision=-1;group.clear();incoming.clear();requests.clear();}if(connection==null)return;
            for(int i=0;i<8;i++){Received r=incoming.poll();if(r==null)break;if(r.connection!=connection)continue;JsonObject o=JsonParser.parseString(new String(r.bytes,StandardCharsets.UTF_8)).getAsJsonObject();String op=o.get("op").getAsString();if(op.equals("error")){status="Ошибка: "+o.get("error").getAsString();continue;}catalog=o;if(editRevision<0)editRevision=o.get("revision").getAsLong();String operation=o.has("operation")?o.get("operation").getAsString():"";if(operation.equals("save"))editRevision=o.get("revision").getAsLong();if(operation.equals("shared")||operation.equals("native"))nativeListRevision=o.get("nativeRevision").getAsLong();if(operation.equals("native"))nativeEditRevision=o.get("nativeRevision").getAsLong();status="Сохранено на сервере, версия "+o.get("revision").getAsLong();if(op.equals("open"))open();}
            if(System.nanoTime()-sentAt>200_000_000L){JsonObject request=requests.poll();if(request!=null){String op=request.get("op").getAsString();if(op.startsWith("_")&&Boolean.getBoolean("sz.client.isolatedTest")){if(op.equals("_close")){if(window!=null)call(window,"return","void");for(String t:List.of("1452958654","417850766")){Object editor=call(type("194946495"),"interface","java.lang.Object",type(t));if(editor!=null)call(editor,"return","void");}}else if(op.equals("_draft")){var p=request.getAsJsonObject("program");name.set(p.get("name").getAsString());loop.set(p.get("loop").getAsBoolean());clearSteps();p.getAsJsonArray("steps").forEach(s->steps.add(s.deepCopy()));draft.set(p.toString());program=name.get();actor=request.get("actor").getAsString();open();}}else{if(op.equals("native")&&!request.has("nativeRevision")&&catalog.has("nativeRevision"))request.addProperty("nativeRevision",catalog.get("nativeRevision").getAsLong());send(request);}sentAt=System.nanoTime();}}
            JsonObject snapshot=new JsonObject();snapshot.add("catalog",catalog);snapshot.addProperty("status",status);snapshot.addProperty("uiFrames",uiFrames);snapshot.addProperty("window",window!=null);snapshot.addProperty("actor",actor);snapshot.add("draft",steps.deepCopy());var nf=new JsonObject();nativeFrames.forEach(nf::addProperty);snapshot.add("nativeFrames",nf);qaState=snapshot.toString();
        }catch(Throwable error){status=Objects.toString(error.getMessage(),error.toString());}
    }
    private static void open()throws Exception{if(window!=null&&(Boolean)call(window,"return","boolean"))return;window=type("szmods.bridge.NpcDirectorWindow").getConstructor().newInstance();call(game,"break","void",window);}
    public static void send(JsonObject request)throws Exception{byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>49152)throw new IllegalArgumentException("npc_editor_request_limit");Object p=type("szmods.bridge.NpcEditorInput").getConstructor(byte[].class).newInstance((Object)bytes);Object envelope=type("385693344").getConstructor(type("499058674")).newInstance(p);call(connection,"else","void",envelope);}
    private static JsonObject request(String op){var r=new JsonObject();r.addProperty("op",op);if(catalog.has("revision"))r.addProperty("revision",catalog.get("revision").getAsLong());return r;}
    private static void queue(JsonObject r){if(!requests.offer(r))status="Очередь редактора заполнена";}
    private static void clearSteps(){while(steps.size()>0)steps.remove(steps.size()-1);}
    private static void add(String action)throws Exception{JsonObject s=new JsonObject();s.addProperty("action",action);if(action.equals("move")){Object p=field(game,"e"),v=call(p,"if","153304200");s.addProperty("x",((Number)field(v,"case")).doubleValue());s.addProperty("y",((Number)field(v,"void")).doubleValue());s.addProperty("z",((Number)field(v,"import")).doubleValue());s.addProperty("gait",gait);s.addProperty("seconds",120);}else if(!action.equals("stop")){s.addProperty("seconds",seconds.get());if(action.equals("look"))s.addProperty("yaw",yaw.get());}steps.add(s);draft.set(draftProgram().toString());}
    private static JsonObject draftProgram(){var p=new JsonObject();p.addProperty("name",name.get());p.addProperty("loop",loop.get());p.add("steps",steps.deepCopy());return p;}
    public static void draw(Object nativeWindow){
        try{uiFrames++;if(Boolean.getBoolean("sz.client.isolatedTest"))ImGui.getIO().setConfigFlags(ImGui.getIO().getConfigFlags()&~1024);ImGui.setNextWindowSize(720,480,4);if(ImGui.begin("NPC Director — серверные сценарии",(ImBoolean)field(nativeWindow,"int"))){
            ImGui.textWrapped(status);if(ImGui.button("Обновить"))queue(request("list"));
            ImGui.sameLine();if(ImGui.button("Shared NPC Editor"))queue(request("shared"));
            if(ImGui.beginCombo("NPC",actor.isEmpty()?"Выбери NPC":actor)){if(catalog.has("actors"))for(var a:catalog.getAsJsonArray("actors")){var o=a.getAsJsonObject();String id=o.get("id").getAsString();if(ImGui.selectable(o.get("preset").getAsString()+" / "+id.substring(id.length()-8),actor.equals(id)))actor=id;}ImGui.endCombo();}
            if(ImGui.button("Создать zombie_01 перед собой")){var r=request("spawn");r.addProperty("preset","zombie_01");queue(r);}
            if(ImGui.collapsingHeader("Группа NPC")){if(catalog.has("actors"))for(var a:catalog.getAsJsonArray("actors")){var o=a.getAsJsonObject();String id=o.get("id").getAsString();ImBoolean chosen=new ImBoolean(group.contains(id));if(ImGui.checkbox(id.substring(id.length()-8)+"##group-"+id,chosen)){if(chosen.get())group.add(id);else group.remove(id);}}if(ImGui.button("Запустить сценарий на группе")){var r=request("run");var ids=new JsonArray();group.forEach(ids::add);r.add("actors",ids);r.addProperty("name",program);queue(r);}}
            if(catalog.has("runs"))for(var e:catalog.getAsJsonArray("runs")){var r=e.getAsJsonObject();ImGui.textWrapped(r.get("program").getAsString()+": шаг "+r.get("step")+" / "+r.get("action")+" "+r.get("error").getAsString());}
            ImGui.separator();ImGui.inputText("Имя сценария",name);ImGui.checkbox("Повторять",loop);
            if(ImGui.beginCombo("Сохранённые",program)){if(catalog.has("programs"))for(var e:catalog.getAsJsonArray("programs")){var p=e.getAsJsonObject();String n=p.get("name").getAsString();if(ImGui.selectable(n,program.equals(n))){program=n;editRevision=catalog.get("revision").getAsLong();name.set(n);loop.set(p.get("loop").getAsBoolean());clearSteps();p.getAsJsonArray("steps").forEach(s->steps.add(s.deepCopy()));draft.set(p.toString());}}ImGui.endCombo();}
            if(ImGui.beginCombo("Темп движения",gait)){for(String g:List.of("SLOW","WALK","JOG","SPRINT"))if(ImGui.selectable(g,gait.equals(g)))gait=g;ImGui.endCombo();}
            if(ImGui.button("+ Идти в мою текущую точку"))add("move");ImGui.inputFloat("Секунды",seconds);seconds.set(Math.max(.05f,Math.min(300,seconds.get())));
            if(ImGui.button("+ Ждать"))add("wait");ImGui.sameLine();if(ImGui.button("+ Следовать за мной"))add("follow");ImGui.sameLine();if(ImGui.button("+ Стоп"))add("stop");
            ImGui.inputFloat("Куда смотреть (yaw)",yaw);if(ImGui.button("+ Повернуться / смотреть"))add("look");
            if(ImGui.button("Очистить черновик")){clearSteps();draft.set("");}
            for(int i=0;i<steps.size();i++)ImGui.textWrapped((i+1)+". "+steps.get(i));
            if(ImGui.button("Сохранить сценарий")){var r=request("save");r.addProperty("revision",editRevision);r.add("program",draftProgram());queue(r);program=name.get();}
            ImGui.sameLine();if(ImGui.button("Запустить на выбранном NPC")){var r=request("run");r.addProperty("actor",actor);r.addProperty("name",program);queue(r);}
            ImGui.sameLine();if(ImGui.button("Остановить NPC")){var r=request("stop");r.addProperty("actor",actor);queue(r);}
            if(ImGui.collapsingHeader("Сценарий JSON (координаты и расширенные действия)")){ImGui.inputTextMultiline("##npc-program",draft,680,130);if(ImGui.button("Сохранить JSON")){var r=request("save");r.addProperty("revision",editRevision);r.add("program",JsonParser.parseString(draft.get()).getAsJsonObject());queue(r);}}
            ImGui.textWrapped("Движение использует штатный контроллер и поиск пути. Сохранение переживает перезапуск; запуск сценария выполняется вручную.");
        }ImGui.end();}catch(Throwable error){status="Редактор: "+Objects.toString(error.getMessage(),error.toString());}
    }
    public static void startQa()throws Exception{int port=Integer.getInteger("sz.npc.editor.qa.port",0);if(!Boolean.getBoolean("sz.client.isolatedTest")||port==0)return;var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",port),8);http.createContext("/state",e->{byte[] b=qaState.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});http.createContext("/request",e->{byte[] bytes=e.getRequestBody().readNBytes(49153);boolean ok=bytes.length<=49152&&requests.offer(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject());byte[] b=(ok?"{}":"{\"error\":\"queue\"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(ok?200:429,b.length);e.getResponseBody().write(b);e.close();});http.setExecutor(Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"npc-editor-qa");t.setDaemon(true);return t;}));http.start();}
    private NpcBridge(){}
}

