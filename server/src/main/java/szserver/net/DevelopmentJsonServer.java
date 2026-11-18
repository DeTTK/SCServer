package szserver.net;

import com.google.gson.*;
import com.google.gson.stream.*;
import szserver.util.Workers;
import szserver.util.JsonDocument;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded newline/UTF-8 frontend for the old dev_server.py protocol. Blocking sockets and
 * durable waits belong exclusively to these workers. Tokens are never echoed or logged.
 */
public final class DevelopmentJsonServer implements AutoCloseable {
    public static final int MAX_LINE=65536,MAX_SESSIONS=16,MAX_RESPONSE=64*1024*1024;
    private final ServerSocket listener=new ServerSocket();
    private final ThreadPoolExecutor workers=Workers.pool("development-json",MAX_SESSIONS,1);
    private final Map<Socket,Long> sockets=new ConcurrentHashMap<>();
    private final Semaphore sessions=new Semaphore(MAX_SESSIONS),responses=new Semaphore(1);
    private final Thread acceptor,watchdog;
    private final DevelopmentService service;
    private final AtomicLong accepted=new AtomicLong(),rejected=new AtomicLong(),requests=new AtomicLong(),errors=new AtomicLong();
    private final AtomicLong responseBytes=new AtomicLong(),completedResponses=new AtomicLong(),busyResponses=new AtomicLong();
    private volatile boolean running,closed;
    public DevelopmentJsonServer(InetSocketAddress address,DevelopmentService service)throws IOException{
        this(address,service,true);
    }
    public DevelopmentJsonServer(InetSocketAddress address,DevelopmentService service,boolean autoStart)throws IOException{
        if(address.getAddress()==null||!address.getAddress().isLoopbackAddress())throw new IllegalArgumentException("development_json_requires_loopback");this.service=Objects.requireNonNull(service);
        try{listener.setReuseAddress(true);listener.bind(address,MAX_SESSIONS);}catch(IOException|RuntimeException e){workers.shutdownNow();listener.close();throw e;}
        acceptor=new Thread(this::accept,"development-json-accept");acceptor.setDaemon(true);watchdog=new Thread(this::watch,"development-json-write-watchdog");watchdog.setDaemon(true);if(autoStart)start();
    }
    public synchronized void start(){if(closed||acceptor.getState()!=Thread.State.NEW)throw new IllegalStateException("development_listener_already_started_or_closed");running=true;try{acceptor.start();watchdog.start();}catch(RuntimeException|Error failure){running=false;try{listener.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}workers.shutdownNow();watchdog.interrupt();throw failure;}}
    public int port(){return listener.getLocalPort();}
    public boolean healthy(){return running&&acceptor.isAlive()&&watchdog.isAlive();}
    public Map<String,Object> metrics(){Map<String,Object> result=new LinkedHashMap<>();result.put("healthy",healthy());result.put("sessions",sockets.size());result.put("accepted",accepted.get());result.put("rejected",rejected.get());result.put("requests",requests.get());result.put("errors",errors.get());result.put("sessionLimit",MAX_SESSIONS);result.put("lineLimit",MAX_LINE);result.put("responseLimit",MAX_RESPONSE);result.put("snapshotCapacity",1);result.put("activeSnapshots",1-responses.availablePermits());result.put("busyResponses",busyResponses.get());result.put("responseBytes",responseBytes.get());result.put("completedResponses",completedResponses.get());result.put("streamedSnapshots",true);return Collections.unmodifiableMap(result);}
    private void accept(){while(running)try{Socket socket=listener.accept();if(!sessions.tryAcquire()){rejected.incrementAndGet();socket.close();continue;}sockets.put(socket,0L);try{workers.execute(()->handle(socket));accepted.incrementAndGet();}catch(RejectedExecutionException e){sockets.remove(socket);sessions.release();socket.close();rejected.incrementAndGet();}}catch(IOException e){if(running){rejected.incrementAndGet();running=false;}}}
    private void watch(){while(running){long now=System.nanoTime();sockets.forEach((socket,deadline)->{if(deadline!=0&&now>=deadline)try{socket.close();}catch(IOException ignored){}});try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}}
    private static byte[] line(InputStream input)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int b;while((b=input.read())>=0){if(b=='\n')return out.toByteArray();if(out.size()>=MAX_LINE)throw new IOException("development_line_limit");out.write(b);}return out.size()==0?null:out.toByteArray();}
    private static JsonElement read(JsonReader reader,int depth,int[] remaining)throws IOException{if(depth>16||--remaining[0]<0)throw new IllegalArgumentException("development_json_structure_limit");switch(reader.peek()){
        case BEGIN_OBJECT:{reader.beginObject();JsonObject o=new JsonObject();while(reader.hasNext()){String key=reader.nextName();o.add(key,read(reader,depth+1,remaining));}reader.endObject();return o;}
        case BEGIN_ARRAY:{reader.beginArray();JsonArray a=new JsonArray();while(reader.hasNext())a.add(read(reader,depth+1,remaining));reader.endArray();return a;}
        case STRING:return new JsonPrimitive(reader.nextString());
        case NUMBER:{String number=reader.nextString();if(number.length()>512||!Double.isFinite(Double.parseDouble(number)))throw new IllegalArgumentException("development_json_number_limit");return new JsonPrimitive(new com.google.gson.internal.LazilyParsedNumber(number));}
        case BOOLEAN:return new JsonPrimitive(reader.nextBoolean());
        case NULL:reader.nextNull();return JsonNull.INSTANCE;
        default:throw new IllegalArgumentException("invalid JSON");
    }}
    public static JsonElement parse(byte[] raw)throws IOException{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();JsonReader reader=new JsonReader(new StringReader(text));reader.setStrictness(Strictness.STRICT);JsonElement value=read(reader,0,new int[]{4096});if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("invalid JSON");return value;}
    public static JsonObject response(JsonElement id,JsonObject result,String error){JsonObject out=new JsonObject();out.add("id",id==null?JsonNull.INSTANCE:id);out.addProperty("ok",error==null);if(error==null)out.add("result",result);else out.addProperty("error",error);return out;}
    private void send(Socket socket,JsonDocument response)throws IOException{sockets.put(socket,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));try{
        // Stream the bounded snapshot: do not allocate an additional giant UTF-16 JSON string
        // followed by a second full UTF-8 byte array while native gameplay shares the heap.
        OutputStream out=new FilterOutputStream(socket.getOutputStream()){
            private long count;
            private void reserve(int n)throws IOException{if(count+n>MAX_RESPONSE)throw new IOException("development_response_limit");count+=n;}
            @Override public void write(int b)throws IOException{reserve(1);out.write(b);responseBytes.incrementAndGet();}
            @Override public void write(byte[] b,int at,int n)throws IOException{reserve(n);out.write(b,at,n);responseBytes.addAndGet(n);}
        };
        Writer writer=new OutputStreamWriter(new BufferedOutputStream(out,32768),StandardCharsets.UTF_8);JsonWriter json=new JsonWriter(writer);json.setSerializeNulls(true);response.write(json);json.flush();writer.write('\n');writer.flush();completedResponses.incrementAndGet();
    }finally{if(sockets.containsKey(socket))sockets.put(socket,0L);}}
    private void handle(Socket socket){DevelopmentService.Session session=new DevelopmentService.Session();try(socket){socket.setSoTimeout(30000);socket.setTcpNoDelay(true);JsonObject connected=new JsonObject();connected.addProperty("event","connected");connected.addProperty("session_id",session.id);send(socket,JsonDocument.of(response(null,connected,null)));InputStream input=new BufferedInputStream(socket.getInputStream(),8192);byte[] raw;
        while(running&&(raw=line(input))!=null){requests.incrementAndGet();JsonElement id=null;JsonDocument result=null;String error=null;boolean permit=false;
            try{
            try{JsonElement request=parse(raw);if(!request.isJsonObject())throw new IllegalArgumentException("request must be an object");id=request.getAsJsonObject().get("id");JsonObject object=request.getAsJsonObject();JsonElement op=object.get("op");if(op!=null&&op.isJsonPrimitive()&&op.getAsJsonPrimitive().isString()&&Set.of("inspect","enter_world").contains(op.getAsString())){if(!responses.tryAcquire(5,TimeUnit.SECONDS)){busyResponses.incrementAndGet();throw new IllegalStateException("development_response_busy");}permit=true;}result=service.reply(session,object);}
            catch(CharacterCodingException e){error="invalid UTF-8";}
            catch(IllegalArgumentException|IllegalStateException e){error=e.getMessage();}
            catch(IOException e){error="invalid JSON";}
            catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(RuntimeException e){error="development_request_failed";}
            if(error!=null)errors.incrementAndGet();Map<String,Object> fields=new LinkedHashMap<>();fields.put("id",id);fields.put("ok",error==null);fields.put(error==null?"result":"error",error==null?result:error);send(socket,new JsonDocument(fields));
            }finally{if(permit)responses.release();}
        }
    }catch(IOException|RuntimeException e){errors.incrementAndGet();}finally{sockets.remove(socket);sessions.release();}}
    @Override public void close()throws Exception{synchronized(this){if(closed)return;closed=true;running=false;}listener.close();for(Socket socket:sockets.keySet())try{socket.close();}catch(IOException ignored){}watchdog.interrupt();try{acceptor.join(2000);watchdog.join(2000);}finally{Workers.stop(workers);}if(acceptor.isAlive()||watchdog.isAlive())throw new IllegalStateException("development_listener_shutdown_timeout");}
}
