package szserver.client;

import szserver.world.World;
import java.io.*;
import java.util.*;

/** Current native scene editor envelopes. Decode only on a command worker, before native QA readers. */
public final class SceneEditPackets {
    public static final int MAX_BATCH=128,MAX_BODY=65500;
    public record Properties(int mode,int renderFlags,World.Vec vector1,World.Vec vector2,float float1,float float2,boolean flag1,World.Vec vector3,boolean flag2,boolean flag3,Integer editorFlags,Integer editorMode,String stateName,boolean flag4,String tag,String instanceName){}
    public record Edit(UUID identity,String asset,float[] transform,Properties properties,UUID parent,UUID group,boolean deletion){public Edit{transform=transform==null?null:transform.clone();}@Override public float[] transform(){return transform==null?null:transform.clone();}}
    public static String kind(int id){return switch(id){case 802->"batch";case 380,622->"delete";case 687->"delete_request";case 257->"transform";case 287->"create";default->null;};}
    public static String kind(String name){return switch(name){case "1065423746"->"batch";case "1803546338","468957270"->"delete";case "137281652"->"delete_request";case "1528927749"->"transform";case "520426365"->"create";default->null;};}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static boolean bool(DataInputStream in)throws IOException{int n=in.readUnsignedByte();if(n>1)throw new IOException("scene_editor_boolean");return n==1;}
    private static UUID optionalUuid(DataInputStream in)throws IOException{return bool(in)?null:uuid(in);}
    private static String text(DataInputStream in)throws IOException{String value=in.readUTF();if(value.length()>1024||value.indexOf('\0')>=0)throw new IOException("scene_editor_string");return value;}
    private static String optionalText(DataInputStream in)throws IOException{return bool(in)?null:text(in);}
    private static float number(DataInputStream in)throws IOException{float value=in.readFloat();if(!Float.isFinite(value)||Math.abs(value)>30_000_000)throw new IOException("scene_editor_number");return value;}
    private static World.Vec vector(DataInputStream in)throws IOException{return new World.Vec(number(in),number(in),number(in));}
    private static int ordinal(DataInputStream in,int max)throws IOException{int value=in.readInt();if(value<0||value>=max)throw new IOException("scene_editor_ordinal");return value;}
    /** Native packets/old Store retain16 cells; native scene geometry projects its12 affine cells. */
    private static float[] matrix(DataInputStream in)throws IOException{if(in.readInt()!=16)throw new IOException("scene_editor_matrix_size");float[] values=new float[16];for(int i=0;i<16;i++)values[i]=number(in);return values;}
    private static Edit row(DataInputStream in,boolean creation)throws IOException{
        UUID id=creation?optionalUuid(in):uuid(in);String asset=creation?text(in):null,instance=creation?optionalText(in):null;int mode=ordinal(in,5);float[] transform=matrix(in);int render=in.readInt();if(render< -128||render>255)throw new IOException("scene_editor_render_flags");World.Vec v1=vector(in),v2=vector(in);float f1=number(in),f2=number(in);boolean flag1=bool(in);World.Vec v3=vector(in);boolean flag2=bool(in),flag3=bool(in);Integer flags=creation?null:in.readInt(),editorMode=creation?null:ordinal(in,3);String state=optionalText(in);boolean flag4=bool(in);String tag=text(in);UUID parent=optionalUuid(in),group=creation?optionalUuid(in):null;
        if(creation&&(asset==null||asset.isBlank()))throw new IOException("scene_editor_asset");return new Edit(id,asset,transform,new Properties(mode,render,v1,v2,f1,f2,flag1,v3,flag2,flag3,flags,editorMode,state,flag4,tag,instance),parent,group,false);
    }
    public static List<Edit> read(String kind,byte[] body)throws IOException{
        if(body.length>MAX_BODY)throw new IOException("scene_editor_packet_size");DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));List<Edit> edits=new ArrayList<>();
        switch(kind){
            case "batch"->{for(boolean creation:new boolean[]{true,false}){int count=in.readInt();if(count<0||count>MAX_BATCH-edits.size()||count>in.available()/100)throw new IOException("scene_editor_batch_count");for(int i=0;i<count;i++)edits.add(row(in,creation));}}
            case "delete","delete_request"->{UUID id=uuid(in);if(kind.equals("delete_request"))optionalUuid(in);edits.add(new Edit(id,null,null,null,null,null,true));}
            // Internal aggregation only: no network packet id maps to this kind.
            case "delete_batch"->{int count=in.readInt();if(count<1||count>MAX_BATCH)throw new IOException("scene_editor_batch_count");for(int i=0;i<count;i++)edits.add(new Edit(uuid(in),null,null,null,null,null,true));}
            case "transform"->{UUID id=uuid(in);float[] transform=matrix(in);String asset=text(in);UUID parent=optionalUuid(in);if(asset.isBlank())throw new IOException("scene_editor_asset");edits.add(new Edit(id,asset,transform,null,parent,null,false));}
            case "create"->{float[] transform=matrix(in);String asset=text(in);UUID parent=optionalUuid(in);if(asset.isBlank())throw new IOException("scene_editor_asset");edits.add(new Edit(null,asset,transform,null,parent,null,false));}
            default->throw new IOException("scene_editor_packet_kind");
        }if(in.available()!=0)throw new IOException("scene_editor_packet_trailing");return List.copyOf(edits);
    }
    /** Validate the original envelope before using its native selection UUID as a batching key. */
    public static UUID deletionSelection(byte[] body)throws IOException{
        read("delete_request",body);DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));uuid(in);return optionalUuid(in);
    }
    /** Complete Inspector updates may supersede older queued states of the exact
     * same selection. Creations and anonymous identities must never be folded. */
    public static Set<UUID> updateSelection(byte[] body)throws IOException{
        if(body.length<8||java.nio.ByteBuffer.wrap(body).getInt()!=0)return Set.of();
        List<Edit> edits=read("batch",body);if(edits.isEmpty())return Set.of();Set<UUID> ids=new HashSet<>();
        for(var edit:edits)if(edit.identity()==null||edit.deletion()||!ids.add(edit.identity()))return Set.of();return Set.copyOf(ids);
    }
    public static byte[] deletionBatch(List<byte[]> messages)throws IOException{
        if(messages.isEmpty()||messages.size()>MAX_BATCH)throw new IOException("scene_editor_batch_count");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(messages.size());
        for(byte[] message:messages){Edit edit=read("delete_request",message).get(0);out.writeLong(edit.identity().getMostSignificantBits());out.writeLong(edit.identity().getLeastSignificantBits());}return bytes.toByteArray();
    }
    public static com.google.gson.JsonArray parts(byte[] body){if(body.length>MAX_BODY)throw new IllegalArgumentException("scene_editor_packet_size");String raw=Base64.getEncoder().encodeToString(body);var parts=new com.google.gson.JsonArray();for(int at=0;at<raw.length();at+=8192)parts.add(raw.substring(at,Math.min(at+8192,raw.length())));return parts;}
    public static byte[] parts(com.google.gson.JsonObject request){var value=request.get("nativePacket");if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()>11)throw new IllegalArgumentException("scene_editor_packet_parts");StringBuilder text=new StringBuilder();for(var part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("scene_editor_packet_parts");text.append(part.getAsString());}byte[] body=Base64.getDecoder().decode(text.toString());if(body.length>MAX_BODY)throw new IllegalArgumentException("scene_editor_packet_size");return body;}
    private SceneEditPackets(){}
}
