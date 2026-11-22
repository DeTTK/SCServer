package szserver.client;

import szserver.net.Wire;
import szserver.world.ScenePayload;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Current native scene inspector RPC and FULL_SAVE envelopes. NBT parsing belongs to workers. */
public final class SceneConfigurationPackets {
    public static final int MAX_NBT=48*1024;
    public record Request(int sequence,UUID identity){}
    public record Save(UUID identity,byte[] payload,boolean compressed){public Save(UUID identity,byte[] payload){this(identity,payload,false);}public Save{payload=payload.clone();}@Override public byte[] payload(){return payload.clone();}}
    public static boolean requestClass(String name){return name.equals("1048773550");}
    public static boolean saveClass(String name){return name.equals("441478008");}
    public static boolean settingsClass(String name){return name.equals("1169723444");}
    public static Save settings(byte[] body)throws IOException{if(body.length<24||body.length>MAX_NBT+20)throw new IOException("scene_configuration_settings_size");ByteBuffer in=ByteBuffer.wrap(body);UUID id=new UUID(in.getLong(),in.getLong());int length=in.getInt();if(length<4||length>MAX_NBT||length!=in.remaining())throw new IOException("scene_configuration_settings_length");byte[] payload=new byte[length];in.get(payload);return new Save(id,payload,true);}
    public static Request request(byte[] body)throws IOException{
        if(body.length<6)throw new IOException("scene_configuration_request_size");ByteBuffer in=ByteBuffer.wrap(body);int sequence=in.getInt(),type=in.getShort()&65535;
        // This wrapper also carries unrelated RPCs. The current registry assigns 52 to scene configuration.
        if(type!=52)return null;if(body.length!=22)throw new IOException("scene_configuration_request_size");return new Request(sequence,new UUID(in.getLong(),in.getLong()));
    }
    public static Save save(byte[] body)throws IOException{
        if(body.length<17||body.length>MAX_NBT+21)throw new IOException("scene_configuration_save_size");ByteBuffer in=ByteBuffer.wrap(body);UUID id=new UUID(in.getLong(),in.getLong());int length;
        try{length=Wire.varint(in);}catch(RuntimeException invalid){throw new IOException("scene_configuration_save_length",invalid);}
        if(length<0||length>MAX_NBT||length!=in.remaining())throw new IOException("scene_configuration_save_length");byte[] payload=new byte[length];in.get(payload);return new Save(id,payload);
    }
    public static void validate(byte[] payload){
        if(payload.length<1||payload.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_nbt_size");ByteBuffer in=ByteBuffer.wrap(payload);
        if(payload[0]!=10)throw new IllegalArgumentException("scene_configuration_nbt_root");
        try{ScenePayload.namedNbt(in);if(in.hasRemaining())throw new IllegalArgumentException("scene_configuration_nbt_trailing");SceneSettings.read(payload,0);}catch(BufferUnderflowException|ArithmeticException invalid){throw new IllegalArgumentException("scene_configuration_nbt_truncated",invalid);}
    }
    /** FULL_SAVE's behavior NBT is followed by native replicated attributes.
     * Ordinary-only convenience method; production typed saves use SceneAttributes and preserve the full buffer. */
    public static byte[] fullSaveNbt(byte[] payload){
        if(payload.length<5||payload.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_save_size");ByteBuffer in=ByteBuffer.wrap(payload);int end;
        try{end=ScenePayload.namedNbt(in);}catch(BufferUnderflowException|ArithmeticException invalid){throw new IllegalArgumentException("scene_configuration_nbt_truncated",invalid);}
        int attributes;try{attributes=Wire.varint(in);}catch(IOException|BufferUnderflowException invalid){throw new IllegalArgumentException("scene_configuration_attributes_truncated",invalid);}
        if(attributes!=0)throw new IllegalArgumentException("scene_configuration_attributes_unsupported");if(in.hasRemaining())throw new IllegalArgumentException("scene_configuration_save_trailing");byte[] nbt=Arrays.copyOf(payload,end);validate(nbt);return nbt;
    }
    public static byte[] compressedSaveNbt(byte[] payload){
        if(payload.length<4||payload.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_compressed_size");byte[] nbt;
        if((payload[0]&255)==40&&(payload[1]&255)==181&&(payload[2]&255)==47&&(payload[3]&255)==253){long size=com.github.luben.zstd.Zstd.decompressedSize(payload);if(size<4||size>MAX_NBT)throw new IllegalArgumentException("scene_configuration_expansion_limit");nbt=com.github.luben.zstd.Zstd.decompress(payload,(int)size);}
        else if((payload[0]&255)==31&&(payload[1]&255)==139){try(var input=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(payload))){nbt=input.readNBytes(MAX_NBT+1);if(nbt.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_expansion_limit");}catch(IOException invalid){throw new IllegalArgumentException("scene_configuration_compression",invalid);}}
        else throw new IllegalArgumentException("scene_configuration_compression");validate(nbt);return nbt;
    }
    public static byte[] response(int sequence,byte[] payload){
        validate(payload);return typedResponse(sequence,payload);
    }
    public static byte[] typedResponse(int sequence,byte[] payload){if(payload.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_response_size");return Wire.dynamic("977073877",Wire.bytes(out->{out.writeInt(sequence);out.writeShort(2);out.writeInt(payload.length);out.write(payload);}));}
    public static com.google.gson.JsonArray parts(byte[] payload){
        return parts(payload,false);
    }
    public static com.google.gson.JsonArray parts(byte[] payload,boolean fullSave){
        return parts(payload,fullSave,null);
    }
    public static com.google.gson.JsonArray parts(byte[] payload,boolean fullSave,String behavior){
        if(fullSave)SceneAttributes.validateFullSave(payload,behavior);else validate(payload);return retainedParts(payload);
    }
    /** Unknown assets have no behavior codec yet. Only retain bounded bytes; do not project them. */
    public static com.google.gson.JsonArray retainedParts(byte[] payload){
        retainedSize(payload);String text=Base64.getEncoder().encodeToString(payload);com.google.gson.JsonArray result=new com.google.gson.JsonArray();if(text.isEmpty())result.add("");else for(int at=0;at<text.length();at+=8192)result.add(text.substring(at,Math.min(at+8192,text.length())));return result;
    }
    private static void retainedSize(byte[] payload){if(payload.length>MAX_NBT)throw new IllegalArgumentException("scene_configuration_save_size");}
    public static byte[] retainedBody(com.google.gson.JsonObject request){
        var value=request.get("configuration");if(value==null||!value.isJsonArray()||value.getAsJsonArray().isEmpty()||value.getAsJsonArray().size()>8)throw new IllegalArgumentException("scene_configuration_parts");StringBuilder encoded=new StringBuilder();for(var part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("scene_configuration_parts");encoded.append(part.getAsString());}byte[] payload=Base64.getDecoder().decode(encoded.toString());retainedSize(payload);return payload;
    }
    public static byte[] parts(com.google.gson.JsonObject request){
        byte[] payload=retainedBody(request);if(request.has("fullSave")&&request.get("fullSave").getAsBoolean())SceneAttributes.validateFullSave(payload,request.has("configurationBehavior")?request.get("configurationBehavior").getAsString():null);else validate(payload);return payload;
    }
    private SceneConfigurationPackets(){}
}
