package szserver.persistence;

import java.io.*;
import java.lang.ref.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Leased, disposable caches only. Canonical journals/checkpoints never enter this namespace. */
public final class DerivedCaches {
    public enum Kind {
        SCENE_ROWS("scene-row-cache-"),SCENE_CHANGES("scene-change-cache-"),WORLD_EVENTS("world-event-cache-");
        final String prefix;Kind(String prefix){this.prefix=prefix;}
        boolean body(String name){return this==SCENE_ROWS?name.matches("rows-[0-9]{1,20}\\.bin"):Set.of("rows.bin","index.bin").contains(name);}
    }
    private static final String GUARD=".derived-cache.guard",OWNER="owner.lock";
    private static final int ROOT_MAGIC=0x53444352,LEASE_MAGIC=0x5344434c,VERSION=1,ROOT_BYTES=24,LEASE_BYTES=44;
    private static final int SCAN_ENTRIES=4096,SCAN_CACHES=128,MAX_FILES=4096;
    private static final long SCAN_NANOS=250_000_000L;
    private static final Cleaner CLEANER=Cleaner.create();
    private static final Map<Path,WeakReference<Context>> CONTEXTS=new HashMap<>();
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("derived_cache_io_on_owner");}
    public static Context context(Path base)throws IOException{
        worker();Files.createDirectories(base);Path root=base.toRealPath();
        synchronized(CONTEXTS){CONTEXTS.entrySet().removeIf(e->e.getValue().get()==null);var ref=CONTEXTS.get(root);Context current=ref==null?null:ref.get();if(current==null){current=new Context(root);CONTEXTS.put(root,new WeakReference<>(current));}return current;}
    }
    public static Lease create(Path base,Kind kind)throws IOException{Context context=context(base);context.sweep();return context.create(kind);}
    private static ByteBuffer read(FileChannel channel,int bytes)throws IOException{ByteBuffer value=ByteBuffer.allocate(bytes);while(value.hasRemaining()){int n=channel.read(value,value.position());if(n<0)throw new EOFException("derived_cache_marker_truncated");if(n==0)Thread.onSpinWait();}return value.flip();}
    private static void write(FileChannel channel,ByteBuffer value)throws IOException{value.flip();while(value.hasRemaining()){int n=channel.write(value,value.position());if(n==0)Thread.onSpinWait();}channel.force(true);}
    private static UUID uuid(ByteBuffer in){return new UUID(in.getLong(),in.getLong());}
    private static void uuid(ByteBuffer out,UUID id){out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());}
    private static FileChannel marker(Path path,boolean create)throws IOException{
        return create?FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,StandardOpenOption.CREATE,LinkOption.NOFOLLOW_LINKS):FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
    private static FileLock claim(FileChannel channel)throws IOException{try{return channel.tryLock();}catch(OverlappingFileLockException active){return null;}}
    private record Guard(FileChannel channel,FileLock lock,UUID identity) implements AutoCloseable {public void close()throws IOException{try{lock.release();}finally{channel.close();}}}
    /** Cleaner action owns no Context/Lease; an abandoned enumeration cannot hold a directory forever. */
    private static final class CursorCleanup implements Runnable {
        final AtomicReference<DirectoryStream<Path>> stream=new AtomicReference<>();
        public void run(){DirectoryStream<Path> current=stream.getAndSet(null);if(current!=null)try{current.close();}catch(IOException ignored){}}
    }
    public static final class Context {
        private final Path base;private final UUID identity;private final Set<Path> active=new HashSet<>();
        private final CursorCleanup cursorCleanup=new CursorCleanup();private final Cleaner.Cleanable cleanable;
        private Iterator<Path> cursor;private volatile int activeLeases;
        private long recovered,bytes,locked,legacy,invalid,errors,rounds;private volatile Map<String,Object> view=Map.of("status","idle");
        private Context(Path base)throws IOException{this.base=base;try(Guard guard=guard(true)){identity=guard.identity();}cleanable=CLEANER.register(this,cursorCleanup);publish("idle",0,0,0,0,0);}
        private Guard guard(boolean wait)throws IOException{
            FileChannel channel=marker(base.resolve(GUARD),true);FileLock lock=null;boolean accepted=false;
            try{long deadline=System.nanoTime()+5_000_000_000L;while((lock=claim(channel))==null){if(!wait)return null;if(System.nanoTime()>deadline)throw new IOException("derived_cache_guard_busy");try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("derived_cache_guard_interrupted",interrupted);}}
                UUID id;if(channel.size()==0){id=UUID.randomUUID();ByteBuffer value=ByteBuffer.allocate(ROOT_BYTES).putInt(ROOT_MAGIC).putInt(VERSION);uuid(value,id);write(channel,value);}else{if(channel.size()!=ROOT_BYTES)throw new IOException("derived_cache_guard_identity");ByteBuffer value=read(channel,ROOT_BYTES);if(value.getInt()!=ROOT_MAGIC||value.getInt()!=VERSION)throw new IOException("derived_cache_guard_identity");id=uuid(value);}
                accepted=true;return new Guard(channel,lock,id);
            }finally{if(!accepted){if(lock!=null)lock.release();channel.close();}}
        }
        private void same(Guard guard)throws IOException{if(!identity.equals(guard.identity()))throw new IOException("derived_cache_root_changed");}
        private synchronized Lease create(Kind kind)throws IOException{
            worker();try(Guard guard=guard(true)){same(guard);UUID id=UUID.randomUUID();Path path=base.resolve(kind.prefix+id);Files.createDirectory(path);FileChannel channel=null;FileLock lock=null;
                try{channel=FileChannel.open(path.resolve(OWNER),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);lock=channel.lock();ByteBuffer value=ByteBuffer.allocate(LEASE_BYTES).putInt(LEASE_MAGIC).putInt(VERSION).putInt(kind.ordinal());uuid(value,identity);uuid(value,id);write(channel,value);active.add(path);activeLeases=active.size();return new Lease(this,path,kind,id,channel,lock);}
                catch(IOException|RuntimeException|Error failure){if(lock!=null)try{lock.release();}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(channel!=null)try{channel.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}try{Files.deleteIfExists(path.resolve(OWNER));Files.deleteIfExists(path);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }
        }
        private static Kind kind(String name){for(Kind kind:Kind.values())if(name.startsWith(kind.prefix))return kind;return null;}
        private static UUID id(Path path,Kind kind){String text=path.getFileName().toString().substring(kind.prefix.length());try{UUID id=UUID.fromString(text);return id.toString().equals(text)?id:null;}catch(IllegalArgumentException ignored){return null;}}
        private boolean lease(FileChannel channel,Kind kind,UUID id)throws IOException{if(channel.size()!=LEASE_BYTES)return false;ByteBuffer value=read(channel,LEASE_BYTES);return value.getInt()==LEASE_MAGIC&&value.getInt()==VERSION&&value.getInt()==kind.ordinal()&&identity.equals(uuid(value))&&id.equals(uuid(value));}
        private record Plan(List<Path> bodies,long bytes){}
        /** Validate every direct leaf before deleting any. No recursive traversal or following links. */
        private Plan plan(Path directory,Kind kind)throws IOException{
            if(!directory.getParent().equals(base)||!directory.toRealPath().equals(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))return null;
            List<Path> bodies=new ArrayList<>();long bytes=0;int files=0;boolean owner=false;
            try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){if(++files>MAX_FILES)return null;BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isRegularFile()||!path.toRealPath().equals(path))return null;String name=path.getFileName().toString();if(name.equals(OWNER)){owner=true;continue;}if(!kind.body(name))return null;bodies.add(path);bytes=Math.addExact(bytes,attributes.size());}}
            return owner?new Plan(List.copyOf(bodies),bytes):null;
        }
        private static void bodies(Plan plan)throws IOException{for(Path path:plan.bodies())Files.deleteIfExists(path);}
        private void resetCursor(){cursorCleanup.run();cursor=null;}
        private void publish(String status,long duration,long reclaimed,long reclaimedBytes,int examined,int failures){
            Map<String,Object> result=new LinkedHashMap<>(Map.of("status",status,"activeLeases",activeLeases,"recoveredDirectories",recovered,"recoveredBytes",bytes,"skippedLocked",locked,"skippedLegacy",legacy,"skippedInvalid",invalid,"errors",errors,"rounds",rounds));
            result.put("roundRecovered",reclaimed);result.put("roundRecoveredBytes",reclaimedBytes);result.put("roundExamined",examined);result.put("roundErrors",failures);result.put("durationMs",duration/1e6);result.put("scanOpen",cursor!=null);result.put("cacheLimitPerRound",SCAN_CACHES);result.put("entryLimitPerRound",SCAN_ENTRIES);result.put("timeBudgetMs",SCAN_NANOS/1e6);view=Collections.unmodifiableMap(result);
        }
        public Map<String,Object> metrics(){var result=new LinkedHashMap<>(view);result.put("activeLeases",activeLeases);return Collections.unmodifiableMap(result);}
        public synchronized Map<String,Object> sweep(){
            worker();long started=System.nanoTime(),reclaimed=0,reclaimedBytes=0;int examined=0,failures=0,entries=0;rounds++;
            try(Guard guard=guard(false)){
                if(guard==null){publish("busy",System.nanoTime()-started,0,0,0,0);return metrics();}same(guard);
                if(cursor==null){DirectoryStream<Path> stream=Files.newDirectoryStream(base);cursorCleanup.stream.set(stream);cursor=stream.iterator();}
                while(entries<SCAN_ENTRIES&&examined<SCAN_CACHES&&System.nanoTime()-started<SCAN_NANOS&&cursor.hasNext()){
                    Path path=cursor.next();entries++;Kind kind=kind(path.getFileName().toString());if(kind==null)continue;examined++;UUID id=id(path,kind);if(id==null){legacy++;continue;}if(active.contains(path)){locked++;continue;}
                    if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)){invalid++;continue;}
                    FileChannel channel=null;FileLock claim=null;boolean accepted=false;Plan plan=null;
                    try{if(!Files.isRegularFile(path.resolve(OWNER),LinkOption.NOFOLLOW_LINKS)){invalid++;continue;}channel=marker(path.resolve(OWNER),false);claim=claim(channel);if(claim==null){locked++;continue;}if(!lease(channel,kind,id)||(plan=plan(path,kind))==null){invalid++;continue;}bodies(plan);accepted=true;}
                    catch(NoSuchFileException raced){/* A concurrently completed explicit close is already reclaimed. */}
                    catch(IOException|RuntimeException failed){errors++;failures++;}
                    finally{if(claim!=null)try{claim.release();}catch(IOException failed){errors++;failures++;}if(channel!=null)try{channel.close();}catch(IOException failed){errors++;failures++;}}
                    if(accepted)try{Files.deleteIfExists(path.resolve(OWNER));Files.deleteIfExists(path);recovered++;bytes=Math.addExact(bytes,plan.bytes());reclaimed++;reclaimedBytes+=plan.bytes();}catch(IOException failed){errors++;failures++;}
                }
                if(!cursor.hasNext())resetCursor();publish(failures>0?"partial":cursor==null?"complete":"partial",System.nanoTime()-started,reclaimed,reclaimedBytes,examined,failures);return metrics();
            }catch(IOException|RuntimeException failed){errors++;resetCursor();publish("failed",System.nanoTime()-started,reclaimed,reclaimedBytes,examined,failures+1);return metrics();}
        }
        private synchronized void release(LeaseCleanup lease){
            worker();boolean remove=false;Guard guard=null;try{
                guard=guard(false);
                if(guard!=null){same(guard);if(lease(lease.channel,lease.kind,lease.id)){Plan plan=plan(lease.path,lease.kind);if(plan!=null){bodies(plan);remove=true;}else invalid++;}else invalid++;}
            }catch(IOException|RuntimeException failed){errors++;}
            finally{try{lease.lock.release();}catch(IOException ignored){}try{lease.channel.close();}catch(IOException ignored){}
                if(remove)try{Files.deleteIfExists(lease.path.resolve(OWNER));Files.deleteIfExists(lease.path);}catch(IOException failed){errors++;}
                if(guard!=null)try{guard.close();}catch(IOException failed){errors++;}active.remove(lease.path);activeLeases=active.size();}
            if(active.isEmpty())resetCursor();publish("idle",0,0,0,0,0);
        }
    }
    private static final class LeaseCleanup implements Runnable {
        final Context context;final Path path;final Kind kind;final UUID id;final FileChannel channel;final FileLock lock;final AtomicBoolean closed=new AtomicBoolean();
        LeaseCleanup(Context context,Path path,Kind kind,UUID id,FileChannel channel,FileLock lock){this.context=context;this.path=path;this.kind=kind;this.id=id;this.channel=channel;this.lock=lock;}
        public void run(){if(closed.compareAndSet(false,true))context.release(this);}
    }
    public static final class Lease implements AutoCloseable {
        private final LeaseCleanup cleanup;private final Cleaner.Cleanable cleanable;
        private Lease(Context context,Path path,Kind kind,UUID id,FileChannel channel,FileLock lock){cleanup=new LeaseCleanup(context,path,kind,id,channel,lock);cleanable=CLEANER.register(this,cleanup);}
        public Path directory(){return cleanup.path;}
        public Map<String,Object> metrics(){return cleanup.context.metrics();}
        public void close(){worker();cleanable.clean();}
    }
    private DerivedCaches(){}
}
