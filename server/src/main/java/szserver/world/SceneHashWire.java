package szserver.world;

import java.nio.ByteBuffer;
import java.util.*;

/** Network type identity is a tagged 64-bit name hash, never a catalog ordinal. */
public final class SceneHashWire {
    public static final short MARKER=-1;
    public static final int GROWTH=8;
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
        byte[] wire=new byte[indexed.length+GROWTH];
        ByteBuffer.wrap(wire).putShort(MARKER).putLong(SceneTypeIds.persistent(name)).put(indexed,2,indexed.length-2);
        return wire;
    }
    public static byte[] record(byte[] indexed){
        Map<Integer,SceneRegistry.Definition> definitions=catalog;
        if(definitions==null)return indexed; // Unconfigured standalone codec tools retain their authored format.
        SceneCollision.worker();
        var layout=ScenePayload.parse(indexed);var source=layout.channels();
        java.util.function.Function<byte[],byte[]> convert=value->{
            var definition=definitions.get(ByteBuffer.wrap(value).getShort()&65535);
            if(definition==null)throw new IllegalArgumentException("scene_wire_unknown_source_type");
            return frame(definition.name(),value);
        };
        byte[] channels=new ScenePayload.Channels(source.objects().stream().map(convert).toList(),source.raw(),source.enabled(),source.containerObjects().stream().map(convert).toList(),source.containerNbt(),source.mesh()).encode();
        byte[] result=Arrays.copyOf(indexed,layout.terrainEnd()+channels.length);
        System.arraycopy(channels,0,result,layout.terrainEnd(),channels.length);
        if(result.length>ScenePayload.MAX_RECORD+ScenePayload.MAX_OBJECTS*GROWTH+32)throw new IllegalArgumentException("scene_wire_record_limit");
        ScenePayload.parseHashWire(result);
        return result;
    }
}
