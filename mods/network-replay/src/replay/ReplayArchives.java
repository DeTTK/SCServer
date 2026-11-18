package replay;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Split at recorded logins. Every original packet belongs to exactly one archive. */
final class ReplayArchives {
    static List<Integer> boundaries(DemoFile demo){
        List<Integer> result=new ArrayList<>();result.add(0);
        for(int i=1;i<demo.events.size();i++){var event=demo.events.get(i);if(!event.incoming()&&event.sourceId()==1)result.add(i);}
        result.add(demo.events.size());return result;
    }
    static synchronized List<Path> split(DemoFile demo,Path directory)throws Exception {
        List<Integer> bounds=boundaries(demo);if(bounds.size()==2)return List.of(demo.path);
        Files.createDirectories(directory);List<Path> result=new ArrayList<>();
        String filename=demo.path.getFileName().toString(),stem=filename.substring(0,filename.length()-5);
        String parentHash=demo.manifest.get("event_stream_sha256").getAsString();
        try(ZipFile original=new ZipFile(demo.path.toFile())){
            for(int session=0;session<bounds.size()-1;session++){
                int first=bounds.get(session),end=bounds.get(session+1);long origin=demo.events.get(first).timeNs();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
                out.writeInt(0x5343444d);out.writeInt(1);out.writeInt(end-first);
                int incoming=0,mapped=0;Map<String,int[]> counts=new TreeMap<>();
                for(int i=first;i<end;i++){
                    var event=demo.events.get(i);byte[] body=event.body();
                    out.writeLong(event.timeNs()-origin);out.writeInt(event.connection());out.writeByte(event.stream());out.writeByte(event.incoming()?1:0);
                    out.writeInt(event.sourceId());out.writeInt(event.targetId());out.writeInt(event.sequence());out.writeByte(event.offset());out.writeInt(body.length);out.write(body);
                    if(event.incoming()){incoming++;if(event.targetId()>=0)mapped++;}
                    counts.computeIfAbsent((event.incoming()?"S>C":"C>S")+":"+event.sourceId(),k->new int[]{0,event.sourceId(),event.targetId()})[0]++;
                }
                out.flush();byte[] stream=bytes.toByteArray();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream));
                JsonObject manifest=demo.manifest.deepCopy();manifest.addProperty("event_count",end-first);manifest.addProperty("duration_ns",demo.events.get(end-1).timeNs()-origin);
                manifest.addProperty("event_stream_sha256",hash);manifest.addProperty("incoming_count",incoming);manifest.addProperty("outgoing_count",end-first-incoming);manifest.addProperty("mapped_incoming",mapped);
                if(demo.captureClockRecorded)manifest.getAsJsonObject("capture_clock").addProperty("origin_ns",Math.addExact(demo.captureMonotonicOriginNs,origin));
                JsonArray sessions=new JsonArray();JsonObject local=new JsonObject();local.addProperty("event",0);local.addProperty("time_ns",0);local.addProperty("connection",demo.events.get(first).connection());sessions.add(local);manifest.add("sessions",sessions);
                JsonObject provenance=new JsonObject();provenance.addProperty("parent_event_stream_sha256",parentHash);provenance.addProperty("ordinal",session+1);provenance.addProperty("session_count",bounds.size()-1);provenance.addProperty("first_event",first);provenance.addProperty("end_event_exclusive",end);provenance.addProperty("source_start_ns",origin);manifest.add("session_origin",provenance);
                JsonArray packetCounts=new JsonArray(),unmapped=new JsonArray();
                for(var count:counts.entrySet()){JsonObject row=new JsonObject();row.addProperty("direction",count.getKey().split(":")[0]);row.addProperty("packet_id",count.getValue()[1]);row.addProperty("count",count.getValue()[0]);packetCounts.add(row);if(count.getValue()[2]<0)unmapped.add(row.deepCopy());}
                manifest.add("packet_counts",packetCounts);manifest.add("unmapped",unmapped);
                Path target=directory.resolve(stem+String.format(Locale.ROOT,"-session-%02d-",session+1)+parentHash.substring(0,8)+".demo");
                if(Files.exists(target)){
                    DemoFile existing=new DemoFile(target);if(!existing.manifest.get("event_stream_sha256").getAsString().equals(hash)||!existing.compatibilityFingerprint.equals(demo.compatibilityFingerprint))throw new IOException("Existing replay session differs: "+target);
                }else{
                    Path temporary=Files.createTempFile(directory,"replay-session-",".tmp");
                    try{
                        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary))){
                            for(var entries=original.entries();entries.hasMoreElements();){ZipEntry entry=entries.nextElement();if(entry.getName().equals("events.bin")||entry.getName().equals("manifest.json"))continue;
                                zip.putNextEntry(new ZipEntry(entry.getName()));try(InputStream input=original.getInputStream(entry)){input.transferTo(zip);}zip.closeEntry();}
                            zip.putNextEntry(new ZipEntry("events.bin"));zip.write(stream);zip.closeEntry();zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                        }
                        new DemoFile(temporary);
                        try{Files.move(temporary,target);}catch(FileAlreadyExistsException concurrent){
                            DemoFile existing=new DemoFile(target);
                            if(!existing.manifest.get("event_stream_sha256").getAsString().equals(hash)||!existing.compatibilityFingerprint.equals(demo.compatibilityFingerprint))throw new IOException("Concurrent replay session differs: "+target,concurrent);
                        }
                    }finally{Files.deleteIfExists(temporary);}
                }
                result.add(target);
            }
        }
        return List.copyOf(result);
    }
}
