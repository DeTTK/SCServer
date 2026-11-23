package bscp.next;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

public final class BridgeDiagnosticsTest {
    static final class Counted extends ByteArrayOutputStream {
        int bulk,single;
        @Override public synchronized void write(int value){single++;super.write(value);}
        @Override public synchronized void write(byte[] bytes,int start,int count){bulk++;super.write(bytes,start,count);}
    }
    public static void main(String[] args)throws Exception {
        Bridge.output=Path.of(args[0]);Files.createDirectories(Bridge.output);
        Bridge.slowWorldTicks=0;Bridge.worldTicks=0;Bridge.slowFrames=0;
        Bridge.lastWorldTick=System.nanoTime()-250_000_000L;Bridge.tick();
        if(Bridge.worldTicks!=1||Bridge.slowWorldTicks!=1)throw new AssertionError("short server pause was lost");
        if(!Bridge.delayed(0,0,0,0,0))throw new AssertionError("short pause did not request diagnostics");
        if(Bridge.delayed(0,0,0,0,1))throw new AssertionError("old pause repeatedly reported");
        var metrics=JsonParser.parseString(Bridge.metrics()).getAsJsonObject();
        if(metrics.get("worldTicks").getAsLong()!=1||!metrics.has("cpuTimeNanos")||!metrics.has("droppedDiagnostics"))throw new AssertionError("incomplete metrics");
        Counted original=new Counted();var output=new Bridge.ConsoleLog(new PrintStream(original,false,StandardCharsets.UTF_8),"diagnostic-test");
        byte[] text=("Unicode: Привет\n"+"token"+"="+"private-value\n").getBytes(StandardCharsets.UTF_8);
        output.write(text,0,text.length);
        if(!Arrays.equals(text,original.toByteArray())||original.bulk!=1||original.single!=0)throw new AssertionError("console bulk output was altered or split into single-byte writes");
        if(Files.readString(Bridge.output.resolve("bridge.jsonl")).contains("private-value"))throw new AssertionError("secret leaked into diagnostic history");
        System.out.println("PASS short world pause, bounded console bulk logging, Unicode, secret redaction and CPU counters");
    }
}
