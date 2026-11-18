package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.util.JsonPages;
import szserver.world.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Single command-owner legacy scene domain, with immutable published reads. Physical geometry stays in World. */
final class SceneDomain implements AutoCloseable {
    static final int ROW_LIMIT=1000000;static final long BYTE_LIMIT=8L*1024*1024*1024;
    static long byteLimit(){return BYTE_LIMIT;}static int rowLimit(){return ROW_LIMIT;}
    private final SceneRows rowStore;private final SceneRows.Index objects,tombstones;private final SceneChangeLog changes;private String logicalWorld="dev:origin";private long head,bytes;
    SceneDomain(){this(java.nio.file.Path.of("var"));}
    SceneDomain(java.nio.file.Path base){rowStore=new SceneRows(base);objects=rowStore.index();tombstones=rowStore.index();try{changes=new SceneChangeLog(base);}catch(RuntimeException error){rowStore.close();throw error;}}
    private final Set<Integer> dirtyObjects=new HashSet<>();
    record CheckpointView(String world,long head,long bytes,Map<String,JsonObject> objects,Map<String,JsonObject> tombstones,Iterable<JsonObject> changes,long changeCount,long changeBytes){}
    CheckpointView checkpointView(){var history=changes.capture();return new CheckpointView(logicalWorld,head,bytes,objects.frozen(),tombstones.frozen(),changes.rows(history),history.count(),history.bytes());}
    void restoreCheckpointWorld(String world){if(!objects.isEmpty()||!tombstones.isEmpty()||changes.capture().count()!=0)throw new IllegalStateException("checkpoint_domain_not_empty");logicalWorld=Json.text(Json.tree(Map.of("world",world)),"world",1024);}
    void restoreCheckpointRow(int kind,JsonObject source){
        validate(source,kind,logicalWorld);JsonObject row=source.deepCopy();int size=SceneDomainCodec.rowBytes(row);if(size>65536||kind!=3&&bytes+size>BYTE_LIMIT)throw new IllegalArgumentException("checkpoint_domain_bytes");
        if(kind==3){long sequence=integer(row,"sequence",1);if(sequence<=head&&changes.contains(sequence))throw new IllegalArgumentException("checkpoint_domain_duplicate_or_limit");changes.append(List.of(row),true);head=Math.max(head,sequence);}
        else{String id=uuid(row,"object_id",false);var target=kind==1?objects:tombstones;if(objects.size()+tombstones.size()>=ROW_LIMIT||objects.containsKey(id)||tombstones.containsKey(id))throw new IllegalArgumentException("checkpoint_domain_duplicate_or_limit");target.writeRow(id,row);dirtyObjects.add(part(id));bytes+=size;}
    }
    private record View(NavigableMap<Integer,SceneRows.Snapshot> objects,NavigableMap<Integer,SceneRows.Snapshot> tombstones,SceneChangeLog.Snapshot changes,String logicalWorld,long head,long bytes,int objectCount,int tombstoneCount){}
    private volatile View view=new View(Collections.emptyNavigableMap(),Collections.emptyNavigableMap(),new SceneChangeLog.Snapshot(0,0),logicalWorld,0,0,0,0);
    static boolean handles(String type){return Set.of("scene_store_create","scene_store_update","scene_store_remove").contains(type);}
    static long integer(JsonObject row,String key,long minimum){try{var v=row.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=new java.math.BigDecimal(v.getAsString()).longValueExact();if(n<minimum)throw new ArithmeticException();return n;}catch(ArithmeticException error){throw new IllegalArgumentException("scene_domain_integer:"+key,error);}}
    static String uuid(JsonObject row,String key,boolean nullable){var v=row.get(key);if((v==null||v.isJsonNull())&&nullable)return null;return UUID.fromString(Json.text(row,key,64)).toString();}
    static String asset(JsonObject row,String key,boolean removal){var v=row.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("scene_domain_asset");String asset=v.getAsString().strip();if((asset.isEmpty()&&!removal)||asset.getBytes(StandardCharsets.UTF_8).length>1024||asset.indexOf('\0')>=0)throw new IllegalArgumentException("scene_domain_asset");return asset;}
    static JsonArray transform(JsonObject row,String key,boolean nullable){var v=row.get(key);if((v==null||v.isJsonNull())&&nullable)return null;if(v==null||!v.isJsonArray()||v.getAsJsonArray().size()!=16)throw new IllegalArgumentException("scene_domain_matrix_size");JsonArray result=new JsonArray();int index=0;for(var value:v.getAsJsonArray()){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("scene_domain_matrix_number");double n=value.getAsDouble();if(!Double.isFinite(n)||index>=12&&index<=14&&Math.abs(n)>30000000)throw new IllegalArgumentException("scene_domain_matrix_bounds");result.add(n);index++;}return result;}
    static void validate(JsonObject row,int kind,String logicalWorld){if(!Json.text(row,"world_id",1024).equals(logicalWorld))throw new IllegalArgumentException("scene_domain_world_mismatch");uuid(row,"object_id",false);integer(row,"revision",1);if(kind!=2){asset(row,"asset_id",kind==3&&"remove".equals(row.get("operation").getAsString()));transform(row,"transform",true);uuid(row,"parent_id",true);}if(kind==1){stamp(row,"created_at");stamp(row,"updated_at");}else if(kind==2)stamp(row,"deleted_at");else{integer(row,"sequence",1);if(!Set.of("create","update","remove").contains(Json.text(row,"operation",32)))throw new IllegalArgumentException("scene_domain_operation");stamp(row,"created_at");}}
    private static void stamp(JsonObject row,String key){var v=row.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().length()>128||v.getAsString().indexOf('\0')>=0)throw new IllegalArgumentException("scene_domain_timestamp");}
    private static boolean flag(JsonObject row,String key){var v=row.get(key);if(v==null)return false;if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("scene_domain_boolean");return v.getAsBoolean();}
    private static String now(){return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now());}
    private static long next(long value){try{return Math.addExact(value,1);}catch(ArithmeticException error){throw new IllegalArgumentException("scene_domain_counter_exhausted",error);}}
    private JsonObject row(String id,String asset,JsonArray matrix,String parent,long revision,String created,String updated){JsonObject row=Json.tree(Map.of("world_id",logicalWorld,"object_id",id,"asset_id",asset,"revision",revision,"created_at",created,"updated_at",updated));row.add("transform",matrix==null?JsonNull.INSTANCE:matrix);row.add("parent_id",parent==null?JsonNull.INSTANCE:new JsonPrimitive(parent));return row;}
    private SceneDomainCodec.Patch plan(String operation,String id,String asset,JsonArray matrix,String parent,boolean optional,long sequence,String timestamp){
        JsonObject before=objects.get(id),after,tombstone=null;long revision;
        if(operation.equals("remove")){
            if(before==null&&!optional)throw new IllegalArgumentException("scene_object_does_not_exist");JsonObject old=before==null?tombstones.get(id):before;revision=old==null?1:next(integer(old,"revision",1));after=before==null?row(id,"",null,null,revision,"",""):before.deepCopy();after.addProperty("revision",revision);tombstone=Json.tree(Map.of("world_id",logicalWorld,"object_id",id,"revision",revision,"deleted_at",timestamp));
        }else{
            if(operation.equals("create")&&before!=null)throw new IllegalArgumentException("scene_object_already_exists");if(operation.equals("update")&&before==null&&!optional)throw new IllegalArgumentException("scene_object_does_not_exist");revision=before==null?1:next(integer(before,"revision",1));after=row(id,asset,matrix,parent,revision,before==null?timestamp:before.get("created_at").getAsString(),timestamp);
        }
        JsonObject change=after.deepCopy();change.remove("updated_at");change.addProperty("operation",operation);change.addProperty("sequence",sequence);change.addProperty("created_at",timestamp);return new SceneDomainCodec.Patch(operation.equals("remove")?List.of():List.of(after),tombstone==null?List.of():List.of(tombstone),List.of(change));
    }
    JsonObject prepare(String type,JsonObject request,JsonObject event,String worldIdentity){
        if(request.has("world_id")&&!Json.text(request,"world_id",1024).equals(logicalWorld))throw new IllegalArgumentException("scene_domain_world_mismatch");String operation=type.substring("scene_store_".length()),id=type.equals("scene_store_create")&&!request.has("object_id")?UUID.randomUUID().toString():uuid(request,"object_id",false);boolean removal=operation.equals("remove");SceneDomainCodec.Patch patch=plan(operation,id,removal?"":asset(request,"asset_id",false),removal?null:transform(request,"transform",false),removal?null:uuid(request,"parent_id",true),flag(request,removal?"missing_ok":"create_if_missing"),next(head),now());attach(event,patch,worldIdentity);event.addProperty("objectId",id);event.addProperty("sceneSequence",next(head));return event;
    }
    UUID parent(String id){JsonObject row=objects.get(id);String parent=row==null?null:uuid(row,"parent_id",true);return parent==null?null:UUID.fromString(parent);}
    private static JsonObject nativeDescriptor(JsonObject row){if(row==null)return null;JsonObject result=new JsonObject();for(String key:List.of("asset_id","transform","parent_id"))result.add(key,row.get(key).deepCopy());return result;}
    private static JsonObject nativeDescriptor(SceneObject object){
        byte[] body=object.record();int at=SceneObject.prefix(body).transformAt();int[] indexes={0,1,2,4,5,6,8,9,10,12,13,14};double[] values={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};for(int i=0;i<12;i++)values[indexes[i]]=ByteBuffer.wrap(body).getFloat(at+i*4);JsonArray matrix=new JsonArray();for(double value:values)matrix.add(value);JsonObject row=new JsonObject();row.addProperty("asset_id",object.asset());row.add("transform",matrix);row.add("parent_id",JsonNull.INSTANCE);return row;
    }
    private static JsonObject checkedNativeDescriptor(JsonElement value){if(value==null||value.isJsonNull())return null;JsonObject row=value.getAsJsonObject();if(!row.keySet().equals(Set.of("asset_id","transform","parent_id")))throw new IllegalArgumentException("scene_native_metadata_fields");asset(row,"asset_id",false);transform(row,"transform",true);uuid(row,"parent_id",true);return row;}
    private static List<JsonObject> nativeMetadata(JsonObject event){
        if(event==null||!event.has("sceneNativeMetadata"))return List.of();JsonObject wrapper=new JsonObject();wrapper.add("sceneDomain",event.get("sceneNativeMetadata"));var patch=SceneDomainCodec.read(wrapper);if(!patch.tombstones().isEmpty()||!patch.changes().isEmpty()||patch.objects().isEmpty()||patch.objects().size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_native_metadata_count");Set<String> ids=new HashSet<>();for(var row:patch.objects()){if(!row.keySet().equals(Set.of("object_id","before","after"))||!ids.add(uuid(row,"object_id",false)))throw new IllegalArgumentException("scene_native_metadata_identity");var before=checkedNativeDescriptor(row.get("before"));var after=checkedNativeDescriptor(row.get("after"));if(Objects.equals(before,after))throw new IllegalArgumentException("scene_native_metadata_no_change");}return patch.objects();
    }
    private static void nativeMetadata(JsonObject event,List<JsonObject> rows){if(rows.isEmpty())return;JsonObject wrapper=new JsonObject();SceneDomainCodec.save(wrapper,new SceneDomainCodec.Patch(rows,List.of(),List.of()));event.add("sceneNativeMetadata",wrapper.get("sceneDomain"));nativeMetadata(event);}
    private void rememberDomainMetadata(JsonObject event,List<JsonObject> changed,List<JsonObject> removed){
        Map<String,JsonObject> rows=new LinkedHashMap<>();for(var row:nativeMetadata(event))rows.put(uuid(row,"object_id",false),row);for(var group:List.of(changed,removed))for(var row:group){String id=uuid(row,"object_id",false);if(rows.containsKey(id)||!objects.containsKey(id))continue;JsonObject before=nativeDescriptor(objects.get(id)),after=group==removed?null:nativeDescriptor(row);if(Objects.equals(before,after))continue;JsonObject saved=new JsonObject();saved.addProperty("object_id",id);saved.add("before",before);saved.add("after",after==null?JsonNull.INSTANCE:after);rows.put(id,saved);}nativeMetadata(event,new ArrayList<>(rows.values()));
    }
    void reverseNativeMetadata(JsonObject original,JsonObject event){
        List<JsonObject> reversed=new ArrayList<>();for(var row:nativeMetadata(original)){String id=uuid(row,"object_id",false);if(!Objects.equals(nativeDescriptor(objects.get(id)),checkedNativeDescriptor(row.get("after"))))throw new IllegalArgumentException("scene_native_metadata_conflicts_with_later_edit");JsonObject change=new JsonObject();change.addProperty("object_id",id);change.add("before",row.get("after").deepCopy());change.add("after",row.get("before").deepCopy());reversed.add(change);}nativeMetadata(event,reversed);
    }
    int removeRegion(JsonObject event,World.Block min,World.Block max,Set<UUID> selected){
        List<JsonObject> metadata=new ArrayList<>(nativeMetadata(event));int count=0;
        for(var entry:objects.entrySet()){
            UUID id=UUID.fromString(entry.getKey());JsonElement transform=entry.getValue().get("transform");JsonArray m=transform.isJsonNull()?null:transform.getAsJsonArray();World.Vec p=m==null?null:new World.Vec(m.get(12).getAsDouble(),m.get(13).getAsDouble(),m.get(14).getAsDouble());
            if(!selected.contains(id)&&!SceneRegionRemoval.inside(p,min,max))continue;selected.add(id);if(selected.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("worldedit_scene_selection_byte_or_identity_limit");
            JsonObject change=new JsonObject();change.addProperty("object_id",id.toString());change.add("before",nativeDescriptor(entry.getValue()));change.add("after",JsonNull.INSTANCE);metadata.add(change);count++;
        }
        nativeMetadata(event,metadata);return count;
    }
    void nativeEdit(JsonObject event,String worldIdentity){
        List<SceneEdits.Change> nativeChanges=event.has("sceneChanges")?SceneEdits.read(event):List.of();Map<UUID,ScenePayload.Entry> finalStates=new LinkedHashMap<>();Set<UUID> previousNative=new HashSet<>();for(var change:nativeChanges){if(change.before()!=null)previousNative.add(change.id());if(change.after()!=null||!finalStates.containsKey(change.id()))finalStates.put(change.id(),change.after());}Map<UUID,SceneRelationships.State> parents=new HashMap<>();for(var relationship:SceneRelationships.read(event))if(relationship.hideout())parents.put(relationship.id(),relationship.after());
        Map<UUID,JsonObject> linkedObjects=new LinkedHashMap<>();if(event.has("sceneLinkObjects")){for(var value:event.getAsJsonArray("sceneLinkObjects")){JsonObject row=value.getAsJsonObject();UUID id=UUID.fromString(Json.text(row,"objectId",36));if(!parents.containsKey(id)||linkedObjects.put(id,row)!=null)throw new IllegalArgumentException("scene_relationship_object");}}
        Set<UUID> linkedOnly=new HashSet<>();for(var linked:linkedObjects.entrySet())if(!finalStates.containsKey(linked.getKey())){linkedOnly.add(linked.getKey());finalStates.put(linked.getKey(),null);}
        for(var c:SceneEditorStates.read(event)){
            JsonObject after=c.after(),before=c.before();boolean hideout=after!=null&&after.get("hideout").getAsBoolean(),wasHideout=before!=null&&before.get("hideout").getAsBoolean();
            if(!hideout&&!wasHideout||finalStates.get(c.id())!=null)continue;
            finalStates.put(c.id(),null);
            if(hideout&&!SceneEditorStates.deleted(after)){JsonObject linked=new JsonObject();linked.add("asset",after.get("asset"));linked.add("transform",after.get("transform"));linkedObjects.put(c.id(),linked);parents.put(c.id(),new SceneRelationships.State(after.has("parent")?UUID.fromString(after.get("parent").getAsString()):null,null));linkedOnly.add(c.id());}
        }
        Map<UUID,JsonObject> desiredMetadata=new LinkedHashMap<>();for(var row:nativeMetadata(event)){UUID id=UUID.fromString(uuid(row,"object_id",false));desiredMetadata.put(id,checkedNativeDescriptor(row.get("after")));finalStates.putIfAbsent(id,null);}
        if(event.has("sceneNativeAssetRequests")){
            var requests=event.getAsJsonArray("sceneNativeAssetRequests");if(requests.isEmpty()||requests.size()>128||event.has("sceneNativeMetadata"))throw new IllegalArgumentException("scene_native_asset_request_count");Set<UUID> ids=new HashSet<>();List<JsonObject> metadata=new ArrayList<>();
            for(var request:requests){var requested=request.getAsJsonObject();UUID id=UUID.fromString(uuid(requested,"objectId",false));if(!ids.add(id))throw new IllegalArgumentException("scene_native_asset_request_duplicate");JsonObject after=new JsonObject();after.addProperty("asset_id",asset(requested,"asset",false));after.add("transform",transform(requested,"transform",false));after.add("parent_id",flag(requested,"hideout")?requested.get("parent_id").deepCopy():objects.containsKey(id.toString())?objects.get(id.toString()).get("parent_id").deepCopy():JsonNull.INSTANCE);checkedNativeDescriptor(after);JsonObject before=nativeDescriptor(objects.get(id.toString()));
                if(before==null)for(var change:nativeChanges)if(change.id().equals(id)&&change.before()!=null){before=nativeDescriptor(change.before().object());break;}
                if(!Objects.equals(before,after)){JsonObject change=new JsonObject();change.addProperty("object_id",id.toString());change.add("before",before==null?JsonNull.INSTANCE:before);change.add("after",after);metadata.add(change);desiredMetadata.put(id,after);finalStates.putIfAbsent(id,null);}
                else if(finalStates.containsKey(id))desiredMetadata.put(id,after);
            }nativeMetadata(event,metadata);
        }
        List<JsonObject> newObjects=new ArrayList<>(),newTombstones=new ArrayList<>(),newChanges=new ArrayList<>();long next=head;String timestamp=now();
        for(var change:finalStates.entrySet()){String id=change.getKey().toString();var entry=change.getValue();SceneDomainCodec.Patch patch;
            if(desiredMetadata.containsKey(change.getKey())){JsonObject desired=desiredMetadata.get(change.getKey());patch=desired==null?plan("remove",id,"",null,null,true,next(next),timestamp):plan(!flag(event,"nativeUpdate")&&!objects.containsKey(id)&&!previousNative.contains(change.getKey())&&!linkedOnly.contains(change.getKey())?"create":"update",id,asset(desired,"asset_id",false),transform(desired,"transform",true),uuid(desired,"parent_id",true),true,next(next),timestamp);}
            else if(entry==null&&linkedOnly.contains(change.getKey())){JsonObject linked=linkedObjects.get(change.getKey());UUID parent=parents.get(change.getKey()).parent();patch=plan("update",id,asset(linked,"asset",false),transform(linked,"transform",false),parent==null?null:parent.toString(),true,next(next),timestamp);}
            else if(entry==null)patch=plan("remove",id,"",null,null,true,next(next),timestamp);
            else{SceneObject object=entry.object();byte[] body=object.record();int at=SceneObject.prefix(body).transformAt();int[] indexes={0,1,2,4,5,6,8,9,10,12,13,14};JsonArray matrix=new JsonArray();double[] values={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};for(int i=0;i<12;i++)values[indexes[i]]=ByteBuffer.wrap(body).getFloat(at+i*4);for(double value:values)matrix.add(value);String parent=parents.containsKey(change.getKey())?(parents.get(change.getKey()).parent()==null?null:parents.get(change.getKey()).parent().toString()):objects.containsKey(id)?uuid(objects.get(id),"parent_id",true):null;patch=plan(!flag(event,"nativeUpdate")&&!objects.containsKey(id)&&!previousNative.contains(change.getKey())?"create":"update",id,object.asset().strip(),matrix,parent,true,next(next),timestamp);}
            next++;newObjects.addAll(patch.objects());newTombstones.addAll(patch.tombstones());newChanges.addAll(patch.changes());
        }
        if(!newObjects.isEmpty()||!newTombstones.isEmpty()||!newChanges.isEmpty()){rememberDomainMetadata(event,newObjects,newTombstones);attach(event,new SceneDomainCodec.Patch(newObjects,newTombstones,newChanges),worldIdentity);event.remove("status");}
    }
    private void attach(JsonObject event,SceneDomainCodec.Patch patch,String worldIdentity){validatePatch(patch,logicalWorld,false);event.addProperty("sceneDomainWorld",logicalWorld);event.addProperty("sceneDomainNamespace",worldIdentity);SceneDomainCodec.save(event,patch);}
    private void validatePatch(SceneDomainCodec.Patch patch,String logicalWorld,boolean baseline){
        long projected=bytes,appended=0;int objectCount=objects.size(),tombstoneCount=tombstones.size();Set<String> ids=new HashSet<>();
        for(var row:patch.objects()){validate(row,1,logicalWorld);String id=uuid(row,"object_id",false);if(!ids.add(id)||baseline&&objects.containsKey(id))throw new IllegalArgumentException("scene_domain_duplicate_object");int size=SceneDomainCodec.rowBytes(row);if(size>65536)throw new IllegalArgumentException("scene_domain_row_limit");appended+=size;projected+=size-objects.rowSize(id)-tombstones.rowSize(id);if(!objects.containsKey(id))objectCount++;if(tombstones.containsKey(id))tombstoneCount--;}
        for(var row:patch.tombstones()){validate(row,2,logicalWorld);String id=uuid(row,"object_id",false);if(!ids.add(id)||baseline&&(objects.containsKey(id)||tombstones.containsKey(id)))throw new IllegalArgumentException("scene_domain_duplicate_tombstone");int size=SceneDomainCodec.rowBytes(row);if(size>65536)throw new IllegalArgumentException("scene_domain_row_limit");appended+=size;projected+=size-objects.rowSize(id)-tombstones.rowSize(id);if(objects.containsKey(id))objectCount--;if(!tombstones.containsKey(id))tombstoneCount++;}
        Set<Long> sequences=new HashSet<>();long next=head;for(var row:patch.changes()){validate(row,3,logicalWorld);long sequence=integer(row,"sequence",1);if(!sequences.add(sequence)||sequence<=head&&changes.contains(sequence)||!baseline&&sequence!=Math.addExact(next,1))throw new IllegalArgumentException("scene_domain_change_sequence");next=sequence;}
        if((long)objectCount+tombstoneCount>ROW_LIMIT||projected>BYTE_LIMIT)throw new IllegalArgumentException("scene_domain_capacity_limit");if(rowStore.shouldCompact(appended,bytes))compactRows(true);rowStore.admit(appended);
    }
    Map<String,Object> compactRows(boolean force){boolean compacted=rowStore.compact(force);if(compacted){for(int part=0;part<256;part++)dirtyObjects.add(part);publish();}return Map.of("compacted",compacted,"rowIndex",rowStore.metrics());}
    private static int size(JsonObject row){return row==null?0:SceneDomainCodec.rowBytes(row);}
    void apply(JsonObject event,boolean baseline){String source=Json.text(event,"sceneDomainWorld",1024);if(!source.equals(logicalWorld)&&(!baseline||!objects.isEmpty()||!tombstones.isEmpty()||changes.capture().count()!=0))throw new IllegalArgumentException("scene_domain_world_mismatch");SceneDomainCodec.Patch patch=SceneDomainCodec.read(event);validatePatch(patch,source,baseline);logicalWorld=source;for(var row:patch.objects()){String id=uuid(row,"object_id",false);bytes+=size(row)-objects.writeRow(id,row)-tombstones.erase(id);dirtyObjects.add(part(id));}for(var row:patch.tombstones()){String id=uuid(row,"object_id",false);bytes+=size(row)-objects.erase(id)-tombstones.writeRow(id,row);dirtyObjects.add(part(id));}changes.append(patch.changes(),baseline);for(var row:patch.changes())head=Math.max(head,integer(row,"sequence",1));}
    private static int part(String id){return Integer.parseInt(id.substring(0,2),16);}
    private static SceneRows.Snapshot objectPart(SceneRows.Index source,int part){return source.part(part);}
    void publish(){
        SceneChangeLog.Snapshot history=changes.capture();if(dirtyObjects.isEmpty()&&view.logicalWorld().equals(logicalWorld)&&view.head()==head&&view.bytes()==bytes&&view.changes().equals(history))return;
        View previous=view;var objectView=new TreeMap<>(previous.objects());var tombstoneView=new TreeMap<>(previous.tombstones());
        for(int part:dirtyObjects){var live=objectPart(objects,part);var dead=objectPart(tombstones,part);if(live.isEmpty())objectView.remove(part);else objectView.put(part,live);if(dead.isEmpty())tombstoneView.remove(part);else tombstoneView.put(part,dead);}
        view=new View(Collections.unmodifiableNavigableMap(objectView),Collections.unmodifiableNavigableMap(tombstoneView),history,logicalWorld,head,bytes,objects.size(),tombstones.size());dirtyObjects.clear();
    }
    JsonObject object(String id){String canonical=UUID.fromString(id).toString();var rows=view.objects().get(part(canonical));JsonObject row=rows==null?null:rows.get(canonical);return row==null?null:row.deepCopy();}
    /** One immutable publication captured for the old GameService full snapshot. Called only
     * from development workers, never from the world or native transport owner. */
    JsonObject snapshot(){View captured=view;JsonArray live=new JsonArray(),dead=new JsonArray();captured.objects().values().forEach(part->part.rows(null,null,null).forEach(live::add));captured.tombstones().values().forEach(part->part.rows(null,null,null).forEach(dead::add));JsonObject result=new JsonObject();result.add("scene_objects",live);result.add("scene_tombstones",dead);return result;}
    szserver.util.JsonDocument snapshotDocument(){View captured=view;Map<String,Object> fields=new LinkedHashMap<>();fields.put("scene_objects",szserver.util.JsonDocument.rows(()->rows(captured.objects(),null,null,null).iterator()));fields.put("scene_tombstones",szserver.util.JsonDocument.rows(()->rows(captured.tombstones(),null,null,null).iterator()));return new szserver.util.JsonDocument(fields);}
    private static java.util.stream.Stream<JsonObject> rows(NavigableMap<Integer,SceneRows.Snapshot> parts,String cursor,Integer x,Integer z){return (cursor==null?parts:parts.tailMap(part(cursor),true)).values().stream().flatMap(rows->rows.rows(cursor,x,z));}
    List<JsonObject> objects(Integer x,Integer z,String after,int limit){if(limit<1||limit>10000||(x==null)!=(z==null))throw new IllegalArgumentException("scene_domain_list_limit");String cursor=after==null?null:UUID.fromString(after).toString();return rows(view.objects(),cursor,x,z).limit(limit).toList();}
    List<JsonObject> tombstones(String after,int limit){if(limit<1||limit>10000)throw new IllegalArgumentException("scene_domain_list_limit");String cursor=after==null?null:UUID.fromString(after).toString();return rows(view.tombstones(),cursor,null,null).limit(limit).map(JsonObject::deepCopy).toList();}
    List<JsonObject> changes(long after,int limit){if(after<0||limit<1||limit>10000)throw new IllegalArgumentException("invalid_scene_change_cursor");return changes.after(view.changes(),after,limit);}
    JsonPages.Spec pageSpec(String field,Integer x,Integer z,String after,int limit){
        View captured=view;Iterable<JsonObject> values;
        if(field.equals("changes")){
            long cursor=after==null?0:Long.parseLong(after);if(cursor<0||limit<1||limit>10000||x!=null||z!=null)throw new IllegalArgumentException("invalid_scene_change_cursor");values=changes.afterRows(captured.changes(),cursor,limit);
        }else {
            if(!Set.of("objects","tombstones").contains(field)||limit<1||limit>10000||(x==null)!=(z==null)||field.equals("tombstones")&&x!=null)throw new IllegalArgumentException("scene_domain_list_limit");String cursor=after==null?null:UUID.fromString(after).toString();
            var parts=field.equals("objects")?captured.objects():captured.tombstones();
            values=()->rows(parts,cursor,x,z).limit(limit).iterator();
        }
        return new JsonPages.Spec(Map.of(),field,values,result->{Map<String,Object> tail=new LinkedHashMap<>();tail.put("domain",metrics(captured));tail.put("next",result.last()==null?null:field.equals("changes")?result.last().get("sequence").getAsLong():result.count()==limit?result.last().get("object_id").getAsString():null);return tail;});
    }
    Map<String,Object> metrics(){return metrics(view);}
    private Map<String,Object> metrics(View v){return Map.of("objects",v.objectCount(),"tombstones",v.tombstoneCount(),"changes",v.changes().count(),"sequence",v.head(),"world_id",v.logicalWorld(),"bytes",v.bytes(),"byteLimit",BYTE_LIMIT,"historyBytes",v.changes().bytes(),"historyIndex",changes.metrics(),"rowIndex",rowStore.metrics());}
    boolean healthy(){return changes.healthy()&&rowStore.healthy();}
    /** The command owner has stopped. Development readers must stop before closing this derived cache. Captured maps remain immutable during its lifetime. */
    @Override public void close(){changes.close();rowStore.close();objects.clear();tombstones.clear();dirtyObjects.clear();head=bytes=0;view=new View(Collections.emptyNavigableMap(),Collections.emptyNavigableMap(),new SceneChangeLog.Snapshot(0,0),logicalWorld,0,0,0,0);}
}
