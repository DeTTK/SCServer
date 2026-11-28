package szserver.net;

import com.google.gson.*;
import environment.protocol.EnvironmentWire;
import szserver.persistence.*;
import szserver.util.Workers;
import szserver.world.RegionTerrain;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Session admission stays on the network owner; codecs, IO and transfer preparation stay on workers. */
final class EnvironmentAccess implements AutoCloseable {
    record Emission(int lane,byte[] bytes){}
    static final class Client {
        boolean enabled,sendState;long nextState,knownConfig=-1;String operation="";
        CompletableFuture<?> pending;Upload upload;Transfer transfer;int page;
        CompletableFuture<List<byte[]>> coverage;int coveragePage;long nextCoverage;
    }
    private static final class Upload {
        final long expected;final String hash;final int size;final ByteArrayOutputStream data;int page;long touched=System.nanoTime();
        Upload(long expected,String hash,int size){this.expected=expected;this.hash=hash;this.size=size;data=new ByteArrayOutputStream(size);}
    }
    private record Transfer(long revision,List<byte[]> frames){}
    private final WorldEnvironment environment;
    private final RegionTerrain regions;
    final WeatherConfigurations configurations;
    private final ThreadPoolExecutor transferWorker=Workers.pool("weather-transfer",1,2);
    private final AtomicInteger uploads=new AtomicInteger();
    private CompletableFuture<Transfer> transfer;private long transferRevision=-1;
    private CompletableFuture<List<byte[]>> coverage;private long coverageAt;
    EnvironmentAccess(WorldEnvironment environment,WeatherConfigurations configurations,RegionTerrain regions){this.environment=environment;this.configurations=configurations;this.regions=regions;}
    static byte[] frame(JsonObject message){try{return Wire.frame(1501,Wire.bytes(out->{out.writeUTF(EnvironmentWire.SNAPSHOT);out.write(EnvironmentWire.encode(message));}));}catch(Exception error){throw new IllegalArgumentException("environment_encode",error);}}
    private JsonObject state(boolean editable,long tick){
        long now=System.currentTimeMillis();var state=environment.state();var clock=environment.clockAnchor();var config=configurations.snapshot();JsonObject out=EnvironmentWire.message("state");
        out.addProperty("worldTime",state.worldTime());out.addProperty("nativeTime",clock.nativeTicks(now));out.addProperty("timeSpeed",clock.speed());out.addProperty("serverMillis",now);
        out.addProperty("nativeDayTicks",EnvironmentClock.NATIVE_DAY_TICKS);out.addProperty("weather",state.weather().wireName());out.addProperty("phenomenon",state.weather().phenomenon());out.addProperty("weatherUntil",state.weatherUntil());out.addProperty("weatherStarted",environment.weatherStarted());out.addProperty("settingsRevision",environment.settingsRevision());out.addProperty("configRevision",config.revision());out.addProperty("configSha256",config.sha256());out.addProperty("editable",editable);out.addProperty("chunkCoverage",editable&&regions!=null);out.addProperty("serverTick",tick);return out;
    }
    void request(Client client,byte[] body,boolean editable)throws Exception {
        JsonObject in=EnvironmentWire.decode(body);String op=in.get("op").getAsString();
        if(in.get("version").getAsInt()!=EnvironmentWire.VERSION)throw new IllegalArgumentException("environment_version");
        if(op.equals("hello")){client.enabled=true;client.sendState=true;return;}
        if(!client.enabled)throw new IllegalArgumentException("environment_hello_required");
        if(op.equals("getConfig")){client.knownConfig=-1;client.transfer=null;return;}
        if(!editable)throw new IllegalArgumentException("environment_requires_world_editor");
        if(op.equals("getChunkCoverage")){
            long now=System.nanoTime();
            if(regions!=null&&client.coverage==null&&now>=client.nextCoverage){
                client.coverage=coverage(now);client.coveragePage=0;client.nextCoverage=now+10_000_000_000L;
            }
            return;
        }
        if(client.pending!=null)throw new IllegalArgumentException("environment_operation_pending");
        if(op.equals("update")){
            Long time=in.has("time")?in.get("time").getAsBigDecimal().longValueExact():null;
            Double speed=in.has("timeSpeed")?in.get("timeSpeed").getAsDouble():null;
            var weather=in.has("weather")?EnvironmentCommands.Weather.read(in.get("weather").getAsString()):null;
            int duration=in.has("durationSeconds")?in.get("durationSeconds").getAsBigDecimal().intValueExact():600;
            client.operation="update";client.pending=environment.update(new WorldEnvironment.Mutation(time,null,weather,duration,speed));return;
        }
        if(op.equals("configBegin")){
            if(client.upload!=null)throw new IllegalArgumentException("weather_upload_already_active");int size=in.get("bytes").getAsInt();String hash=in.get("sha256").getAsString();
            if(size<1||size>WeatherConfigurations.MAX_COMPRESSED||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("weather_upload_header");
            if(uploads.incrementAndGet()>2){uploads.decrementAndGet();throw new IllegalArgumentException("weather_upload_capacity");}
            client.upload=new Upload(in.get("expectedRevision").getAsLong(),hash,size);return;
        }
        if(op.equals("configPart")){
            Upload upload=client.upload;if(upload==null||in.get("index").getAsInt()!=upload.page)throw new IllegalArgumentException("weather_upload_sequence");byte[] part=Base64.getDecoder().decode(in.get("data").getAsString());
            if(part.length<1||part.length>EnvironmentWire.PAGE_BYTES||upload.data.size()+part.length>upload.size)throw new IllegalArgumentException("weather_upload_part");upload.data.write(part);upload.page++;upload.touched=System.nanoTime();
            if(upload.data.size()==upload.size){client.upload=null;uploads.decrementAndGet();byte[] compressed=upload.data.toByteArray();if(!WeatherConfigurations.hash(compressed).equals(upload.hash))throw new IllegalArgumentException("weather_upload_checksum");client.operation="saveConfig";client.pending=configurations.save(upload.expected,compressed);}
            return;
        }
        throw new IllegalArgumentException("unknown_environment_operation");
    }
    private synchronized CompletableFuture<Transfer> transfer(){
        var snapshot=configurations.snapshot();if(transfer!=null&&transferRevision==snapshot.revision())return transfer;
        transferRevision=snapshot.revision();var result=new CompletableFuture<Transfer>();transfer=result;
        try{transferWorker.execute(()->{try{
            List<byte[]> frames=new ArrayList<>();JsonObject begin=EnvironmentWire.message("configBegin");begin.addProperty("revision",snapshot.revision());begin.addProperty("sha256",snapshot.sha256());begin.addProperty("bytes",snapshot.bytes().length);frames.add(frame(begin));
            for(int at=0,index=0;at<snapshot.bytes().length;at+=EnvironmentWire.PAGE_BYTES,index++){JsonObject part=EnvironmentWire.message("configPart");part.addProperty("index",index);part.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(snapshot.bytes(),at,Math.min(snapshot.bytes().length,at+EnvironmentWire.PAGE_BYTES))));frames.add(frame(part));}
            result.complete(new Transfer(snapshot.revision(),List.copyOf(frames)));
        }catch(Exception error){result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    private CompletableFuture<List<byte[]>> coverage(long now){
        if(coverage!=null&&(!coverage.isDone()||now-coverageAt<5_000_000_000L))return coverage;
        coverageAt=now;var result=new CompletableFuture<List<byte[]>>();coverage=result;
        try{transferWorker.execute(()->{
            try{
                var masks=regions.storedChunkMasks();
                if(masks.size()>EnvironmentWire.MAX_COVERAGE_REGIONS)throw new IOException("weather_chunk_coverage_limit");
                int perPage=EnvironmentWire.PAGE_BYTES/EnvironmentWire.COVERAGE_REGION_BYTES;
                int pages=Math.max(1,(masks.size()+perPage-1)/perPage);
                var iterator=masks.entrySet().iterator();var frames=new ArrayList<byte[]>(pages);
                for(int page=0;page<pages;page++){
                    byte[] encoded=Wire.bytes(out->{
                        for(int n=0;n<perPage&&iterator.hasNext();n++){
                            var region=iterator.next();
                            out.writeLong(region.getKey());
                            out.write(Arrays.copyOf(region.getValue().toByteArray(),128));
                        }
                    });
                    JsonObject part=EnvironmentWire.message("chunkCoverage");part.addProperty("index",page);part.addProperty("pages",pages);part.addProperty("data",Base64.getEncoder().encodeToString(encoded));frames.add(frame(part));
                }
                result.complete(List.copyOf(frames));
            }catch(Exception error){result.completeExceptionally(error);}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}
        return result;
    }
    List<Emission> poll(Client client,boolean editable,long tick,boolean room){
        if(!client.enabled)return List.of();List<Emission> out=new ArrayList<>();long now=System.nanoTime();
        if(client.upload!=null&&now-client.upload.touched>30_000_000_000L){disconnect(client);JsonObject reply=EnvironmentWire.message("ack");reply.addProperty("operation","saveConfig");reply.addProperty("ok",false);reply.addProperty("error","weather_upload_timeout");out.add(new Emission(0,frame(reply)));}
        if(client.pending!=null&&client.pending.isDone()){
            JsonObject reply=EnvironmentWire.message("ack");reply.addProperty("operation",client.operation);
            try{Object result=client.pending.join();reply.addProperty("ok",true);if(result instanceof WeatherConfigurations.Snapshot snapshot)reply.addProperty("configRevision",snapshot.revision());}catch(CompletionException error){Throwable cause=error.getCause();reply.addProperty("ok",false);reply.addProperty("error",Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));}
            out.add(new Emission(0,frame(reply)));client.pending=null;client.sendState=true;
        }
        if(client.sendState||now>=client.nextState){out.add(new Emission(0,frame(state(editable,tick))));client.nextState=now+1_000_000_000L;client.sendState=false;}
        if(room){
            if(client.coverage!=null&&client.coverage.isDone()){
                if(editable&&!client.coverage.isCompletedExceptionally()){
                    var frames=client.coverage.join();
                    out.add(new Emission(1,frames.get(client.coveragePage++)));
                    if(client.coveragePage==frames.size())client.coverage=null;
                }else client.coverage=null;
            }
            long revision=configurations.snapshot().revision();
            if(client.transfer==null&&revision>0&&client.knownConfig!=revision){var future=transfer();if(future.isDone()&&!future.isCompletedExceptionally()){client.transfer=future.join();client.page=0;}}
            if(client.transfer!=null){for(int n=0;n<2&&client.page<client.transfer.frames().size();n++)out.add(new Emission(1,client.transfer.frames().get(client.page++)));if(client.page==client.transfer.frames().size()){client.knownConfig=client.transfer.revision();client.transfer=null;}}
        }
        return out;
    }
    void disconnect(Client client){if(client.upload!=null){client.upload=null;uploads.decrementAndGet();}}
    public void close()throws Exception{Workers.stop(transferWorker);configurations.close();}
}
