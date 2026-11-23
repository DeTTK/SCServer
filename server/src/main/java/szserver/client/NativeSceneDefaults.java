package szserver.client;

import com.google.gson.*;
import szserver.commands.SceneSeeds;
import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Resource-specific native seeds on one command worker. No provisional behavior or borrowed live object state. */
public final class NativeSceneDefaults implements SceneSeeds,AutoCloseable {
    private static final long MAX_BYTES=4L*1024*1024;
    private final SceneRegistry.Manifest registry;
    private final ClientSceneCodec codec;
    private final Map<String,ScenePayload.Entry> cache=new LinkedHashMap<>(128,.75f,true);
    private final Map<String,String> failures=new LinkedHashMap<>(128,.75f,true);
    private Thread owner;private long bytes,hits,misses,errors;private double maxMs;
    private volatile Map<String,Object> view=Map.of("enabled",true,"ready",false);
    public NativeSceneDefaults(Path runtime,Path resources,SceneRegistry.Manifest registry,Path bindings)throws Exception{
        this.registry=registry;ClientSceneCodec prepared=new ClientSceneCodec(runtime,resources);try{prepared.prepareDefaults(bindings);}catch(Exception error){prepared.close();throw error;}codec=prepared;publish();
    }
    public static Path resourceRoot(Path manifest)throws IOException{long size=RuntimeFiles.size(manifest);if(size<1||size>24L*1024*1024)throw new IOException("scene_registry_file_limit");JsonObject root=JsonParser.parseString(RuntimeFiles.readString(manifest)).getAsJsonObject();if(!root.has("config_root"))throw new IOException("scene_default_resource_root_missing");return Path.of(root.get("config_root").getAsString());}
    /** The reviewed 94-byte generic envelope carries no player/map data and has a neutral transform/identity. */
    public static SceneObject envelope(SceneRegistry.Definition definition){return new SceneObject(definition.name(),Wire.bytes(out->{out.writeShort(definition.id());out.writeInt(0x6000);out.writeByte(4);out.writeInt(15);out.writeInt(0x00f00000);out.writeInt(1);out.writeLong(0);out.writeLong(0);for(int i=0;i<12;i++)out.writeFloat(i==0||i==4||i==8?1:0);out.writeByte(0);out.writeByte(1);out.writeByte(1);out.write(new byte[]{10,0,0,0});out.write(new byte[4]);}));}
    /** Legacy creation channel choice, now backed by real selected behavior defaults. */
    public static boolean container(SceneRegistry.Definition definition){JsonObject properties=definition.definition();return !definition.behaviour().isEmpty()||flag(properties,"animated")||flag(properties,"has_interact")||properties.has("default_state")&&!properties.get("default_state").isJsonNull();}
    private static boolean flag(JsonObject properties,String key){return properties.has(key)&&!properties.get(key).isJsonNull()&&properties.get(key).getAsBoolean();}
    @Override public ScenePayload.Entry create(String asset)throws Exception{
        if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("scene_default_worker_owner");SceneRegistry.Definition definition=registry.names().get(asset);if(definition==null)throw new IllegalArgumentException("scene_editor_unknown_asset");
        boolean timed=SceneAttributes.timestampedBehavior(definition.behaviour());ScenePayload.Entry existing=timed?null:cache.get(asset);if(existing!=null){hits++;publish();return existing;}
        String failed=failures.get(asset);if(failed!=null){hits++;publish();throw new IllegalArgumentException("scene_default_factory_failed:"+asset+":"+failed);}
        long start=System.nanoTime();misses++;
        try{SceneObject object=codec.defaultObject(definition,envelope(definition));SceneAttributes.validateCurrent(object.record(),definition.behaviour());if(object.networkId()!=definition.id()||!object.identity().equals(new UUID(0,0))||object.x()!=0||object.y()!=0||object.z()!=0)throw new IllegalStateException("scene_default_native_envelope_changed");ScenePayload.Entry entry=new ScenePayload.Entry(container(definition),object);if(!timed){cache.put(asset,entry);bytes+=object.record().length;while(cache.size()>128||bytes>MAX_BYTES){var first=cache.entrySet().iterator().next();bytes-=first.getValue().object().record().length;cache.remove(first.getKey());}}return entry;}
        catch(Exception|LinkageError error){errors++;Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();String detail=cause.getClass().getSimpleName()+":"+String.valueOf(cause.getMessage());if(detail.length()>256)detail=detail.substring(0,256);failures.put(asset,detail);while(failures.size()>128)failures.remove(failures.keySet().iterator().next());throw new IllegalArgumentException("scene_default_factory_failed:"+asset+":"+detail,error);}
        finally{maxMs=Math.max(maxMs,(System.nanoTime()-start)/1e6);publish();}
    }
    private void publish(){view=Map.of("enabled",true,"ready",true,"entries",cache.size(),"bytes",bytes,"failedAssets",failures.size(),"hits",hits,"misses",misses,"errors",errors,"maxFactoryMs",maxMs);}
    @Override public Map<String,Object> metrics(){return view;}
    @Override public void close()throws IOException{codec.close();}
}
