package szserver.world;

import com.google.gson.*;
import szserver.client.SceneCompatibility;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit stable-name registry translation; immutable manifests loaded before transport starts. */
public final class SceneRegistry {
    public record Definition(int id,String name,String behaviour,String profile,JsonObject definition){}
    public record Manifest(String build,Map<Integer,Definition> ids,Map<String,Definition> names,String digest,SceneTypeIds.Catalog persistent){
        public Manifest(String build,Map<Integer,Definition> ids,Map<String,Definition> names,String digest){this(build,ids,names,digest,new SceneTypeIds.Catalog(ids));}
    }
    private final Manifest source,target;private final Path provenance;private final SceneCompatibility compatibility;
    private final Path targetPath,resources;
    private final Map<String,Manifest> snapshots=new LinkedHashMap<>(8,.75f,true);
    private final AtomicLong translated=new AtomicLong(),unchanged=new AtomicLong(),dropped=new AtomicLong(),records=new AtomicLong();
    /** Native exports place the registry either in region/ or beside that directory. */
    public static Path sourceRegistry(Path map){
        Path region=Files.isDirectory(map.resolve("region"))?map.resolve("region"):map;
        Path local=region.resolve("registry.json");
        if(Files.exists(local)||region.getFileName()==null||!region.getFileName().toString().equals("region"))return local;
        return region.toAbsolutePath().getParent().resolve("registry.json");
    }
    public SceneRegistry(Path map,Path targetRegistry,Path codecs)throws Exception{
        this(map,targetRegistry,codecs,null);
    }
    public SceneRegistry(Path map,Path targetRegistry,Path codecs,Path configuredResources)throws Exception{
        resources=configuredResources==null?null:configuredResources.toRealPath();
        Path sourcePath=sourceRegistry(map);
        if(Files.isDirectory(map.resolve("region")))map=map.resolve("region");
        source=load(sourcePath);targetPath=targetRegistry.toRealPath();target=load(targetPath);provenance=map.resolve("object-registry-provenance");compatibility=new SceneCompatibility(codecs,target);
    }
    public Manifest target(){return target;}
    public Path resources(){return resources;}
    Path targetPath(){return targetPath;}
    public String sourceDigest(){return source.digest();}
    public String checkpointIdentity(){return source.digest()+":"+target.digest()+":"+compatibility.metadataDigest();}
    private SceneRegistry(SceneRegistry other){source=other.source;target=other.target;targetPath=other.targetPath;resources=other.resources;provenance=other.provenance;compatibility=other.compatibility;}
    /** Immutable definitions/codecs can be shared; each IO worker owns its provenance LRU and counters. */
    public SceneRegistry fork(){return new SceneRegistry(this);}
    private SceneRegistry(SceneRegistry other,Path map)throws Exception{source=load(sourceRegistry(map));target=other.target;targetPath=other.targetPath;resources=other.resources;provenance=(Files.isDirectory(map.resolve("region"))?map.resolve("region"):map).resolve("object-registry-provenance");compatibility=other.compatibility;}
    /** A new immutable source map can reuse the initialized target codecs. */
    public SceneRegistry forMap(Path map)throws Exception{return new SceneRegistry(this,map);}
    public static Manifest load(Path path)throws Exception{
        long size=Files.size(path);if(size<1||size>24*1024*1024)throw new IllegalArgumentException("scene_registry_file_limit");byte[] raw=Files.readAllBytes(path);JsonObject document=JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(!document.get("format").getAsString().equals("szserver.stalcraft-polymorphic-registry")||document.get("version").getAsInt()!=1)throw new IllegalArgumentException("scene_registry_format");JsonArray entries=document.getAsJsonArray("entries");if(entries.size()>32768)throw new IllegalArgumentException("scene_registry_entry_limit");Map<Integer,Definition> ids=new HashMap<>();Map<String,Definition> names=new HashMap<>();
        for(JsonElement value:entries){JsonObject row=value.getAsJsonObject();int id=row.get("network_id").getAsInt();String name=row.get("stable_name").getAsString();if(id<0||id>32767||name.isEmpty()||name.length()>1024)throw new IllegalArgumentException("scene_registry_entry");Definition definition=new Definition(id,name,row.get("behaviour").getAsString(),row.get("wire_profile_sha256").getAsString(),row.has("definition")?row.getAsJsonObject("definition"):new JsonObject());if(ids.put(id,definition)!=null||names.put(name,definition)!=null)throw new IllegalArgumentException("scene_registry_duplicate");}
        return new Manifest(document.get("build").getAsString(),Map.copyOf(ids),Map.copyOf(names),digest(raw));
    }
    private static String digest(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private Manifest source(byte[] record)throws Exception{
        String hash=digest(record);Path marker=provenance.resolve("records").resolve(hash.substring(0,2)).resolve(hash+".json");if(!Files.exists(marker))return source;
        if(Files.size(marker)>1024)throw new IllegalArgumentException("scene_provenance_marker_limit");String key=JsonParser.parseString(Files.readString(marker)).getAsJsonObject().get("registry_sha256").getAsString();if(!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("scene_provenance_digest");
        Manifest cached=snapshots.get(key);if(cached!=null)return cached;Manifest loaded=load(provenance.resolve("manifests").resolve(key+".json"));if(!loaded.digest().equals(key))throw new IllegalArgumentException("scene_provenance_hash_mismatch");snapshots.put(key,loaded);while(snapshots.size()>8)snapshots.remove(snapshots.keySet().iterator().next());return loaded;
    }
    /** Single terrain loader owns provenance cache and codec use. */
    public ScenePayload.Channels project(byte[] record,ScenePayload.Channels channels)throws Exception{
        Manifest from=source(record);List<byte[]> blob=translate(channels.objects(),from),container=translate(channels.containerObjects(),from);records.incrementAndGet();return new ScenePayload.Channels(blob,channels.raw(),channels.enabled(),container,channels.containerNbt(),channels.mesh());
    }
    private List<byte[]> translate(List<byte[]> frames,Manifest from){
        List<byte[]> output=new ArrayList<>(frames.size());for(byte[] frame:frames){SceneObject.Prefix prefix=SceneObject.prefix(frame);int old=ByteBuffer.wrap(frame).getShort();Definition original=from.ids().get(old),to;
            if(original==null){to=target.ids().get(old);if(to==null)throw new IllegalArgumentException("scene_unknown_source_id:"+old);}else{to=target.names().get(original.name());if(to==null){dropped.incrementAndGet();continue;}if(!original.behaviour().equals(to.behaviour()))throw new IllegalArgumentException("scene_behaviour_changed:"+original.name());}
            byte[] updated=frame.clone();ByteBuffer.wrap(updated).putShort((short)to.id());
            if(from.build().equals("07.06")&&to.behaviour().isEmpty()&&updated[prefix.behaviourAt()]==0){byte[] expanded=new byte[updated.length+3];System.arraycopy(updated,0,expanded,0,prefix.behaviourAt());expanded[prefix.behaviourAt()]=10;System.arraycopy(updated,prefix.behaviourAt()+1,expanded,prefix.behaviourAt()+4,updated.length-prefix.behaviourAt()-1);updated=expanded;}
            // Existing metadata still owns serializer-epoch migrations, even for equal registry snapshots.
            if(!compatibility.hasMetadata(to.name())&&from.digest().equals(target.digest())&&(prefix.flags()&0x400)==0&&szserver.client.SceneAttributes.reviewedBehavior(to.behaviour()))szserver.client.SceneAttributes.validateCurrent(updated,to.behaviour());else try{updated=compatibility.object(updated,to);}catch(IllegalArgumentException error){throw new IllegalArgumentException("scene_asset_projection:"+to.name()+":flags="+prefix.flags()+":packed="+prefix.packed()+":behaviorAt="+prefix.behaviourAt()+":"+error.getMessage(),error);}new SceneObject(to.name(),updated);output.add(updated);if(old==to.id())unchanged.incrementAndGet();else translated.incrementAndGet();
        }return List.copyOf(output);
    }
    public Map<String,Object> metrics(){return Map.of("source",source.build(),"target",target.build(),"targetSha256",target.digest(),"targetEntries",target.ids().size(),"records",records.get(),"translated",translated.get(),"unchanged",unchanged.get(),"droppedMissingAssets",dropped.get());}
}
