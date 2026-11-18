package szserver.net;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import szserver.util.Workers;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated format-2 release delivery on dedicated bounded HTTP workers.
 * Local native-menu HTTP is supported; remote delivery requires actual TLS.
 * Releases/keys are supplied by the operator, never generated from client classes.
 */
public final class LauncherBackend implements AutoCloseable {
    public record Settings(boolean enabled,Path release,Path privateDirectory,InetSocketAddress tlsAddress,Path keyStore,Path passwordFile){
        public static Settings configured(Properties p,Path root,Path data){
            String enabled=p.getProperty("launcher.enabled","false");if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException("invalid_launcher_enabled");
            Path release=root.resolve(p.getProperty("launcher.releaseDirectory",data.resolve("launcher-release").toString())).normalize(),privateDir=root.resolve(p.getProperty("launcher.privateDirectory",data.resolve("launcher-private").toString())).normalize();
            String store=p.getProperty("launcher.tlsKeyStore","").strip(),password=p.getProperty("launcher.tlsPasswordFile","").strip();if(store.isEmpty()!=password.isEmpty())throw new IllegalArgumentException("launcher_tls_requires_keystore_and_password_file");
            int port=Integer.parseInt(p.getProperty("launcher.tlsPort","29650"));if(port<0||port>65535)throw new IllegalArgumentException("invalid_launcher_tls_port");
            return new Settings(enabled.equals("true"),release,privateDir,store.isEmpty()?null:new InetSocketAddress(p.getProperty("launcher.tlsHost","127.0.0.1"),port),store.isEmpty()?null:root.resolve(store),password.isEmpty()?null:root.resolve(password));
        }
    }
    private final LocalAccounts accounts;
    private final Path release,privateDirectory;
    private final LauncherAccess access;
    private final ThreadPoolExecutor requests=Workers.pool("launcher-http",2,32);
    private final HttpsServer tls;
    private volatile boolean closed,started;
    private final java.util.concurrent.atomic.AtomicLong auditFailures=new java.util.concurrent.atomic.AtomicLong();
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final class RequestError extends Exception{final int status;RequestError(int status,String message){super(message);this.status=status;}}
    public LauncherBackend(LocalAccounts accounts,Settings settings)throws Exception{
        this(accounts,settings,true);
    }
    public LauncherBackend(LocalAccounts accounts,Settings settings,boolean autoStart)throws Exception{
        this.accounts=Objects.requireNonNull(accounts);release=settings.release.toAbsolutePath().normalize();privateDirectory=settings.privateDirectory.toAbsolutePath().normalize();access=new LauncherAccess(privateDirectory);HttpsServer server=null;
        try{
            if(settings.keyStore!=null){SSLContext context=tlsContext(settings.keyStore,settings.passwordFile);server=HttpsServer.create(settings.tlsAddress,32);server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(requests);server.createContext("/",this::serve);}
            tls=server;accounts.attachLauncher(this);if(autoStart)start();
        }catch(Throwable error){accounts.detachLauncher(this);if(server!=null)server.stop(0);requests.shutdownNow();try{access.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    public synchronized void start(){if(closed||started)throw new IllegalStateException("launcher_already_started_or_closed");if(tls!=null)tls.start();started=true;}
    private static SSLContext tlsContext(Path store,Path passwordFile)throws Exception{
        if(Files.size(passwordFile)>4096)throw new IllegalArgumentException("launcher_password_file_limit");char[] password=Files.readString(passwordFile,StandardCharsets.UTF_8).strip().toCharArray();
        try{KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password);}KeyManagerFactory manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,password);SSLContext context=SSLContext.getInstance("TLS");context.init(manager.getKeyManagers(),null,null);return context;}finally{Arrays.fill(password,'\0');}
    }
    public LauncherAccess access(){return access;}
    public int tlsPort(){return tls==null?-1:tls.getAddress().getPort();}
    /** Native-menu request is handed off before reading a potentially slow request body. */
    public void dispatch(HttpExchange exchange){try{requests.execute(()->serve(exchange));}catch(RejectedExecutionException full){send(exchange,503,Map.of("error","launcher_overloaded"));}}
    public static boolean transportAllowed(HttpExchange exchange){return exchange instanceof HttpsExchange||exchange.getRemoteAddress().getAddress().isLoopbackAddress();}
    private static <T>T await(CompletableFuture<T> future)throws Exception{try{return future.get();}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception e)throw e;throw error;}}
    private static JsonObject readJson(Path path,int limit)throws IOException{try(var stream=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("launcher_document_limit");return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}}
    private static String string(JsonObject object,String key)throws RequestError{JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new RequestError(503,"release_unavailable");return value.getAsString();}
    private void serve(HttpExchange exchange){
        int status=500;String identity="-",path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod(),header=exchange.getRequestHeaders().getFirst("Authorization"),remote=exchange.getRemoteAddress().getAddress().getHostAddress();boolean responseStarted=false;
        try{
            if(!path.startsWith("/launcher/"))throw new RequestError(404,"artifact_not_found");
            if(!transportAllowed(exchange))throw new RequestError(403,"https_required");
            LauncherAccess.Identity owner=await(access.authenticate(header));if(owner==null)throw new RequestError(401,"invalid_or_revoked_token");identity=owner.id();
            String name=path.substring("/launcher/".length());if(name.equals("check")&&method.equals("GET")){status=200;send(exchange,status,Map.of("ok",true,"login",owner.login()));return;}
            JsonObject manifest=readJson(release.resolve("manifest.json"),1024*1024);
            if(!manifest.has("format")||!manifest.get("format").isJsonPrimitive()||!manifest.get("format").getAsJsonPrimitive().isNumber()||manifest.get("format").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(2))!=0)throw new RequestError(503,"encrypted_release_not_published");
            String releaseId=string(manifest,"release");
            if(name.equals("launch")&&method.equals("POST")){
                String length=exchange.getRequestHeaders().getFirst("Content-Length");int count;try{count=Integer.parseInt(Objects.requireNonNullElse(length,"0"));}catch(NumberFormatException bad){throw new RequestError(400,"invalid_launch_request");}if(count<1||count>4096)throw new RequestError(400,"invalid_launch_request");
                byte[] body=exchange.getRequestBody().readNBytes(count+1);if(body.length!=count)throw new RequestError(400,"invalid_launch_request");JsonObject request;try{request=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException bad){throw new RequestError(400,"invalid_request");}
                if(!request.has("release")||!request.get("release").equals(new JsonPrimitive(releaseId)))throw new RequestError(409,"release_changed");
                JsonObject keys=readJson(privateDirectory.resolve("release-keys.json"),256*1024);String key=string(keys,releaseId);try{if(Base64.getDecoder().decode(key).length!=32)throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw new RequestError(503,"release_unavailable");}
                String session=await(accounts.ensureLauncherProfile(owner.login()));status=200;send(exchange,status,Map.of("key_b64",key,"login",owner.login(),"session",session,"release",releaseId));return;
            }
            if(!method.equals("GET")||!name.matches("[a-zA-Z0-9_.-]+")||name.equals(".")||name.equals(".."))throw new RequestError(404,"artifact_not_found");
            Set<String> allowed=new HashSet<>(Set.of("manifest.json","manifest.sig"));JsonElement artifacts=manifest.get("artifacts");if(artifacts==null||!artifacts.isJsonObject())throw new RequestError(503,"release_unavailable");for(var row:artifacts.getAsJsonObject().entrySet()){if(!row.getValue().isJsonObject())throw new RequestError(503,"release_unavailable");allowed.add(string(row.getValue().getAsJsonObject(),"file"));}if(!allowed.contains(name))throw new RequestError(404,"artifact_not_found");
            Path target=release.resolve(name);if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target))throw new RequestError(503,"release_unavailable");
            // Opening with NOFOLLOW_LINKS closes the check/open symlink race. No arbitrary path is served.
            try(FileChannel file=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                long size=file.size();exchange.getResponseHeaders().set("Content-Type",name.endsWith(".json")?"application/json":"application/octet-stream");exchange.getResponseHeaders().set("Cache-Control","no-store");status=200;exchange.sendResponseHeaders(status,size==0?-1:size);responseStarted=true;
                try(var output=exchange.getResponseBody()){byte[] buffer=new byte[256*1024];var bytes=java.nio.ByteBuffer.wrap(buffer);long remaining=size;while(remaining>0){bytes.clear();bytes.limit((int)Math.min(buffer.length,remaining));int n=file.read(bytes);if(n<0)throw new EOFException("launcher_artifact_truncated");if(n==0)continue;output.write(buffer,0,n);remaining-=n;}}
            }
        }catch(RequestError error){status=error.status;if(!responseStarted)send(exchange,status,Map.of("error",error.getMessage()));}
        catch(NoSuchFileException error){status=503;if(!responseStarted)send(exchange,status,Map.of("error","release_unavailable"));}
        catch(JsonParseException|IllegalStateException|ClassCastException error){status=access.healthy()?400:503;if(!responseStarted)send(exchange,status,Map.of("error",access.healthy()?"invalid_request":"launcher_storage_failed"));}
        catch(RejectedExecutionException error){status=503;if(!responseStarted)send(exchange,status,Map.of("error","launcher_overloaded"));}
        catch(Exception error){status=503;if(!responseStarted)send(exchange,status,Map.of("error","release_unavailable"));}
        finally{exchange.close();try{await(access.audit(method,path,status,remote,identity,header));}catch(Exception error){auditFailures.incrementAndGet();}}
    }
    static void send(HttpExchange exchange,int status,Object data){try{byte[] payload=JSON.toJson(data).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,payload.length);exchange.getResponseBody().write(payload);}catch(IOException ignored){}finally{exchange.close();}}
    public boolean healthy(){return started&&!closed&&access.healthy()&&auditFailures.get()==0;}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"access",access.metrics(),"queued",requests.getQueue().size(),"active",requests.getActiveCount(),"workers",2,"queueLimit",32,"tls",tls!=null,"auditFailures",auditFailures.get(),"externalNotificationsSent",0);}
    @Override public void close()throws Exception{synchronized(this){if(closed)return;closed=true;}accounts.detachLauncher(this);if(tls!=null)tls.stop(0);try{Workers.stop(requests);}finally{access.close();}}
}
