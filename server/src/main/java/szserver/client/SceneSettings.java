package szserver.client;

import com.google.gson.*;
import szserver.world.ScenePayload;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Bounded named NBT interpretation on scene loader/editor workers. Native readers verify QA fixtures. */
public final class SceneSettings {
    /** Installed 1395149101.char uses string-table entry4227, verified by NativeActorMotion. */
    public static final String LADDER_SOURCE_KEY="provideLadderSource";
    public static boolean ladderSource(szserver.world.SceneObject object){
        szserver.world.SceneCollision.worker();byte[] record=object.record();var settings=compound(record,szserver.world.SceneObject.prefix(record).behaviourAt());
        if(settings==null||!settings.has(LADDER_SOURCE_KEY))return true;var enabled=settings.get(LADDER_SOURCE_KEY);return enabled.isJsonPrimitive()&&(enabled.getAsJsonPrimitive().isBoolean()?enabled.getAsBoolean():enabled.getAsInt()!=0);
    }
    public static JsonObject read(byte[] record,int start){
        JsonObject root=compound(record,start);if(root==null)return null;JsonElement settings=root.get("settings");return settings!=null&&settings.isJsonObject()?settings.getAsJsonObject():null;
    }
    /** Some native behavior families, including grenade_aoe_object, save directly in the root. */
    public static JsonObject compound(byte[] record,int start){
        if(start>=record.length||(record[start]&255)!=10)return null;
        ByteBuffer checked=ByteBuffer.wrap(record);checked.position(start);int end=ScenePayload.namedNbt(checked);
        try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(record,start,end-start));in.readByte();in.readUTF();return payload(in,10).getAsJsonObject();}catch(IOException invalid){throw new IllegalArgumentException("scene_settings_nbt",invalid);}
    }
    private static JsonElement payload(DataInputStream in,int type)throws IOException{
        return switch(type){
            case 1->new JsonPrimitive(in.readByte());case 2->new JsonPrimitive(in.readShort());case 3->new JsonPrimitive(in.readInt());case 4->new JsonPrimitive(in.readLong());case 5->new JsonPrimitive(in.readFloat());case 6->new JsonPrimitive(in.readDouble());case 8->new JsonPrimitive(in.readUTF());
            case 7->{int n=in.readInt();in.skipNBytes(n);yield JsonNull.INSTANCE;}
            case 11->{int n=in.readInt();in.skipNBytes(4L*n);yield JsonNull.INSTANCE;}
            case 9->{int child=in.readUnsignedByte(),n=in.readInt();JsonArray list=new JsonArray();for(int i=0;i<n;i++)list.add(payload(in,child));yield list;}
            case 10->{JsonObject map=new JsonObject();for(int child;(child=in.readUnsignedByte())!=0;){String name=in.readUTF();if(map.has(name))throw new IOException("scene_settings_duplicate_key");map.add(name,payload(in,child));}yield map;}
            default->throw new IOException("scene_settings_type");
        };
    }
    private SceneSettings(){}
}
