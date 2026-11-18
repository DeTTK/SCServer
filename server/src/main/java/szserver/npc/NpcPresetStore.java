package szserver.npc;

import szserver.client.*;import com.google.gson.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.nio.channels.*;import java.nio.*;import java.io.*;import java.util.*;

/** Lossless native settings. Unknown fields survive edits; references are stable UUIDs. */
public final class NpcPresetStore implements AutoCloseable {
    public record Preset(UUID id,int index,String key,String name,String description,byte[] config,byte[] entity){}
    public record Reply(List<byte[]> packets,boolean changed,UUID selected){}
    private final NpcEditorCodec codec;private final Path file;private Map<UUID,Preset> rows=new LinkedHashMap<>();private long revision;
    public NpcPresetStore(Path runtime,Path data)throws Exception{
        codec=new NpcEditorCodec(runtime);file=data.resolve("npc-presets.json");JsonObject json;
        if(Files.exists(file)){if(Files.size(file)>4*1024*1024)throw new IOException("npc_preset_store_limit");json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();revision=json.get("revision").getAsLong();}
        else{try(var in=NpcPresetStore.class.getResourceAsStream("npc-editor-defaults.json")){if(in==null)throw new IOException("npc_editor_defaults_missing");json=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}}
        if(json.get("version").getAsInt()!=1)throw new IOException("npc_preset_version");Set<Integer> indices=new HashSet<>();Set<String> keys=new HashSet<>();
        for(var entry:json.getAsJsonArray("profiles")){var o=entry.getAsJsonObject();String key=o.get("key").getAsString();int index=o.get("index").getAsInt();UUID id=o.has("id")?UUID.fromString(o.get("id").getAsString()):UUID.nameUUIDFromBytes(("sz-npc:"+key).getBytes(StandardCharsets.UTF_8));var p=new Preset(id,index,key,o.has("name")?o.get("name").getAsString():key,o.has("description")?o.get("description").getAsString():"",Base64.getDecoder().decode(o.get("config").getAsString()),Base64.getDecoder().decode(o.get("entity").getAsString()));if(rows.put(id,p)!=null||!indices.add(index)||!keys.add(key)||rows.size()>128)throw new IOException("npc_preset_identity");SceneNbt.readTile(p.entity);var visual=codec.profile(index,key,p.config,NpcVisuals.profiles().get(Math.min(index,9)));NpcVisuals.install(visual);}
    }
    public long revision(){return revision;}
    public List<Preset> presets(){return List.copyOf(rows.values());}
    private Preset required(UUID id){Preset p=rows.get(id);if(p==null)throw new IllegalArgumentException("npc_preset_missing");return p;}
    private UUID reference(Object packet)throws Exception{for(var f:packet.getClass().getFields())if(f.getType().getName().equals("1058130208"))return codec.refId(f.get(packet));throw new IllegalArgumentException("npc_reference_missing");}
    public byte[] list()throws Exception{return codec.list(rows.values().stream().map(p->new NpcEditorCodec.Summary(p.id,p.key,p.name,p.description)).toList());}
    public Reply nativeRequest(String name,byte[] bytes,long expected,UUID selected)throws Exception{
        Object packet=codec.decode(name,bytes);List<byte[]> replies=new ArrayList<>();boolean changed=false;UUID nextSelected=selected;
        switch(name){
            case "536730109"->{UUID id=reference(packet);required(id);replies.add(codec.locations(id));}
            case "1021019243"->{UUID id=reference(packet);Preset p=required(id);nextSelected=id;replies.add(codec.open(id,p.entity));}
            case "1785466534"->{if(selected==null)throw new IllegalArgumentException("open_npc_preset_first");replies.add(codec.component(NpcEditorCodec.field(packet,"double"),required(selected).config));}
            case "482715697","1450003595"->{/* Native editor selects a working context, then flushes after each component apply. */}
            case "466145232"->{if(selected==null)throw new IllegalArgumentException("open_npc_preset_first");Preset p=required(selected);byte[] delta=codec.nbt(NpcEditorCodec.field(packet,"private"));var config=new LinkedHashMap<>(SceneNbt.readTile(p.config).values());config.putAll(SceneNbt.readTile(delta).values());var entity=new LinkedHashMap<>(SceneNbt.readTile(p.entity).values());entity.putAll(SceneNbt.readTile(delta).values());Preset next=new Preset(p.id,p.index,p.key,p.name,p.description,SceneNbt.writeTile(new SceneNbt.Root("",config)),SceneNbt.writeTile(new SceneNbt.Root("",entity)));commit(expected,next);changed=true;}
            case "1809022406","1495851331","923493987"->{Preset p=required(reference(packet));String value=(String)NpcEditorCodec.field(packet,name.equals("1495851331")?"boolean":"continue");if(value.length()>2048)throw new IllegalArgumentException("npc_metadata_limit");String key=name.equals("1809022406")?value:p.key;if(!key.matches("[a-zA-Z0-9_/-]{1,80}"))throw new IllegalArgumentException("npc_preset_key");Preset next=new Preset(p.id,p.index,key,name.equals("1495851331")?value:p.name,name.equals("923493987")?value:p.description,p.config,p.entity);commit(expected,next);changed=true;replies.add(list());}
            case "72832071"->{Preset p=required(reference(packet));if(rows.size()>=128)throw new IllegalArgumentException("npc_preset_capacity");int index=rows.values().stream().mapToInt(Preset::index).max().orElse(9)+1;String key=p.key+"_copy_"+index;Preset next=new Preset(UUID.randomUUID(),index,key,p.name+" copy",p.description,p.config.clone(),p.entity.clone());commit(expected,next);changed=true;nextSelected=next.id;replies.add(list());}
            default->throw new IllegalArgumentException("native_npc_operation_not_implemented:"+name);
        }
        return new Reply(List.copyOf(replies),changed,nextSelected);
    }
    private void commit(long expected,Preset value)throws Exception{
        if(expected!=revision)throw new IllegalArgumentException("npc_preset_revision_conflict_reload");if(rows.values().stream().anyMatch(p->!p.id.equals(value.id)&&p.key.equals(value.key)))throw new IllegalArgumentException("npc_preset_key_duplicate");NpcVisuals.Profile visual=codec.profile(value.index,value.key,value.config,NpcVisuals.profiles().get(0));var next=new LinkedHashMap<>(rows);next.put(value.id,value);var o=new JsonObject();o.addProperty("version",1);o.addProperty("revision",revision+1);var list=new JsonArray();for(Preset p:next.values()){var j=new JsonObject();j.addProperty("id",p.id.toString());j.addProperty("index",p.index);j.addProperty("key",p.key);j.addProperty("name",p.name);j.addProperty("description",p.description);j.addProperty("config",Base64.getEncoder().encodeToString(p.config));j.addProperty("entity",Base64.getEncoder().encodeToString(p.entity));list.add(j);}o.add("profiles",list);byte[] bytes=o.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024)throw new IllegalArgumentException("npc_preset_store_limit");Files.createDirectories(file.getParent());Path temp=file.resolveSibling("npc-presets.json.tmp");try{try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);rows=next;revision++;NpcVisuals.install(visual);}finally{Files.deleteIfExists(temp);}
    }
    public void close()throws Exception{codec.close();}
}
