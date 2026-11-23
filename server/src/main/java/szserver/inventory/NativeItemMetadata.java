package szserver.inventory;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Versioned startup snapshot exported from actual factories; no native constructors while replaying inventory. */
final class NativeItemMetadata {
    record Row(int width,int height,int limit){}
    static Map<Integer,Row> read(Path definitions)throws Exception{
        try(InputStream resource=NativeItemMetadata.class.getResourceAsStream("/szserver/inventory/native-item-metadata.json.gz")){
            if(resource==null)return Map.of(); // Source-only synthetic fixtures use the explicit legacy reader.
            byte[] raw;try(var in=new GZIPInputStream(resource)){raw=in.readNBytes(2_000_001);}if(raw.length>2_000_000)throw new IOException("native_item_metadata_size");
            return parse(raw,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(definitions))));
        }
    }
    static Map<Integer,Row> parse(byte[] raw,String digest)throws IOException{
            JsonObject data=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();
            if(data.get("version").getAsInt()!=1||!digest.equals(data.get("definitionsSha256").getAsString()))throw new IOException("native_item_metadata_release_mismatch");
            Map<Integer,Row> rows=new HashMap<>();for(var entry:data.getAsJsonObject("rows").entrySet()){int id=Integer.parseInt(entry.getKey());JsonObject v=entry.getValue().getAsJsonObject();int width=v.get("width").getAsBigDecimal().intValueExact(),height=v.get("height").getAsBigDecimal().intValueExact(),limit=v.get("limit").getAsBigDecimal().intValueExact();if(id<1||id>65535||width<1||height<1||width>256||height>256||limit<1)throw new IOException("native_item_metadata_row");rows.put(id,new Row(width,height,limit));}return Map.copyOf(rows);
    }
    private NativeItemMetadata(){}
}
