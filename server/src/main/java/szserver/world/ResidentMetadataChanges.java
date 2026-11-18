package szserver.world;

import java.io.*;
import java.util.*;

/** Only touched identities, including all their physical culling references and logical portal state. */
public final class ResidentMetadataChanges {
    private static final int MAGIC=0x535a4d44,VERSION=1,MAX=48*1024*1024;
    private final Set<UUID> ids;
    private final ResidentWorldMetadata.Snapshot before,after;
    private final byte[] encoded;
    private ResidentMetadataChanges(Set<UUID> ids,ResidentWorldMetadata.Snapshot before,ResidentWorldMetadata.Snapshot after)throws IOException{
        SceneCollision.worker();this.ids=Set.copyOf(ids);this.before=before;this.after=after;
        if(ids.isEmpty()||ids.size()>SceneLimits.IDENTITIES||!before.identity().equals(after.identity())||!before.registry().equals(after.registry()))throw new IOException("resident_metadata_delta_binding");
        if(!before.sameIndexes(before.select(ids))||!after.sameIndexes(after.select(ids)))throw new IOException("resident_metadata_delta_scope");
        long bound=20L+ids.size()*16L+before.payload().bytes()+after.payload().bytes();if(bound>MAX)throw new IOException("resident_metadata_delta_size");
        var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(ids.size());for(UUID id:new TreeSet<>(ids)){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}for(var snapshot:List.of(before,after)){byte[] body=snapshot.encode();out.writeInt(body.length);out.write(body);}}encoded=buffer.toByteArray();
    }
    public static ResidentMetadataChanges prepare(World.SceneMetadataView view,World next,Set<UUID> ids){
        try{return new ResidentMetadataChanges(ids,ResidentWorldMetadata.capture(view).select(ids),ResidentWorldMetadata.capture(next).select(ids));}catch(IOException invalid){throw new IllegalArgumentException("resident_metadata_delta",invalid);}
    }
    public byte[] encode(){return encoded.clone();}
    public int bytes(){return encoded.length;}
    public static ResidentMetadataChanges decode(byte[] bytes)throws IOException{
        if(bytes.length<20||bytes.length>MAX)throw new IOException("resident_metadata_delta_size");try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("resident_metadata_delta_version");int count=in.readInt();if(count<1||count>SceneLimits.IDENTITIES)throw new IOException("resident_metadata_delta_count");Set<UUID> ids=new HashSet<>();for(int n=0;n<count;n++)if(!ids.add(new UUID(in.readLong(),in.readLong())))throw new IOException("resident_metadata_delta_duplicate");var values=new ArrayList<ResidentWorldMetadata.Snapshot>();for(int n=0;n<2;n++){int size=in.readInt();if(size<68||size>in.available())throw new IOException("resident_metadata_delta_length");values.add(ResidentWorldMetadata.decode(in.readNBytes(size)));}if(in.available()!=0)throw new IOException("resident_metadata_delta_trailing");return new ResidentMetadataChanges(ids,values.get(0),values.get(1));}
    }
    public World.SceneMetadataPrepared inverse(World.SceneMetadataView view){
        SceneCollision.worker();var current=ResidentWorldMetadata.capture(view);if(!current.select(ids).sameIndexes(after))throw new IllegalArgumentException("resident_scene_metadata_undo_compare_failed");
        var restored=current.replace(ids,before).prepareRestore(view);try{return new World.SceneMetadataPrepared(view,restored.detached(),new ResidentMetadataChanges(ids,after,before));}catch(IOException invalid){throw new IllegalArgumentException("resident_metadata_delta",invalid);}
    }
}
