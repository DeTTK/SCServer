package szserver.world;

import java.nio.ByteBuffer;
import java.util.*;

/** Network type identity is a tagged 64-bit name hash, never a catalog ordinal. */
public final class SceneHashWire {
    public static final short MARKER=-1;
    public static final int GROWTH=8;
    public static final int FIRST_SCENE_HANDLE=1<<30;
    private static volatile Map<Integer,SceneRegistry.Definition> catalog;
    private SceneHashWire(){}
    public static void configure(Collection<SceneRegistry.Definition> definitions){
        Map<Integer,SceneRegistry.Definition> ids=new HashMap<>();
        for(var definition:definitions)if(ids.put(definition.id(),definition)!=null)throw new IllegalArgumentException("scene_wire_duplicate_id");
        SceneTypeIds.index(definitions.stream().map(SceneRegistry.Definition::name).toList());
        catalog=Map.copyOf(ids);
    }
    public static byte[] frame(String name,byte[] indexed){
        SceneObject.prefix(indexed);
        return typeFrame(name,indexed);
    }
    private static byte[] typeFrame(String name,byte[] indexed){
        byte[] wire=new byte[indexed.length+GROWTH];
        ByteBuffer.wrap(wire).putShort(MARKER).putLong(SceneTypeIds.persistent(name)).put(indexed,2,indexed.length-2);
        return wire;
    }
    /** Scene UUIDs are stable, but imported instance handles are only unique in their source map. */
    public static final class SceneInstances {
        private final Map<UUID,Integer> handles=new HashMap<>();
        private final Set<Integer> assigned=new HashSet<>();
        synchronized int handle(UUID identity){
            return handles.computeIfAbsent(identity,id->{
                int first=FIRST_SCENE_HANDLE|(id.hashCode()&(FIRST_SCENE_HANDLE-1));
                int candidate=first;
                do{
                    if(assigned.add(candidate))return candidate;
                    candidate=candidate==Integer.MAX_VALUE?FIRST_SCENE_HANDLE:candidate+1;
                }while(candidate!=first);
                throw new IllegalStateException("scene_instance_handle_capacity");
            });
        }
    }
    public static byte[] record(byte[] indexed,SceneInstances instances){
        Map<Integer,SceneRegistry.Definition> definitions=catalog;
        if(definitions==null)return indexed; // Unconfigured standalone codec tools retain their authored format.
        SceneCollision.worker();
        var layout=ScenePayload.parse(indexed);var source=layout.channels();
        List<byte[]> objects=source.objects(),containers=source.containerObjects();
        int objectCount=objects.size()+containers.size();
        ByteBuffer revision=instances==null||objectCount==0?null:ByteBuffer.allocate(16+4*objectCount);
        if(revision!=null){
            if(indexed[8]!=1)throw new IllegalArgumentException("scene_wire_missing_terrain_revision");
            revision.put(indexed,17,16);
        }
        java.util.function.Function<byte[],byte[]> convert=value->{
            var definition=definitions.get(ByteBuffer.wrap(value).getShort()&65535);
            if(definition==null)throw new IllegalArgumentException("scene_wire_unknown_source_type");
            var prefix=SceneObject.prefix(value);
            byte[] wire=typeFrame(definition.name(),value);
            if(revision!=null){
                int handle=instances.handle(prefix.identity());
                ByteBuffer.wrap(wire).putInt(prefix.transformAt()-20+GROWTH,handle);
                revision.putInt(handle);
            }
            return wire;
        };
        byte[] channels=new ScenePayload.Channels(objects.stream().map(convert).toList(),source.raw(),source.enabled(),containers.stream().map(convert).toList(),source.containerNbt(),source.mesh()).encode();
        byte[] result=Arrays.copyOf(indexed,layout.terrainEnd()+channels.length);
        System.arraycopy(channels,0,result,layout.terrainEnd(),channels.length);
        if(revision!=null){
            UUID wireRevision=UUID.nameUUIDFromBytes(revision.array());
            ByteBuffer.wrap(result).putLong(17,wireRevision.getMostSignificantBits())
                .putLong(25,wireRevision.getLeastSignificantBits());
        }
        if(result.length>ScenePayload.MAX_RECORD+ScenePayload.MAX_OBJECTS*GROWTH+32)throw new IllegalArgumentException("scene_wire_record_limit");
        ScenePayload.parseHashWire(result);
        return result;
    }
}
