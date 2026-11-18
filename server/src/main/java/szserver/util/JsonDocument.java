package szserver.util;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.*;

/** Captured documents with streamed arrays. Array sources must describe an immutable publication. */
public final class JsonDocument {
    private static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static final class Rows {
        private final Iterable<JsonObject> source;
        private Rows(Iterable<JsonObject> source){this.source=Objects.requireNonNull(source);}
    }
    private final Map<String,Object> fields;
    public JsonDocument(Map<String,Object> fields){this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}
    public static Rows rows(Iterable<JsonObject> rows){return new Rows(rows);}
    public static JsonDocument of(JsonObject object){Map<String,Object> fields=new LinkedHashMap<>();object.entrySet().forEach(entry->fields.put(entry.getKey(),entry.getValue()));return new JsonDocument(fields);}
    public JsonDocument append(JsonDocument document){Map<String,Object> combined=new LinkedHashMap<>(fields);for(var entry:document.fields.entrySet()){if(combined.containsKey(entry.getKey()))throw new IllegalArgumentException("json_document_duplicate_field");combined.put(entry.getKey(),entry.getValue());}return new JsonDocument(combined);}
    public void write(JsonWriter writer)throws IOException {
        writer.beginObject();
        for(var entry:fields.entrySet()){
            writer.name(entry.getKey());Object value=entry.getValue();
            if(value instanceof JsonDocument document)document.write(writer);
            else if(value instanceof Rows rows){writer.beginArray();for(JsonObject row:rows.source){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("json_document_cancelled");JSON.toJson(row,writer);}writer.endArray();}
            else JSON.toJson(value,value==null?Object.class:value.getClass(),writer);
        }
        writer.endObject();
    }
    /** Compatibility for small internal callers. Network frontends use write() directly. */
    public JsonObject materialize(){
        JsonObject result=new JsonObject();
        fields.forEach((key,value)->{
            if(value instanceof JsonDocument document)result.add(key,document.materialize());
            else if(value instanceof Rows rows){JsonArray array=new JsonArray();for(JsonObject row:rows.source)array.add(row.deepCopy());result.add(key,array);}
            else result.add(key,JSON.toJsonTree(value));
        });
        return result;
    }
}
