package szserver.persistence;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Fragment one logical state row at a time. Native bodies remain opaque bytes, never rewritten
 * through game factories. Consumers must build detached state until finish() succeeds. */
public final class CheckpointDataRows {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static final int MAX_BYTES=16*1024*1024,PART_BYTES=24576;
    private CheckpointDataRows(){}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static long integer(JsonObject row,String key,long min,long max){
        try{var v=row.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=v.getAsBigDecimal().longValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(RuntimeException invalid){throw new IllegalArgumentException("checkpoint_state_integer:"+key,invalid);}
    }
    public static void fields(JsonObject row,String... keys){if(!row.keySet().equals(Set.of(keys)))throw new IllegalArgumentException("checkpoint_state_fields");}
    public static UUID uuid(JsonObject row,String key){String value=Json.text(row,key,36);UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("checkpoint_state_uuid");return id;}
    public static boolean flag(JsonObject row,String key){var value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("checkpoint_state_boolean");return value.getAsBoolean();}
    public static void write(CheckpointSink sink,JsonObject value)throws IOException{
        byte[] bytes=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);if(bytes.length<1||bytes.length>MAX_BYTES)throw new IOException("checkpoint_state_row_limit");
        // Same strict parser/depth/node budget as the source reducers. Opaque blobs are split separately.
        try{Json.parse(bytes,2*1024*1024);}catch(RuntimeException invalid){throw new IOException("checkpoint_state_row_structure",invalid);}
        if(sink instanceof CheckpointSink.Logical logical){logical.logicalRow(value,bytes);return;}
        JsonObject start=new JsonObject();start.addProperty("frame","start");start.addProperty("bytes",bytes.length);start.addProperty("sha256",hash(bytes));sink.row(start);
        int index=0;for(int at=0;at<bytes.length;at+=PART_BYTES){JsonObject part=new JsonObject();part.addProperty("frame","part");part.addProperty("index",index++);part.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,at,Math.min(bytes.length,at+PART_BYTES))));sink.row(part);}
        JsonObject end=new JsonObject();end.addProperty("frame","end");end.addProperty("parts",index);sink.row(end);
    }
    public static JsonArray binary(byte[] bytes){if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("checkpoint_state_binary_limit");JsonArray parts=new JsonArray();for(int at=0;at<bytes.length;at+=8192)parts.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,at,Math.min(bytes.length,at+8192))));return parts;}
    public static byte[] binary(JsonElement value,int max){
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()>(max+8191)/8192)throw new IllegalArgumentException("checkpoint_state_binary");ByteArrayOutputStream out=new ByteArrayOutputStream();int count=value.getAsJsonArray().size(),at=0;
        for(var part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString())throw new IllegalArgumentException("checkpoint_state_binary");String text=part.getAsString();if(text.length()>10924)throw new IllegalArgumentException("checkpoint_state_binary");byte[] raw=Base64.getDecoder().decode(text);if(raw.length<1||raw.length>8192||++at<count&&raw.length!=8192||!Base64.getEncoder().encodeToString(raw).equals(text)||out.size()+raw.length>max)throw new IllegalArgumentException("checkpoint_state_binary");out.writeBytes(raw);}return out.toByteArray();
    }
    public static final class Reader implements WorldCheckpointStore.Rows {
        private final WorldCheckpointStore.Rows consumer;private String section,sha;private ByteArrayOutputStream data;private int bytes,parts;private boolean finished;
        public Reader(WorldCheckpointStore.Rows consumer){this.consumer=Objects.requireNonNull(consumer);}
        @Override public void accept(String next,JsonObject row)throws Exception{
            if(finished)throw new IllegalArgumentException("checkpoint_state_reader_finished");String frame=Json.text(row,"frame",16);
            switch(frame){
                case "start"->{fields(row,"frame","bytes","sha256");if(data!=null)throw new IllegalArgumentException("checkpoint_state_nested_row");bytes=(int)integer(row,"bytes",1,MAX_BYTES);sha=Json.text(row,"sha256",64);if(!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("checkpoint_state_row_hash");section=next;data=new ByteArrayOutputStream(Math.min(bytes,PART_BYTES));parts=0;}
                case "part"->{fields(row,"frame","index","data");active(next);if(integer(row,"index",0,Integer.MAX_VALUE)!=parts)throw new IllegalArgumentException("checkpoint_state_part_order");String text=Json.text(row,"data",32768);byte[] raw=Base64.getDecoder().decode(text);int remaining=bytes-data.size();if(raw.length!=Math.min(remaining,PART_BYTES)||raw.length==0||!Base64.getEncoder().encodeToString(raw).equals(text))throw new IllegalArgumentException("checkpoint_state_part_size");data.writeBytes(raw);parts++;}
                case "end"->{fields(row,"frame","parts");active(next);if(integer(row,"parts",1,Integer.MAX_VALUE)!=parts||data.size()!=bytes)throw new IllegalArgumentException("checkpoint_state_row_incomplete");byte[] raw=data.toByteArray();if(!hash(raw).equals(sha))throw new IllegalArgumentException("checkpoint_state_row_hash");JsonObject value=Json.parse(raw,2*1024*1024);data=null;consumer.accept(section,value);}
                default->throw new IllegalArgumentException("checkpoint_state_frame");
            }
        }
        private void active(String next){if(data==null||!next.equals(section))throw new IllegalArgumentException("checkpoint_state_fragment_section");}
        public void finish(){if(data!=null)throw new IllegalArgumentException("checkpoint_state_row_incomplete");finished=true;}
    }
}
