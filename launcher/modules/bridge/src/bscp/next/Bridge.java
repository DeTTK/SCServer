package bscp.next;

import java.lang.instrument.*;
import java.lang.management.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import com.sun.net.httpserver.*;
import jdk.jfr.*;

/** Explicit opt-in agent. No hook is installed in the normal launcher. */
public final class Bridge {
    static Properties config = new Properties(); static Path output; static String token, kind; static final Object lock=new Object();
    static final ArrayDeque<String> history=new ArrayDeque<>(); static long dropped; static Recording recording; static ScheduledExecutorService monitor;
    static FileChannel leaseChannel; static FileLock lease; static HttpServer http;
    public static Path bundleRoot(Path original){if(!kind.equals("server"))return original;String root=config.getProperty("serverRoot","");return root.isBlank()?Path.of(config.getProperty("serverJar")).toAbsolutePath().getParent():Path.of(root).toAbsolutePath();}
    public static Path bundlePath(Path path){return path.isAbsolute()?path:bundleRoot(path).resolve(path).normalize();}
    public static String[] clientArguments(String[] original){String session=config.getProperty("nativeSession","");String deferred=config.getProperty("deferredSessionFile","");if(!deferred.isBlank()&&!Boolean.parseBoolean(config.getProperty("deferredSessionAlias","false"))){long until=System.nanoTime()+600_000_000_000L;boolean ready=false;while(System.nanoTime()<until){try{String json=Files.readString(Path.of(deferred)).strip();if(json.startsWith("\"")&&json.endsWith("\"")){session=new String(Base64.getDecoder().decode(json.substring(1,json.length()-1)),java.nio.charset.StandardCharsets.UTF_8);ready=true;break;}}catch(java.io.IOException absent){}try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("server startup interrupted");}}if(!ready)throw new IllegalStateException("server did not provide a session in 10 minutes");}if(Boolean.parseBoolean(config.getProperty("deferredSessionAlias","false")))event("client-start","parallel-alias");if(session.isBlank())throw new IllegalStateException("native session missing");String[] args=original.clone();for(int i=0;i+1<args.length;i++)if(args[i].equals("--session")){args[i+1]=session;return args;}throw new IllegalStateException("native session argument missing");}
    static boolean connectedOnce;
    public static int relayPort(int port){int remote=Integer.parseInt(config.getProperty("relayRemoteBase","0"));if(remote==0)return port;if(port==remote+1)return Integer.parseInt(config.getProperty("relayGamePort"));if(port==remote+2)return Integer.parseInt(config.getProperty("relayWorldPort"));return port;}
    public static String passiveControlState(Object game,long frame){return "{\"observerDisabled\":true}";}
    static java.lang.reflect.Method exact(Object owner,String name,Class<?> result,Class<?>... parameters)throws Exception{return Arrays.stream(owner.getClass().getMethods()).filter(m->m.getName().equals(name)&&m.getReturnType().equals(result)&&Arrays.equals(m.getParameterTypes(),parameters)).findFirst().orElseThrow();}
    public static void frontend(Object game){
        if(connectedOnce||!Boolean.parseBoolean(config.getProperty("autoJoin","false")))return;
        try{var field=game.getClass().getDeclaredField("implements");field.setAccessible(true);Object screen=field.get(game);if(screen==null||!screen.getClass().getName().equals("1790054239"))return;var c=screen.getClass().getDeclaredField("short");c.setAccessible(true);Object controller=c.get(screen);if(controller==null)return;var available=(List<?>)exact(controller,"double",List.class).invoke(controller);if(available.isEmpty())return;
            Object character=available.get(0);var select=Arrays.stream(controller.getClass().getMethods()).filter(m->m.getName().equals("transient")&&m.getReturnType()==void.class&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(character)).findFirst().orElseThrow();select.invoke(controller,character);connectedOnce=true;exact(controller,"transient",void.class).invoke(controller);event("auto-connect","requested");
        }catch(Throwable error){connectedOnce=true;event("auto-connect-failed",error.toString());}
    }
    static volatile long lastFrameNanos, maxFrameNanos, frames, slowFrames, lastWorldTick, maxWorldTickGap, worldTicks, slowWorldTicks;
    public static void tick(){long now=System.nanoTime(),old=lastWorldTick;lastWorldTick=now;worldTicks++;if(old!=0){long gap=now-old;maxWorldTickGap=Math.max(maxWorldTickGap,gap);if(gap>100_000_000L){slowWorldTicks++;WorldPause event=new WorldPause();event.milliseconds=gap/1e6;event.commit();}}}
    @Name("bscp.WorldPause") @Label("BSCP delayed world tick") static class WorldPause extends Event {@Label("Duration ms") double milliseconds;}
    static boolean delayed(long scheduler,long world,long frame,long priorFrames,long priorTicks){return scheduler>1_500_000_000L||world>1_000_000_000L||frame>2_000_000_000L||slowFrames>priorFrames||slowWorldTicks>priorTicks;}
    public static void frame(){long now=System.nanoTime(),previous=lastFrameNanos;lastFrameNanos=now;frames++;if(previous!=0){long gap=now-previous;maxFrameNanos=Math.max(maxFrameNanos,gap);if(gap>100_000_000L){slowFrames++;FramePause event=new FramePause();event.milliseconds=gap/1e6;event.commit();}}}
    @Name("bscp.FramePause") @Label("BSCP long frame") static class FramePause extends Event {@Label("Duration ms") double milliseconds;}
    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        try {
            if(argument==null)throw new IllegalArgumentException("agent configuration is required");
            try(var input=Files.newInputStream(Path.of(argument))){config.load(input);}
            output=Path.of(config.getProperty("output")).toAbsolutePath();Files.createDirectories(output);token=config.getProperty("token");kind=config.getProperty("kind","client");
            System.setOut(new PrintStream(new ConsoleLog(System.out,"stdout"),true,java.nio.charset.StandardCharsets.UTF_8));System.setErr(new PrintStream(new ConsoleLog(System.err,"stderr"),true,java.nio.charset.StandardCharsets.UTF_8));
            if(token==null||token.length()<32)throw new IllegalArgumentException("agent token missing");
            if(kind.equals("server")) {
                var worldLease=Path.of(config.getProperty("worldLease"));Files.createDirectories(worldLease.getParent());leaseChannel=FileChannel.open(worldLease,StandardOpenOption.CREATE,StandardOpenOption.WRITE);lease=leaseChannel.tryLock();if(lease==null)throw new IllegalStateException("world already owned by a Java process");
                ServerPatches.verify(config.getProperty("serverJar"));
                instrumentation.addTransformer(new ServerPatches());
            }
            if(kind.equals("client")){
                Path clientLease=Path.of(config.getProperty("clientLease"));Files.createDirectories(clientLease.getParent());leaseChannel=FileChannel.open(clientLease,StandardOpenOption.CREATE,StandardOpenOption.WRITE);lease=leaseChannel.tryLock();if(lease==null)throw new IllegalStateException("client profile already owned by another Java process");
                instrumentation.addTransformer(new ClientHooks());
            }
            var previous=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{StringWriter s=new StringWriter();error.printStackTrace(new PrintWriter(s));event("exception",thread.getName()+" "+s);if(previous!=null)previous.uncaughtException(thread,error);else error.printStackTrace();});
            http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),4);
            var workers=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"bscp-diagnostics-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());http.setExecutor(workers);
            http.createContext("/",exchange->{try{
                if(!MessageDigest.isEqual(("Bearer "+token).getBytes(java.nio.charset.StandardCharsets.UTF_8),Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"").getBytes(java.nio.charset.StandardCharsets.UTF_8))){respond(exchange,401,"{}");return;}
                String path=exchange.getRequestURI().getPath();if(exchange.getRequestMethod().equals("GET")&&path.equals("/metrics"))respond(exchange,200,metrics());
                else if(exchange.getRequestMethod().equals("POST")&&path.equals("/incident")){incident("manual");respond(exchange,200,"{\"saved\":true}");}
                else if(exchange.getRequestMethod().equals("GET")&&path.equals("/threads")){respond(exchange,200,quote(threads()));}
                else respond(exchange,404,"{}");
            }catch(Exception e){event("diagnostic-error",e.toString());exchange.close();}});Thread starter=new Thread(http::start,"bscp-diagnostics-start");starter.setDaemon(true);starter.start();starter.join();
            Files.writeString(output.resolve("agent-status.json"),"{\"pid\":"+ProcessHandle.current().pid()+",\"port\":"+http.getAddress().getPort()+",\"kind\":"+quote(kind)+"}");
            if(Boolean.parseBoolean(config.getProperty("diagnostics","true"))){
                recording=new Recording(Configuration.getConfiguration("default"));recording.setName("BSCP rolling diagnostics");recording.setMaxAge(Duration.ofMinutes(3));recording.setMaxSize(12L*1024*1024);recording.setToDisk(true);recording.start();
                monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bscp-diagnostic-watchdog");t.setDaemon(true);return t;});
                final long[] previousTick={System.nanoTime()}, lastIncident={0}, previousSlow={slowFrames,slowWorldTicks};
                monitor.scheduleAtFixedRate(()->{try{long now=System.nanoTime(),gap=now-previousTick[0];previousTick[0]=now;event("metrics",metrics());long worldGap=lastWorldTick==0?0:now-lastWorldTick;long frameGap=lastFrameNanos==0?0:now-lastFrameNanos;boolean delayed=delayed(gap,worldGap,frameGap,previousSlow[0],previousSlow[1]);previousSlow[0]=slowFrames;previousSlow[1]=slowWorldTicks;if(delayed&&now-lastIncident[0]>30_000_000_000L){lastIncident[0]=now;incident("pause schedulerMs="+(gap/1_000_000)+" worldMs="+(worldGap/1_000_000)+" frameMs="+(frameGap/1_000_000));}}catch(Exception e){event("watchdog-error",e.toString());}},1,1,TimeUnit.SECONDS);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{event("exit","normal-or-jvm-shutdown");if(recording!=null)recording.close();if(http!=null)http.stop(0);if(monitor!=null)monitor.shutdownNow();if(lease!=null)lease.release();if(leaseChannel!=null)leaseChannel.close();}catch(Exception ignored){}},"bscp-bridge-shutdown"));
            event("bridge-ready",kind);
        } catch(Throwable error){error.printStackTrace();Runtime.getRuntime().halt(78);}
    }
    static final class ConsoleLog extends OutputStream {
        final PrintStream original;final String type;final ByteArrayOutputStream line=new ByteArrayOutputStream();ConsoleLog(PrintStream p,String t){original=p;type=t;}
        void capture(int value){if(value=='\n'){event(type,line.toString(java.nio.charset.StandardCharsets.UTF_8));line.reset();}else if(line.size()<32768)line.write(value);}
        @Override public synchronized void write(int value){original.write(value);capture(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){original.write(bytes,offset,length);for(int i=offset;i<offset+length;i++)capture(bytes[i]&255);}
        @Override public void flush(){original.flush();}
    }
    static void respond(HttpExchange e,int status,String value)throws IOException{byte[] b=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,b.length);try(var out=e.getResponseBody()){out.write(b);}}
    public static String quote(String value){StringBuilder b=new StringBuilder("\"");for(char c:value.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
    static String metrics(){var runtime=Runtime.getRuntime();long gcMs=0,gcCount=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){gcMs+=Math.max(0,gc.getCollectionTime());gcCount+=Math.max(0,gc.getCollectionCount());}return "{\"pid\":"+ProcessHandle.current().pid()+",\"uptimeMs\":"+ManagementFactory.getRuntimeMXBean().getUptime()+",\"heapUsed\":"+(runtime.totalMemory()-runtime.freeMemory())+",\"heapMax\":"+runtime.maxMemory()+",\"threads\":"+ManagementFactory.getThreadMXBean().getThreadCount()+",\"gcMs\":"+gcMs+",\"gcCount\":"+gcCount+",\"frames\":"+frames+",\"slowFrames\":"+slowFrames+",\"maxFrameMs\":"+(maxFrameNanos/1e6)+",\"worldTicks\":"+worldTicks+",\"slowWorldTicks\":"+slowWorldTicks+",\"cpuTimeNanos\":"+ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos()+",\"droppedDiagnostics\":"+dropped+",\"maxWorldTickGapMs\":"+(maxWorldTickGap/1e6)+",\"worldTickAgeMs\":"+(lastWorldTick==0?0:(System.nanoTime()-lastWorldTick)/1e6)+"}";}
    public static void event(String type,String message){synchronized(lock){try{
        message=message.replaceAll("(?i)(Bearer\\s+)[^\\s\"<>]+","$1[redacted]").replaceAll("(?i)((?:token|password|secret|nativeSession|privateKey|session)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'<>}]+","$1[redacted]");
        String line="{\"time\":"+quote(Instant.now().toString())+",\"nanoTime\":"+System.nanoTime()+",\"type\":"+quote(type)+",\"message\":"+quote(message.length()>32768?message.substring(0,32768):message)+"}";
        history.addLast(line);while(history.size()>180)history.removeFirst();Path file=output.resolve("bridge.jsonl");if(Files.exists(file)&&Files.size(file)>4L*1024*1024){for(int i=2;i>=0;i--){Path from=i==0?file:output.resolve("bridge.jsonl."+i),to=output.resolve("bridge.jsonl."+(i+1));if(Files.exists(from))Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
        Files.writeString(file,line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }catch(Exception ignored){dropped++;}}}
    static String threads(){StringBuilder b=new StringBuilder();for(var t:ManagementFactory.getThreadMXBean().dumpAllThreads(true,true))b.append(t).append('\n');return b.toString();}
    static synchronized void incident(String reason)throws Exception {
        Path incidents=output.resolve("incidents");Files.createDirectories(incidents);var files=new ArrayList<Path>();try(var stream=Files.list(incidents)){stream.forEach(files::add);}files.sort(Comparator.comparing(Path::toString));while(files.size()>9){Files.deleteIfExists(files.remove(0));}
        String id=Long.toString(System.currentTimeMillis());synchronized(lock){Files.writeString(incidents.resolve(id+".jsonl"),String.join("\n",history)+"\n");}Files.writeString(incidents.resolve(id+".threads.log"),threads());
        if(recording!=null)recording.dump(incidents.resolve(id+".jfr"));event("incident",reason);
    }
}
