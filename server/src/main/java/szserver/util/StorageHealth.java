package szserver.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Cached volume readiness. Filesystem probes run at startup, then on a dedicated worker. */
public final class StorageHealth implements AutoCloseable {
    public static final long DEFAULT_MINIMUM=256L*1024*1024;
    private static final long STALE_NANOS=TimeUnit.SECONDS.toNanos(20);
    private record Reading(long usable,long total,long nanos,long wall,String thread,String error){}
    private final Path data;
    private final long minimum;
    private final ScheduledExecutorService worker;
    private volatile Reading reading;
    private volatile boolean closed;

    public StorageHealth(Path data,long minimum){
        if(minimum<0)throw new IllegalArgumentException("invalid_storage_minimum_free_bytes");
        this.data=data.toAbsolutePath().normalize();this.minimum=minimum;
        sample();
        worker=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"storage-health");thread.setDaemon(true);return thread;});
        worker.scheduleWithFixedDelay(this::sample,5,5,TimeUnit.SECONDS);
    }
    public static StorageHealth configured(Path data,Properties settings){
        long minimum;
        try{minimum=Long.parseLong(settings.getProperty("storage.minimumFreeBytes",Long.toString(DEFAULT_MINIMUM)).strip());}
        catch(NumberFormatException bad){throw new IllegalArgumentException("invalid_storage_minimum_free_bytes",bad);}
        return new StorageHealth(data,minimum);
    }
    private void sample(){
        if(closed)return;
        long usable=-1,total=-1;String error="";
        try{
            FileStore store=Files.getFileStore(data);usable=store.getUsableSpace();total=store.getTotalSpace();
            if(usable<0||total<0)throw new IOException("invalid_volume_space");
        }catch(IOException|RuntimeException failure){error=failure.getClass().getSimpleName();}
        reading=new Reading(usable,total,System.nanoTime(),System.currentTimeMillis(),Thread.currentThread().getName(),error);
    }
    /** No IO, waits or locks: safe in HTTP and transport readiness callers. */
    public boolean ready(){Reading value=reading;return !closed&&value.error().isEmpty()&&System.nanoTime()-value.nanos()<STALE_NANOS&&value.usable()>=minimum;}
    public void requireReady()throws IOException{if(!ready())throw new IOException("storage_not_ready:"+status(reading));}
    private String status(Reading value){return closed?"closed":!value.error().isEmpty()?"unavailable":System.nanoTime()-value.nanos()>=STALE_NANOS?"stale":value.usable()<minimum?"low_space":"ready";}
    public Map<String,Object> metrics(){
        Reading value=reading;String status=status(value);
        return Map.of("dataDirectory",data.toString(),"status",status,"ready",status.equals("ready"),"usableBytes",value.usable(),"totalBytes",value.total(),"minimumFreeBytes",minimum,"checkedAtMillis",value.wall(),"sampleAgeMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-value.nanos()),"samplerThread",value.thread(),"error",value.error());
    }
    @Override public void close(){closed=true;worker.shutdownNow();}
}
