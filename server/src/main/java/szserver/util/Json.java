package szserver.util;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson NULL_FINGERPRINT_GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private Json() {}
    public static byte[] bytes(Object value) { return GSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    public static JsonObject parse(byte[] bytes) {
        return parse(bytes,16384);
    }
    /** Larger BLOB text only for the bounded offline snapshot reader. */
    public static JsonObject parse(byte[] bytes,int stringLimit) {
        return parse(bytes,stringLimit,4096);
    }
    /** Explicitly bounded internal archives; ordinary HTTP/journal parsing keeps4096 nodes. */
    public static JsonObject parse(byte[] bytes,int stringLimit,int nodeLimit) {
        if(stringLimit<16384||stringLimit>2*1024*1024)throw new IllegalArgumentException("json_string_limit_configuration");
        if(nodeLimit<4096||nodeLimit>1000000)throw new IllegalArgumentException("json_node_limit_configuration");
        try {
            JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0, new int[]{nodeLimit},stringLimit);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT || !value.isJsonObject())
                throw new IllegalArgumentException("json_object_required");
            return value.getAsJsonObject();
        } catch (Exception e) { throw new IllegalArgumentException("invalid_json", e); }
    }
    private static JsonElement read(JsonReader r,int depth,int[] budget,int stringLimit)throws java.io.IOException {
        if(depth>16||--budget[0]<0)throw new IllegalArgumentException("json_structure_limit");
        switch(r.peek()) {
            case BEGIN_OBJECT: {
                r.beginObject();JsonObject o=new JsonObject();
                while(r.hasNext()){String key=r.nextName();if(key.length()>128||o.has(key))throw new IllegalArgumentException("duplicate_or_long_json_key");o.add(key,read(r,depth+1,budget,stringLimit));}
                r.endObject();return o;
            }
            case BEGIN_ARRAY: {
                r.beginArray();JsonArray a=new JsonArray();while(r.hasNext())a.add(read(r,depth+1,budget,stringLimit));r.endArray();return a;
            }
            case STRING: {String s=r.nextString();if(s.length()>stringLimit)throw new IllegalArgumentException("json_string_limit");return new JsonPrimitive(s);}
            case NUMBER: {String s=r.nextString();if(s.length()>128)throw new IllegalArgumentException("json_number_limit");java.math.BigDecimal n=new java.math.BigDecimal(s);if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("json_number_limit");return new JsonPrimitive(n);}
            case BOOLEAN: return new JsonPrimitive(r.nextBoolean());
            case NULL: r.nextNull();return JsonNull.INSTANCE;
            default: throw new IllegalArgumentException("invalid_json_token");
        }
    }
    private static JsonElement canonical(JsonElement e){
        if(e.isJsonObject()){JsonObject o=new JsonObject();e.getAsJsonObject().keySet().stream().sorted().forEach(k->o.add(k,canonical(e.getAsJsonObject().get(k))));return o;}
        if(e.isJsonArray()){JsonArray a=new JsonArray();for(JsonElement item:e.getAsJsonArray())a.add(canonical(item));return a;}
        if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isNumber())return new JsonPrimitive(new java.math.BigDecimal(e.getAsString()).stripTrailingZeros());
        return e;
    }
    public static String fingerprint(JsonObject value){
        return fingerprint(value,false);
    }
    /** Opt in for commands where explicit null and omission have different mutation intent.
     * Existing command hashes keep their original representation for durable retries. */
    public static String fingerprint(JsonObject value,boolean retainNulls){
        try{byte[] encoded=retainNulls?NULL_FINGERPRINT_GSON.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8):bytes(canonical(value));return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static String text(JsonObject object, String field, int max) {
        JsonElement e = object.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("string_required:"+field);
        String value = e.getAsString();
        if (value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid_string:"+field);
        return value;
    }
    public static double number(JsonObject o, String field, double min, double max) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("number_required:"+field);
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException("number_out_of_range:"+field);
        return v;
    }
    public static int integer(JsonObject o, String field, int min, int max) {
        double v = number(o, field, min, max);
        if (v != Math.rint(v)) throw new IllegalArgumentException("integer_required:"+field);
        return (int)v;
    }
    public static JsonObject object(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("object_required:"+field);
        return e.getAsJsonObject();
    }
    public static JsonObject tree(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }
}
