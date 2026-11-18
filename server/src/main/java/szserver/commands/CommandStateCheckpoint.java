package szserver.commands;

import com.google.gson.*;
import szserver.persistence.*;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import static szserver.persistence.CheckpointDataRows.*;

/** Complete command-owner state beside WorldMapCheckpoint. Exports require the same serialized
 * command barrier. All restoration is detached; publishing the bundle belongs to bootstrap. */
final class CommandStateCheckpoint {
    record State(DurableEntityProgram actors,Map<String,TreeMap<Integer,JsonObject>> inventories,
        SceneDomain domain,SceneEditorStates editor,SceneDeletedStates deleted,SceneRelationships links) implements AutoCloseable {
        static State empty(){return empty(java.nio.file.Path.of("var"));}
        static State empty(java.nio.file.Path base){var domain=new SceneDomain(base);SceneEditorStates editor=null;SceneDeletedStates deleted=null;try{editor=new SceneEditorStates(base);deleted=new SceneDeletedStates(base);return new State(new DurableEntityProgram(),new HashMap<>(),domain,editor,deleted,new SceneRelationships(domain));}catch(Throwable failure){domain.close();if(editor!=null)editor.close();if(deleted!=null)deleted.close();throw failure;}}
        public void close(){domain.close();editor.close();deleted.close();links.release();}
        void publish(){domain.publish();editor.publish();links.publish();}
    }
    record Export(Map<String,World.DurableEntity> actors,long counter,ActorRuntime.Snapshot runtime,
        Map<String,TreeMap<Integer,JsonObject>> inventories,SceneDomain.CheckpointView domain,
        Map<UUID,JsonObject> editor,long editorBytes,Map<UUID,SceneDeletedStates.Removed> deleted,long deletedBytes,
        Map<UUID,SceneRelationships.State> links){}
    /** Freeze only indexes and small values; archived row bodies are read on the save worker. */
    static Export capture(State state){
        var inventories=new HashMap<String,TreeMap<Integer,JsonObject>>();state.inventories().forEach((id,rows)->{var copy=new TreeMap<Integer,JsonObject>();rows.forEach((slot,row)->copy.put(slot,row.deepCopy()));inventories.put(id,copy);});
        return new Export(Map.copyOf(state.actors().rows()),state.actors().counter(),state.actors().runtime(),Map.copyOf(inventories),state.domain().checkpointView(),state.editor().checkpointRows(),state.editor().checkpointBytes(),state.deleted().checkpointRows(),state.deleted().checkpointBytes(),Map.copyOf(state.links().checkpointRows()));
    }
    private CommandStateCheckpoint(){}
    private static JsonObject row(String kind){JsonObject row=new JsonObject();row.addProperty("kind",kind);return row;}
    private static JsonObject control(String kind,int count,long bytes){JsonObject row=row(kind);row.addProperty("count",count);row.addProperty("bytes",bytes);return row;}
    static void write(CheckpointSink sink,State state)throws Exception{write(sink,capture(state));}
    static void write(CheckpointSink sink,Export state)throws Exception{
        sink.begin("durable_entities");JsonObject program=row("program_control");program.addProperty("version",state.runtime()==null?3:!state.runtime().mines().isEmpty()?6:state.runtime().version()==2?5:4);if(state.runtime()!=null)program.addProperty("tick",state.runtime().tick());program.addProperty("count",state.actors().size());program.addProperty("counter",state.counter());if(program.get("version").getAsInt()==6)program.addProperty("mines",state.runtime().mines().size());CheckpointDataRows.write(sink,program);
        if(state.runtime()!=null)for(var mine:state.runtime().mines()){var value=row("mine");value.addProperty("x",mine.block().x());value.addProperty("y",mine.block().y());value.addProperty("z",mine.block().z());value.addProperty("cooldown",mine.cooldown());CheckpointDataRows.write(sink,value);}
        Map<String,ActorRuntime> runtime=new HashMap<>();if(state.runtime()!=null)for(var actor:state.runtime().actors())runtime.put(actor.id(),actor);
        Map<String,ActorRuntime.Controller> controllers=new HashMap<>();if(state.runtime()!=null)for(var controller:state.runtime().controllers())controllers.put(controller.state().id(),controller);
        for(var actor:new TreeMap<>(state.actors()).values()){JsonObject value=row("actor");if(state.runtime()!=null)value.add("runtime",binary(ActorRuntimeCodec.encode(runtime.get(actor.id()))));if(controllers.containsKey(actor.id()))value.add("controller",binary(ActorRuntimeCodec.encode(controllers.get(actor.id()))));value.addProperty("id",actor.id());value.addProperty("type",actor.kind());value.add("spawn",Json.tree(actor.spawn()));value.add("goal",actor.goal()==null?JsonNull.INSTANCE:Json.tree(actor.goal()));value.addProperty("incarnation",actor.incarnation());value.add("patrol",Json.GSON.toJsonTree(actor.patrol()));value.add("follow",actor.follow()==null?JsonNull.INSTANCE:Json.tree(actor.follow()));CheckpointDataRows.write(sink,value);}sink.end();
        sink.begin("admin_inventories");JsonObject inventory=row("inventory_control");inventory.addProperty("owners",state.inventories().size());inventory.addProperty("slots",state.inventories().values().stream().mapToInt(Map::size).sum());CheckpointDataRows.write(sink,inventory);
        for(var owner:new TreeMap<>(state.inventories()).entrySet()){JsonObject value=row("owner");value.addProperty("id",owner.getKey());CheckpointDataRows.write(sink,value);for(var stack:owner.getValue().values()){JsonObject item=row("item");item.addProperty("owner",owner.getKey());item.add("stack",stack);CheckpointDataRows.write(sink,item);}}sink.end();
        sink.begin("scene_domain");var domain=state.domain();JsonObject d=row("domain_control");d.addProperty("version",2);d.addProperty("world",domain.world());d.addProperty("head",domain.head());d.addProperty("bytes",domain.bytes());d.addProperty("changeBytes",domain.changeBytes());d.addProperty("objects",domain.objects().size());d.addProperty("tombstones",domain.tombstones().size());d.addProperty("changes",domain.changeCount());CheckpointDataRows.write(sink,d);
        for(var section:List.<Map.Entry<String,Iterable<JsonObject>>>of(Map.entry("object",domain.objects().values()),Map.entry("tombstone",domain.tombstones().values()),Map.entry("change",domain.changes())))for(var value:section.getValue()){JsonObject entry=row(section.getKey());entry.add("value",value);CheckpointDataRows.write(sink,entry);}sink.end();
        sink.begin("editor_states");CheckpointDataRows.write(sink,control("editor_control",state.editor().size(),state.editorBytes()));for(var entry:new TreeMap<>(state.editor()).entrySet()){JsonObject value=row("editor");value.addProperty("id",entry.getKey().toString());value.add("value",entry.getValue());CheckpointDataRows.write(sink,value);}sink.end();
        sink.begin("deleted_native");CheckpointDataRows.write(sink,control("deleted_control",state.deleted().size(),state.deletedBytes()));for(var entry:new TreeMap<>(state.deleted()).entrySet()){var removed=entry.getValue();JsonObject value=row("deleted");value.addProperty("id",entry.getKey().toString());value.addProperty("x",removed.x());value.addProperty("z",removed.z());value.addProperty("container",removed.entry().container());value.addProperty("asset",removed.entry().object().asset());value.add("record",binary(removed.entry().object().record()));CheckpointDataRows.write(sink,value);}sink.end();
        sink.begin("relationships");JsonObject relationships=row("links_control");relationships.addProperty("count",state.links().size());CheckpointDataRows.write(sink,relationships);for(var entry:new TreeMap<>(state.links()).entrySet()){JsonObject value=row("link");value.addProperty("id",entry.getKey().toString());value.add("value",SceneRelationships.row(entry.getValue()));CheckpointDataRows.write(sink,value);}sink.end();
    }
    static final class Reader {
        private final State state;private final Map<String,JsonObject> controls=new HashMap<>();private final List<World.DurableEntity> actors=new ArrayList<>();private final Set<String> actorIds=new HashSet<>();private final List<ActorRuntime> actorRuntime=new ArrayList<>();private final List<ActorRuntime.Controller> actorControllers=new ArrayList<>();private final List<ActorMineState> mines=new ArrayList<>();private long slots;private int sectionAt=-1;private boolean finished;
        Reader(){this(java.nio.file.Path.of("var"));}
        Reader(java.nio.file.Path base){state=State.empty(base);}
        void discard(){state.close();}
        private static final List<String> SECTIONS=WorldCheckpointStore.SECTIONS.subList(6,12);
        void accept(String section,JsonObject row){
            if(finished)throw new IllegalArgumentException("checkpoint_command_finished");int at=SECTIONS.indexOf(section);if(at<0||at<sectionAt)throw new IllegalArgumentException("checkpoint_command_section");sectionAt=at;String kind=Json.text(row,"kind",32);
            if(kind.endsWith("_control")){String expected=List.of("program_control","inventory_control","domain_control","editor_control","deleted_control","links_control").get(at);if(!kind.equals(expected)||controls.containsKey(section))throw new IllegalArgumentException("checkpoint_command_control");validateControl(section,row);controls.put(section,row.deepCopy());return;}
            JsonObject control=controls.get(section);if(control==null)throw new IllegalArgumentException("checkpoint_command_control_required");
            switch(section){
                case "durable_entities"->{
                    long version=integer(control,"version",1,6);
                    if(kind.equals("mine")&&version==6){fields(row,"kind","x","y","z","cooldown");if(mines.size()>=integer(control,"mines",0,8192))throw new IllegalArgumentException("checkpoint_mine_count");mines.add(new ActorMineState(new World.Block((int)integer(row,"x",-MapBounds.HORIZONTAL,MapBounds.HORIZONTAL),(int)integer(row,"y",0,255),(int)integer(row,"z",-MapBounds.HORIZONTAL,MapBounds.HORIZONTAL)),(int)integer(row,"cooldown",0,1_000_000)));break;}
                    if(!kind.equals("actor"))throw new IllegalArgumentException("checkpoint_command_kind");
                    // Older writers could pair a pre-spawn empty runtime snapshot with a new durable actor.
                    // Its explicit encoded null is recoverable from the durable spawn program.
                    boolean pendingRuntime=version>=4&&java.util.Arrays.equals(binary(row.get("runtime"),2*1024*1024),new byte[]{110,117,108,108});
                    if(version>=5&&!pendingRuntime)fields(row,"kind","id","type","spawn","goal","incarnation","patrol","follow","runtime","controller");else if(version>=5)fields(row,"kind","id","type","spawn","goal","incarnation","patrol","follow","runtime");else if(version==4)fields(row,"kind","id","type","spawn","goal","incarnation","patrol","follow","runtime");else if(version==3)fields(row,"kind","id","type","spawn","goal","incarnation","patrol","follow");else if(version==2)fields(row,"kind","id","type","spawn","goal","incarnation","patrol");else fields(row,"kind","id","type","spawn","goal","incarnation");
                    String id=uuid(row,"id").toString();if(!actorIds.add(id)||actors.size()>=integer(control,"count",0,512))throw new IllegalArgumentException("checkpoint_actor_duplicate_or_count");String type=Json.text(row,"type",16);if(!Set.of("NPC","BOT").contains(type))throw new IllegalArgumentException("checkpoint_actor_type");
                    List<World.Vec> patrol=List.of();if(version>=2){if(!row.get("patrol").isJsonArray())throw new IllegalArgumentException("checkpoint_actor_patrol");if(!row.getAsJsonArray("patrol").isEmpty()){JsonObject route=new JsonObject();route.add("points",row.get("patrol"));patrol=CommandService.patrolRoute(route);}}
                    World.Follow follow=null;if(version>=3&&!row.get("follow").isJsonNull()){JsonObject value=Json.object(row,"follow");fields(value,"character","distance");follow=new World.Follow(uuid(value,"character"),Json.number(value,"distance",1,6));}
                    actors.add(new World.DurableEntity(id,type,position(Json.object(row,"spawn")),row.get("goal").isJsonNull()?null:position(Json.object(row,"goal")),integer(row,"incarnation",1,integer(control,"counter",0,Long.MAX_VALUE)),patrol,follow));if(version>=4&&!pendingRuntime)actorRuntime.add(ActorRuntimeCodec.actor(binary(row.get("runtime"),2*1024*1024)));if(version>=5&&!pendingRuntime)actorControllers.add(ActorRuntimeCodec.controller(binary(row.get("controller"),2*1024*1024)));
                }
                case "admin_inventories"->{if(kind.equals("owner")){fields(row,"kind","id");String id=uuid(row,"id").toString();if(state.inventories().size()>=integer(control,"owners",0,256)||state.inventories().putIfAbsent(id,new TreeMap<>())!=null)throw new IllegalArgumentException("checkpoint_inventory_owner");}
                    else if(kind.equals("item")){fields(row,"kind","owner","stack");String owner=uuid(row,"owner").toString();var inventory=state.inventories().get(owner);if(inventory==null)throw new IllegalArgumentException("checkpoint_inventory_owner_required");JsonObject stack=Json.object(row,"stack");fields(stack,"slot","itemId","count","nativeState");int slot=(int)integer(stack,"slot",0,159);integer(stack,"itemId",0,65535);integer(stack,"count",1,64);var value=stack.get("nativeState");if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().length()>87384)throw new IllegalArgumentException("checkpoint_inventory_state");String text=value.getAsString();byte[] raw=Base64.getDecoder().decode(text);if(raw.length>65536||!Base64.getEncoder().encodeToString(raw).equals(text))throw new IllegalArgumentException("checkpoint_inventory_state");if(inventory.putIfAbsent(slot,stack.deepCopy())!=null||++slots>integer(control,"slots",0,40960))throw new IllegalArgumentException("checkpoint_inventory_slot");}
                    else throw new IllegalArgumentException("checkpoint_command_kind");}
                case "scene_domain"->{fields(row,"kind","value");int type=switch(kind){case "object"->1;case "tombstone"->2;case "change"->3;default->throw new IllegalArgumentException("checkpoint_command_kind");};state.domain().restoreCheckpointRow(type,Json.object(row,"value"));}
                case "editor_states"->{if(!kind.equals("editor"))throw new IllegalArgumentException("checkpoint_command_kind");fields(row,"kind","id","value");state.editor().restoreCheckpointRow(uuid(row,"id"),Json.object(row,"value"));}
                case "deleted_native"->{if(!kind.equals("deleted"))throw new IllegalArgumentException("checkpoint_command_kind");fields(row,"kind","id","x","z","container","asset","record");UUID id=uuid(row,"id");int x=(int)integer(row,"x",Integer.MIN_VALUE,Integer.MAX_VALUE),z=(int)integer(row,"z",Integer.MIN_VALUE,Integer.MAX_VALUE);SceneObject object=new SceneObject(Json.text(row,"asset",4096),binary(row.get("record"),ScenePayload.MAX_OBJECT));state.deleted().restoreCheckpointRow(id,new SceneDeletedStates.Removed(x,z,new ScenePayload.Entry(flag(row,"container"),object)));}
                case "relationships"->{if(!kind.equals("link"))throw new IllegalArgumentException("checkpoint_command_kind");fields(row,"kind","id","value");var value=Json.object(row,"value");fields(value,"parent","group");state.links().restoreCheckpointRow(uuid(row,"id"),new SceneRelationships.State(nullableUuid(value,"parent"),nullableUuid(value,"group")));}
                default->throw new IllegalArgumentException("checkpoint_command_section");
            }
        }
        private static UUID nullableUuid(JsonObject row,String key){return row.get(key).isJsonNull()?null:uuid(row,key);}
        private static World.Vec position(JsonObject row){fields(row,"x","y","z");return new World.Vec(Json.number(row,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.number(row,"y",0,255),Json.number(row,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));}
        private void validateControl(String section,JsonObject row){switch(section){
            case "durable_entities"->{long v=integer(row,"version",1,6);if(v>=4){if(v==6){fields(row,"kind","version","count","counter","tick","mines");integer(row,"mines",0,8192);}else fields(row,"kind","version","count","counter","tick");integer(row,"tick",0,Long.MAX_VALUE/2);}else fields(row,"kind","version","count","counter");integer(row,"count",0,512);integer(row,"counter",0,Long.MAX_VALUE);}
            case "admin_inventories"->{fields(row,"kind","owners","slots");integer(row,"owners",0,256);integer(row,"slots",0,40960);}
            case "scene_domain"->{boolean current=row.has("version");if(current){fields(row,"kind","version","world","head","bytes","changeBytes","objects","tombstones","changes");integer(row,"version",2,2);integer(row,"changeBytes",0,Long.MAX_VALUE);}else fields(row,"kind","world","head","bytes","objects","tombstones","changes");state.domain().restoreCheckpointWorld(Json.text(row,"world",1024));integer(row,"head",0,Long.MAX_VALUE);integer(row,"bytes",0,SceneDomain.byteLimit());for(String key:List.of("objects","tombstones"))integer(row,key,0,SceneDomain.rowLimit());integer(row,"changes",0,current?Long.MAX_VALUE:SceneDomain.rowLimit());}
            case "editor_states"->{fields(row,"kind","count","bytes");integer(row,"count",0,SceneEditorStates.rowLimit());integer(row,"bytes",0,SceneEditorStates.byteLimit());}
            case "deleted_native"->{fields(row,"kind","count","bytes");integer(row,"count",0,SceneDeletedStates.rowLimit());integer(row,"bytes",0,SceneDeletedStates.byteLimit());}
            case "relationships"->{fields(row,"kind","count");integer(row,"count",0,SceneRelationships.rowLimit());}
            default->throw new IllegalArgumentException("checkpoint_command_section");}}
        State finish(){
            if(finished||controls.size()!=6)throw new IllegalArgumentException("checkpoint_command_incomplete");
            check("durable_entities","count",actors.size());var program=controls.get("durable_entities");state.actors().restore(actors,integer(program,"counter",0,Long.MAX_VALUE));
            long version=integer(program,"version",1,6);if(version==6)check("durable_entities","mines",mines.size());if(version>=4&&actorRuntime.size()==actors.size())state.actors().runtime(new ActorRuntime.Snapshot(version>=5?2:1,integer(program,"tick",0,Long.MAX_VALUE/2),state.actors().counter(),actorRuntime,actorControllers,mines));
            check("admin_inventories","owners",state.inventories().size());check("admin_inventories","slots",slots);
            var domain=state.domain().checkpointView();check("scene_domain","objects",domain.objects().size());check("scene_domain","tombstones",domain.tombstones().size());check("scene_domain","changes",domain.changeCount());check("scene_domain","head",domain.head());
            check("scene_domain","bytes",controls.get("scene_domain").has("version")?domain.bytes():Math.addExact(domain.bytes(),domain.changeBytes()));if(controls.get("scene_domain").has("version"))check("scene_domain","changeBytes",domain.changeBytes());
            check("editor_states","count",state.editor().currentRows().size());check("editor_states","bytes",state.editor().checkpointBytes());check("deleted_native","count",state.deleted().currentRows().size());check("deleted_native","bytes",state.deleted().checkpointBytes());check("relationships","count",state.links().checkpointRows().size());state.publish();finished=true;return state;
        }
        private void check(String section,String key,long actual){if(actual!=integer(controls.get(section),key,0,Long.MAX_VALUE))throw new IllegalArgumentException("checkpoint_command_count:"+section+":"+key);}
    }
}
