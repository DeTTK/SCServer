package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** Bounded typed JSON to standard NBT; the installed game classes interpret item semantics. */
final class NativeTypedNbt {
    private static final Map<String,Integer> TYPES=Map.ofEntries(Map.entry("byte",1),Map.entry("boolean",1),Map.entry("short",2),Map.entry("integer",3),Map.entry("long",4),Map.entry("float",5),Map.entry("double",6),Map.entry("byte_array",7),Map.entry("string",8),Map.entry("list",9),Map.entry("compound",10),Map.entry("integer_array",11));
    static byte[] encode(JsonObject tags)throws IOException{ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);out.writeByte(10);out.writeUTF("");compound(out,tags,0);byte[] raw=buffer.toByteArray();NativeNbtLimits.validate(raw);return raw;}
    private static int type(JsonObject tag){if(!tag.has("type"))return 10;Integer type=TYPES.get(tag.get("type").getAsString());if(type==null)throw new IllegalArgumentException("unknown_nbt_type");return type;}
    private static JsonElement value(JsonObject tag){return tag.has("type")?Objects.requireNonNull(tag.get("value"),"nbt_value_required"):tag;}
    private static long integer(JsonElement value,long low,long high){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("nbt_integer_required");long number=new BigDecimal(value.getAsString()).longValueExact();if(number<low||number>high)throw new IllegalArgumentException("nbt_integer_range");return number;}
    private static void compound(DataOutputStream out,JsonObject tags,int depth)throws IOException{if(depth>8||tags.size()>256)throw new IOException("nbt_complexity");for(var entry:tags.entrySet()){if(entry.getKey().isEmpty()||entry.getKey().length()>128||!entry.getValue().isJsonObject())throw new IOException("nbt_entry");JsonObject tag=entry.getValue().getAsJsonObject();out.writeByte(type(tag));out.writeUTF(entry.getKey());payload(out,tag,depth+1);}out.writeByte(0);}
    private static void payload(DataOutputStream out,JsonObject tag,int depth)throws IOException{
        if(depth>8)throw new IOException("nbt_depth");JsonElement value=value(tag);
        switch(type(tag)){
            case 1 -> {if(tag.get("type").getAsString().equals("boolean")){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("nbt_boolean");out.writeByte(value.getAsBoolean()?1:0);}else out.writeByte((int)integer(value,-128,127));}
            case 2 -> out.writeShort((int)integer(value,-32768,32767));case 3 -> out.writeInt((int)integer(value,Integer.MIN_VALUE,Integer.MAX_VALUE));case 4 -> out.writeLong(integer(value,Long.MIN_VALUE,Long.MAX_VALUE));
            case 5 -> {float number=value.getAsFloat();if(!Float.isFinite(number))throw new IOException("nbt_nonfinite");out.writeFloat(number);}case 6 -> {double number=value.getAsDouble();if(!Double.isFinite(number))throw new IOException("nbt_nonfinite");out.writeDouble(number);}
            case 7 -> {byte[] bytes;if(value.isJsonArray()){JsonArray array=value.getAsJsonArray();if(array.size()>8192)throw new IOException("nbt_array");bytes=new byte[array.size()];for(int n=0;n<bytes.length;n++)bytes[n]=(byte)integer(array.get(n),0,255);}else {String hex=value.isJsonObject()?value.getAsJsonObject().get("$bytes").getAsString():value.getAsString();if(hex.length()>16384)throw new IOException("nbt_array");bytes=HexFormat.of().parseHex(hex);}out.writeInt(bytes.length);out.write(bytes);}
            case 8 -> {if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().length()>1024)throw new IOException("nbt_string");out.writeUTF(value.getAsString());}
            case 9 -> {JsonArray list=value.getAsJsonArray();if(list.size()>256)throw new IOException("nbt_list");int child=list.isEmpty()?0:type(list.get(0).getAsJsonObject());out.writeByte(child);out.writeInt(list.size());for(JsonElement element:list){JsonObject row=element.getAsJsonObject();if(type(row)!=child)throw new IOException("nbt_list_mixed_types");payload(out,row,depth+1);}}
            case 10 -> compound(out,value.getAsJsonObject(),depth+1);
            case 11 -> {JsonArray array=value.getAsJsonArray();if(array.size()>8192)throw new IOException("nbt_array");out.writeInt(array.size());for(JsonElement element:array)out.writeInt((int)integer(element,Integer.MIN_VALUE,Integer.MAX_VALUE));}
            default -> throw new IOException("nbt_type");
        }
    }
}
