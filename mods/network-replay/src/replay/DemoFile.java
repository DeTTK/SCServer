package replay;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Original ordered traffic is immutable. No packet payload is silently discarded. */
public final class DemoFile {
    public record Event(long timeNs, int connection, int stream, boolean incoming,
                        int sourceId, int targetId, int sequence, int offset, byte[] body) {
        public Event { body=body.clone(); }
        public byte[] body(){return body.clone();}
        byte[] payload(){return Arrays.copyOfRange(body,offset,body.length);}
    }
    public final Path path;
    public final JsonObject manifest,packets,registries,hashes,registryEvidence;
    public final String compatibilityFingerprint;
    private final JsonObject sourceResources;
    public final String sourceConfigBundleHash;
    public final List<Event> events;
    public final long durationNs;
    public final long captureMonotonicOriginNs;
    public final boolean captureClockRecorded;
    private final int unmappedIncoming;
    public DemoFile(Path path)throws IOException {
        this.path=path.toAbsolutePath();
        try(ZipFile zip=new ZipFile(this.path.toFile())){
            Set<String> names=new HashSet<>();for(var entries=zip.entries();entries.hasMoreElements();)if(!names.add(entries.nextElement().getName()))throw new IOException("Duplicate demo entry");
            manifest=json(zip,"manifest.json");packets=json(zip,"mappings/packets.json");
            registries=json(zip,"mappings/registries.json");hashes=json(zip,"mappings/target-hashes.json");
            registryEvidence=zip.getEntry("mappings/attribute-evidence.json")==null?new JsonObject():json(zip,"mappings/attribute-evidence.json");
            String configHash="";sourceResources=new JsonObject();String bundleHash="";
            ZipEntry configEntry=zip.getEntry("resources/source-configs.json");
            if(manifest.has("source_resource_configs")){
                JsonObject evidence=manifest.getAsJsonObject("source_resource_configs");
                if(evidence.get("version").getAsInt()!=1||!evidence.get("origin").getAsString().equals("provided_client_resources"))throw new IOException("Unsupported source configuration provenance");
                if(configEntry==null||configEntry.getSize()>16*1024*1024)throw new IOException("Missing or oversized source configuration");
                byte[] configBytes;try(InputStream in=zip.getInputStream(configEntry)){configBytes=in.readNBytes(16*1024*1024+1);}
                if(configBytes.length>16*1024*1024)throw new IOException("Oversized source configuration");
                configHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(configBytes));
                if(!configHash.equals(evidence.get("entry_sha256").getAsString()))throw new IOException("Source configuration hash mismatch");
                JsonObject configs=JsonParser.parseString(new String(configBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                bundleHash=configs.get("source_sha256").getAsString();
                if(!bundleHash.matches("[0-9a-f]{64}")||!bundleHash.equals(evidence.get("bundle_sha256").getAsString())||!registryEvidence.has("resource_evidence")||!bundleHash.equals(registryEvidence.getAsJsonObject("resource_evidence").get("configs_sha256").getAsString()))throw new IOException("Source configuration bundle mismatch");
                JsonObject resources=configs.getAsJsonObject("resources");
                if(resources.size()==0||resources.size()!=evidence.get("resource_count").getAsInt())throw new IOException("Source configuration resource count mismatch");
                for(var resource:resources.entrySet()){
                    String name=resource.getKey();if(!name.startsWith("assets/")||!name.endsWith(".json")||name.contains("\\")||Arrays.stream(name.split("/",-1)).anyMatch(part->part.isEmpty()||part.equals(".")||part.equals("..")))throw new IOException("Invalid source configuration resource name");
                    sourceResources.add(name,resource.getValue());
                }
            }else if(configEntry!=null)throw new IOException("Unbound source configuration archive");
            sourceConfigBundleHash=bundleHash;
            compatibilityFingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((packets+"\n"+registries+"\n"+hashes+(configHash.isEmpty()?"":"\n"+configHash)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(!manifest.get("format").getAsString().equals("stalcraft-network-demo")||manifest.get("version").getAsInt()!=1)throw new IOException("Unsupported .demo format");
            captureClockRecorded=manifest.has("capture_clock");
            captureMonotonicOriginNs=readCaptureClock(manifest);
            ZipEntry entry=required(zip,"events.bin");
            if(entry.getSize()>512L*1024*1024)throw new IOException("Demo exceeds 512 MiB event limit");
            List<Event> all=new ArrayList<>();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(DataInputStream in=new DataInputStream(new DigestInputStream(new BufferedInputStream(zip.getInputStream(entry)),digest))){
                if(in.readInt()!=0x5343444d||in.readInt()!=1)throw new IOException("Invalid event header");
                int count=in.readInt();if(count<1||count>2_000_000||count!=manifest.get("event_count").getAsInt())throw new IOException("Invalid event count");
                long previous=-1,total=0;
                for(int i=0;i<count;i++){
                    long ns=in.readLong();int connection=in.readInt(),stream=in.readUnsignedByte(),direction=in.readUnsignedByte();
                    int source=in.readInt(),target=in.readInt(),sequence=in.readInt(),offset=in.readUnsignedByte(),length=in.readInt();
                    if(ns<previous||stream>1||direction>1||source<0||source>65535||target < -1||target>65535||length<1||length>32*1024*1024||offset<1||offset>3||offset>length||(total+=length)>512L*1024*1024)throw new IOException("Invalid event "+i);
                    byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException("Truncated event "+i);
                    int[] pid=readId(bytes);if(pid[0]!=source||pid[1]!=offset)throw new IOException("Packet metadata mismatch at "+i);
                    all.add(new Event(ns,connection,stream,direction==1,source,target,sequence,offset,bytes));previous=ns;
                }
                if(in.read()!=-1)throw new IOException("Trailing event data");
                durationNs=previous;if(durationNs!=manifest.get("duration_ns").getAsLong())throw new IOException("Duration mismatch");
            }
            if(!manifest.has("event_stream_sha256")||!HexFormat.of().formatHex(digest.digest()).equals(manifest.get("event_stream_sha256").getAsString()))throw new IOException("Event stream hash mismatch");
            events=List.copyOf(all);
            unmappedIncoming=events.stream().filter(e->e.incoming&&e.targetId<0).mapToInt(Event::sourceId).findFirst().orElse(-1);
            Math.addExact(captureMonotonicOriginNs,durationNs);
        }catch(RuntimeException | GeneralSecurityException e){throw new IOException("Invalid demo metadata",e);}
    }
    static long readCaptureClock(JsonObject manifest)throws IOException {
        if(!manifest.has("capture_clock"))return 0;
        JsonObject clock=manifest.getAsJsonObject("capture_clock");
        if(!clock.get("domain").getAsString().equals("perf_counter_ns")||!clock.get("provenance").getAsString().equals("events.jsonl.ts_ns")||!clock.get("source_events_sha256").getAsString().equals(manifest.get("events_sha256").getAsString()))throw new IOException("Unbound capture clock provenance");
        long value=clock.get("origin_ns").getAsBigDecimal().longValueExact();if(value<0)throw new IOException("Negative capture clock origin");return value;
    }
    static ZipEntry required(ZipFile zip,String name)throws IOException {ZipEntry e=zip.getEntry(name);if(e==null)throw new IOException("Missing "+name);return e;}
    static JsonObject json(ZipFile zip,String name)throws IOException {
        ZipEntry entry=required(zip,name);if(entry.getSize()>16*1024*1024)throw new IOException("Oversized "+name);
        try(Reader reader=new InputStreamReader(zip.getInputStream(entry),java.nio.charset.StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
    }
    public static int[] readId(byte[] bytes)throws IOException {int id=0;for(int i=0;i<Math.min(3,bytes.length);i++){id|=(bytes[i]&127)<<(i*7);if((bytes[i]&128)==0){if(id>65535)break;return new int[]{id,i+1};}}throw new IOException("Invalid packet ID");}
    public JsonObject mapping(Event e){JsonElement info=packets.get(Integer.toString(e.sourceId));return info==null?null:info.getAsJsonObject();}
    JsonElement sourceResource(String name){JsonElement value=sourceResources.get(name);return value==null?null:value.deepCopy();}
    public int sourceResourceCount(){return sourceResources.size();}
    public void verifyClass(String name)throws Exception {
        JsonElement expected=hashes.get(name);if(expected==null)throw new IOException("Unbound target class "+name);
        verifyClassBytes(name,expected.getAsString());
    }
    /** Mod implementation dependencies may predate their declaration in an archive. */
    void verifyDependency(String name,String pinnedHash)throws Exception {
        JsonElement declared=hashes.get(name);
        if(declared!=null&&!pinnedHash.equals(declared.getAsString()))throw new IOException("Incompatible native dependency "+name);
        verifyClassBytes(name,pinnedHash);
    }
    private static void verifyClassBytes(String name,String expected)throws Exception {
        try(InputStream in=DemoFile.class.getClassLoader().getResourceAsStream(name+".class")){
            if(in==null)throw new IOException("Missing target class "+name);
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            if(!actual.equals(expected)&&!ReplayClientCompatibility.accepts(name,expected,actual))throw new IOException("Target client changed: "+name+"; reconvert the capture");
        }
    }
    /** A supported dispatcher permits playback; visual certification stays separate. */
    public String playbackBlocker(){
        if(!manifest.has("source_profile")||!manifest.get("source_profile").getAsString().equals("08.10-2026"))return "Для этой версии ещё не установлен проверенный адаптер воспроизведения";
        if(unmappedIncoming>=0)return "Unmapped incoming packet "+unmappedIncoming;
        return null;
    }
}
