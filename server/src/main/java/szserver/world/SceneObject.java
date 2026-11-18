package szserver.world;

import java.nio.*;
import java.util.*;

/** Immutable native object body with a stable asset name and UUID, independent of registry IDs. */
public final class SceneObject {
    public record Prefix(int flags,int packed,int transformAt,int behaviourAt,UUID identity,byte[] nativeName){}
    private final String asset;private final byte[] record;private final Prefix prefix;private final NativePortal.Parameters portalParameters;private final double scale;private final World.Vec halfExtent;
    public SceneObject(String asset,byte[] record){this.asset=Objects.requireNonNull(asset);this.record=record.clone();prefix=prefix(this.record);NativePortal.Parameters parameters=null;try{parameters=NativePortal.Parameters.read(szserver.client.SceneSettings.read(this.record,prefix.behaviourAt()));}catch(RuntimeException malformed){/* Unrelated behavior families may own a different layout; they retain their opaque body. */}portalParameters=parameters;double maximum=0;ByteBuffer in=ByteBuffer.wrap(this.record);for(int axis=0;axis<3;axis++){double squared=0;for(int n=0;n<3;n++){double value=in.getFloat(prefix.transformAt()+(axis*3+n)*4);squared+=value*value;}maximum=Math.max(maximum,Math.sqrt(squared));}scale=maximum==0?1:maximum;double[] extent=new double[3];for(int axis=0;axis<3;axis++)for(int n=0;n<3;n++)extent[axis]+=.5*Math.abs((double)in.getFloat(prefix.transformAt()+(axis*3+n)*4));halfExtent=new World.Vec(extent[0],extent[1],extent[2]);}
    public NativePortal.Parameters portalParameters(){return portalParameters;}
    /** Current active bit read by the installed scene reader; used by native feedback filtering. */
    public boolean feedbackActive(){return (prefix.packed()&4)!=0;}
    public double scale(){return scale;}
    public World.Vec halfExtent(){return halfExtent;}
    public boolean hasCullingReference(){return (prefix.flags()&4)!=0;}
    public String asset(){return asset;}public UUID identity(){return prefix.identity();}public int networkId(){return ByteBuffer.wrap(record).getShort();}
    /** Native per-instance behavior takes precedence over the asset's declared behavior. */
    public String behaviour(String declared){
        if((prefix.flags()&0x200)==0)return declared;
        try{var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(prefix.nativeName()));String value=in.readUTF();if(in.available()!=0)throw new java.io.IOException("trailing behavior name");return value;}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("scene_behavior_name",invalid);}
    }
    public float x(){return ByteBuffer.wrap(record).getFloat(prefix.transformAt()+36);}public float y(){return ByteBuffer.wrap(record).getFloat(prefix.transformAt()+40);}public float z(){return ByteBuffer.wrap(record).getFloat(prefix.transformAt()+44);}
    /** Snapshot budgeting must not copy a potentially large native body. */
    public int recordSize(){return record.length;}
    /** Collision/editor workers need only these48 bytes, not an opaque multi-megabyte body. */
    public float[] affine(){float[] result=new float[12];ByteBuffer in=ByteBuffer.wrap(record);for(int n=0;n<12;n++)result[n]=in.getFloat(prefix.transformAt()+n*4);return result;}
    public byte[] record(){return record.clone();}
    /** Called only on scene access/editor workers. Preserve the exact FULL_SAVE named NBT bytes. */
    public byte[] configuration(){ByteBuffer in=ByteBuffer.wrap(record);in.position(prefix.behaviourAt());int end;try{end=ScenePayload.namedNbt(in);}catch(BufferUnderflowException invalid){throw new IllegalArgumentException("scene_configuration_nbt_truncated",invalid);}return Arrays.copyOfRange(record,prefix.behaviourAt(),end);}
    public SceneObject configured(byte[] payload){
        szserver.client.SceneConfigurationPackets.validate(payload);
        // Validate the reviewed NBT/attribute boundary before replacing only behavior settings.
        szserver.client.SceneAttributes.layout(record);
        byte[] previous=configuration();int end=prefix.behaviourAt()+previous.length;byte[] changed=new byte[record.length-previous.length+payload.length];if(changed.length>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_object_bounds");System.arraycopy(record,0,changed,0,prefix.behaviourAt());System.arraycopy(payload,0,changed,prefix.behaviourAt(),payload.length);System.arraycopy(record,end,changed,prefix.behaviourAt()+payload.length,record.length-end);return new SceneObject(asset,changed);
    }
    public SceneObject shifted(UUID identity,double dx,double dy,double dz){
        byte[] changed=record.clone();ByteBuffer out=ByteBuffer.wrap(changed);int at=prefix.transformAt();out.putLong(at-16,identity.getMostSignificantBits()).putLong(at-8,identity.getLeastSignificantBits());
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(16).putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).array());long handle=Long.remainderUnsigned(ByteBuffer.wrap(hash).getLong(),2500000)+1;out.putInt(at-20,(int)handle);}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        out.putFloat(at+36,(float)(x()+dx)).putFloat(at+40,(float)(y()+dy)).putFloat(at+44,(float)(z()+dz));return new SceneObject(asset,changed);
    }
    @Override public boolean equals(Object other){return other instanceof SceneObject o&&asset.equals(o.asset)&&Arrays.equals(record,o.record);}
    @Override public int hashCode(){return 31*asset.hashCode()+Arrays.hashCode(record);}
    public static Prefix prefix(byte[] record){
        if(record.length<78||record.length>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_object_bounds");ByteBuffer in=ByteBuffer.wrap(record);if(in.getShort()<0)throw new IllegalArgumentException("scene_object_registry_id");int flags=in.getInt(),packed=in.get()&255;
        if((flags&~0x3ffff)!=0||(packed&~7)!=0)throw new IllegalArgumentException("scene_object_presence_flags");
        if((flags&128)!=0)ScenePayload.take(in,16);if((flags&0x8000)!=0)ScenePayload.utf(in);if((flags&0x4000)!=0)in.getInt();if((flags&0x2000)!=0)in.getInt();in.getInt();UUID identity=new UUID(in.getLong(),in.getLong());int transformAt=in.position();
        for(int n=0;n<12;n++){float value=in.getFloat();if(!Float.isFinite(value)||n>=9&&Math.abs(value)>30000000)throw new IllegalArgumentException("scene_object_transform");}
        int kind=in.get()&255;if(kind>2)throw new IllegalArgumentException("scene_object_activation_mode");if((flags&0x1000)!=0)ScenePayload.utf(in);if((flags&0x800)!=0&&((in.get()&255)>4))throw new IllegalArgumentException("scene_object_collision_mode");if((in.get()&255)>4)throw new IllegalArgumentException("scene_object_collision_mode");in.get();byte[] nativeName=(flags&0x200)!=0?ScenePayload.utf(in):new byte[0];
        return new Prefix(flags,packed,transformAt,in.position(),identity,nativeName);
    }
}
