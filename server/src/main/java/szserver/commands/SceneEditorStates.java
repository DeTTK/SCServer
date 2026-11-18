package szserver.commands;

import com.google.gson.*;
import com.github.luben.zstd.Zstd;
import szserver.client.*;
import szserver.net.Wire;
import szserver.util.Json;
import szserver.world.*;
import java.nio.*;
import java.util.*;

/** Command-owner pending native construction. Snapshots never become fabricated world geometry. */
final class SceneEditorStates implements AutoCloseable {
    private static final int MAGIC=0x53455331,RAW_LIMIT=SceneLimits.RAW_BYTES,COMPRESSED_LIMIT=SceneLimits.COMPRESSED_BYTES,ROW_LIMIT=192*1024;
    private static final long BYTE_LIMIT=8L*1024*1024*1024;
    static long byteLimit(){return BYTE_LIMIT;}static int rowLimit(){return 1000000;}
    record Change(UUID id,JsonObject before,JsonObject after){Change reverse(){return new Change(id,after,before);}}
    private final DiskSceneValues<JsonObject> states;
    SceneEditorStates(){this(java.nio.file.Path.of("var"));}
    SceneEditorStates(java.nio.file.Path base){states=new DiskSceneValues<>(base,JsonObject::deepCopy,JsonObject::deepCopy);}
    private record Publication(int objects,long bytes){}
    private volatile Publication view=new Publication(0,0);private long bytes;
    JsonObject current(UUID id){return states.get(id);}
    Map<UUID,JsonObject> currentRows(){return states.rows();}
    Map<UUID,JsonObject> checkpointRows(){return states.frozenRows();}
    long checkpointBytes(){return bytes;}
    void restoreCheckpointRow(UUID id,JsonObject source){validateRow(source);JsonObject row=source.deepCopy();int size=size(row);if(states.containsKey(id)||states.size()>=rowLimit()||bytes+size>BYTE_LIMIT)throw new IllegalArgumentException("checkpoint_editor_duplicate_or_limit");states.admit(states.encodedSize(id,row));states.write(id,row);bytes+=size;}
    JsonObject snapshot(UUID id){return states.snapshot(id);}
    static boolean deleted(JsonObject row){return row.get("deleted").getAsBoolean();}
    static boolean deferFullSave(JsonObject row){return row!=null&&(row.get("asset").getAsString().isEmpty()||row.has("reason")&&row.get("reason").getAsString().equals("scene_editor_unknown_asset"));}
    static boolean unresolvedSnapshot(JsonObject row){return row!=null&&row.has("unresolvedSnapshot")&&row.get("unresolvedSnapshot").getAsBoolean();}
    static float[] matrix(JsonObject row){JsonArray a=row.getAsJsonArray("transform");if(a.isEmpty())return null;float[] result=new float[16];for(int i=0;i<16;i++)result[i]=a.get(i).getAsFloat();return result;}
    static World.Vec position(JsonObject row){JsonArray a=row.has("position")?row.getAsJsonArray("position"):null;if(a!=null&&!a.isEmpty())return new World.Vec(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());JsonArray m=row.getAsJsonArray("transform");return m.isEmpty()?null:new World.Vec(m.get(12).getAsDouble(),m.get(13).getAsDouble(),m.get(14).getAsDouble());}
    static byte[] binary(JsonObject row,String key){return row.has(key)?Base64.getDecoder().decode(row.get(key).getAsString()):null;}
    static JsonObject nativeState(JsonObject previous,String asset,SceneEditPackets.Edit edit,String reason,boolean hideout){
        JsonObject row=previous==null?new JsonObject():previous.deepCopy();row.remove("unresolvedSnapshot");row.addProperty("asset",asset);if(asset.isEmpty())row.addProperty("unresolvedAsset",true);else row.remove("unresolvedAsset");row.addProperty("deleted",false);row.addProperty("reason",reason);row.addProperty("hideout",hideout);row.remove("parent");row.remove("group");if(edit.parent()!=null)row.addProperty("parent",edit.parent().toString());if(edit.group()!=null)row.addProperty("group",edit.group().toString());
        JsonArray transform=new JsonArray();for(float v:edit.transform())transform.add((double)v);row.add("transform",transform);row.remove("position");
        if(edit.properties()!=null){row.add("properties",Json.tree(edit.properties()));explicitVisibility(row);}validateRow(row);return row;
    }
    static boolean constructionFailure(IllegalArgumentException failure){String detail=failure.getMessage();return detail!=null&&(detail.equals("scene_editor_unknown_asset")||detail.equals("scene_editor_template_not_found")||detail.startsWith("scene_default_factory_failed:"));}
    static void validateRow(JsonObject row){
        if(!Set.of("asset","transform","properties","fullSave","settings","deleted","reason","hideout","parent","group","legacyState","legacyFullSave","position","legacy","unresolvedAsset","unresolvedSnapshot","cullingCleanup").containsAll(row.keySet()))throw new IllegalArgumentException("scene_editor_state_fields");
        if(row.has("cullingCleanup")){
            JsonObject c=Json.object(row,"cullingCleanup");if(!Set.of("ignore","clearedVolumes").containsAll(c.keySet())||c.isEmpty())throw new IllegalArgumentException("scene_editor_state_culling");
            if(c.has("ignore")&&(!c.get("ignore").isJsonPrimitive()||!c.getAsJsonPrimitive("ignore").isBoolean()||!c.get("ignore").getAsBoolean()))throw new IllegalArgumentException("scene_editor_state_culling");
            if(c.has("clearedVolumes")){JsonArray a=c.getAsJsonArray("clearedVolumes");if(a==null||a.isEmpty()||a.size()>128)throw new IllegalArgumentException("scene_editor_state_culling");Set<UUID> ids=new HashSet<>();for(var v:a){String text=v.getAsString();UUID id=UUID.fromString(text);if(!id.toString().equals(text)||!ids.add(id))throw new IllegalArgumentException("scene_editor_state_culling");}}
        }
        boolean legacy=row.has("legacy")&&row.get("legacy").isJsonPrimitive()&&row.getAsJsonPrimitive("legacy").isBoolean()&&row.get("legacy").getAsBoolean();
        if(row.has("legacy")&&!legacy)throw new IllegalArgumentException("scene_editor_state_legacy");
        boolean unresolved=row.has("unresolvedAsset");
        if(unresolved&&(!row.get("unresolvedAsset").isJsonPrimitive()||!row.getAsJsonPrimitive("unresolvedAsset").isBoolean()||!row.get("unresolvedAsset").getAsBoolean()||!row.has("properties")||row.get("hideout").getAsBoolean()||!row.get("asset").getAsString().isEmpty()))throw new IllegalArgumentException("scene_editor_state_unresolved_asset");
        boolean snapshot=row.has("unresolvedSnapshot");if(snapshot&&(!row.get("unresolvedSnapshot").isJsonPrimitive()||!row.getAsJsonPrimitive("unresolvedSnapshot").isBoolean()||!row.get("unresolvedSnapshot").getAsBoolean()||legacy||unresolved||!row.get("asset").getAsString().isEmpty()||row.get("hideout").getAsBoolean()||!row.getAsJsonArray("transform").isEmpty()||row.has("properties")||row.has("position")||!row.has("fullSave")))throw new IllegalArgumentException("scene_editor_state_unresolved_snapshot");
        if(!row.get("asset").isJsonPrimitive()||!row.getAsJsonPrimitive("asset").isString()||row.get("asset").getAsString().length()>1024||row.get("asset").getAsString().indexOf('\0')>=0||!legacy&&!unresolved&&!snapshot&&row.get("asset").getAsString().isBlank())throw new IllegalArgumentException("scene_editor_state_asset");
        if(legacy||snapshot){JsonArray a=row.getAsJsonArray("transform");if(a==null||!a.isEmpty()&&a.size()!=16)throw new IllegalArgumentException("scene_editor_state_matrix");for(var v:a)number(v);if(row.has("position")){JsonArray p=row.getAsJsonArray("position");if(p==null||!p.isEmpty()&&p.size()!=3)throw new IllegalArgumentException("scene_editor_state_position");for(var v:p)number(v);}}else SceneDomain.transform(row,"transform",false);
        if(row.has("legacyState")&&(!legacy||!row.get("legacyState").isJsonObject()))throw new IllegalArgumentException("scene_editor_state_legacy");
        for(String key:List.of("parent","group"))if(row.has(key))UUID.fromString(Json.text(row,key,36));if(!row.has("deleted")||!row.get("deleted").isJsonPrimitive()||!row.getAsJsonPrimitive("deleted").isBoolean())throw new IllegalArgumentException("scene_editor_state_deleted");
        if(!row.has("hideout")||!row.get("hideout").isJsonPrimitive()||!row.getAsJsonPrimitive("hideout").isBoolean())throw new IllegalArgumentException("scene_editor_state_hideout");
        float[] m=matrix(row);if(m!=null)for(float f:m)if(!Float.isFinite(f)||Math.abs(f)>30000000)throw new IllegalArgumentException("scene_editor_state_matrix");
        if(row.has("reason")&&row.get("reason").getAsString().length()>512)throw new IllegalArgumentException("scene_editor_state_reason");
        if(row.has("properties")){var p=Json.GSON.fromJson(row.get("properties"),SceneEditPackets.Properties.class);if(p==null||p.mode()<0||p.mode()>4||p.renderFlags() < -128||p.renderFlags()>255||p.vector1()==null||p.vector2()==null||p.vector3()==null||p.editorMode()!=null&&(p.editorMode()<0||p.editorMode()>2))throw new IllegalArgumentException("scene_editor_state_properties");for(var v:List.of(p.vector1(),p.vector2(),p.vector3()))for(double n:new double[]{v.x(),v.y(),v.z()})if(!Double.isFinite(n)||Math.abs(n)>30000000)throw new IllegalArgumentException("scene_editor_state_vector");for(float f:new float[]{p.float1(),p.float2()})if(!Float.isFinite(f)||Math.abs(f)>30000000)throw new IllegalArgumentException("scene_editor_state_number");for(String t:new String[]{p.tag(),p.instanceName(),p.stateName()})if(t!=null&&(t.length()>1024||t.indexOf('\0')>=0))throw new IllegalArgumentException("scene_editor_state_text");}
        for(String key:List.of("fullSave","settings")){byte[] body=binary(row,key);if(body!=null&&(key.equals("settings")&&body.length<1||body.length>SceneConfigurationPackets.MAX_NBT))throw new IllegalArgumentException("scene_editor_state_payload_limit");}
        if(row.has("legacyFullSave")){byte[] body=binary(row,"legacyFullSave");if(!legacy||body.length<1||body.length>SceneConfigurationPackets.MAX_NBT+5)throw new IllegalArgumentException("scene_editor_state_payload_limit");ByteBuffer in=ByteBuffer.wrap(body);int size=ScenePayload.varint(in);if(size!=in.remaining())throw new IllegalArgumentException("legacy_scene_full_save_frame");if(size>0&&!Arrays.equals(ScenePayload.take(in,size),binary(row,"fullSave")))throw new IllegalArgumentException("legacy_scene_full_save_body");}
    }
    private static void number(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble())||Math.abs(value.getAsDouble())>30000000)throw new IllegalArgumentException("scene_editor_state_number");}
    private static float[] objectMatrix(SceneObject object){float[] result=new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};ByteBuffer in=ByteBuffer.wrap(object.record());in.position(SceneObject.prefix(object.record()).transformAt());int[] indexes={0,1,2,4,5,6,8,9,10,12,13,14};for(int index:indexes)result[index]=in.getFloat();return result;}
    static SceneObject materialize(SceneObject object,JsonObject row,String behavior){
        float[] m=matrix(row);if(m==null)m=objectMatrix(object);SceneObject result=SceneInspectorProjection.transform(object,m);byte[] save=binary(row,"fullSave"),settings=binary(row,"settings");
        if(save!=null&&save.length>0){boolean current=true;try{SceneAttributes.validateFullSave(save,behavior);}catch(IllegalArgumentException invalid){if(!row.has("legacyFullSave"))throw invalid;current=false;}result=current?SceneAttributes.configured(result,save,behavior):LegacySceneState.apply(result,m,null,binary(row,"legacyFullSave"),behavior);}
        if(settings!=null)result=SceneSpecialConfiguration.configured(result,settings,behavior);
        if(row.has("legacyState"))result=LegacySceneState.apply(result,m,row.getAsJsonObject("legacyState"),null,behavior);
        if(row.has("properties"))result=SceneInspectorProjection.apply(result,new SceneEditPackets.Edit(result.identity(),result.asset(),m,Json.GSON.fromJson(row.get("properties"),SceneEditPackets.Properties.class),null,null,false),behavior);
        return culling(result,row,behavior,false);
    }
    private static void explicitVisibility(JsonObject row){if(!row.has("cullingCleanup"))return;JsonObject c=row.getAsJsonObject("cullingCleanup");c.remove("ignore");if(c.isEmpty())row.remove("cullingCleanup");}
    /** Removed associations survive template retries; an explicit newer Inspector visibility wins. */
    static SceneObject culling(SceneObject object,JsonObject row,String behavior,boolean explicitVisibility){
        if(row==null||!row.has("cullingCleanup"))return object;JsonObject c=row.getAsJsonObject("cullingCleanup");UUID ref=SceneCulling.read(object,behavior).volume();boolean clear=false;
        if(ref!=null&&c.has("clearedVolumes"))for(var value:c.getAsJsonArray("clearedVolumes"))if(ref.toString().equals(value.getAsString())){clear=true;break;}
        return SceneCulling.apply(object,behavior,clear,null,!explicitVisibility&&c.has("ignore")?true:null);
    }
    void plan(List<Change> changes,UUID id,JsonObject after){if(after!=null)after=Json.parse(Json.bytes(after),ROW_LIMIT);JsonObject before=states.get(id);if(!Objects.equals(before,after))changes.add(new Change(id,before,after));}
    void finish(JsonObject event,List<Change> changes){if(changes.isEmpty())return;save(event,changes);validate(event);}
    void remember(JsonObject event,UUID id,JsonObject after){var changes=new ArrayList<>(read(event));plan(changes,id,after);finish(event,changes);}
    void configuration(JsonObject event,UUID id,byte[] payload,boolean fullSave){JsonObject current=states.get(id);if(current==null||deleted(current))throw new IllegalArgumentException("scene_object_not_found");JsonObject after=current.deepCopy();after.addProperty(fullSave?"fullSave":"settings",Base64.getEncoder().encodeToString(payload));if(fullSave){after.remove("settings");if(!Arrays.equals(binary(current,"fullSave"),payload))after.remove("legacyFullSave");}remember(event,id,after);}
    void orphanConfiguration(JsonObject event,UUID id,byte[] payload){JsonObject row=new JsonObject();row.addProperty("asset","");row.add("transform",new JsonArray());row.addProperty("unresolvedSnapshot",true);row.addProperty("hideout",false);row.addProperty("deleted",false);row.addProperty("reason","scene_editor_snapshot_unresolved");row.addProperty("fullSave",Base64.getEncoder().encodeToString(payload));validateRow(row);remember(event,id,row);}
    void reverse(JsonObject original,JsonObject event){var changes=read(original);if(!changes.isEmpty()){save(event,changes.stream().map(Change::reverse).toList());validate(event);}}
    static void save(JsonObject event,List<Change> changes){
        if(changes.isEmpty()||changes.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_editor_state_count");
        long bytes=8;for(var change:changes){bytes+=24;for(var row:new JsonObject[]{change.before(),change.after()})if(row!=null){int size=Json.bytes(row).length;if(size>ROW_LIMIT)throw new IllegalArgumentException("scene_editor_state_row_limit");bytes+=size;}if(bytes>RAW_LIMIT)throw new IllegalArgumentException("scene_editor_state_raw_limit");}
        byte[] raw=Wire.bytes(out->{out.writeInt(MAGIC);out.writeInt(changes.size());for(var c:changes){out.writeLong(c.id().getMostSignificantBits());out.writeLong(c.id().getLeastSignificantBits());for(var row:new JsonObject[]{c.before(),c.after()}){byte[] body=row==null?new byte[0]:Json.bytes(row);out.writeInt(body.length);out.write(body);}}});
        if(raw.length>RAW_LIMIT)throw new IllegalArgumentException("scene_editor_state_raw_limit");byte[] packed=Zstd.compress(raw,3);if(packed.length>COMPRESSED_LIMIT)throw new IllegalArgumentException("scene_editor_state_compressed_limit");JsonArray parts=new JsonArray();String text=Base64.getEncoder().encodeToString(packed);for(int at=0;at<text.length();at+=8192)parts.add(text.substring(at,Math.min(at+8192,text.length())));event.add("editorStateChanges",parts);
    }
    static List<Change> read(JsonObject event){
        if(!event.has("editorStateChanges"))return List.of();JsonArray parts=event.getAsJsonArray("editorStateChanges");if(parts.size()<1||parts.size()>SceneLimits.PARTS)throw new IllegalArgumentException("scene_editor_state_parts");StringBuilder text=new StringBuilder();for(var p:parts){if(!p.isJsonPrimitive()||!p.getAsJsonPrimitive().isString()||p.getAsString().length()>8192)throw new IllegalArgumentException("scene_editor_state_part");text.append(p.getAsString());}
        byte[] packed=Base64.getDecoder().decode(text.toString());long length=Zstd.decompressedSize(packed);if(packed.length>COMPRESSED_LIMIT||length<8||length>RAW_LIMIT)throw new IllegalArgumentException("scene_editor_state_expansion_limit");ByteBuffer in=ByteBuffer.wrap(Zstd.decompress(packed,(int)length));if(in.getInt()!=MAGIC)throw new IllegalArgumentException("scene_editor_state_version");int count=in.getInt();if(count<1||count>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_editor_state_count");var result=new ArrayList<Change>();Set<UUID> ids=new HashSet<>();
        for(int n=0;n<count;n++){UUID id=new UUID(in.getLong(),in.getLong());if(!ids.add(id))throw new IllegalArgumentException("scene_editor_state_duplicate");JsonObject[] rows=new JsonObject[2];for(int i=0;i<2;i++){int size=in.getInt();if(size<0||size>RAW_LIMIT)throw new IllegalArgumentException("scene_editor_state_row_limit");if(size>0){rows[i]=Json.parse(ScenePayload.take(in,size),ROW_LIMIT);validateRow(rows[i]);}}if(Objects.equals(rows[0],rows[1]))throw new IllegalArgumentException("scene_editor_state_no_change");result.add(new Change(id,rows[0],rows[1]));}if(in.hasRemaining())throw new IllegalArgumentException("scene_editor_state_trailing");return List.copyOf(result);
    }
    void validate(JsonObject event){long projected=bytes,appendBytes=0;int count=states.size();for(var c:read(event)){if(!Objects.equals(states.get(c.id()),c.before()))throw new IllegalArgumentException("scene_editor_state_conflicts_with_later_edit");projected+=size(c.after())-size(c.before());appendBytes+=states.encodedSize(c.id(),c.after());if(c.before()==null)count++;if(c.after()==null)count--;}if(count>rowLimit()||projected>BYTE_LIMIT)throw new IllegalArgumentException("scene_editor_state_capacity");states.admit(appendBytes);}
    private static int size(JsonObject row){return row==null?0:Json.bytes(row).length;}
    void apply(JsonObject event){validate(event);for(var c:read(event)){bytes+=size(c.after())-size(c.before());if(c.after()==null)states.erase(c.id());else states.write(c.id(),c.after());}}
    void publish(){states.publish();view=new Publication(states.size(),bytes);}
    Map<String,Object> metrics(){Publication current=view;return Map.of("objects",current.objects(),"bytes",current.bytes(),"byteLimit",BYTE_LIMIT,"rowLimit",rowLimit(),"rowIndex",states.metrics());}
    boolean healthy(){return states.healthy();}
    Map<String,Object> compactRows(boolean force){return states.compact(force);}
    public void close(){states.close();bytes=0;view=new Publication(0,0);}
    void release(){close();}
    static List<PortalMetadata> portals(JsonObject event){Set<UUID> nativeAfter=new HashSet<>();if(event.has("sceneChanges"))for(var c:SceneEdits.read(event))if(c.after()!=null)nativeAfter.add(c.id());List<PortalMetadata> result=new ArrayList<>();for(var c:read(event))if(!nativeAfter.contains(c.id())){JsonObject row=c.after();if(unresolvedSnapshot(row)){if(c.before()==null||unresolvedSnapshot(c.before()))continue;result.add(new PortalMetadata(c.id(),"",null,0,true));continue;}if(row==null&&unresolvedSnapshot(c.before()))continue;JsonArray m=row==null?null:row.getAsJsonArray("transform");double[] values=m==null||m.isEmpty()?null:new double[16];if(values!=null)for(int i=0;i<16;i++)values[i]=m.get(i).getAsDouble();result.add(new PortalMetadata(c.id(),row==null?"":row.get("asset").getAsString(),row==null?null:position(row),PortalMetadata.matrixScale(values),row==null||deleted(row)));}return List.copyOf(result);}
}
