package szserver.client;

import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Canonical specialized behavior NBT bridge. Runs on workers without game factories or disk IO. */
public final class SceneSpecialConfiguration {
    private static final String[] DIRECTIONS={"ALL","FROM_ORIGIN","TO_ORIGIN"},REELS={"PLAYER","FROM_ORIGIN","TO_ORIGIN"};
    private static final String[] CABLE_FLOATS={"width","uvLengthMultiplier","weight","rigidity","length"},SLOW={"speedModifier","startDistance","exponent","resultSpeed"};
    public static byte[] configuration(SceneObject object,String behavior){
        if(!SceneSpecialPayload.handles(behavior))return object.configuration();
        return SceneNbt.write(new SceneNbt.Root("",state(object,behavior)));
    }
    public static SceneObject configured(SceneObject object,byte[] nbt,String behavior){
        if(!SceneSpecialPayload.handles(behavior))return object.configured(nbt);
        Map<String,SceneNbt.Tag> input=SceneNbt.read(nbt).values(),values=state(object,behavior);
        if(behavior.equals("curve")&&input.containsKey("nodesCount"))curveGroup(input);
        // These three canonical imports assign absent scalar keys to defaults; cables/curves use presence checks.
        if(behavior.equals("lootable")){values.put("empty",input.getOrDefault("empty",SceneNbt.number(1,(byte)0)));values.put("overriddenPresetId",input.getOrDefault("overriddenPresetId",SceneNbt.string("")));}
        else if(behavior.equals("prestige/respawn_marker")){values.put("respawnName",input.getOrDefault("respawnName",SceneNbt.string("")));values.put("killedByEnemy",input.getOrDefault("killedByEnemy",SceneNbt.number(1,(byte)0)));}
        else if(behavior.equals("modeller_mesh")){values.put("meshName",input.getOrDefault("meshName",SceneNbt.string("")));if(input.containsKey("meshData")&&!bytes(input,"meshData").isEmpty())values.put("meshData",input.get("meshData"));}
        else for(var entry:input.entrySet())if(values.containsKey(entry.getKey())&&!(behavior.equals("curve")&&!input.containsKey("nodesCount")&&entry.getKey().startsWith("curve")&&!entry.getKey().equals("curveCuboid")))values.put(entry.getKey(),entry.getValue());
        byte[] body=body(values,behavior);
        // Preserve source representation and journal size when a native settings dialog saves unchanged values.
        if(state(object,behavior).equals(readBody(body,behavior)))return object;
        return SceneAttributes.configuredBody(object,body,behavior);
    }
    static byte[] retainOmitted(byte[] previous,byte[] incoming,String behavior){
        if(behavior.equals("lootable")&&(previous[0]&2)!=0&&(incoming[0]&2)==0)return Wire.bytes(out->{out.writeByte(incoming[0]|2);out.write(previous,1,previous.length-1);});
        if(behavior.equals("modeller_mesh")&&previous[0]==0&&incoming[0]==1){ByteBuffer old=ByteBuffer.wrap(previous);old.get();SceneSpecialPayload.mesh(old);return Wire.concat(new byte[]{0},Arrays.copyOfRange(incoming,1,incoming.length),Arrays.copyOfRange(previous,old.position(),previous.length));}
        return incoming;
    }
    private static Map<String,SceneNbt.Tag> state(SceneObject object,String behavior){var layout=SceneAttributes.layout(object.record(),behavior);return readBody(Arrays.copyOfRange(object.record(),SceneObject.prefix(object.record()).behaviourAt(),layout.nbtEnd()),behavior);}
    private static Map<String,SceneNbt.Tag> readBody(byte[] body,String behavior){
        ByteBuffer in=ByteBuffer.wrap(body);Map<String,SceneNbt.Tag> values=new LinkedHashMap<>();
        switch(behavior){
            case "lootable"->{int flags=in.get()&255;put(values,"empty",1,(byte)(flags&1));values.put("overriddenPresetId",SceneNbt.string((flags&2)!=0?utf(in):""));}
            case "cable2d"->{put(values,"textureIndex",3,in.getInt());cable(in,values);}
            case "cable3d"->{shape(in,values);cable(in,values);}
            case "zipline"->{put(values,"textureIndex",3,in.getInt());values.put("direction",SceneNbt.string(DIRECTIONS[in.getInt()]));slow(in,values);shape(in,values);cable(in,values);}
            case "zipline_vertical"->{cable(in,values);shape(in,values);put(values,"ziplineVerticalEndOffset",5,in.getFloat());floats(in,values,"ziplineVerticalEjectDirForward",3);floats(in,values,"ziplineVerticalEjectDirBackward",3);values.put("direction",SceneNbt.string(DIRECTIONS[in.getInt()]));values.put("reelSide",SceneNbt.string(REELS[in.getInt()]));slow(in,values);}
            case "curve"->{int count=in.getInt();put(values,"nodesCount",3,count);String[] keys={"curvePositions","curveDirections","curveUp","curveScales","curveRoll","curvePointsPerSegment"};List<List<Integer>> arrays=new ArrayList<>();for(int i=0;i<6;i++)arrays.add(new ArrayList<>());for(int i=0;i<count;i++){int[] lengths={3,3,3,2,1,1};for(int j=0;j<6;j++)for(int k=0;k<lengths[j];k++)arrays.get(j).add(in.getInt());}for(int i=0;i<6;i++)values.put(keys[i],SceneNbt.ints(arrays.get(i)));shape(in,values);put(values,"uvLengthMultiplier",5,in.getFloat());floats(in,values,"curveCuboid",6);}
            case "prestige/respawn_marker"->{int end=ScenePayload.namedNbt(in);values.putAll(SceneNbt.read(Arrays.copyOf(body,end)).values());values.put("respawnName",SceneNbt.string(utf(in)));put(values,"killedByEnemy",1,(byte)(ScenePayload.bool(in)?1:0));}
            case "modeller_mesh"->{int mode=in.get()&255,start=in.position();SceneSpecialPayload.mesh(in);values.put("meshData",SceneNbt.bytes(Arrays.copyOfRange(body,start,in.position())));values.put("meshName",SceneNbt.string(mode==0?utf(in):""));}
            default->throw new IllegalArgumentException("scene_configuration_behavior_unsupported");
        }if(in.hasRemaining())throw new IllegalArgumentException("scene_configuration_behavior_trailing");return values;
    }
    private static void cable(ByteBuffer in,Map<String,SceneNbt.Tag> values){floats(in,values,"firstPoint",3);floats(in,values,"secondPoint",3);put(values,"width",5,in.getFloat());put(values,"segmentsCount",3,in.getInt());for(int i=1;i<CABLE_FLOATS.length;i++)put(values,CABLE_FLOATS[i],5,in.getFloat());floats(in,values,"cableCuboid",6);}
    private static void shape(ByteBuffer in,Map<String,SceneNbt.Tag> values){floats(in,values,"shapeVertices",in.getInt());floats(in,values,"shapeLines",in.getInt());}
    private static void slow(ByteBuffer in,Map<String,SceneNbt.Tag> values){for(String key:SLOW)put(values,key,6,in.getDouble());}
    private static void floats(ByteBuffer in,Map<String,SceneNbt.Tag> values,String key,int count){List<Integer> list=new ArrayList<>(count);for(int i=0;i<count;i++)list.add(in.getInt());values.put(key,SceneNbt.ints(list));}
    private static void put(Map<String,SceneNbt.Tag> values,String key,int type,Number value){values.put(key,SceneNbt.number(type,value));}
    private static byte[] body(Map<String,SceneNbt.Tag> values,String behavior){
        byte[] bytes=Wire.bytes(out->{switch(behavior){
            case "lootable"->{out.writeByte((bool(values,"empty")?1:0)|2);out.writeUTF(string(values,"overriddenPresetId"));}
            case "cable2d"->{out.writeInt(integer(values,"textureIndex"));writeCable(out,values);}
            case "cable3d"->{writeShape(out,values);writeCable(out,values);}
            case "zipline"->{out.writeInt(integer(values,"textureIndex"));out.writeInt(ordinal(values,"direction",DIRECTIONS));writeSlow(out,values);writeShape(out,values);writeCable(out,values);}
            case "zipline_vertical"->{writeCable(out,values);writeShape(out,values);out.writeFloat(floating(values,"ziplineVerticalEndOffset"));writeFloats(out,values,"ziplineVerticalEjectDirForward",3);writeFloats(out,values,"ziplineVerticalEjectDirBackward",3);out.writeInt(ordinal(values,"direction",DIRECTIONS));out.writeInt(ordinal(values,"reelSide",REELS));writeSlow(out,values);}
            case "curve"->{curveGroup(values);int count=integer(values,"nodesCount");out.writeInt(count);String[] keys={"curvePositions","curveDirections","curveUp","curveScales","curveRoll","curvePointsPerSegment"};int[] lengths={3,3,3,2,1,1};for(int i=0;i<count;i++)for(int j=0;j<6;j++)for(int k=0;k<lengths[j];k++)out.writeInt(ints(values,keys[j]).get(i*lengths[j]+k));writeShape(out,values);out.writeFloat(floating(values,"uvLengthMultiplier"));writeFloats(out,values,"curveCuboid",6);}
            case "prestige/respawn_marker"->{out.write(SceneNbt.write(new SceneNbt.Root("",values)));out.writeUTF(string(values,"respawnName"));out.writeBoolean(bool(values,"killedByEnemy"));}
            case "modeller_mesh"->{out.writeByte(0);for(byte b:bytes(values,"meshData"))out.writeByte(b);out.writeUTF(string(values,"meshName"));}
            default->throw new IllegalArgumentException("scene_configuration_behavior_unsupported");
        }});ByteBuffer in=ByteBuffer.wrap(bytes);try{SceneSpecialPayload.end(in,behavior);if(in.hasRemaining())throw new IllegalArgumentException("scene_configuration_behavior_trailing");}catch(BufferUnderflowException invalid){throw new IllegalArgumentException("scene_configuration_behavior_truncated",invalid);}return bytes;
    }
    private static void writeCable(DataOutputStream out,Map<String,SceneNbt.Tag> values)throws IOException{writeFloats(out,values,"firstPoint",3);writeFloats(out,values,"secondPoint",3);out.writeFloat(floating(values,"width"));out.writeInt(integer(values,"segmentsCount"));for(int i=1;i<CABLE_FLOATS.length;i++)out.writeFloat(floating(values,CABLE_FLOATS[i]));writeFloats(out,values,"cableCuboid",6);}
    private static void writeShape(DataOutputStream out,Map<String,SceneNbt.Tag> values)throws IOException{for(String key:new String[]{"shapeVertices","shapeLines"}){var list=ints(values,key);out.writeInt(list.size());for(int bits:list)out.writeInt(bits);}}
    private static void writeSlow(DataOutputStream out,Map<String,SceneNbt.Tag> values)throws IOException{for(String key:SLOW)out.writeDouble(number(values,key).doubleValue());}
    private static void writeFloats(DataOutputStream out,Map<String,SceneNbt.Tag> values,String key,int size)throws IOException{var list=ints(values,key);if(list.size()<size)throw new IllegalArgumentException("scene_configuration_array_shape:"+key);for(int i=0;i<size;i++)out.writeInt(list.get(i));}
    private static void curveGroup(Map<String,SceneNbt.Tag> values){int count=integer(values,"nodesCount");if(count<0||count>SceneConfigurationPackets.MAX_NBT/52)throw new IllegalArgumentException("scene_configuration_curve_count");String[] keys={"curvePositions","curveDirections","curveUp","curveScales","curveRoll","curvePointsPerSegment"};int[] lengths={3,3,3,2,1,1};for(int i=0;i<6;i++)if((count!=0||values.containsKey(keys[i]))&&ints(values,keys[i]).size()<count*lengths[i])throw new IllegalArgumentException("scene_configuration_array_shape:"+keys[i]);}
    private static Number number(Map<String,SceneNbt.Tag> values,String key){var tag=values.get(key);if(tag==null||tag.type()<1||tag.type()>6||!(tag.value() instanceof Number number))throw new IllegalArgumentException("scene_configuration_numeric:"+key);return number;}
    private static int integer(Map<String,SceneNbt.Tag> values,String key){return number(values,key).intValue();}
    private static float floating(Map<String,SceneNbt.Tag> values,String key){return number(values,key).floatValue();}
    private static boolean bool(Map<String,SceneNbt.Tag> values,String key){return number(values,key).byteValue()!=0;}
    private static String string(Map<String,SceneNbt.Tag> values,String key){var tag=values.get(key);if(tag==null||tag.type()!=8)throw new IllegalArgumentException("scene_configuration_string:"+key);return (String)tag.value();}
    @SuppressWarnings("unchecked") private static List<Integer> ints(Map<String,SceneNbt.Tag> values,String key){var tag=values.get(key);if(tag==null||tag.type()!=11)throw new IllegalArgumentException("scene_configuration_array:"+key);return (List<Integer>)tag.value();}
    @SuppressWarnings("unchecked") private static List<Byte> bytes(Map<String,SceneNbt.Tag> values,String key){var tag=values.get(key);if(tag==null||tag.type()!=7)throw new IllegalArgumentException("scene_configuration_bytes:"+key);return (List<Byte>)tag.value();}
    private static int ordinal(Map<String,SceneNbt.Tag> values,String key,String[] names){String value=string(values,key);for(int i=0;i<names.length;i++)if(value.equals(names[i]))return i;throw new IllegalArgumentException("scene_configuration_enum:"+key);}
    private static String utf(ByteBuffer in){try{return Wire.input(ScenePayload.utf(in)).readUTF();}catch(IOException invalid){throw new IllegalArgumentException("scene_configuration_utf",invalid);}}
    private SceneSpecialConfiguration(){}
}
