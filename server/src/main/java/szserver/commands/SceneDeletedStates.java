package szserver.commands;

import com.google.gson.*;
import szserver.world.*;
import java.util.*;

/** Command-owner last native body of a deleted UUID, including reversible deletion history. */
final class SceneDeletedStates implements AutoCloseable {
    private static final long LIMIT=8L*1024*1024*1024;
    static long byteLimit(){return LIMIT;}static int rowLimit(){return 1000000;}
    record Removed(int x,int z,ScenePayload.Entry entry){}
    private final DiskSceneValues<Removed> removed;
    SceneDeletedStates(){this(java.nio.file.Path.of("var"));}
    SceneDeletedStates(java.nio.file.Path base){removed=new DiskSceneValues<>(base,SceneDeletedStates::encode,SceneDeletedStates::decode);}
    private static JsonObject encode(Removed value){JsonObject row=new JsonObject();row.addProperty("x",value.x());row.addProperty("z",value.z());row.addProperty("container",value.entry().container());row.addProperty("asset",value.entry().object().asset());row.addProperty("record",Base64.getEncoder().encodeToString(value.entry().object().record()));return row;}
    private static Removed decode(JsonObject row){return new Removed(row.get("x").getAsInt(),row.get("z").getAsInt(),new ScenePayload.Entry(row.get("container").getAsBoolean(),new SceneObject(row.get("asset").getAsString(),Base64.getDecoder().decode(row.get("record").getAsString()))));}
    private long bytes;private volatile Map<String,Object> metrics=Map.of("objects",0,"bytes",0L,"byteLimit",LIMIT);
    Removed current(UUID id){return removed.get(id);}
    Map<UUID,Removed> currentRows(){return removed.rows();}
    Map<UUID,Removed> checkpointRows(){return removed.frozenRows();}
    long checkpointBytes(){return bytes;}
    void restoreCheckpointRow(UUID id,Removed row){if(row==null||row.entry()==null||row.entry().occurrences().size()!=1||!id.equals(row.entry().object().identity())||row.x()!=(int)Math.floor(row.entry().object().x()/16)||row.z()!=(int)Math.floor(row.entry().object().z()/16))throw new IllegalArgumentException("checkpoint_deleted_identity_or_home");int size=size(row);if(removed.containsKey(id)||removed.size()>=rowLimit()||bytes+size>LIMIT)throw new IllegalArgumentException("checkpoint_deleted_duplicate_or_limit");removed.admit(removed.encodedSize(id,row));removed.write(id,row);bytes+=size;metrics=Map.of("objects",removed.size(),"bytes",bytes,"byteLimit",LIMIT);}
    private static int size(Removed value){return value==null?0:128+value.entry().object().recordSize()+value.entry().object().asset().length()*2;}
    private static JsonObject wrapped(JsonObject event){JsonObject wrapper=new JsonObject();wrapper.addProperty("type","scene_deleted");wrapper.add("sceneChanges",event.get("sceneDeletedState"));return wrapper;}
    private static List<SceneEdits.Change> read(JsonObject event){return event.has("sceneDeletedState")?SceneEdits.read(wrapped(event)):List.of();}
    private static Removed value(SceneEdits.Change change,boolean after){var entry=after?change.after():change.before();return entry==null?null:new Removed((int)Math.floor(entry.object().x()/16),(int)Math.floor(entry.object().z()/16),entry.canonical());}
    private static void rememberBefore(Map<UUID,Removed> before,SceneEdits.Change change){if(change.before()==null)return;Removed value=value(change,false);if(!before.containsKey(change.id())||value.x()==change.x()&&value.z()==change.z())before.put(change.id(),value);}
    private List<SceneEdits.Change> derived(JsonObject event){
        if(!event.has("sceneChanges"))return List.of();Map<UUID,Removed> live=new LinkedHashMap<>(),before=new HashMap<>();
        for(var change:SceneEdits.read(event)){rememberBefore(before,change);if(change.after()!=null||!live.containsKey(change.id()))live.put(change.id(),value(change,true));}
        Set<UUID> livePending=new HashSet<>();for(var change:SceneEditorStates.read(event))if(change.after()!=null&&!SceneEditorStates.deleted(change.after()))livePending.add(change.id());
        List<SceneEdits.Change> result=new ArrayList<>();for(var row:live.entrySet()){
            // Undo of materialization restores a live pending record, rather than a deleted native body.
            UUID id=row.getKey();Removed old=removed.get(id),next=row.getValue()==null&&!livePending.contains(id)?before.getOrDefault(id,old):null;if(Objects.equals(old,next))continue;
            Removed home=next==null?old:next;if(old!=null&&next!=null&&(old.x()!=next.x()||old.z()!=next.z()))throw new IllegalArgumentException("scene_deleted_state_location_conflict");
            result.add(new SceneEdits.Change(home.x(),home.z(),id,old==null?null:old.entry(),next==null?null:next.entry()));
        }return result;
    }
    private static void save(JsonObject event,List<SceneEdits.Change> changes){if(changes.isEmpty())return;JsonObject wrapper=new JsonObject();SceneEdits.save(wrapper,changes);event.add("sceneDeletedState",wrapper.get("sceneChanges"));}
    void prepare(JsonObject event){if(event.has("sceneDeletedState")){validate(read(event));return;}var changes=derived(event);validate(changes);
        // Ordinary deletes derive their retained bodies from sceneChanges; do not double journal payloads.
        // Creation from a tombstone must also remember the previous deleted body for exact undo.
        if(changes.stream().anyMatch(change->change.before()!=null))save(event,changes);
    }
    void reverse(JsonObject original,JsonObject event){
        if(!original.has("sceneDeletedState")&&original.has("sceneChanges")){
            Map<UUID,Removed> before=new HashMap<>(),last=new HashMap<>();for(var change:SceneEdits.read(original)){rememberBefore(before,change);if(change.after()!=null||!last.containsKey(change.id()))last.put(change.id(),value(change,true));}
            // Older ordinary deletes journal their native body once. A newer cache edit must still block undo.
            for(var row:last.entrySet())if(row.getValue()==null&&before.containsKey(row.getKey())&&!Objects.equals(removed.get(row.getKey()),before.get(row.getKey())))throw new IllegalArgumentException("scene_deleted_state_conflicts_with_later_edit");
        }
        save(event,read(original).stream().map(SceneEdits.Change::reverse).toList());
    }
    void withAdditional(JsonObject event,List<SceneEdits.Change> additional){
        if(additional.isEmpty())return;var changes=new ArrayList<>(derived(event));changes.addAll(additional);validate(changes);save(event,changes);
    }
    private void validate(List<SceneEdits.Change> changes){long projected=bytes,appendBytes=0;int count=removed.size();Set<UUID> ids=new HashSet<>();for(var change:changes){if(!ids.add(change.id()))throw new IllegalArgumentException("scene_deleted_state_duplicate");Removed old=value(change,false),next=value(change,true);if(!Objects.equals(removed.get(change.id()),old))throw new IllegalArgumentException("scene_deleted_state_conflicts_with_later_edit");projected+=size(next)-size(old);appendBytes+=removed.encodedSize(change.id(),next);if(old==null)count++;if(next==null)count--;}
        if(count>rowLimit()||projected>LIMIT)throw new IllegalArgumentException("scene_deleted_state_capacity");removed.admit(appendBytes);}
    void apply(JsonObject event){
        // Existing journals predate explicit retained tombstones; derive them from their full native snapshots.
        List<SceneEdits.Change> changes=event.has("sceneDeletedState")?read(event):derived(event);validate(changes);for(var change:changes){Removed old=value(change,false),next=value(change,true);bytes+=size(next)-size(old);if(next==null)removed.erase(change.id());else removed.write(change.id(),next);}metrics=Map.of("objects",removed.size(),"bytes",bytes,"byteLimit",LIMIT);
    }
    Map<String,Object> metrics(){var result=new LinkedHashMap<>(metrics);result.put("rowLimit",rowLimit());result.put("rowIndex",removed.metrics());return Collections.unmodifiableMap(result);}
    boolean healthy(){return removed.healthy();}
    Map<String,Object> compactRows(boolean force){return removed.compact(force);}
    public void close(){removed.close();bytes=0;metrics=Map.of("objects",0,"bytes",0L,"byteLimit",LIMIT);}
    void release(){close();}
}
