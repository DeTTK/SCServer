package szserver.migration;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Offline, checksummed, bounded row reader. SQL/schema text is data, never executed. */
public final class LegacySnapshot {
    private static final int HISTORY_ROWS=10_000_000;private static final long HISTORY_BYTES=8L*1024*1024*1024;
    private static boolean largeOffline(String table){return Set.of("scene_objects","scene_object_tombstones","scene_object_changes","imeditor_objects","imeditor_events","imeditor_object_components","native_block_overrides","native_authority_world_events","character_inventory","character_equipment","character_storage","world_drops","character_preferences","character_vitals","character_effects","character_migrations","spawn_points","character_loadouts","native_authority_players","native_authority_chunk_records").contains(table);}
    @FunctionalInterface public interface Visitor{void row(JsonObject row)throws Exception;}
    private final Path root;
    private final Map<String,JsonObject> tables=new TreeMap<>();
    private final JsonObject manifest;
    private final String manifestSha256;
    public LegacySnapshot(Path directory)throws Exception{
        root=directory.toRealPath();Path file=root.resolve("manifest.json");if(Files.size(file)>65536)throw new IOException("legacy_manifest_limit");byte[] manifestBytes=Files.readAllBytes(file);manifest=Json.parse(manifestBytes);manifestSha256=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes));
        if(!Json.text(manifest,"format",64).equals("sz-legacy-sqlite-v1")||!manifest.get("complete").getAsBoolean()||!manifest.get("consistentReadTransaction").getAsBoolean())throw new IOException("legacy_snapshot_incomplete");
        long bytes=0,rows=0;for(JsonElement element:manifest.getAsJsonArray("tables")){JsonObject table=element.getAsJsonObject();String name=Json.text(table,"name",128),part=Json.text(table,"file",64);if(!part.matches("table-[0-9]+\\.ndjson")||tables.putIfAbsent(name,table)!=null)throw new IOException("legacy_snapshot_table");Path path=root.resolve(part);if(Files.isSymbolicLink(path)||!path.toRealPath().startsWith(root)||!Files.isRegularFile(path))throw new IOException("legacy_snapshot_path");long size=Files.size(path);if(largeOffline(name)){if(size>HISTORY_BYTES)throw new IOException("legacy_snapshot_history_limit");}else{bytes=Math.addExact(bytes,size);rows=Math.addExact(rows,count(name));if(bytes>64L*1024*1024||rows>100000)throw new IOException("legacy_snapshot_limit");}if((count(name)==0)!=(size==0)||size!=table.get("bytes").getAsLong()||!sha(path).equals(Json.text(table,"sha256",64)))throw new IOException("legacy_snapshot_checksum");}
        if(bytes>64L*1024*1024||rows>100000||tables.size()>128)throw new IOException("legacy_snapshot_limit");
    }
    public String registryHash(){return Json.text(manifest,"itemRegistrySha256",64);}
    public String purpose(){return manifest.has("purpose")?Json.text(manifest,"purpose",64):"realm";}
    public String manifestSha256(){return manifestSha256;}
    public boolean hasTable(String name){return tables.containsKey(name);}
    /** AUTOINCREMENT heads include deleted rows and must not be inferred from current maxima. */
    public long sqliteSequence(String name){if(!manifest.has("sqliteSequences")||!manifest.get("sqliteSequences").isJsonObject())throw new IllegalArgumentException("legacy_snapshot_sequence_missing");JsonElement value=manifest.getAsJsonObject("sqliteSequences").get(name);if(value==null)return 0;if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]+"))throw new IllegalArgumentException("legacy_snapshot_sequence_invalid");try{return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_snapshot_sequence_invalid",error);}}
    public Set<String> columns(String name){JsonObject table=tables.get(name);Set<String> result=new HashSet<>();if(table!=null)for(JsonElement column:table.getAsJsonArray("columns"))result.add(column.getAsString());return Set.copyOf(result);}
    public Map<String,Integer> counts(){Map<String,Integer> result=new TreeMap<>();tables.keySet().forEach(name->result.put(name,count(name)));return Map.copyOf(result);}
    public int count(String table){JsonObject row=tables.get(table);return row==null?0:Json.integer(row,"rows",0,largeOffline(table)?HISTORY_ROWS:100000);}
    public void visit(String name,Visitor action)throws Exception{
        JsonObject table=tables.get(name);if(table==null)return;Path file=root.resolve(table.get("file").getAsString());MessageDigest digest=MessageDigest.getInstance("SHA-256");int count=0;
        int rowLimit=Set.of("native_block_overrides","imeditor_objects","imeditor_events","imeditor_object_components").contains(name)?2*1024*1024:65536;
        try(var input=new BufferedInputStream(new DigestInputStream(Files.newInputStream(file),digest))){byte[] line;while((line=line(input,rowLimit))!=null){JsonObject row=Json.parse(line,Set.of("native_block_overrides","imeditor_objects","imeditor_events","imeditor_object_components").contains(name)?2*1024*1024:16384);Set<String> expected=new HashSet<>();for(JsonElement column:table.getAsJsonArray("columns"))expected.add(column.getAsString());if(!expected.equals(row.keySet()))throw new IOException("legacy_row_columns");action.row(row);if(++count>(largeOffline(name)?HISTORY_ROWS:100000))throw new IOException("legacy_rows_limit");}}
        if(count!=count(name)||!HexFormat.of().formatHex(digest.digest()).equals(table.get("sha256").getAsString()))throw new IOException("legacy_snapshot_changed");
    }
    private static byte[] line(InputStream input,int limit)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;while((value=input.read())>=0){if(value=='\n')return bytes.toByteArray();if(bytes.size()>=limit)throw new IOException("legacy_row_limit");bytes.write(value);}return bytes.size()==0?null:bytes.toByteArray();}
    public void archive(Path target)throws Exception{Files.createDirectory(target);Files.copy(root.resolve("manifest.json"),target.resolve("manifest.json"));if(!sha(target.resolve("manifest.json")).equals(manifestSha256))throw new IOException("legacy_snapshot_changed");for(JsonObject table:tables.values()){String part=table.get("file").getAsString();Path copy=target.resolve(part);Files.copy(root.resolve(part),copy);if(!sha(copy).equals(table.get("sha256").getAsString()))throw new IOException("legacy_snapshot_changed");}}
    public static String sha(Path file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=new DigestInputStream(Files.newInputStream(file),digest)){byte[] buffer=new byte[16384];while(input.read(buffer)>=0){}}return HexFormat.of().formatHex(digest.digest());}
}
