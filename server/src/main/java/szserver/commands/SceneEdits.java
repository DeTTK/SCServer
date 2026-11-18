package szserver.commands;

import com.github.luben.zstd.Zstd;
import com.google.gson.*;
import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Bounded durable snapshots; never executed on a transport or world owner. */
final class SceneEdits {
    private static final int MAGIC=0x53454531,BATCH_MAGIC=0x53454532,PLACEMENT_MAGIC=0x53454533,OCCURRENCE_MAGIC=0x53454534,MAX_RAW=SceneLimits.RAW_BYTES,MAX_COMPRESSED=SceneLimits.COMPRESSED_BYTES;
    record Change(int x,int z,UUID id,ScenePayload.Entry before,ScenePayload.Entry after,boolean sourcePlacement){
        Change(int x,int z,UUID id,ScenePayload.Entry before,ScenePayload.Entry after){this(x,z,id,before,after,outside(x,z,before)||outside(x,z,after));}
        private static boolean outside(int x,int z,ScenePayload.Entry entry){return entry!=null&&entry.occurrences().stream().anyMatch(o->Math.floor(o.object().x()/16)!=x||Math.floor(o.object().z()/16)!=z);}
        Change reverse(){return new Change(x,z,id,after,before,sourcePlacement);}
    }
    /** Exact DataOutput size, checked before copying native bodies or allocating a serialization buffer. */
    static int rawSize(List<Change> changes){
        int count=changes.size();if(count<1||count>SceneLimits.PLACEMENTS)throw new IllegalArgumentException("scene_edit_count");
        boolean occurrences=occurrences(changes),placements=occurrences||changes.stream().anyMatch(Change::sourcePlacement);
        long size=placements||count>2?8:5;
        for(Change change:changes){
            size+=(placements?1:0)+24+2;
            for(ScenePayload.Entry entry:new ScenePayload.Entry[]{change.before,change.after})if(entry!=null){
                if(occurrences)size+=4;
                for(var o:entry.occurrences()){size+=1+2+modifiedUtfSize(o.object().asset())+4+(long)o.object().recordSize();if(size>MAX_RAW)throw new IllegalArgumentException("scene_edit_raw_limit");}
            }
            if(size>MAX_RAW)throw new IllegalArgumentException("scene_edit_raw_limit");
        }
        return (int)size;
    }
    private static boolean occurrences(List<Change> changes){for(var c:changes)if(c.before()!=null&&c.before().occurrences().size()>1||c.after()!=null&&c.after().occurrences().size()>1)return true;return false;}
    /** writeUTF counts UTF-16 code units: NUL costs two bytes and a surrogate pair costs six. */
    private static int modifiedUtfSize(String value){
        int size=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);size+=c>=1&&c<=127?1:c<=2047?2:3;if(size>65535)throw new IllegalArgumentException("scene_edit_asset");}return size;
    }
    static void save(JsonObject event,List<Change> changes){
        int expectedSize=rawSize(changes);
        boolean occurrences=occurrences(changes),placements=occurrences||changes.stream().anyMatch(Change::sourcePlacement);
        byte[] raw=Wire.bytes(out->{out.writeInt(occurrences?OCCURRENCE_MAGIC:placements?PLACEMENT_MAGIC:changes.size()>2?BATCH_MAGIC:MAGIC);if(placements||changes.size()>2)out.writeInt(changes.size());else out.writeByte(changes.size());for(Change change:changes){if(placements)out.writeBoolean(change.sourcePlacement);out.writeInt(change.x);out.writeInt(change.z);out.writeLong(change.id.getMostSignificantBits());out.writeLong(change.id.getLeastSignificantBits());for(ScenePayload.Entry entry:new ScenePayload.Entry[]{change.before,change.after}){out.writeBoolean(entry!=null);if(entry!=null){if(occurrences)out.writeInt(entry.occurrences().size());for(var o:entry.occurrences()){out.writeBoolean(o.container());out.writeUTF(o.object().asset());byte[] body=o.object().record();out.writeInt(body.length);out.write(body);}}}}});
        if(raw.length!=expectedSize)throw new IllegalStateException("scene_edit_size_mismatch");byte[] compressed=Zstd.compress(raw,3);if(compressed.length>MAX_COMPRESSED)throw new IllegalArgumentException("scene_edit_compressed_limit");String text=Base64.getEncoder().encodeToString(compressed);JsonArray parts=new JsonArray();for(int at=0;at<text.length();at+=8192)parts.add(text.substring(at,Math.min(at+8192,text.length())));event.add("sceneChanges",parts);
    }
    static List<Change> read(JsonObject event){
        JsonElement value=event.get("sceneChanges");if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()<1||value.getAsJsonArray().size()>SceneLimits.PARTS)throw new IllegalArgumentException("scene_edit_parts_limit");StringBuilder encoded=new StringBuilder();for(JsonElement part:value.getAsJsonArray()){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IllegalArgumentException("scene_edit_part_limit");encoded.append(part.getAsString());}
        byte[] compressed=Base64.getDecoder().decode(encoded.toString());if(compressed.length>MAX_COMPRESSED)throw new IllegalArgumentException("scene_edit_compressed_limit");long size=Zstd.decompressedSize(compressed);if(size<5||size>MAX_RAW)throw new IllegalArgumentException("scene_edit_decompression_limit");ByteBuffer input=ByteBuffer.wrap(Zstd.decompress(compressed,(int)size));int magic=input.getInt();if(magic!=MAGIC&&magic!=BATCH_MAGIC&&magic!=PLACEMENT_MAGIC&&magic!=OCCURRENCE_MAGIC)throw new IllegalArgumentException("scene_edit_version");int count=magic==MAGIC?input.get()&255:input.getInt();if(count<1||count>(magic==MAGIC?2:SceneLimits.PLACEMENTS))throw new IllegalArgumentException("scene_edit_count");List<Change> changes=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(int n=0;n<count;n++){boolean placement=(magic==PLACEMENT_MAGIC||magic==OCCURRENCE_MAGIC)&&ScenePayload.bool(input);int x=input.getInt(),z=input.getInt();if(Math.abs((long)x)>szserver.world.MapBounds.CHUNK||Math.abs((long)z)>szserver.world.MapBounds.CHUNK)throw new IllegalArgumentException("scene_edit_chunk_bounds");UUID id=new UUID(input.getLong(),input.getLong());if(!keys.add(x+":"+z+":"+id))throw new IllegalArgumentException("scene_edit_duplicate");ScenePayload.Entry[] states=new ScenePayload.Entry[2];for(int i=0;i<2;i++)if(ScenePayload.bool(input)){int members=magic==OCCURRENCE_MAGIC?input.getInt():1;if(members<1||members>ScenePayload.MAX_OBJECTS||members>input.remaining()/85)throw new IllegalArgumentException("scene_edit_occurrence_count");List<ScenePayload.Occurrence> values=new ArrayList<>(members);for(int m=0;m<members;m++){boolean container=ScenePayload.bool(input);byte[] name=ScenePayload.utf(input);String asset;try{asset=new DataInputStream(new ByteArrayInputStream(name)).readUTF();}catch(IOException error){throw new IllegalArgumentException("scene_edit_asset",error);}if(asset.isBlank()||asset.length()>1024||asset.indexOf('\0')>=0)throw new IllegalArgumentException("scene_edit_asset");int length=input.getInt();if(length<78||length>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_edit_object_limit");SceneObject object=new SceneObject(asset,ScenePayload.take(input,length));if(!object.identity().equals(id))throw new IllegalArgumentException("scene_edit_identity");if(!placement&&(Math.floor(object.x()/16)!=x||Math.floor(object.z()/16)!=z)||!szserver.world.MapBounds.scene(object.x(),object.y(),object.z()))throw new IllegalArgumentException("scene_edit_position");values.add(new ScenePayload.Occurrence(container,object));}states[i]=ScenePayload.Entry.of(values);}
            if(Objects.equals(states[0],states[1])&&!"scene_import".equals(event.has("type")?event.get("type").getAsString():""))throw new IllegalArgumentException("scene_edit_no_change");changes.add(new Change(x,z,id,states[0],states[1],placement));
        }if(input.hasRemaining())throw new IllegalArgumentException("scene_edit_trailing");return List.copyOf(changes);
    }
}
