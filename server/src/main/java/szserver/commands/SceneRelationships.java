package szserver.commands;

import com.google.gson.*;
import szserver.client.SceneEditPackets;
import szserver.util.Json;
import java.util.*;

/** Command-owner links. Old ImEditor references are sidecar state, separate from
 * Hideout scene-store parents; neither implicitly changes a native transform. */
final class SceneRelationships {
    record State(UUID parent,UUID group){}
    record Change(UUID id,boolean hideout,State before,State after){Change reverse(){return new Change(id,hideout,after,before);}}
    static final State EMPTY=new State(null,null);
    private final SceneDomain domain;
    private final Map<UUID,State> editor=new HashMap<>();
    static int rowLimit(){return 1000000;}
    private volatile Map<Integer,Map<UUID,State>> view=Map.of();private final Map<Integer,Map<UUID,State>> parts=new HashMap<>();private final Set<Integer> dirty=new HashSet<>();private volatile int publishedSize;
    private static int part(UUID id){return id.hashCode()&255;}
    private void changed(UUID id,State row){int part=part(id);var values=parts.computeIfAbsent(part,k->new HashMap<>());if(EMPTY.equals(row))values.remove(id);else values.put(id,row);dirty.add(part);}
    Map<UUID,State> checkpointRows(){return Collections.unmodifiableMap(editor);}
    void restoreCheckpointRow(UUID id,State row){if(EMPTY.equals(row)||editor.size()>=rowLimit()||editor.putIfAbsent(id,row)!=null)throw new IllegalArgumentException("checkpoint_relationship_duplicate_or_limit");changed(id,row);}
    SceneRelationships(SceneDomain domain){this.domain=domain;}
    private State current(UUID id,boolean hideout){return hideout?new State(domain.parent(id.toString()),null):editor.getOrDefault(id,EMPTY);}
    void remember(JsonObject event,String kind,UUID id,SceneEditPackets.Edit edit){
        if(!kind.equals("batch")&&!kind.equals("create")&&!kind.equals("transform"))return;
        boolean hideout=!kind.equals("batch");State before=current(id,hideout),after=new State(edit.parent(),hideout?null:edit.group());
        if(before.equals(after))return;var changes=new ArrayList<>(read(event));changes.add(new Change(id,hideout,before,after));save(event,changes);
    }
    static void object(JsonObject event,UUID id,String asset,float[] matrix){JsonArray rows=event.has("sceneLinkObjects")?event.getAsJsonArray("sceneLinkObjects"):new JsonArray();JsonObject row=new JsonObject();row.addProperty("objectId",id.toString());row.addProperty("asset",asset);JsonArray transform=new JsonArray();for(float value:matrix)transform.add(value);row.add("transform",transform);rows.add(row);event.add("sceneLinkObjects",rows);}
    static UUID uuid(JsonObject value,String key){JsonElement field=value.get(key);return field==null||field.isJsonNull()?null:UUID.fromString(field.getAsString());}
    static State state(JsonObject value){return new State(uuid(value,"parent"),uuid(value,"group"));}
    static JsonObject row(State value){JsonObject result=new JsonObject();result.add("parent",value.parent()==null?JsonNull.INSTANCE:new JsonPrimitive(value.parent().toString()));result.add("group",value.group()==null?JsonNull.INSTANCE:new JsonPrimitive(value.group().toString()));return result;}
    static List<Change> read(JsonObject event){
        if(!event.has("sceneRelationships"))return List.of();JsonElement field=event.get("sceneRelationships");if(!field.isJsonArray()||field.getAsJsonArray().size()<1||field.getAsJsonArray().size()>128)throw new IllegalArgumentException("scene_relationship_count");List<Change> result=new ArrayList<>();Set<UUID> ids=new HashSet<>();
        for(var value:field.getAsJsonArray()){JsonObject row=value.getAsJsonObject();UUID id=UUID.fromString(Json.text(row,"objectId",36));boolean hideout=row.get("hideout").getAsBoolean();State before=state(Json.object(row,"before")),after=state(Json.object(row,"after"));if(!ids.add(id)||before.equals(after)||hideout&&(before.group()!=null||after.group()!=null))throw new IllegalArgumentException("scene_relationship_change");result.add(new Change(id,hideout,before,after));}return List.copyOf(result);
    }
    static void save(JsonObject event,List<Change> changes){JsonArray rows=new JsonArray();for(var c:changes){JsonObject row=new JsonObject();row.addProperty("objectId",c.id().toString());row.addProperty("hideout",c.hideout());row.add("before",row(c.before()));row.add("after",row(c.after()));rows.add(row);}event.add("sceneRelationships",rows);read(event);}
    void validate(JsonObject event){int additions=0;for(var c:read(event)){if(!current(c.id(),c.hideout()).equals(c.before()))throw new IllegalArgumentException("scene_relationship_conflicts_with_later_edit");if(!c.hideout()&&!editor.containsKey(c.id())&&!EMPTY.equals(c.after()))additions++;}if((long)editor.size()+additions>rowLimit())throw new IllegalArgumentException("scene_relationship_capacity");}
    void reverse(JsonObject original,JsonObject event){var changes=read(original);if(!changes.isEmpty())save(event,changes.stream().map(Change::reverse).toList());if(original.has("sceneLinkObjects"))event.add("sceneLinkObjects",original.get("sceneLinkObjects").deepCopy());validate(event);}
    void apply(JsonObject event){for(var c:read(event))if(!c.hideout()){if(!editor.getOrDefault(c.id(),EMPTY).equals(c.before()))throw new IllegalArgumentException("scene_relationship_replay_conflict");if(EMPTY.equals(c.after()))editor.remove(c.id());else editor.put(c.id(),c.after());changed(c.id(),c.after());}if(editor.size()>rowLimit())throw new IllegalArgumentException("scene_relationship_capacity");}
    void publish(){if(dirty.isEmpty())return;var next=new HashMap<>(view);for(int part:dirty){var values=parts.get(part);if(values.isEmpty())next.remove(part);else next.put(part,Map.copyOf(values));}view=Map.copyOf(next);publishedSize=editor.size();dirty.clear();}
    JsonObject snapshot(UUID id){return row(view.getOrDefault(part(id),Map.of()).getOrDefault(id,EMPTY));}
    Map<String,Object> metrics(){return Map.of("editorLinks",publishedSize,"limit",rowLimit());}
    void release(){editor.clear();parts.clear();dirty.clear();view=Map.of();publishedSize=0;}
    static boolean hasSceneParents(JsonObject event){return read(event).stream().anyMatch(Change::hideout);}
}
