package szserver.commands;

import com.github.luben.zstd.Zstd;
import com.google.gson.*;
import szserver.net.Wire;
import szserver.world.*;
import java.nio.*;
import java.util.*;

/** Compact optional before/after tile states. Codec runs only on the serial command worker. */
final class TileEdits {
    private static final int MAGIC=0x54584531,FULL_MAGIC=0x54584532,MAX_COMPRESSED=45000,MAX_FULL_RAW=4*1024*1024;
    record States(TilePayload.Tile[] before,TilePayload.Tile[] after){}
    static void save(JsonObject event,TilePayload.Tile[] before,TilePayload.Tile[] after){
        boolean any=false;for(int i=0;i<before.length;i++)if(before[i]!=null||after[i]!=null){any=true;break;}if(!any)return;
        boolean full=false;long expected=8;for(TilePayload.Tile[] states:new TilePayload.Tile[][]{before,after})for(TilePayload.Tile tile:states){expected+=1+(tile==null?0:tile.record().length);if(tile!=null&&tile.completeStateSize()>0)full=true;}
        if(full)for(TilePayload.Tile[] states:new TilePayload.Tile[][]{before,after})for(TilePayload.Tile tile:states)expected+=4+(tile==null?0:tile.completeStateSize());if(expected>(full?MAX_FULL_RAW:TilePayload.MAX_RAW))throw new IllegalArgumentException("tile_edit_raw_limit");final boolean complete=full;
        byte[] raw=Wire.bytes(out->{out.writeInt(complete?FULL_MAGIC:MAGIC);out.writeInt(before.length);for(TilePayload.Tile[] states:new TilePayload.Tile[][]{before,after})for(TilePayload.Tile tile:states){byte[] record=tile==null?new byte[0]:tile.record();out.writeByte(record.length);out.write(record);if(complete){byte[] state=tile==null?null:tile.completeState();out.writeInt(state==null?0:state.length);if(state!=null)out.write(state);}}});
        byte[] compressed=Zstd.compress(raw,3);
        if(compressed.length>MAX_COMPRESSED)throw new IllegalArgumentException("tile_edit_compressed_limit");String encoded=Base64.getEncoder().encodeToString(compressed);JsonArray parts=new JsonArray();for(int at=0;at<encoded.length();at+=8192)parts.add(encoded.substring(at,Math.min(at+8192,encoded.length())));event.add("tiles",parts);
    }
    static States read(JsonObject event,List<World.Block> cells){
        TilePayload.Tile[] before=new TilePayload.Tile[cells.size()],after=new TilePayload.Tile[cells.size()];if(!event.has("tiles"))return new States(before,after);
        JsonElement value=event.get("tiles");if(!value.isJsonArray()||value.getAsJsonArray().size()<1||value.getAsJsonArray().size()>8)throw new IllegalArgumentException("tile_edit_parts_limit");StringBuilder encoded=new StringBuilder();
        for(JsonElement part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("tile_edit_part_limit");encoded.append(part.getAsString());}
        byte[] compressed=Base64.getDecoder().decode(encoded.toString());if(compressed.length>MAX_COMPRESSED)throw new IllegalArgumentException("tile_edit_compressed_limit");long size=Zstd.decompressedSize(compressed);
        if(size<8||size>MAX_FULL_RAW)throw new IllegalArgumentException("tile_edit_decompression_limit");ByteBuffer input=ByteBuffer.wrap(Zstd.decompress(compressed,(int)size));int magic=input.getInt();boolean full=magic==FULL_MAGIC;
        if(magic!=MAGIC&&!full||!full&&size>TilePayload.MAX_RAW||input.getInt()!=cells.size()||cells.size()>8192)throw new IllegalArgumentException("tile_edit_layout");
        for(TilePayload.Tile[] states:new TilePayload.Tile[][]{before,after})for(int i=0;i<states.length;i++){
            if(!input.hasRemaining())throw new IllegalArgumentException("tile_edit_truncated");int length=input.get()&255;if(length==0){if(full){if(input.remaining()<4||input.getInt()!=0)throw new IllegalArgumentException("tile_edit_complete_in_empty");}continue;}
            if(length<20||length>68||input.remaining()<length)throw new IllegalArgumentException("tile_edit_record_limit");byte[] record=new byte[length];input.get(record);TilePayload.Tile tile=TilePayload.single(record);World.Block p=cells.get(i);
            if(tile.x()!=p.x()||tile.y()!=p.y()||tile.z()!=p.z())throw new IllegalArgumentException("tile_edit_position_mismatch");
            if(full){if(input.remaining()<4)throw new IllegalArgumentException("tile_edit_complete_truncated");int count=input.getInt();if(count<0||count>szserver.client.CompleteTileState.MAX_STATE||count>input.remaining())throw new IllegalArgumentException("tile_edit_complete_size");if(count>0){byte[] state=new byte[count];input.get(state);TilePayload.Tile complete=szserver.client.CompleteTileState.decode(state);if(!Arrays.equals(complete.record(),tile.record()))throw new IllegalArgumentException("tile_edit_complete_projection_mismatch");tile=complete;}}
            states[i]=tile;
        }if(input.hasRemaining())throw new IllegalArgumentException("tile_edit_trailing_bytes");return new States(before,after);
    }
}
