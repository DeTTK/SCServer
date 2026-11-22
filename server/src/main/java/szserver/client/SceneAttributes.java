package szserver.client;

import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Bounded current-epoch attribute codec. No native constructors, migrations or IO on gameplay owners. */
public final class SceneAttributes {
    /** 1993742576.it.u: timestamped GOInstance_currentState in the installed wire epoch, shared by stateful factories. */
    public static final int OBJECT_STATE=467;
    private static SceneAttributeSchema.Descriptor descriptor(int id){return id==OBJECT_STATE?new SceneAttributeSchema.Descriptor("java.lang.String",true,0):SceneAttributeSchema.TYPES.get(id);}
    private static boolean permits(Set<Integer> family,Set<Integer> ids){return family!=null&&ids.stream().allMatch(id->id==OBJECT_STATE||family.contains(id));}
    public static String objectState(SceneObject object,String behavior,String fallback){byte[] value=objectStateValue(object,behavior);if(value==null)return fallback;try{var in=Wire.input(value);in.readLong();if(in.readBoolean())return fallback;String state=in.readUTF();return state.isEmpty()?fallback:state;}catch(IOException invalid){throw new IllegalArgumentException("scene_state_value",invalid);}}
    public static long objectStateTime(SceneObject object,String behavior){byte[] value=objectStateValue(object,behavior);return value==null?0:ByteBuffer.wrap(value).getLong();}
    private static byte[] objectStateValue(SceneObject object,String behavior){var prefix=SceneObject.prefix(object.record());if((prefix.packed()&1)==0)return null;byte[] record=object.record();var layout=layout(record,behavior);ByteBuffer in=ByteBuffer.wrap(record);in.position(layout.nbtEnd());return attributes(in).get(OBJECT_STATE);}
    public static byte[] interactionState(SceneObject object,String behavior,String next,long now){if(next==null||next.length()>256||next.indexOf('\0')>=0)throw new IllegalArgumentException("scene_state_name");byte[] record=object.record();var layout=layout(record,behavior);int begin=SceneObject.prefix(record).behaviourAt();return Wire.bytes(out->{out.write(record,begin,layout.nbtEnd()-begin);Wire.varint(out,1);out.writeShort(OBJECT_STATE);out.writeLong(now);out.writeBoolean(false);out.writeUTF(next);});}
    public record Layout(int nbtEnd,int tailAt,SceneGenericState.Layout generic){}
    public static boolean reviewedBehavior(String behavior){return behavior!=null&&SceneAttributeSchema.FAMILIES.containsKey(behavior);}
    public static boolean timestampedBehavior(String behavior){var family=SceneAttributeSchema.FAMILIES.get(behavior);return family!=null&&family.stream().anyMatch(id->SceneAttributeSchema.TYPES.get(id).timestamp());}
    /** Exact current-registry records need no legacy attribute migration. Validate family membership too. */
    public static void validateCurrent(byte[] record,String behavior){
        var layout=layout(record,behavior);var prefix=SceneObject.prefix(record);if((prefix.packed()&1)!=0){ByteBuffer in=ByteBuffer.wrap(record);in.position(layout.nbtEnd());var allowed=behavior==null?null:SceneAttributeSchema.FAMILIES.get(behavior);if(!permits(allowed,attributes(in).keySet()))throw new IllegalArgumentException("scene_configuration_attribute_family");}
    }
    private record State(int nbtEnd,int end,Map<Integer,byte[]> values){}
    public static Layout layout(byte[] record){
        return layout(record,null);
    }
    public static Layout layout(byte[] record,String behavior){
        try{
            var prefix=SceneObject.prefix(record);if((prefix.flags()&0x400)!=0)throw new IllegalArgumentException("scene_configuration_developer_state_unsupported");
            ByteBuffer in=ByteBuffer.wrap(record);in.position(prefix.behaviourAt());int end=SceneSpecialPayload.handles(behavior)?SceneSpecialPayload.end(in,behavior):nbt(in,behavior);if((prefix.packed()&1)!=0)attributes(in);int tail=in.position();var generic=SceneGenericState.read(in,prefix.flags(),prefix.packed());if(in.hasRemaining())throw new IllegalArgumentException("scene_object_trailing");return new Layout(end,tail,generic);
        }catch(BufferUnderflowException invalid){throw new IllegalArgumentException("scene_attribute_truncated",invalid);}
    }
    private static int nbt(ByteBuffer in,String behavior){if(in.get(in.position())!=10)throw new IllegalArgumentException("scene_configuration_nbt_root");int begin=in.position(),end=ScenePayload.namedNbt(in);byte[] body=Arrays.copyOfRange(in.array(),begin,end);SceneConfigurationPackets.validate(body);
        if("incident/spider".equals(behavior)){
            var values=SceneNbt.read(body).values();
            for(String name:List.of("SpiderState","ShieldState")){var tag=values.get(name);if(tag==null)continue;int count=name.equals("SpiderState")?3:2;if(tag.type()!=3||!(tag.value() instanceof Integer value)||value<0||value>=count)throw new IllegalArgumentException("scene_incident_enum:"+name);}
            var health=values.get("Health");if(health!=null&&(health.type()!=5||!(health.value() instanceof Float value)||!Float.isFinite(value)))throw new IllegalArgumentException("scene_incident_health");
        }return end;}
    private static Map<Integer,byte[]> attributes(ByteBuffer in){
        int count=ScenePayload.varint(in);if(count>256||count>in.remaining()/3)throw new IllegalArgumentException("scene_attribute_count");Map<Integer,byte[]> values=new TreeMap<>();
        for(int i=0;i<count;i++){int id=in.getShort()&65535;var descriptor=descriptor(id);if(descriptor==null||values.containsKey(id))throw new IllegalArgumentException("scene_attribute_identity:"+id);int at=in.position();value(in,descriptor);values.put(id,Arrays.copyOfRange(in.array(),at,in.position()));}return values;
    }
    private static State fullSave(byte[] payload){
        return fullSave(payload,null);
    }
    private static State fullSave(byte[] payload,String behavior){
        if(payload.length<2||payload.length>SceneConfigurationPackets.MAX_NBT)throw new IllegalArgumentException("scene_configuration_save_size");try{ByteBuffer in=ByteBuffer.wrap(payload);int end=SceneSpecialPayload.handles(behavior)?SceneSpecialPayload.end(in,behavior):nbt(in,behavior);var values=attributes(in);if(in.hasRemaining())throw new IllegalArgumentException("scene_configuration_save_trailing");return new State(end,in.position(),values);}catch(BufferUnderflowException invalid){throw new IllegalArgumentException("scene_attribute_truncated",invalid);}
    }
    public static void validateFullSave(byte[] payload){fullSave(payload);}
    public static void validateFullSave(byte[] payload,String behavior){fullSave(payload,behavior);}
    /** FULL_SAVE sets listed native attributes; attributes omitted from a partial save retain their values. */
    public static SceneObject configured(SceneObject object,byte[] payload,String behavior){
        Set<Integer> allowed=behavior==null?null:SceneAttributeSchema.FAMILIES.get(behavior);if(allowed==null)throw new IllegalArgumentException("scene_configuration_behavior_unsupported");State save=fullSave(payload,behavior);if(!permits(allowed,save.values().keySet()))throw new IllegalArgumentException("scene_configuration_attribute_family");
        byte[] record=object.record();var prefix=SceneObject.prefix(record);Layout layout=layout(record,behavior);ByteBuffer input=ByteBuffer.wrap(record);input.position(layout.nbtEnd());Map<Integer,byte[]> values=(prefix.packed()&1)!=0?attributes(input):new TreeMap<>();if(!permits(allowed,values.keySet()))throw new IllegalArgumentException("scene_configuration_attribute_family");values.putAll(save.values());int packed=values.isEmpty()?prefix.packed():prefix.packed()|1;
        byte[] body=Arrays.copyOf(payload,save.nbtEnd());if(SceneSpecialPayload.handles(behavior))body=SceneSpecialConfiguration.retainOmitted(Arrays.copyOfRange(record,prefix.behaviourAt(),layout.nbtEnd()),body,behavior);final byte[] mergedBody=body;
        byte[] changed=Wire.bytes(out->{out.write(record,0,prefix.behaviourAt());out.write(mergedBody);if((packed&1)!=0){Wire.varint(out,values.size());for(var entry:values.entrySet()){out.writeShort(entry.getKey());out.write(entry.getValue());}}out.write(record,layout.tailAt(),record.length-layout.tailAt());});if(changed.length>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_object_bounds");changed[6]=(byte)packed;return new SceneObject(object.asset(),changed);
    }
    static SceneObject configuredBody(SceneObject object,byte[] body,String behavior){byte[] record=object.record();var prefix=SceneObject.prefix(record);var layout=layout(record,behavior);byte[] changed=Wire.concat(Arrays.copyOf(record,prefix.behaviourAt()),body,Arrays.copyOfRange(record,layout.nbtEnd(),record.length));if(changed.length>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_object_bounds");validateCurrent(changed,behavior);return new SceneObject(object.asset(),changed);}
    private static void finite(float value){if(!Float.isFinite(value))throw new IllegalArgumentException("scene_attribute_nonfinite");}
    private static void vector(ByteBuffer in){for(int i=0;i<3;i++)finite(in.getFloat());}
    private static void utf(ByteBuffer in){byte[] bytes=ScenePayload.utf(in);try{Wire.input(bytes).readUTF();}catch(IOException invalid){throw new IllegalArgumentException("scene_attribute_utf",invalid);}}
    private static void nullable(ByteBuffer in,int size){if(!ScenePayload.bool(in))ScenePayload.take(in,size);}
    private static void value(ByteBuffer in,SceneAttributeSchema.Descriptor descriptor){
        if(descriptor.timestamp())in.getLong();if(ScenePayload.bool(in))return;
        if(descriptor.enumCount()>0){int ordinal=in.getShort()&65535;if(ordinal>=descriptor.enumCount())throw new IllegalArgumentException("scene_attribute_enum");return;}
        switch(descriptor.type()){
            case "java.lang.Boolean"->ScenePayload.bool(in);case "java.lang.Byte"->in.get();case "java.lang.Short","java.lang.Character"->in.getShort();case "java.lang.Integer"->in.getInt();case "java.lang.Float"->finite(in.getFloat());case "java.lang.Double"->{if(!Double.isFinite(in.getDouble()))throw new IllegalArgumentException("scene_attribute_nonfinite");}
            case "java.lang.Long","java.time.Instant","java.time.Duration","862285083"->in.getLong();case "java.util.UUID"->ScenePayload.take(in,16);case "java.lang.String"->utf(in);
            case "1839922469"->{utf(in);utf(in);ScenePayload.bool(in);ScenePayload.bool(in);}case "438226217"->{if(in.get()!=1)throw new IllegalArgumentException("scene_attribute_version");utf(in);utf(in);}case "792097079"->{utf(in);utf(in);utf(in);nullable(in,16);nullable(in,8);ScenePayload.bool(in);ScenePayload.bool(in);ScenePayload.bool(in);}
            case "1992787979"->{in.getInt();in.getInt();ScenePayload.bool(in);in.getLong();}case "450400847"->{if(!ScenePayload.bool(in))utf(in);in.getInt();in.getInt();ScenePayload.bool(in);}
            case "211097523"->SceneItemValues.take(in);
            default->throw new IllegalArgumentException("scene_attribute_type:"+descriptor.type());
        }
    }
    private SceneAttributes(){}
}
