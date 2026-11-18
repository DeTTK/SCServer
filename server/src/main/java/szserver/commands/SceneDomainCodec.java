package szserver.commands;

import com.github.luben.zstd.Zstd;
import com.google.gson.*;
import szserver.net.Wire;
import szserver.util.Json;
import szserver.world.SceneLimits;
import java.nio.*;
import java.util.*;

/** Compressed bounded scene metadata batches. No native parsing or file IO. */
final class SceneDomainCodec {
    private static final Gson ROW_JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static int rowBytes(JsonObject row){return ROW_JSON.toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    record Patch(List<JsonObject> objects,List<JsonObject> tombstones,List<JsonObject> changes){}
    static void save(JsonObject event,Patch patch){
        List<byte[]> sections=new ArrayList<>();int kind=0,total=8;
        for(var rows:List.of(patch.objects(),patch.tombstones(),patch.changes())){if(rows.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_domain_patch_count");kind++;JsonArray batch=new JsonArray();int bytes=0;for(JsonObject row:rows){int size=rowBytes(row);if(size>48000)throw new IllegalArgumentException("scene_domain_row_limit");if(batch.size()==32||bytes+size>48000){total=retain(sections,kind,batch,total);batch=new JsonArray();bytes=0;}batch.add(row);bytes+=size;}if(!batch.isEmpty())total=retain(sections,kind,batch,total);}
        if(sections.isEmpty()||sections.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_domain_patch_count");byte[] raw=Wire.bytes(out->{out.writeInt(0x53444d31);out.writeInt(sections.size());for(byte[] section:sections)out.write(section);});if(raw.length>SceneLimits.RAW_BYTES)throw new IllegalArgumentException("scene_domain_patch_raw_limit");byte[] compressed=Zstd.compress(raw,3);if(compressed.length>SceneLimits.COMPRESSED_BYTES)throw new IllegalArgumentException("scene_domain_patch_compressed_limit");String encoded=Base64.getEncoder().encodeToString(compressed);JsonArray parts=new JsonArray();for(int at=0;at<encoded.length();at+=8192)parts.add(encoded.substring(at,Math.min(encoded.length(),at+8192)));event.add("sceneDomain",parts);
    }
    /** Admit each bounded section before retaining it or allocating the combined transaction. */
    private static int retain(List<byte[]> sections,int kind,JsonArray rows,int total){byte[] section=section(kind,rows);if(section.length>SceneLimits.RAW_BYTES-total)throw new IllegalArgumentException("scene_domain_patch_raw_limit");sections.add(section);return total+section.length;}
    private static byte[] section(int kind,JsonArray rows){JsonObject wrapper=new JsonObject();wrapper.add("rows",rows);byte[] body=ROW_JSON.toJson(wrapper).getBytes(java.nio.charset.StandardCharsets.UTF_8);return Wire.bytes(out->{out.writeByte(kind);out.writeInt(body.length);out.write(body);});}
    static Patch read(JsonObject event){
        var value=event.get("sceneDomain");if(value==null||!value.isJsonArray())throw new IllegalArgumentException("scene_domain_parts_limit");var parts=value.getAsJsonArray();if(parts==null||parts.size()<1||parts.size()>SceneLimits.PARTS)throw new IllegalArgumentException("scene_domain_parts_limit");StringBuilder encoded=new StringBuilder();for(var part:parts){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("scene_domain_part_limit");encoded.append(part.getAsString());}byte[] compressed=Base64.getDecoder().decode(encoded.toString());if(compressed.length>SceneLimits.COMPRESSED_BYTES)throw new IllegalArgumentException("scene_domain_compressed_limit");long size=Zstd.decompressedSize(compressed);if(size<8||size>SceneLimits.RAW_BYTES)throw new IllegalArgumentException("scene_domain_expansion_limit");ByteBuffer in=ByteBuffer.wrap(Zstd.decompress(compressed,(int)size));if(in.getInt()!=0x53444d31)throw new IllegalArgumentException("scene_domain_version");int count=in.getInt();if(count<1||count>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_domain_sections_limit");List<JsonObject> objects=new ArrayList<>(),tombstones=new ArrayList<>(),changes=new ArrayList<>();for(int n=0;n<count;n++){if(in.remaining()<5)throw new IllegalArgumentException("scene_domain_section_limit");int kind=in.get()&255,length=in.getInt();if(kind<1||kind>3||length<1||length>65536||length>in.remaining())throw new IllegalArgumentException("scene_domain_section_limit");byte[] body=new byte[length];in.get(body);JsonArray rows=Json.parse(body).getAsJsonArray("rows");if(rows==null||rows.size()<1||rows.size()>32)throw new IllegalArgumentException("scene_domain_rows_limit");for(var rowValue:rows){if(!rowValue.isJsonObject())throw new IllegalArgumentException("scene_domain_row_object");(kind==1?objects:kind==2?tombstones:changes).add(rowValue.getAsJsonObject());}}if(in.hasRemaining())throw new IllegalArgumentException("scene_domain_trailing");return new Patch(List.copyOf(objects),List.copyOf(tombstones),List.copyOf(changes));
    }
    private SceneDomainCodec(){}
}
