package replay;
import java.io.IOException;import java.util.*;import java.util.function.LongSupplier;

/** Native model/material/texture references, independent of camera and LOD selection. */
final class SceneMapAssets implements AutoCloseable {
    interface Access {
        void retain(Object ref)throws Exception;
        void release(Object ref)throws Exception;
        boolean ready(Object model,SceneMapAssets owner)throws Exception;
    }
    final Access access;
    final Set<Object> retained=Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Object> residentTextures=Collections.newSetFromMap(new IdentityHashMap<>());
    long textureBudget;
    final List<Object> models=new ArrayList<>();final Set<Object> known=Collections.newSetFromMap(new IdentityHashMap<>());
    int cursor;boolean closed;
    SceneMapAssets(){this(NATIVE);}
    SceneMapAssets(Access access){this.access=access;}
    void retain(Object ref)throws Exception{if(ref!=null&&retained.add(ref))try{access.retain(ref);}catch(Exception error){retained.remove(ref);throw error;}}
    void model(Object model)throws Exception{if(model==null||known.contains(model))return;retain(model);known.add(model);models.add(model);}
    boolean texture(Object texture)throws Exception {
        if(texture==null)return true;retain(texture);
        Native.call(texture,"goto","void",0);
        boolean ready=(boolean)Native.call(texture,"catch","boolean")&&(boolean)Native.call(texture,"extends","boolean",0);
        if((boolean)Native.call(texture,"float","boolean"))return true; // Native broken-texture fallback.
        if(residentTextures.contains(texture))return ready;
        Object resource=Native.call(texture,"float","java.lang.Object");if(resource==null)return false;
        Object metadata=Native.field(resource,"const");if(metadata==null)return false;
        int levels=((Number)Native.field(metadata,"public")).intValue();int faces=(int)Native.call(metadata,"strictfp","int");
        if(levels<1||levels>32||faces!=1&&faces!=6)throw new IOException("Invalid recorded texture mip layout");
        long bytes=0;
        for(int level=0;level<levels;level++){int amount=(int)Native.call(metadata,"package","int",level);if(amount<0)throw new IOException("Invalid recorded texture mip size");bytes=Math.addExact(bytes,Math.multiplyExact((long)amount,faces));}
        if(textureBudget==0)textureBudget=nativeTextureBudget();
        ReplayRender.retainSceneTexture(texture,bytes,textureBudget);residentTextures.add(texture);return ready;
    }
    static long nativeTextureBudget()throws Exception {
        long budget=512L*1024*1024;
        Object capabilities=Native.call(Native.type("org.lwjgl.opengl.GL"),"getCapabilities","org.lwjgl.opengl.GLCapabilities");
        if(Boolean.TRUE.equals(Native.field(capabilities,"GL_NVX_gpu_memory_info"))){long dedicated=((Number)Native.call(Native.type("org.lwjgl.opengl.GL11"),"glGetInteger","int",0x9047)).longValue()*1024;if(dedicated>0)budget=Math.max(budget,Math.min(3L*1024*1024*1024,dedicated/3));}
        return budget;
    }
    void prefab(Object prefab)throws Exception {
        Object descriptor=Native.field(prefab,"class");Object variants=Native.field(descriptor,"a");
        if(variants instanceof Iterable<?> values)for(Object lod:values)lod(lod);
        lod(Native.field(prefab,"c"));
    }
    void lod(Object lod)throws Exception {if(lod!=null)for(Object model:(Object[])Native.field(lod,"try"))model(model);}
    boolean ready(long deadline,LongSupplier clock)throws Exception {
        if(closed)throw new IllegalStateException("Closed map assets");
        while(cursor<models.size()&&clock.getAsLong()<deadline){if(!access.ready(models.get(cursor),this))return false;cursor++;}
        return cursor==models.size();
    }
    Map<String,Object> diagnostic(){return Map.of("models",models.size(),"ready_models",cursor,"retained_references",retained.size(),"resident_textures",residentTextures.size(),"shared_texture_bytes",ReplayRender.sceneTextureBytes(),"texture_budget_bytes",textureBudget);}
    public void close()throws Exception {if(closed)return;closed=true;Exception failure=null;for(Object texture:residentTextures)try{ReplayRender.releaseSceneTexture(texture);}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}residentTextures.clear();for(Object ref:retained)try{access.release(ref);}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}retained.clear();known.clear();models.clear();if(failure!=null)throw failure;}
    private static final Access NATIVE=new Access(){
        public void retain(Object ref)throws Exception{Native.call(ref,"while","void");}
        public void release(Object ref)throws Exception{Native.call(ref,"char","void");}
        public boolean ready(Object model,SceneMapAssets owner)throws Exception{
            boolean ready=(boolean)Native.call(model,"native","boolean",0);
            if(Native.field(model,"finally").toString().equals("BROKEN"))throw new IOException("Recorded map model failed: "+model);
            Object materials=Native.call(Native.field(model,"if"),"float","java.lang.Object");
            if(materials!=null)for(Object material:((Map<?,?>)Native.call(materials,"continue","java.util.Map")).values())
                for(Object texture:(Object[])Native.field(material,"e"))ready&=owner.texture(texture);
            return ready;
        }
    };
}
