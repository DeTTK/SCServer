package szserver.util;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Captured cursor pages; only the HTTP writer retains one row, never a complete JSON array. */
public final class JsonPages {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public record Result(int count,JsonObject last){}
    public record Spec(Map<String,Object> prefix,String field,Iterable<JsonObject> rows,Function<Result,Map<String,Object>> suffix){
        public Spec {prefix=Collections.unmodifiableMap(new LinkedHashMap<>(prefix));Objects.requireNonNull(field);Objects.requireNonNull(rows);Objects.requireNonNull(suffix);}
    }
    private final Semaphore admission=new Semaphore(2);
    private final AtomicLong completed=new AtomicLong(),rejected=new AtomicLong(),failures=new AtomicLong(),writtenRows=new AtomicLong(),writtenBytes=new AtomicLong();
    private final AtomicLong maxWriteNanos=new AtomicLong();
    public Page admit(Spec spec){Objects.requireNonNull(spec);if(!admission.tryAcquire()){rejected.incrementAndGet();throw new RejectedExecutionException("backend_page_capacity");}return new Page(spec);}
    public final class Page implements AutoCloseable {
        private final Spec spec;private final AtomicBoolean closed=new AtomicBoolean(),opened=new AtomicBoolean();private long bytes;private int count;private long started;
        private Page(Spec spec){this.spec=Objects.requireNonNull(spec);}
        /** Seek and validate the first row before the HTTP headers are committed. */
        public Cursor open(){if(closed.get()||!opened.compareAndSet(false,true))throw new IllegalStateException("backend_page_already_opened");started=System.nanoTime();Iterator<JsonObject> rows=spec.rows().iterator();return new Cursor(rows,rows.hasNext()?rows.next():null);}
        public final class Cursor {
            private final Iterator<JsonObject> rows;private final JsonObject first;
            private Cursor(Iterator<JsonObject> rows,JsonObject first){this.rows=rows;this.first=first;}
            public void write(OutputStream output)throws IOException {
                OutputStream counter=new FilterOutputStream(output){@Override public void write(int value)throws IOException{out.write(value);bytes++;}@Override public void write(byte[] value,int offset,int length)throws IOException{out.write(value,offset,length);bytes+=length;}};
                JsonWriter writer=new JsonWriter(new OutputStreamWriter(new BufferedOutputStream(counter,32768),StandardCharsets.UTF_8));writer.setSerializeNulls(true);writer.beginObject();
                for(var entry:spec.prefix().entrySet()){writer.name(entry.getKey());JSON.toJson(entry.getValue(),entry.getValue()==null?Object.class:entry.getValue().getClass(),writer);}
                writer.name(spec.field()).beginArray();JsonObject last=first;
                if(first!=null){JSON.toJson(first,writer);count++;}
                while(rows.hasNext()){if(closed.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("backend_page_cancelled");last=rows.next();JSON.toJson(last,writer);count++;}
                writer.endArray();for(var entry:spec.suffix().apply(new Result(count,last)).entrySet()){writer.name(entry.getKey());JSON.toJson(entry.getValue(),entry.getValue()==null?Object.class:entry.getValue().getClass(),writer);}writer.endObject();writer.flush();completed.incrementAndGet();
            }
        }
        public void failed(){failures.incrementAndGet();}
        @Override public void close(){if(closed.compareAndSet(false,true)){writtenRows.addAndGet(count);writtenBytes.addAndGet(bytes);if(started!=0)maxWriteNanos.accumulateAndGet(System.nanoTime()-started,Math::max);admission.release();}}
    }
    public Map<String,Object> metrics(){return Map.of("capacity",2,"active",2-admission.availablePermits(),"completed",completed.get(),"rejected",rejected.get(),"failures",failures.get(),"encodedRows",writtenRows.get(),"writtenBytes",writtenBytes.get(),"maxWriteMs",maxWriteNanos.get()/1e6,"streamed",true);}
}
