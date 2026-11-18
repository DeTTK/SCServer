package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.World;
import java.util.*;

/** Compact durable cuboid description; enumeration and JSON decoding stay off the tick. */
final class BlockEdit {
    static final int LIMIT=1_000_000,BATCH=128;
    static List<World.Block> cells(JsonObject request){
        JsonObject min=Json.object(request,"min"),max=Json.object(request,"max");
        int x=Json.integer(min,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),y=Json.integer(min,"y",0,255),z=Json.integer(min,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);
        int xx=Json.integer(max,"x",x,szserver.world.MapBounds.HORIZONTAL),yy=Json.integer(max,"y",y,255),zz=Json.integer(max,"z",z,szserver.world.MapBounds.HORIZONTAL);
        long volume=((long)xx-x+1)*(yy-y+1)*((long)zz-z+1);
        if(volume>LIMIT)throw new IllegalArgumentException("block_edit_volume_limit_1000000");
        return BlockCells.cuboid(x,y,z,xx,yy,zz);
    }

    static List<World.Block> translated(List<World.Block> source,int dx,int dy,int dz){
        if(source instanceof BlockCells.Cuboid cuboid)return cuboid.translated(dx,dy,dz);
        ArrayList<World.Block> result=new ArrayList<>(source.size());
        for(World.Block p:source){long x=(long)p.x()+dx,y=(long)p.y()+dy,z=(long)p.z()+dz;
            if(x<-szserver.world.MapBounds.HORIZONTAL||x>szserver.world.MapBounds.HORIZONTAL||y<0||y>255||z<-szserver.world.MapBounds.HORIZONTAL||z>szserver.world.MapBounds.HORIZONTAL)throw new IllegalArgumentException("edit_destination_out_of_bounds");
            result.add(new World.Block((int)x,(int)y,(int)z));}
        return List.copyOf(result);
    }
    static JsonElement encode(int[] values){
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(values.length*2);
        for(int value:values){if(value<0||value>65535)throw new IllegalArgumentException("invalid_edit_value");buffer.putShort((short)value);}
        String encoded=Base64.getEncoder().encodeToString(buffer.array());
        if(encoded.length()<=16384)return new JsonPrimitive(encoded);
        JsonArray parts=new JsonArray();for(int at=0;at<encoded.length();at+=8192)parts.add(encoded.substring(at,Math.min(encoded.length(),at+8192)));return parts;
    }
    static int[] decode(JsonObject event,String field,int count){
        if(count<1||count>LIMIT*2)throw new IllegalArgumentException("invalid_edit_count");
        JsonElement encoded=event.get(field);String value;
        if(encoded!=null&&encoded.isJsonArray()){
            JsonArray parts=encoded.getAsJsonArray();if(parts.size()<1||parts.size()>3)throw new IllegalArgumentException("invalid_edit_parts");
            StringBuilder joined=new StringBuilder();for(JsonElement part:parts){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("invalid_edit_parts");joined.append(part.getAsString());}value=joined.toString();
        }else value=Json.text(event,field,16384);
        byte[] raw=Base64.getDecoder().decode(value);
        if(raw.length!=count*2)throw new IllegalArgumentException("invalid_edit_"+field);
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(raw);int[] result=new int[count];for(int i=0;i<count;i++)result[i]=Short.toUnsignedInt(buffer.getShort());return result;
    }
}
