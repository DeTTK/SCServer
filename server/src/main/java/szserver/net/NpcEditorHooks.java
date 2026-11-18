package szserver.net;

import szserver.npc.*;
import szserver.world.*;
import szserver.client.NpcVisuals;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated editor adapter. IO and authoring codecs have their own bounded worker. */
public final class NpcEditorHooks {
    private static final Map<Object,Service> services=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class SessionState{boolean pending;long at;UUID selected;}
    private static final class Service{
        final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),task->{Thread t=new Thread(task,"npc-authoring");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        final Map<Object,SessionState> sessions=new WeakHashMap<>();final NpcProgramStore store;final NpcPresetStore presets;
        Service(Path data,Path runtime)throws Exception{store=new NpcProgramStore(data);presets=worker.submit(()->new NpcPresetStore(runtime,data)).get(30,TimeUnit.SECONDS);}
    }
    public static void prepare(Object server,Path data,Path runtime)throws Exception{synchronized(services){if(!services.containsKey(server))services.put(server,new Service(data,runtime));}}
    public static void close(Object server){Service s; synchronized(services){s=services.remove(server);}if(s!=null){s.worker.shutdown();try{if(!s.worker.awaitTermination(10,TimeUnit.SECONDS))s.worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    private static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}}
    private static Object call(Object o,String name,Object...args){search:for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i]))continue search;try{m.setAccessible(true);return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(name,e.getCause());}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}}throw new IllegalStateException(name);}
    private static boolean live(Object server,Object session){return ((Map<?,?>)field(server,"sessions")).get(field(session,"address"))==session&&Boolean.TRUE.equals(field(session,"ready"));}
    private static void send(Object server,Object session,JsonObject message){if(!live(server,session))return;byte[] bytes=message.toString().getBytes(StandardCharsets.UTF_8);call(server,"enqueueOrRetire",session,Wire.dynamic("szmods.bridge.NpcEditorSnapshot",Wire.bytes(out->{out.writeInt(bytes.length);out.write(bytes);})) );}
    private static Service service(Object server){synchronized(services){var s=services.get(server);if(s==null)throw new IllegalStateException("npc_editor_not_prepared");return s;}}
    public static boolean chat(Object server,Object session,String text){if(!text.trim().equalsIgnoreCase("/npc editor"))return false;JsonObject request=new JsonObject();request.addProperty("op","open");request(server,session,request);return true;}
    public static boolean application(Object server,Object session,int id,byte[] body){
        if(id!=5&&id!=1501)return false;
        try{var in=Wire.input(body);if(!in.readUTF().equals("szmods.bridge.NpcEditorInput"))return false;if(in.available()<2||in.available()>49152)return true;byte[] bytes=in.readAllBytes();JsonObject request=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();request(server,session,request);}catch(Exception error){JsonObject result=new JsonObject();result.addProperty("op","error");result.addProperty("error",Objects.toString(error.getMessage(),"npc_editor_invalid_packet"));send(server,session,result);}return true;
    }
    private static void request(Object server,Object session,JsonObject request){
        try{
            if(!live(server,session))return;UUID player=(UUID)call(server,"character",session);if(!Boolean.TRUE.equals(call(field(server,"playerRules"),"entitled",player)))throw new IllegalArgumentException("npc_admin_required");
            Service s=service(server);SessionState client=s.sessions.computeIfAbsent(session,k->new SessionState());long now=System.nanoTime();if(client.pending||now-client.at<100_000_000L)throw new IllegalArgumentException("npc_editor_busy");client.at=now;client.pending=true;
            String op=request.get("op").getAsString();WorldLoop world=(WorldLoop)field(server,"world");double[] position=((double[])field(session,"pos")).clone();float yaw=((Number)field(session,"yaw")).floatValue();UUID selection=client.selected;
            try{s.worker.execute(()->{
                try{
                    List<byte[]> nativeReplies=new ArrayList<>();UUID selected=selection;
                    switch(op){
                        case "open","list"->{}
                        case "shared"->nativeReplies.add(s.presets.list());
                        case "native"->{byte[] data=Base64.getDecoder().decode(request.get("body").getAsString());if(data.length>32768)throw new IllegalArgumentException("npc_native_packet_limit");var reply=s.presets.nativeRequest(request.get("class").getAsString(),data,request.get("nativeRevision").getAsLong(),selection);nativeReplies.addAll(reply.packets());selected=reply.selected();}
                        case "spawn"->{String preset=request.get("preset").getAsString();var r=new JsonObject();r.addProperty("type","spawn");r.addProperty("commandId",UUID.randomUUID().toString());r.addProperty("entityId",NpcVisuals.identity(preset).toString());r.addProperty("kind","BOT");var p=new JsonObject();p.addProperty("x",position[0]-Math.sin(Math.toRadians(yaw))*3);p.addProperty("y",position[1]);p.addProperty("z",position[2]+Math.cos(Math.toRadians(yaw))*3);r.add("position",p);((szserver.commands.CommandService)field(server,"commands")).submit(r).get(20,TimeUnit.SECONDS);}
                        case "save"->s.store.save(request.get("revision").getAsLong(),NpcProgram.parse(request.getAsJsonObject("program")));
                        case "delete"->s.store.delete(request.get("revision").getAsLong(),request.get("name").getAsString());
                        case "run"->{List<String> ids=new ArrayList<>();if(request.has("actors")){for(var a:request.getAsJsonArray("actors")){ids.add(a.getAsString());if(ids.size()>16)throw new IllegalArgumentException("npc_group_size");}}else ids.add(request.get("actor").getAsString());NpcProgram program=s.store.get(request.get("name").getAsString());world.submit(w->{NpcDirector.of(w).startMany(w,ids,program,player);return null;}).get(10,TimeUnit.SECONDS);}
                        case "stop"->{String actor=request.get("actor").getAsString();if(!NpcVisuals.managed(actor))throw new IllegalArgumentException("select_authored_npc");world.submit(w->{NpcDirector.of(w).stop(w,actor);return null;}).get(10,TimeUnit.SECONDS);}
                        default->throw new IllegalArgumentException("npc_editor_operation");
                    }
                    JsonObject result=s.store.catalog();result.addProperty("op",op.equals("open")?"open":"catalog");result.addProperty("operation",op);if(op.equals("native"))result.addProperty("nativeClass",request.get("class").getAsString());result.addProperty("ok",true);result.addProperty("nativeRevision",s.presets.revision());var profiles=new JsonArray();s.presets.presets().forEach(p->{var row=new JsonObject();row.addProperty("key",p.key());row.addProperty("id",p.id().toString());profiles.add(row);});result.add("profiles",profiles);var actors=new JsonArray();world.snapshot().entities().stream().filter(a->NpcVisuals.managed(a.id())).limit(128).forEach(a->{JsonObject row=new JsonObject();row.addProperty("id",a.id());row.addProperty("preset",NpcVisuals.profile(a.id()).key());row.addProperty("x",a.x());row.addProperty("y",a.y());row.addProperty("z",a.z());row.addProperty("health",a.health());row.addProperty("navigation",a.navigation());actors.add(row);});result.add("actors",actors);
                    var runs=new JsonArray();for(var run:world.submit(w->NpcDirector.of(w).published()).get(10,TimeUnit.SECONDS)){var row=new JsonObject();row.addProperty("actor",run.actor());row.addProperty("program",run.program());row.addProperty("step",run.step());row.addProperty("action",run.action());row.addProperty("error",run.error());runs.add(row);}result.add("runs",runs);
                    UUID newSelection=selected;call(server,"adminTask",(Runnable)()->{client.pending=false;client.selected=newSelection;send(server,session,result);if(live(server,session))for(byte[] packet:nativeReplies)call(server,"enqueueOrRetire",session,packet);});
                }catch(Throwable error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();String message=Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());try{call(server,"adminTask",(Runnable)()->{client.pending=false;JsonObject result=new JsonObject();result.addProperty("op","error");result.addProperty("error",message);send(server,session,result);});}catch(RejectedExecutionException ignored){}}
            });}catch(RejectedExecutionException full){client.pending=false;throw full;}
        }catch(Exception error){Service s; synchronized(services){s=services.get(server);}if(s!=null){var c=s.sessions.get(session);if(c!=null&&!c.pending)c.pending=false;}JsonObject result=new JsonObject();result.addProperty("op","error");result.addProperty("error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));send(server,session,result);}
    }
    private NpcEditorHooks(){}
}
