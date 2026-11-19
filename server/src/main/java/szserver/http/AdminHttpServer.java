package szserver.http;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import szserver.commands.CommandService;
import szserver.commands.MapReads;
import szserver.util.*;
import szserver.world.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Admin/research API, explicitly distinct from BSCPCRAFT's native transport. */
public final class AdminHttpServer implements AutoCloseable {
    private static final Gson SCENE_JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final HttpServer http;
    private final ThreadPoolExecutor incoming=Workers.pool("http-input",4,64),outgoing=Workers.pool("http-output",4,256);
    private final ScheduledExecutorService timeouts=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"http-timeouts");t.setDaemon(true);return t;});
    private final Semaphore requests=new Semaphore(128);
    private final CommandService commands;
    private final WorldLoop world;
    private final String token;
    private final Supplier<Map<String,Object>> health;
    private final szserver.net.LauncherBackend launcher;
    private boolean started,closed;private volatile szserver.net.LocalAccounts accounts;
    private volatile ServerLifecycle lifecycle;
    private volatile szserver.net.GameServer game;
    private record ScreenshotImage(byte[] bytes){}
    public synchronized void bindScreenshots(szserver.net.GameServer game){if(started||closed||this.game!=null)throw new IllegalStateException("screenshot_binding_unavailable");this.game=Objects.requireNonNull(game);}
    public synchronized void bindLifecycle(ServerLifecycle lifecycle){if(started||closed||this.lifecycle!=null)throw new IllegalStateException("lifecycle_binding_unavailable");this.lifecycle=Objects.requireNonNull(lifecycle);}
    public synchronized void bindAccounts(szserver.net.LocalAccounts accounts){if(started||closed||this.accounts!=null)throw new IllegalStateException("account_admin_binding_unavailable");this.accounts=Objects.requireNonNull(accounts);}
    public AdminHttpServer(InetSocketAddress address,String token,CommandService commands,WorldLoop world,Supplier<Map<String,Object>> health)throws IOException{
        this(address,token,commands,world,health,null);
    }
    public AdminHttpServer(InetSocketAddress address,String token,CommandService commands,WorldLoop world,Supplier<Map<String,Object>> health,szserver.net.LauncherBackend launcher)throws IOException{
        this.launcher=launcher;
        this.token=token;this.commands=commands;this.world=world;this.health=health;
        http=HttpServer.create(address,64);http.setExecutor(incoming);http.createContext("/",this::handle);
    }
    public int port(){return http.getAddress().getPort();}
    public synchronized void start(){if(closed||started)throw new IllegalStateException("admin_listener_already_started_or_closed");http.start();started=true;}
    private boolean authorized(HttpExchange x){
        String provided=x.getRequestHeaders().getFirst("Authorization");
        return provided!=null&&MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8));
    }
    private void handle(HttpExchange x){
        if(!requests.tryAcquire()){send(x,503,Map.of("error","http_overloaded"));return;}
        try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.equals("/health")&&method.equals("GET")){
                Map<String,Object> data=health.get();send(x,Boolean.TRUE.equals(data.get("healthy"))?200:503,data);requests.release();return;
            }
            if(!authorized(x)){send(x,401,Map.of("error","authorization_required"));requests.release();return;}
            if(path.equals("/server")&&method.equals("GET")){
                if(lifecycle==null)throw new IllegalStateException("lifecycle_unavailable");
                send(x,200,lifecycle.status());requests.release();return;
            }
            if(path.equals("/server/stop")&&method.equals("POST")){
                if(lifecycle==null)throw new IllegalStateException("lifecycle_unavailable");
                if(!"application/json".equalsIgnoreCase(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim()))throw new IllegalArgumentException("application_json_required");
                byte[] body=x.getRequestBody().readNBytes(65);
                if(body.length>64||!Json.parse(body).isEmpty())throw new IllegalArgumentException("server_stop_body_must_be_empty");
                lifecycle.request();
                try{send(x,202,lifecycle.status());}finally{requests.release();lifecycle.signal();}
                return;
            }
            if(lifecycle!=null&&lifecycle.stopping()&&!method.equals("GET")){send(x,503,Map.of("error","server_stopping"));requests.release();return;}
            if(path.equals("/clients/screenshots")||path.startsWith("/clients/screenshots/")){screenshots(x,path,method);return;}
            if(path.startsWith("/launcher/")){launcher(x,path,method);return;}
            MapReads.Request mapRead=mapRead(x,path,method);if(mapRead!=null){respondFuture(x,commands.readMap(mapRead),path,"");return;}
            if(method.equals("GET")){
                Object data;
                if(path.equals("/world/environment")||path.equals("/v2/world/environment")){respondFuture(x,commands.environment().expire().thenApply(ignored->commands.environmentView()),path,"");return;}
                else if(path.equals("/world/events")||path.equals("/v2/world/events")){var parameters=query(x);dispatch(x,200,commands.worldEventPage(Long.parseLong(parameters.getOrDefault("after","0")),Integer.parseInt(parameters.getOrDefault("limit","512"))));return;}
                else if(path.equals("/world"))data=world.snapshot();
                else if(path.equals("/backend/accounts/index")){if(accounts==null)throw new IllegalStateException("account_backend_unavailable");data=accounts.indexMaintenanceStatus();}
                else if(path.equals("/world/checkpoint"))data=commands.checkpointStatus();
                else if(path.equals("/scene/compact"))data=commands.sceneMaintenanceStatus();
                else if(path.equals("/world/cache-cleanup"))data=commands.cacheCleanupStatus();
                else if(path.equals("/world/journals/compact"))data=commands.worldJournalMaintenanceStatus();
                else if(path.equals("/world/history/pack"))data=commands.historyPackingStatus();
                else if(path.equals("/world/map/snapshot"))data=commands.nativeMapArchiveStatus();
                else if(path.equals("/world/map/commit"))data=commands.nativeMapCommitStatus();
                else if(path.equals("/world/map/maintenance")||path.equals("/world/map/consolidation"))data=commands.nativeMapMaintenanceStatus();
                else if(path.equals("/scene/objects")||path.equals("/scene/tombstones")||path.equals("/scene/changes")){
                    Map<String,String> parameters=query(x);int limit=Integer.parseInt(parameters.getOrDefault("limit","1024"));String field=path.substring(7);
                    dispatch(x,200,commands.scenePage(field,field.equals("objects")&&parameters.containsKey("x")?Integer.valueOf(parameters.get("x")):null,field.equals("objects")&&parameters.containsKey("z")?Integer.valueOf(parameters.get("z")):null,parameters.get("after"),limit));return;
                }
                else if(path.startsWith("/scene/editor-state/")){data=commands.nativeEditorState(UUID.fromString(path.substring(20)));if(data==null){send(x,404,Map.of("error","scene_editor_state_not_found"));requests.release();return;}}
                else if(path.startsWith("/scene/editor-links/")){data=commands.nativeEditorRelationships(UUID.fromString(path.substring(20)));}
                else if(path.startsWith("/scene/object/")){data=commands.sceneObject(path.substring(14));if(data==null){send(x,404,Map.of("error","scene_object_does_not_exist"));requests.release();return;}}
                else if(path.startsWith("/inventory/"))data=commands.inventory(UUID.fromString(path.substring(11)).toString());
                else if(path.startsWith("/commands/")){data=commands.status(path.substring(10));if(data==null){send(x,404,Map.of("error","command_not_committed_or_unknown"));requests.release();return;}}
                else{send(x,404,Map.of("error","unknown_route"));requests.release();return;}
                send(x,200,data);requests.release();return;
            }
            if(!method.equals("POST")||(!path.equals("/backend/accounts/index")&&!path.equals("/commands")&&!path.equals("/bot/input")&&!path.equals("/bot/control")&&!path.equals("/bot/equipment")&&!path.equals("/bot/effect")&&!path.equals("/bot/state")&&!path.equals("/bot/seat")&&!path.equals("/world/checkpoint")&&!path.equals("/scene/compact")&&!path.equals("/world/cache-cleanup")&&!path.equals("/world/history/pack")&&!path.equals("/world/journals/compact")&&!path.equals("/world/map/snapshot")&&!path.equals("/world/map/commit")&&!path.equals("/world/map/maintenance")&&!path.equals("/world/map/consolidation")&&!path.equals("/world/environment")&&!path.equals("/v2/world/environment"))){send(x,404,Map.of("error","unknown_route"));requests.release();return;}
            if(!"application/json".equalsIgnoreCase(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim()))
                throw new IllegalArgumentException("application_json_required");
            String contentLength=x.getRequestHeaders().getFirst("Content-Length");
            if(contentLength!=null&&Long.parseLong(contentLength)>65536)throw new IllegalArgumentException("request_too_large");
            byte[] body=x.getRequestBody().readNBytes(65537);
            if(body.length>65536)throw new IllegalArgumentException("request_too_large");
            JsonObject request=Json.parse(body);
            CompletableFuture<?> future;String commandId="";
            if(path.equals("/backend/accounts/index")){if(accounts==null)throw new IllegalStateException("account_backend_unavailable");if(!request.isEmpty())throw new IllegalArgumentException("account_index_body_must_be_empty");future=accounts.compactIndex();}
            else if(path.equals("/world/environment")||path.equals("/v2/world/environment")){if(!Set.of("time","timeDelta","weather","durationTicks","timeSpeed").containsAll(request.keySet()))throw new IllegalArgumentException("invalid_environment_fields");Long time=request.has("time")?exact(request,"time"):null,delta=request.has("timeDelta")?exact(request,"timeDelta"):null;long duration=request.has("durationTicks")?exact(request,"durationTicks"):12000;if(duration<20||duration>1728000||duration%20!=0)throw new IllegalArgumentException("invalid_weather_duration");var mutation=new szserver.persistence.WorldEnvironment.Mutation(time,delta,request.has("weather")?szserver.net.EnvironmentCommands.Weather.read(Json.text(request,"weather",16)):null,(int)(duration/20),request.has("timeSpeed")?Json.number(request,"timeSpeed",0,10000):null);commandId="";future=commands.environment().update(mutation).thenApply(ignored->commands.environmentView());}
            else if(path.equals("/bot/control")){
                if(!request.keySet().equals(Set.of("entityId","yaw","pitch","heading","gait","jump","crouch","proneToggle")))throw new IllegalArgumentException("actor_control_fields");
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();
                for(String field:List.of("jump","crouch","proneToggle"))if(!request.get(field).isJsonPrimitive()||!request.get(field).getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("actor_control_boolean");
                var input=new szserver.world.ActorControllerEngine.Input((float)Json.number(request,"yaw",-360000,360000),(float)Json.number(request,"pitch",-90,90),(float)Json.number(request,"heading",-180,180),szserver.world.ActorLocomotion.Gait.valueOf(Json.text(request,"gait",16)),request.get("jump").getAsBoolean(),request.get("crouch").getAsBoolean(),request.get("proneToggle").getAsBoolean());
                future=world.submit(w->{w.control(id,input);return Map.of("status","applied");});
            }
            else if(path.equals("/bot/equipment")){future=commands.actorEquipment(request);}
            else if(path.equals("/bot/effect")){future=commands.actorEffect(request);}
            else if(path.equals("/bot/state")){future=commands.actorCondition(request);}
            else if(path.equals("/bot/seat")){future=commands.actorSeat(request);}
            else if(path.equals("/commands")){
                commandId=Json.text(request,"commandId",64);future=commands.submit(request);
            }else if(path.equals("/world/checkpoint")){
                if(!request.isEmpty())throw new IllegalArgumentException("checkpoint_body_must_be_empty");future=commands.checkpoint();
            }else if(path.equals("/world/cache-cleanup")){if(!request.isEmpty())throw new IllegalArgumentException("cache_cleanup_body_must_be_empty");future=commands.cleanDerivedCaches();
            }else if(path.equals("/world/history/pack")){if(!request.isEmpty())throw new IllegalArgumentException("world_history_pack_body_must_be_empty");future=commands.packHistory();
            }else if(path.equals("/world/journals/compact")){if(!request.isEmpty())throw new IllegalArgumentException("world_journals_compact_body_must_be_empty");future=commands.compactWorldJournals();
            }else if(path.equals("/world/map/snapshot")){if(!request.isEmpty())throw new IllegalArgumentException("native_map_snapshot_body_must_be_empty");future=commands.saveNativeMapArchive();
            }else if(path.equals("/world/map/commit")){if(!request.isEmpty())throw new IllegalArgumentException("native_map_commit_body_must_be_empty");future=commands.commitNativeMap();
            }else if(path.equals("/world/map/consolidation")){if(!request.isEmpty())throw new IllegalArgumentException("native_consolidation_body_must_be_empty");future=commands.consolidateNativeMap();
            }else if(path.equals("/world/map/maintenance")){if(!request.isEmpty())throw new IllegalArgumentException("native_map_maintenance_body_must_be_empty");future=commands.maintainNativeMap();
            }else if(path.equals("/scene/compact")){if(!request.isEmpty())throw new IllegalArgumentException("scene_compact_body_must_be_empty");future=commands.compactSceneRows();
            }else{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();
                double ix=Json.number(request,"x",-1,1),iz=Json.number(request,"z",-1,1);
                boolean jump=request.has("jump")&&request.get("jump").isJsonPrimitive()&&request.get("jump").getAsJsonPrimitive().isBoolean()&&request.get("jump").getAsBoolean();
                future=world.submit(w->{w.input(id,ix,iz,jump);return Map.of("status","applied");});
            }
            respondFuture(x,future,path,commandId);
        }catch(RejectedExecutionException e){send(x,503,Map.of("error","server_overloaded"));requests.release();}
        catch(IllegalArgumentException e){send(x,400,Map.of("error",Optional.ofNullable(e.getMessage()).orElse("invalid_request")));requests.release();}
        catch(Exception e){send(x,500,Map.of("error","request_failed"));requests.release();}
    }
    private void screenshots(HttpExchange x,String path,String method)throws IOException{
        if(game==null)throw new IllegalStateException("screenshots_unavailable");
        String[] parts=path.split("/");
        if(parts.length==3&&method.equals("POST")){
            if(!"application/json".equalsIgnoreCase(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim()))throw new IllegalArgumentException("application_json_required");
            byte[] bytes=x.getRequestBody().readNBytes(129);if(bytes.length>128)throw new IllegalArgumentException("request_too_large");
            JsonObject body=Json.parse(bytes);if(!body.keySet().equals(Set.of("requestId")))throw new IllegalArgumentException("screenshot_request_fields");
            respondFuture(x,game.requestScreenshots(UUID.fromString(Json.text(body,"requestId",36))),path,"");return;
        }
        if(parts.length==4){
            UUID id=UUID.fromString(parts[3]);
            if(method.equals("GET")){dispatch(x,200,game.screenshots().status(id));return;}
            if(method.equals("DELETE")){game.screenshots().delete(id);send(x,200,Map.of("deleted",true));requests.release();return;}
        }
        if(parts.length==5&&method.equals("GET")){
            byte[] bytes=game.screenshots().image(UUID.fromString(parts[3]),UUID.fromString(parts[4]));
            if(bytes==null){send(x,404,Map.of("error","screenshot_not_available"));requests.release();return;}
            dispatch(x,200,new ScreenshotImage(bytes));return;
        }
        send(x,404,Map.of("error","unknown_route"));requests.release();
    }
    private static boolean mapPath(String path){return path.startsWith("/v1/chunks/")||path.startsWith("/v2/world/chunks/")||path.startsWith("/v3/world/chunks/")||path.startsWith("/v2/world/blocks/");}
    private static MapReads.Request mapRead(HttpExchange exchange,String path,String method)throws IOException {
        if(!mapPath(path))return null;
        Map<String,String> params=new HashMap<>();String raw=exchange.getRequestURI().getRawQuery();
        if(raw!=null){if(raw.length()>128)throw new IllegalArgumentException("query_too_large");for(String part:raw.split("&")){String[] pair=part.split("=",2);if(pair.length!=2||!pair[0].equals("dimension")||params.putIfAbsent(pair[0],pair[1])!=null)throw new IllegalArgumentException("invalid_map_query");}}
        if(Integer.parseInt(params.getOrDefault("dimension","0"))!=0)throw new IllegalArgumentException("unknown_dimension");
        if(method.equals("GET")){
            var block=java.util.regex.Pattern.compile("/v2/world/blocks/(-?[0-9]+)/(-?[0-9]+)/(-?[0-9]+)/native").matcher(path);
            if(block.matches())return new MapReads.Request("block",List.of(),List.of(new World.Block(Integer.parseInt(block.group(1)),Integer.parseInt(block.group(2)),Integer.parseInt(block.group(3)))));
            var chunk=java.util.regex.Pattern.compile("/(v1/chunks|v[23]/world/chunks)/(-?[0-9]+)/(-?[0-9]+)/(surface|raw|revision|native|native-record)").matcher(path);
            if(chunk.matches()){
                String group=chunk.group(1),op=chunk.group(4);boolean valid=group.equals("v1/chunks")?Set.of("surface","raw").contains(op):group.equals("v2/world/chunks")?Set.of("revision","native").contains(op):op.equals("native-record");
                if(valid)return new MapReads.Request(op.equals("native")?"summary":op.equals("native-record")?"record":op,List.of(new MapReads.Chunk(Integer.parseInt(chunk.group(2)),Integer.parseInt(chunk.group(3)))),List.of());
            }
        }else if(method.equals("POST")&&(path.equals("/v2/world/chunks/raw")||path.equals("/v2/world/blocks/native"))){
            if(!"application/json".equalsIgnoreCase(Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim()))throw new IllegalArgumentException("application_json_required");
            byte[] bytes=exchange.getRequestBody().readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("request_too_large");JsonObject body=Json.parse(bytes);
            boolean blocks=path.equals("/v2/world/blocks/native");String key=blocks?"positions":"chunks";
            if(!Set.of("dimension",key).containsAll(body.keySet())||!body.has(key)||!body.get(key).isJsonArray())throw new IllegalArgumentException("invalid_map_batch");
            if(body.has("dimension")&&Json.integer(body,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE)!=0)throw new IllegalArgumentException("unknown_dimension");
            JsonArray values=body.getAsJsonArray(key);if(values.size()<1||values.size()>(blocks?256:128))throw new IllegalArgumentException("invalid_map_batch");
            List<World.Block> cells=new ArrayList<>();List<MapReads.Chunk> chunks=new ArrayList<>();
            for(JsonElement value:values){if(!value.isJsonArray()||value.getAsJsonArray().size()!=(blocks?3:2))throw new IllegalArgumentException("invalid_map_coordinate");int[] row=new int[blocks?3:2];for(int i=0;i<row.length;i++){JsonElement number=value.getAsJsonArray().get(i);if(!number.isJsonPrimitive()||!number.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("invalid_map_coordinate");try{row[i]=number.getAsBigDecimal().intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("invalid_map_coordinate");}}if(blocks)cells.add(new World.Block(row[0],row[1],row[2]));else chunks.add(new MapReads.Chunk(row[0],row[1]));}
            return new MapReads.Request(blocks?"blocks":"batch",chunks,cells);
        }
        return null;
    }
    private void launcher(HttpExchange x,String path,String method)throws IOException{
        if(launcher==null){send(x,503,Map.of("error","launcher_not_configured"));requests.release();return;}
        var access=launcher.access();CompletableFuture<?> future;
        if(method.equals("GET"))future=switch(path){case "/launcher/tokens"->access.tokens();case "/launcher/outbox"->access.pending();case "/launcher/audit"->access.audits();case "/launcher/offset"->access.offset();default->null;};
        else if(method.equals("POST")){
            if(!Set.of("/launcher/tokens","/launcher/revoke","/launcher/outbox/delivered","/launcher/offset").contains(path)){send(x,404,Map.of("error","unknown_route"));requests.release();return;}
            if(!"application/json".equalsIgnoreCase(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim()))throw new IllegalArgumentException("application_json_required");
            byte[] body=x.getRequestBody().readNBytes(65537);if(body.length>65536)throw new IllegalArgumentException("request_too_large");JsonObject request=Json.parse(body);
            future=switch(path){
                case "/launcher/tokens"->access.issue(Json.text(request,"login",128),request.has("days")?Json.integer(request,"days",0,3650):0);
                case "/launcher/revoke"->access.revoke(Json.text(request,"id",12));
                case "/launcher/offset"->{JsonElement value=request.get("offset");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]+"))throw new IllegalArgumentException("invalid_launcher_offset");long offset;try{offset=value.getAsBigDecimal().longValueExact();}catch(ArithmeticException bad){throw new IllegalArgumentException("invalid_launcher_offset");}yield access.setOffset(offset).thenApply(ignored->Map.of("ok",true));}
                case "/launcher/outbox/delivered"->{JsonElement values=request.get("ids");if(values==null||!values.isJsonArray()||values.getAsJsonArray().size()>1000)throw new IllegalArgumentException("invalid_launcher_delivery_ids");List<Long> ids=new ArrayList<>();for(JsonElement value:values.getAsJsonArray()){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]+"))throw new IllegalArgumentException("invalid_launcher_delivery_ids");try{ids.add(value.getAsBigDecimal().longValueExact());}catch(ArithmeticException bad){throw new IllegalArgumentException("invalid_launcher_delivery_ids");}}yield access.delivered(ids).thenApply(ignored->Map.of("ok",true));}
                default->null;
            };
        }else future=null;
        if(future==null){send(x,404,Map.of("error","unknown_route"));requests.release();return;}respondFuture(x,future,path,"");
    }
    private void respondFuture(HttpExchange x,CompletableFuture<?> future,String path,String id){
            AtomicBoolean answered=new AtomicBoolean();
            ScheduledFuture<?> timeout=timeouts.schedule(()->{
                if(answered.compareAndSet(false,true)){
                    if(mapPath(path))future.cancel(false);
                    if(path.equals("/backend/accounts/index")||path.equals("/world/checkpoint")||path.equals("/scene/compact")||path.equals("/world/cache-cleanup")||path.equals("/world/history/pack")||path.equals("/world/journals/compact")||path.equals("/world/map/snapshot")||path.equals("/world/map/commit")||path.equals("/world/map/maintenance")||path.equals("/world/map/consolidation"))dispatch(x,202,Map.of("status","pending","statusRoute",path));
                    else dispatch(x,id.isEmpty()?503:202,id.isEmpty()?Map.of("error","world_timeout"):Map.of("status","pending","commandId",id));
                }
            },3,TimeUnit.SECONDS);
            future.whenComplete((result,error)->{
                if(!answered.compareAndSet(false,true)){if(result instanceof MapReads.Reply reply)reply.close();return;}
                timeout.cancel(false);
                if(error==null)dispatch(x,200,result);
                else{
                    Throwable e=error instanceof CompletionException?error.getCause():error;
                    int status=e instanceof IllegalArgumentException?400:e instanceof RejectedExecutionException?503:500;
                    // Do not leak reflective client traces or local paths through the API.
                    String message=status==400?Optional.ofNullable(e.getMessage()).orElse("invalid_command"):status==503?"server_overloaded":"command_failed";
                    dispatch(x,status,Map.of("error",message));
                }
            });
    }
    private static long exact(JsonObject row,String key){try{var value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("environment_integer:"+key,error);}}
    private void dispatch(HttpExchange x,int status,Object data){
        try{outgoing.execute(()->{try{send(x,status,data);}finally{requests.release();}});}
        catch(RejectedExecutionException e){if(data instanceof JsonPages.Page page)page.close();if(data instanceof MapReads.Reply reply)reply.close();x.close();requests.release();}
    }
    private static Map<String,String> query(HttpExchange exchange){String raw=exchange.getRequestURI().getRawQuery();if(raw==null)return Map.of();if(raw.length()>4096)throw new IllegalArgumentException("query_too_large");Map<String,String> result=new HashMap<>();for(String part:raw.split("&")){String[] pair=part.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";if(result.putIfAbsent(key,value)!=null||!Set.of("after","limit","x","z").contains(key))throw new IllegalArgumentException("invalid_scene_query");}return result;}
    private static void send(HttpExchange x,int status,Object data){
        try{
            if(data instanceof ScreenshotImage image){x.getResponseHeaders().set("Content-Type","image/jpeg");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(status,image.bytes().length);x.getResponseBody().write(image.bytes());return;}
            if(data instanceof JsonPages.Page page){boolean headers=false;try(page){var cursor=page.open();x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,0);headers=true;cursor.write(x.getResponseBody());}catch(IOException|RuntimeException error){page.failed();if(!headers)send(x,500,Map.of("error","backend_page_failed"));}return;}
            if(data instanceof MapReads.Reply reply){try(reply){x.getResponseHeaders().set("Content-Type",reply.contentType());x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,reply.bytes().length);x.getResponseBody().write(reply.bytes());}return;}
            byte[] payload=x.getRequestURI().getPath().startsWith("/scene/")||x.getRequestURI().getPath().startsWith("/launcher/")||x.getRequestURI().getPath().endsWith("/world/events")?SCENE_JSON.toJson(data).getBytes(StandardCharsets.UTF_8):Json.bytes(data);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");
            x.sendResponseHeaders(status,payload.length);x.getResponseBody().write(payload);
        }catch(IOException ignored){}finally{x.close();}
    }
    @Override public void close()throws InterruptedException{synchronized(this){if(closed)return;closed=true;}http.stop(started?1:0);timeouts.shutdownNow();try{Workers.stop(incoming);}finally{Workers.stop(outgoing);}}
}
