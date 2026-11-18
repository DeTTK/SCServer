package szserver;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.CRC32;

/** Bounded-heap streaming recovery, complete durable prefixes, schema failures and crash/CRC guards. */
public final class JournalStreamingTests {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface Action{void run()throws Exception;}
    static void reject(Action action,String text)throws Exception{
        try{action.run();throw new AssertionError("accepted "+text);}catch(ExecutionException failure){check(failure.getCause().getMessage().contains(text),text);}catch(IOException failure){check(failure.getMessage().contains(text),text);}
    }
    static void fixture(Path path,int count,int padding)throws Exception{
        byte[] event=Json.bytes(Json.tree(Map.of("value",7,"padding","x".repeat(padding))));CRC32 crc=new CRC32();crc.update(event);
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))){out.writeInt(0x535a4a31);out.writeInt(1);for(int i=0;i<count;i++){out.writeInt(event.length);out.writeInt((int)crc.getValue());out.write(event);}}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),data=Files.createTempDirectory(root.resolve("var"),"journal-streaming-");final int records=12032;
        Path large=data.resolve("large.sjournal");fixture(large,records,8192);long durable=Files.size(large);
        check(Runtime.getRuntime().maxMemory()<=70L*1024*1024,"streaming suite runs with64MiB heap");
        try(Journal journal=Journal.streaming(large)){
            check(journal.recovered().isEmpty()&&journal.streamedRecovery(),"no duplicate retained JSON recovery list");
            check(((Number)journal.metrics().get("commits")).longValue()==records,"valid records counted beyond10000");
            AtomicInteger count=new AtomicInteger();AtomicLong sum=new AtomicLong();journal.replay(event->{check(event.get("padding").getAsString().length()==8192,"full record padding retained");count.incrementAndGet();sum.addAndGet(event.get("value").getAsLong());}).get(30,TimeUnit.SECONDS);
            check(count.get()==records&&sum.get()==records*7L&&Files.size(large)==durable,"streamed all98MiB under64MiB heap without truncation");
            reject(()->journal.replay(event->{throw new IOException("test_schema_failure");}).get(),"test_schema_failure");check(journal.healthy()&&Files.size(large)==durable,"consumer rejection cannot mutate source or poison writer");
            count.set(0);journal.replay(event->count.incrementAndGet()).get();check(count.get()==records,"failed consumer can retry a full validated prefix");
        }
        Files.write(large,new byte[]{0,0,0},StandardOpenOption.APPEND);
        try(Journal journal=Journal.streaming(large)){check(Files.size(large)==durable,"only incomplete crash tail trimmed");AtomicInteger count=new AtomicInteger();journal.replay(event->count.incrementAndGet()).get();check(count.get()==records,"complete prefix survives tail recovery");}
        // Corruption after the former10000 threshold must still fail startup, even
        // without retained JSON. Repair only this private fixture for later probes.
        long at=8+(durable-8)/records*11000+8;byte saved;
        try(var file=java.nio.channels.FileChannel.open(large,StandardOpenOption.READ,StandardOpenOption.WRITE)){ByteBuffer value=ByteBuffer.allocate(1);file.read(value,at);saved=value.array()[0];file.write(ByteBuffer.wrap(new byte[]{(byte)(saved^1)}),at);}
        reject(()->{try(Journal ignored=Journal.streaming(large)){}},"journal_checksum_mismatch");
        try(var file=java.nio.channels.FileChannel.open(large,StandardOpenOption.WRITE)){file.write(ByteBuffer.wrap(new byte[]{saved}),at);}
        Path small=data.resolve("prefix.sjournal");fixture(small,2,0);
        try(Journal journal=Journal.streaming(small)){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger count=new AtomicInteger();
            var replay=journal.replay(event->{if(count.getAndIncrement()==0){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test_replay_gate_timeout");}});
            check(entered.await(5,TimeUnit.SECONDS),"replay owns writer queue");var append=journal.append(Json.tree(Map.of("value",9)));replay.cancel(true);release.countDown();append.get(5,TimeUnit.SECONDS);
            check(count.get()==2,"caller cancellation does not abort accepted prefix scan");count.set(0);journal.replay(event->count.incrementAndGet()).get();check(count.get()==3,"later append follows replay and appears in next prefix");
        }
        Path retained=data.resolve("retained-limit.sjournal");fixture(retained,records,0);reject(()->{try(Journal ignored=new Journal(retained)){}},"journal_event_limit");check(Files.size(retained)>0,"legacy retaining mode keeps explicit bounded compatibility contract");
        var report=Json.tree(Map.of("result","PASS","assertions",checks,"records",records,"journalBytes",durable,"maxHeapBytes",Runtime.getRuntime().maxMemory(),"retainedWorldEvents",0,"qa",data.toString(),"desktopInputUsed",false));Files.write(root.resolve("results/test-journal-streaming.json"),Json.bytes(report));
        System.out.println("PASS journal streaming "+checks+" checks;12032 records/"+durable+" bytes under64MiB heap,serialized durable prefix,consumer/cancellation guards,tail repair,late CRC failure");
    }
}
