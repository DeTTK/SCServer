package replay;

/** Native corpses use a physical bone source, rather than a character animation graph. */
final class SceneRemains {
    static boolean is(Object entity)throws Exception {return Native.type("544082630").isInstance(entity);}
    static Object renderer(Object entity)throws Exception {if(!is(entity))return null;Object renderer=Native.field(entity,"long");return renderer!=null&&Native.type("1326794892").isInstance(renderer)?renderer:null;}
    static SceneTracks.Bytes captureMode(Object entity)throws Exception {
        return new SceneTracks.Bytes(((Enum<?>)Native.field(entity,"import")).name().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    static Object selectedMode(Object entity,SceneTracks.ObjectState state)throws Exception {
        if(!is(entity))return null;
        SceneTracks.Value value=state.channels().get("body-mode");
        if(value!=null){
            if(!(value instanceof SceneTracks.Bytes bytes))throw new java.io.IOException("Invalid corpse mode track");
            String name=new String(bytes.data(),java.nio.charset.StandardCharsets.US_ASCII);
            for(Object mode:Native.type("582607647").getEnumConstants())if(((Enum<?>)mode).name().equals(name))return mode;
            throw new java.io.IOException("Unknown recorded corpse mode: "+name);
        }
        // Older tracks stored physical bones but no mode. Their quarantined spawn
        // can fall back to LEFTOVERS when its former live actor is unavailable.
        // A reconstructed physical renderer must use the recorded body mesh.
        return renderer(entity)!=null?Native.field(Native.type("582607647"),"RAGDOLL"):null;
    }
    static SceneTracks.Bones displayed(Object renderer)throws Exception {
        Object base=Native.call(Native.field(renderer,"double"),"try","1118769848");
        Object source=Native.call(renderer,"native","1408210865");Native.call(source,"case","void",FreeCamera.renderAlpha());
        return read(source,base);
    }
    /** Physical getters reuse scratch vectors: copy each component before asking for another bone. */
    static SceneTracks.Bones read(Object source,Object base)throws Exception {
        SceneTracks.Bones initial=ScenePoseCodec.read(base);float[] p=new float[initial.boneCount()*3],q=new float[initial.boneCount()*4];
        for(int n=0;n<initial.boneCount();n++){
            Object v=Native.call(source,"else","1987590071",n);String[] axes={"assert","byte","catch"};for(int k=0;k<3;k++)p[n*3+k]=((Number)Native.field(v,axes[k])).floatValue();
            Object rotation=Native.call(source,"continue","600627034",n);String[] components={"public","implements","float","catch"};for(int k=0;k<4;k++)q[n*4+k]=((Number)Native.field(rotation,components[k])).floatValue();
        }
        return new SceneTracks.Bones(p,q,initial.curves());
    }
    static Object view(Object pose)throws Exception {return Native.type("577326810").getConstructor(Native.type("1118769848"),float.class).newInstance(pose,1f);}
    static SceneTracks.Bones renderBones(SceneTracks.Bones physical,float scale)throws java.io.IOException {
        if(!Float.isFinite(scale)||scale<=0)throw new java.io.IOException("Invalid corpse render scale");
        float[] positions=physical.positions();for(int n=0;n<positions.length;n++)positions[n]/=scale;
        return new SceneTracks.Bones(positions,physical.rotations(),physical.curves());
    }
}
