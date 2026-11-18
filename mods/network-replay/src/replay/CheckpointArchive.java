package replay;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Portable, deduplicated full-state sidecar. No native references or Java object deserialization. */
final class CheckpointArchive implements AutoCloseable {
    enum Section { WORLD, ENTITIES, ANIMATION, CLOCKS, PHYSICS, EFFECTS, AUDIO, SOURCE_CONTROLLERS, PENDING_DISPATCH }
    static final long MAX_GAP_NS=500_000_000L;
    static final int MAX_BLOB=32*1024*1024,MAX_STATE=64*1024*1024,MAX_MANIFEST=16*1024*1024;
    record Part(int version,byte[] bytes) {
        Part {if(version<1||bytes==null||bytes.length>MAX_BLOB)throw new IllegalArgumentException("Invalid checkpoint section");bytes=bytes.clone();}
        public byte[] bytes(){return bytes.clone();}
    }
    record State(Map<Section,Part> parts) {
        State {if(!parts.keySet().equals(EnumSet.allOf(Section.class)))throw new IllegalArgumentException("Incomplete world checkpoint");long size=0;for(Part part:parts.values())size+=part.bytes.length;if(size>MAX_STATE)throw new IllegalArgumentException("Oversized world checkpoint");parts=Map.copyOf(parts);}
    }
    record Point(long position,int cursor,JsonObject sections) {}
    final ZipFile zip;final List<Point> points;
    static Path beside(DemoFile demo){return demo.path.resolveSibling(demo.path.getFileName()+".checkpoints");}
    static String sha(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static byte[] read(ZipFile zip,String name,int limit)throws IOException {
        ZipEntry entry=zip.getEntry(name);if(entry==null||entry.getSize()<0||entry.getSize()>limit)throw new IOException("Missing/oversized checkpoint entry: "+name);
        try(InputStream in=zip.getInputStream(entry)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit||bytes.length!=entry.getSize())throw new IOException("Invalid checkpoint entry length: "+name);return bytes;}
    }
    static void boundary(Timeline timeline,long position,int cursor)throws IOException {
        try{timeline.validateCheckpoint(new Timeline.Checkpoint(position,cursor,Boolean.TRUE),timeline.duration);}catch(IllegalArgumentException e){throw new IOException("Invalid checkpoint event boundary",e);}
    }
    static void spacing(long previous,long current)throws IOException {
        if(current<0||current<=previous||current-previous>MAX_GAP_NS)throw new IOException("Incomplete checkpoint time coverage");
    }
    CheckpointArchive(Path path,DemoFile demo,String codecFingerprint)throws IOException {
        ZipFile opened=new ZipFile(path.toFile());
        try{
            Set<String> names=new HashSet<>();for(var entries=opened.entries();entries.hasMoreElements();)if(!names.add(entries.nextElement().getName()))throw new IOException("Duplicate checkpoint entry");
            JsonObject manifest=JsonParser.parseString(new String(read(opened,"manifest.json",MAX_MANIFEST),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(!manifest.get("format").getAsString().equals("stalcraft-replay-checkpoints")||manifest.get("version").getAsInt()!=1
                ||!manifest.get("event_stream_sha256").equals(demo.manifest.get("event_stream_sha256"))
                ||!manifest.get("compatibility_fingerprint").getAsString().equals(demo.compatibilityFingerprint)
                ||!manifest.get("codec_fingerprint").getAsString().equals(codecFingerprint))throw new IOException("Checkpoint cache belongs to a different recording or native codec");
            Timeline timeline=new Timeline(demo.events);List<Point> all=new ArrayList<>();long previous=-1;
            JsonArray rows=manifest.getAsJsonArray("points");if(rows.size()==0||rows.size()>1000000)throw new IOException("Invalid checkpoint count");
            for(JsonElement row:rows){
                JsonObject value=row.getAsJsonObject();long position=value.get("position_ns").getAsLong();int cursor=value.get("cursor").getAsInt();
                spacing(previous,position);boundary(timeline,position,cursor);JsonObject sections=value.getAsJsonObject("sections");
                if(sections.size()!=Section.values().length)throw new IOException("Incomplete checkpoint sections");
                long size=0;for(Section section:Section.values()){
                    JsonObject part=sections.getAsJsonObject(section.name());String hash=part.get("sha256").getAsString();
                    if(part.get("version").getAsInt()<1||!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid checkpoint section descriptor");
                    ZipEntry blob=opened.getEntry("blobs/"+hash);if(blob==null||blob.getSize()<0||blob.getSize()>MAX_BLOB)throw new IOException("Missing checkpoint blob");
                    size+=blob.getSize();if(size>MAX_STATE)throw new IOException("Oversized checkpoint state");
                }
                all.add(new Point(position,cursor,sections.deepCopy()));previous=position;
            }
            if(all.get(0).position()!=0||previous!=demo.durationNs)throw new IOException("Incomplete checkpoint duration");
            points=List.copyOf(all);zip=opened;
        }catch(Exception e){opened.close();if(e instanceof IOException io)throw io;throw new IOException("Invalid checkpoint archive",e);}
    }
    Timeline.Checkpoint before(long target)throws IOException {
        int low=0,high=points.size();while(low<high){int mid=(low+high)>>>1;if(points.get(mid).position()<=target)low=mid+1;else high=mid;}
        if(low==0)return null;Point point=points.get(low-1);Map<Section,Part> parts=new EnumMap<>(Section.class);Map<String,byte[]> cache=new HashMap<>();
        // Verify every blob before a native restorer can see the checkpoint.
        for(Section section:Section.values()){
            JsonObject descriptor=point.sections().getAsJsonObject(section.name());String hash=descriptor.get("sha256").getAsString();byte[] bytes=cache.get(hash);
            if(bytes==null){bytes=read(zip,"blobs/"+hash,MAX_BLOB);if(!sha(bytes).equals(hash))throw new IOException("Checkpoint blob hash mismatch");cache.put(hash,bytes);}
            parts.put(section,new Part(descriptor.get("version").getAsInt(),bytes));
        }
        return new Timeline.Checkpoint(point.position(),point.cursor(),new State(parts));
    }
    public void close()throws IOException {zip.close();}
    static final class Writer implements AutoCloseable {
        final Path destination,temporary;final ZipOutputStream zip;final DemoFile demo;final Timeline timeline;final JsonObject manifest=new JsonObject();
        final JsonArray points=new JsonArray();final Set<String> blobs=new HashSet<>();long previous=-1;boolean committed,closed;
        Writer(Path destination,DemoFile demo,String codecFingerprint)throws IOException {
            if(codecFingerprint==null||codecFingerprint.isBlank())throw new IllegalArgumentException("Missing native checkpoint codec fingerprint");
            this.destination=destination.toAbsolutePath();this.demo=demo;timeline=new Timeline(demo.events);
            temporary=Files.createTempFile(this.destination.getParent(),this.destination.getFileName()+".partial-",".zip");zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
            manifest.addProperty("format","stalcraft-replay-checkpoints");manifest.addProperty("version",1);manifest.add("event_stream_sha256",demo.manifest.get("event_stream_sha256"));
            manifest.addProperty("compatibility_fingerprint",demo.compatibilityFingerprint);manifest.addProperty("codec_fingerprint",codecFingerprint);
        }
        void append(long position,int cursor,State state)throws IOException {
            if(closed)throw new IOException("Checkpoint writer closed");spacing(previous,position);boundary(timeline,position,cursor);
            JsonObject sections=new JsonObject();for(Section section:Section.values()){
                Part part=state.parts().get(section);byte[] bytes=part.bytes();String hash=sha(bytes);
                if(blobs.add(hash)){zip.putNextEntry(new ZipEntry("blobs/"+hash));zip.write(bytes);zip.closeEntry();}
                JsonObject descriptor=new JsonObject();descriptor.addProperty("version",part.version());descriptor.addProperty("sha256",hash);sections.add(section.name(),descriptor);
            }
            JsonObject point=new JsonObject();point.addProperty("position_ns",position);point.addProperty("cursor",cursor);point.add("sections",sections);points.add(point);previous=position;
        }
        void commit()throws IOException {
            if(closed||points.size()==0||points.get(0).getAsJsonObject().get("position_ns").getAsLong()!=0||previous!=demo.durationNs)throw new IOException("Checkpoint preparation has not covered the recording");
            manifest.add("points",points);byte[] bytes=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>MAX_MANIFEST)throw new IOException("Checkpoint index too large");
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(bytes);zip.closeEntry();zip.close();closed=true;
            // An interrupted preparation must leave the prior finished cache usable.
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);committed=true;
        }
        public void close()throws IOException {try{if(!closed){zip.close();closed=true;}}finally{if(!committed)Files.deleteIfExists(temporary);}}
    }
}
