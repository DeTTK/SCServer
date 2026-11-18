package szserver.world;

import com.google.gson.JsonObject;
import szserver.util.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Canonical command bytes travel in the same immutable record as their native before/after data. */
public final class ResidentCommandEnvelope {
    private static final int MAGIC=0x535a4345,VERSION=1,MAX_COMMAND=16*1024*1024;
    private ResidentCommandEnvelope(){}
    public static final class Prepared {
        private final byte[] bytes;private final String id,request;private final UUID operation;private volatile long event;
        private Prepared(JsonObject source){SceneCollision.worker();bytes=Json.bytes(source);if(bytes.length<2||bytes.length>MAX_COMMAND)throw new IllegalArgumentException("resident_command_size");var checked=Json.parse(bytes,2*1024*1024);id=Json.text(checked,"commandId",64);request=Json.text(checked,"request",64);if(id.isBlank()||id.indexOf('\0')>=0||!request.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("resident_command_identity");Json.text(checked,"type",32);operation=UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));}
        public String id(){return id;}public String request(){return request;}public UUID operation(){return operation;}public int bytes(){return bytes.length;}public long event(){return event;}
        public JsonObject json(){SceneCollision.worker();return Json.parse(bytes,2*1024*1024);}
        /** Owner commit only. Future completion publishes this pointer to the command worker. */
        public void committed(long value){if(value<1||event!=0&&event!=value)throw new IllegalStateException("resident_command_commit_identity");event=value;}
    }
    public static Prepared prepare(JsonObject event){return new Prepared(Objects.requireNonNull(event));}
    public static ResidentOperationLog.Payload wrap(ResidentOperationLog.Payload nativeData,Prepared command){
        if(command==null)return Objects.requireNonNull(nativeData);long bound=16L+command.bytes.length+(nativeData==null?0:nativeData.bytes());
        return new ResidentOperationLog.Payload(bound,()->{byte[] nativeBytes=nativeData==null?new byte[0]:nativeData.encoder().encode();if(nativeData!=null&&(nativeBytes.length<1||nativeBytes.length>nativeData.bytes()))throw new IOException("resident_command_native_bound");var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(nativeBytes.length);out.write(nativeBytes);out.writeInt(command.bytes.length);out.write(command.bytes);}return buffer.toByteArray();});
    }
    private record Parts(byte[] nativeBytes,byte[] command){}
    private static Parts parts(ResidentOperationLog.Stored event)throws IOException{
        byte[] body=event.payload();if(body.length<4||ByteBuffer.wrap(body).getInt()!=MAGIC)return null;
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){in.readInt();if(in.readInt()!=VERSION)throw new IOException("resident_command_envelope_version");int size=in.readInt();if(size<0||size>48*1024*1024||size>in.available()-4)throw new IOException("resident_command_native_size");byte[] nativeBytes=in.readNBytes(size);int length=in.readInt();if(length<2||length>MAX_COMMAND||length!=in.available())throw new IOException("resident_command_body_size");return new Parts(nativeBytes,in.readNBytes(length));}
    }
    public static ResidentOperationLog.Stored nativeEvent(ResidentOperationLog.Stored event)throws IOException{var parts=parts(event);if(parts==null)return event;if(parts.nativeBytes().length==0)throw new IOException("resident_command_native_missing");return new ResidentOperationLog.Stored(event.id(),event.revision(),event.actor(),event.operation(),event.kind(),parts.nativeBytes());}
    public static JsonObject command(ResidentOperationLog.Stored event)throws IOException{
        var parts=parts(event);if(parts==null)return null;try{var command=prepare(Json.parse(parts.command(),2*1024*1024));if(!command.operation().equals(event.operation()))throw new IOException("resident_command_operation_mismatch");return command.json();}catch(IllegalArgumentException invalid){throw new IOException("resident_command_body_invalid",invalid);}
    }
}
