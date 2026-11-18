package environment.protocol;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Small reliable messages; native configuration generations are transferred as bounded pages. */
public final class EnvironmentWire {
    public static final String INPUT="szmods.bridge.EnvironmentInput",SNAPSHOT="szmods.bridge.EnvironmentSnapshot";
    public static final int VERSION=1,MAX_MESSAGE=24576,PAGE_BYTES=12288,MAX_CONFIG=4*1024*1024;
    private EnvironmentWire(){}
    /** Missing inherited fields have the same native meaning as {isLocked:true}.
     * Keep root fields intact: only child styles have an inheritance target. */
    public static String compactConfiguration(String text) {
        JsonObject root=JsonParser.parseString(text).getAsJsonObject();
        for(var value:root.getAsJsonObject("styles").entrySet()){
            JsonObject style=value.getValue().getAsJsonObject();if(!style.has("parentStyleID")||style.get("parentStyleID").isJsonNull())continue;
            for(var profile:style.entrySet())if(profile.getValue().isJsonObject())
                for(var group:profile.getValue().getAsJsonObject().entrySet())if(group.getValue().isJsonObject())
                    group.getValue().getAsJsonObject().entrySet().removeIf(entry->{JsonElement field=entry.getValue();return field.isJsonObject()&&field.getAsJsonObject().size()==1&&field.getAsJsonObject().has("isLocked")&&field.getAsJsonObject().get("isLocked").getAsBoolean();});
        }
        return root.toString();
    }
    public static byte[] encode(JsonObject message)throws IOException {
        byte[] raw=message.toString().getBytes(StandardCharsets.UTF_8);if(raw.length>MAX_MESSAGE)throw new IOException("environment_message_limit");
        ByteArrayOutputStream out=new ByteArrayOutputStream(raw.length+4);DataOutputStream data=new DataOutputStream(out);data.writeInt(raw.length);data.write(raw);return out.toByteArray();
    }
    public static byte[] read(DataInput in)throws IOException {
        int size=in.readInt();if(size<2||size>MAX_MESSAGE)throw new IOException("environment_message_limit");byte[] raw=new byte[size+4];java.nio.ByteBuffer.wrap(raw).putInt(size);in.readFully(raw,4,size);return raw;
    }
    public static JsonObject decode(byte[] bytes)throws IOException {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){byte[] frame=read(input);if(input.available()!=0)throw new IOException("environment_message_tail");return JsonParser.parseString(new String(frame,4,frame.length-4,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException error){throw new IOException("invalid_environment_message",error);}
    }
    public static JsonObject message(String op){JsonObject out=new JsonObject();out.addProperty("op",op);out.addProperty("version",VERSION);return out;}
}
