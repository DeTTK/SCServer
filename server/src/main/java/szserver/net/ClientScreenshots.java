package szserver.net;

import szserver.client.ScreenshotPackets;
import szserver.util.Workers;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Ephemeral admin captures, bounded independently of gameplay and durable world IO. */
public final class ClientScreenshots implements AutoCloseable {
    public record Client(UUID session,UUID character,String name){}
    private static final long TIMEOUT=30_000,TTL=300_000;
    private static final int BUDGET=32*1024*1024,MAX_IMAGE=2*1024*1024;
    private static final class Entry {
        final Client client;String status="waiting";byte[] image;int width,height;
        Entry(Client client){this.client=client;}
    }
    private static final class Job {
        final UUID id;final long created;final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
        int bytes;boolean retired;
        Job(UUID id,long now){this.id=id;created=now;}
    }
    private final ThreadPoolExecutor images=Workers.pool("client-screenshot-image",2,32);
    // The native reply has no request ID. A timed-out session stays fenced until its old
    // response arrives or it disconnects; it must never be attached to a newer request.
    private final Map<UUID,Entry> awaiting=new HashMap<>();
    private final java.util.function.LongSupplier clock;
    public ClientScreenshots(){this(System::currentTimeMillis);}
    ClientScreenshots(java.util.function.LongSupplier clock){this.clock=clock;}
    private Job job;private int rawBytes;private boolean closed;

    public synchronized Map<String,Object> begin(UUID id,List<Client> clients,java.util.function.Consumer<Client> send){
        long now=clock.getAsLong();expire(now);
        if(closed)throw new RejectedExecutionException("screenshots_closed");
        if(job!=null&&job.id.equals(id))return view(now);
        if(job!=null&&!done())throw new IllegalArgumentException("screenshots_busy");
        if(clients.size()>32)throw new IllegalArgumentException("screenshot_client_limit");
        retire();job=new Job(id,now);
        for(Client client:clients){
            Entry entry=new Entry(client);job.entries.put(client.session(),entry);
            if(awaiting.containsKey(client.session())){entry.status="awaiting_previous_response";continue;}
            awaiting.put(client.session(),entry);
            try{send.accept(client);}catch(RuntimeException error){awaiting.remove(client.session(),entry);entry.status="request_failed";}
        }
        return view(now);
    }
    public synchronized void receive(UUID session,byte[] body){
        expire(clock.getAsLong());Entry entry=awaiting.remove(session);
        if(entry==null||job==null||job.entries.get(session)!=entry||!entry.status.equals("waiting"))return;
        Job target=job;
        if(body.length>ScreenshotPackets.MAX_COMPRESSED+8||rawBytes+body.length>BUDGET){entry.status="image_limit";return;}
        rawBytes+=body.length;entry.status="processing";
        try{images.execute(()->decode(target,entry,body));}
        catch(RejectedExecutionException full){rawBytes-=body.length;entry.status="image_queue_full";}
    }
    private void decode(Job target,Entry entry,byte[] body){
        byte[] jpeg=null;int width=0,height=0;String failure="invalid_image";
        try{
            byte[] compressed=ScreenshotPackets.image(body),encoded;
            try(var gzip=new GZIPInputStream(new ByteArrayInputStream(compressed))){
                encoded=gzip.readNBytes(16*1024*1024+1);
                if(encoded.length>16*1024*1024)throw new IOException("expanded_image_limit");
            }
            BufferedImage source;
            try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))){
                var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("image_format");
                var reader=readers.next();
                try{
                    String format=reader.getFormatName();if(!format.equalsIgnoreCase("png")&&!format.equalsIgnoreCase("jpeg"))throw new IOException("image_format");
                    reader.setInput(input,true,true);width=reader.getWidth(0);height=reader.getHeight(0);
                    if(width<1||height<1||width>8192||height>8192||(long)width*height>16_777_216L)throw new IOException("image_dimensions");
                    source=reader.read(0);
                }finally{reader.dispose();}
            }
            double scale=Math.min(1,2560.0/Math.max(width,height));
            width=Math.max(1,(int)(width*scale));height=Math.max(1,(int)(height*scale));
            var rgb=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=rgb.createGraphics();
            try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(source,0,0,width,height,null);}finally{graphics.dispose();source.flush();}
            var out=new ByteArrayOutputStream();
            // Memory-only output: ImageIO's global disk-cache default must not spill captures.
            var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
            try(var output=new javax.imageio.stream.MemoryCacheImageOutputStream(out)){
                writer.setOutput(output);var parameters=writer.getDefaultWriteParam();parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);parameters.setCompressionQuality(.90f);
                writer.write(null,new IIOImage(rgb,null,null),parameters);
            }finally{writer.dispose();rgb.flush();}
            jpeg=out.toByteArray();if(jpeg.length>MAX_IMAGE){jpeg=null;failure="image_limit";}
        }catch(IOException|RuntimeException error){/* Untrusted images never expose paths or decoder details. */}
        synchronized(this){
            rawBytes-=body.length;
            if(closed||target.retired||job!=target||!entry.status.equals("processing"))return;
            if(jpeg==null)entry.status=failure;
            else if(target.bytes+jpeg.length>BUDGET)entry.status="image_limit";
            else{target.bytes+=jpeg.length;entry.image=jpeg;entry.width=width;entry.height=height;entry.status="received";}
        }
    }
    public synchronized void disconnected(UUID session){Entry entry=awaiting.remove(session);if(entry!=null&&entry.status.equals("waiting"))entry.status="disconnected";}
    public synchronized void advance(){expire(clock.getAsLong());}
    public synchronized Map<String,Object> status(UUID id){expire(clock.getAsLong());require(id);return view(clock.getAsLong());}
    public synchronized byte[] image(UUID id,UUID session){expire(clock.getAsLong());require(id);Entry entry=job.entries.get(session);return entry==null?null:entry.image;}
    public synchronized void delete(UUID id){require(id);retire();}
    private void require(UUID id){if(job==null||!job.id.equals(id))throw new IllegalArgumentException("screenshot_job_not_found");}
    private boolean done(){return job.entries.values().stream().noneMatch(entry->entry.status.equals("waiting")||entry.status.equals("processing"));}
    private void expire(long now){
        if(job==null)return;
        if(now-job.created>=TTL){retire();return;}
        if(now-job.created>=TIMEOUT)for(Entry entry:job.entries.values())if(entry.status.equals("waiting")||entry.status.equals("processing"))entry.status="timeout";
    }
    private Map<String,Object> view(long now){
        return Map.of("requestId",job.id.toString(),"createdAt",Instant.ofEpochMilli(job.created).toString(),"done",done(),"capture","desktop","expiresAt",Instant.ofEpochMilli(job.created+TTL).toString(),
            "clients",job.entries.values().stream().map(entry->Map.<String,Object>of("sessionId",entry.client.session().toString(),"characterId",entry.client.character().toString(),"name",entry.client.name(),"status",entry.status,"bytes",entry.image==null?0:entry.image.length,"width",entry.width,"height",entry.height)).toList());
    }
    private void retire(){if(job!=null){job.retired=true;for(Entry entry:job.entries.values())entry.image=null;job=null;}}
    @Override public void close(){synchronized(this){closed=true;retire();awaiting.clear();}images.shutdownNow();}
}
