package szserver.client;

import com.github.luben.zstd.Zstd;
import szserver.net.Wire;
import szserver.world.*;
import java.nio.*;
import java.util.*;

/** Original complete Carpenter NBT and its atomic chunk appearance; codecs run on workers. */
public final class CompleteTileState {
    public static final int MAX_STATE=1024*1024;
    private static boolean compressed(byte[] state){return state.length>=4&&ByteBuffer.wrap(state).getInt()==0x28b52ffd;}
    public static byte[] raw(byte[] state){if(state.length<4||state.length>MAX_STATE)throw new IllegalArgumentException("tile_complete_state_size");if(!compressed(state))return state.clone();long size=Zstd.decompressedSize(state);if(size<4||size>MAX_STATE)throw new IllegalArgumentException("tile_complete_expansion_limit");return Zstd.decompress(state,(int)size);}
    private static int integer(Map<String,SceneNbt.Tag> root,String name,int fallback,int min,int max){var tag=root.get(name);if(tag==null)return fallback;if(tag.type()<1||tag.type()>4||!(tag.value() instanceof Number))throw new IllegalArgumentException("tile_complete_integer:"+name);long value=((Number)tag.value()).longValue();if(value<min||value>max)throw new IllegalArgumentException("tile_complete_integer_bounds:"+name);return (int)value;}
    private static byte[] bytes(Map<String,SceneNbt.Tag> root,String name,int count){var tag=root.get(name);if(tag==null)return new byte[count];if(tag.type()!=7||!(tag.value() instanceof List<?> list)||list.size()!=count)throw new IllegalArgumentException("tile_complete_array:"+name);byte[] result=new byte[count];for(int i=0;i<count;i++)result[i]=((Number)list.get(i)).byteValue();return result;}
    public static TilePayload.Tile decode(byte[] state){
        var root=SceneNbt.readTile(raw(state)).values();var id=root.get("id");if(id==null||id.type()!=8||!((String)id.value()).startsWith("TileEntityCarpenters"))throw new IllegalArgumentException("tile_complete_identity");boolean builder=id.value().equals("TileEntityCarpentersProBuilder");
        int x=integer(root,"x",0,-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),y=integer(root,"y",0,0,255),z=integer(root,"z",0,-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);int[] covers=new int[7];for(int i=0;i<7;i++)covers[i]=integer(root,"cover_"+i,-1,Short.MIN_VALUE,Short.MAX_VALUE);
        byte[] overlay=bytes(root,"overlay",7),colors=bytes(root,"color",7),sides=bytes(root,"ctm_data",6);int exponent=integer(root,"color_exponent",0,Short.MIN_VALUE,Short.MAX_VALUE),rotation=integer(root,"rot",0,Short.MIN_VALUE,Short.MAX_VALUE),packed=integer(root,"packcovers",0,-128,127),shape=integer(root,"data",0,Integer.MIN_VALUE,Integer.MAX_VALUE),extension=integer(root,"data_ext",builder?-1:0,Integer.MIN_VALUE,Integer.MAX_VALUE);
        int flags=8;for(byte value:overlay)if(value!=0)flags|=2;for(byte value:colors)if(value!=0)flags|=1;for(byte value:sides)if(value!=0)flags|=64;if(exponent!=0)flags|=4;if(rotation!=0)flags|=16;if(packed!=0)flags|=32;final int presence=flags;
        byte[] record=Wire.bytes(out->{out.writeByte(builder?1:0);out.writeInt(x);out.writeInt(y);out.writeInt(z);out.writeByte(presence);for(int value:covers)out.writeShort(value);if((presence&2)!=0)for(byte value:overlay)out.writeShort(value==0?-1:value);if((presence&4)!=0)out.writeShort(exponent);if((presence&1)!=0)out.write(colors);if((presence&16)!=0)out.writeShort(rotation);if((presence&32)!=0)out.writeByte(packed);if((presence&64)!=0)out.write(sides);out.writeInt(shape);if(builder)out.writeInt(extension);});
        return TilePayload.single(record).withCompleteState(state);
    }
    public static byte[] relocate(byte[] state,World.Block at){
        var original=SceneNbt.readTile(raw(state));var values=new LinkedHashMap<>(original.values());for(var entry:Map.of("x",at.x(),"y",at.y(),"z",at.z()).entrySet())values.put(entry.getKey(),SceneNbt.number(3,entry.getValue()));byte[] changed=SceneNbt.writeTile(new SceneNbt.Root(original.name(),values));return compressed(state)?Zstd.compress(changed,3):changed;
    }
    /** Envelope matches the promoted 237043901 packet and custom action127. Compression and packet preparation run only on a worker. */
    public static byte[] frame(TilePayload.Tile tile){byte[] original=tile.completeState();if(original==null)throw new IllegalArgumentException("tile_complete_state_missing");byte[] state=compressed(original)?original:Zstd.compress(original,3);if(state.length>MAX_STATE)throw new IllegalArgumentException("tile_complete_wire_state_limit");return Wire.frame(806,Wire.bytes(out->{out.writeInt(tile.x());out.writeShort(tile.y());out.writeInt(tile.z());out.writeByte(127);out.writeInt(state.length);out.write(state);}));}
    public static com.google.gson.JsonArray parts(byte[] state){if(state.length<4||state.length>MAX_STATE)throw new IllegalArgumentException("tile_complete_state_size");String encoded=Base64.getEncoder().encodeToString(state);var result=new com.google.gson.JsonArray();for(int at=0;at<encoded.length();at+=8192)result.add(encoded.substring(at,Math.min(at+8192,encoded.length())));return result;}
    public static byte[] parts(com.google.gson.JsonObject request){var value=request.get("completeTile");if(value==null||!value.isJsonArray()||value.getAsJsonArray().isEmpty()||value.getAsJsonArray().size()>171)throw new IllegalArgumentException("tile_complete_parts");StringBuilder result=new StringBuilder();for(var part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("tile_complete_parts");result.append(part.getAsString());}byte[] state=Base64.getDecoder().decode(result.toString());if(state.length>MAX_STATE)throw new IllegalArgumentException("tile_complete_state_size");return state;}
    /** Compare compounds on workers; compression and compound tag order are not gameplay changes. */
    public static boolean same(byte[] first,byte[] second){SceneCollision.worker();return Arrays.equals(first,second)||first!=null&&second!=null&&SceneNbt.readTile(raw(first)).values().equals(SceneNbt.readTile(raw(second)).values());}
    private CompleteTileState(){}
}
