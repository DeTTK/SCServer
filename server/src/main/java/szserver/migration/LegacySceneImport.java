package szserver.migration;

import com.google.gson.*;
import szserver.client.LegacySceneState;
import szserver.commands.SceneImportSeed;
import szserver.commands.ScenePortalImportSeed;
import szserver.commands.SceneRelationshipSeed;
import szserver.commands.SceneEditorStateImportSeed;
import szserver.commands.SceneDeletedImportSeed;
import szserver.net.*;
import szserver.util.Json;
import szserver.world.*;
import szserver.persistence.Journal;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only offline conversion of pending native editor overlays, never historical replay over current SZR. */
public final class LegacySceneImport {
    public record Context(String sourceWorld,Path map,Path registry,Path codecs,Integer fallbackSurface,boolean projectNativeBlocks){public Context(String sourceWorld,Path map,Path registry,Path codecs,Integer fallbackSurface){this(sourceWorld,map,registry,codecs,fallbackSurface,false);}}
    public record Plan(LegacyEventPlan data,int projected,int historical,long eventHead,int components,long bytes,int relationships,int relationshipBatches,int retainedDeleted,SceneRegistry registry) implements AutoCloseable{public long records(){return data.records();}public void visit(Journal.Recovery sink)throws Exception{data.visit(sink);}public void close()throws Exception{data.close();}}
    private static final Set<String> TABLES=Set.of("imeditor_objects","imeditor_events","imeditor_object_components");
    private static boolean present(JsonObject row,String key){return row.has(key)&&!row.get(key).isJsonNull();}
    private static long integer(JsonObject row,String key,long min){var v=row.get(key);try{if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=new java.math.BigDecimal(v.getAsString()).longValueExact();if(n<min)throw new ArithmeticException();return n;}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_scene_integer:"+key,error);}}
    private static UUID identity(JsonObject row,String key){String value=Json.text(row,key,36);UUID id;try{id=UUID.fromString(value);}catch(IllegalArgumentException error){throw new IllegalArgumentException("legacy_scene_identity",error);}if(!id.toString().equals(value))throw new IllegalArgumentException("legacy_scene_identity");return id;}
    private static byte[] blob(JsonObject row,String key){if(!present(row,key))return null;JsonObject value=Json.object(row,key);if(value.size()!=1||!value.has("$blob"))throw new IllegalArgumentException("legacy_scene_blob");String text=value.get("$blob").getAsString();int limit=key.equals("native_record")?ScenePayload.MAX_OBJECT:key.equals("full_save")?szserver.client.SceneConfigurationPackets.MAX_NBT+5:Set.of("payload","last_payload").contains(key)?1024*1024:65536;if(text.length()>(limit+2)/3*4)throw new IllegalArgumentException("legacy_scene_blob_limit");byte[] body=Base64.getDecoder().decode(text);if(body.length>limit||!Base64.getEncoder().encodeToString(body).equals(text))throw new IllegalArgumentException("legacy_scene_blob_limit");return body;}
    /** Store asset_id is editable metadata; the retained native body owns its factory. */
    private static SceneObject nativeRecord(UUID id,byte[] record,SceneRegistry registry){
        if(record==null||record.length<2)throw new IllegalArgumentException("legacy_scene_native_record_required");var definition=registry.target().ids().get(ByteBuffer.wrap(record).getShort()&65535);if(definition==null)throw new IllegalArgumentException("legacy_scene_registry_epoch_mismatch");SceneObject object=new SceneObject(definition.name(),record);if(!object.identity().equals(id))throw new IllegalArgumentException("legacy_scene_native_identity_mismatch");return object;
    }
    private static JsonElement encoded(JsonObject row,String key){int limit=key.equals("state_json")?192*1024:16384;String value=Json.text(row,key,limit);return Json.parse(("{\"value\":"+value+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),limit).get("value");}
    private static void common(JsonObject row,Context context){if(!Json.text(row,"world_id",1024).equals(context.sourceWorld()))throw new IllegalArgumentException("legacy_scene_source_world_unmapped");}
    @FunctionalInterface private interface Cold<T>{T get()throws Exception;}
    private static <T>T ready(Cold<T> read)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);while(true){try{return read.get();}catch(IllegalArgumentException error){if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;if(System.nanoTime()>=deadline)throw new IllegalArgumentException("legacy_scene_map_load_timeout");Thread.sleep(10);}}}
    private static Terrain.SceneHome origin(JsonObject row){if(present(row,"origin_chunk_x")!=present(row,"origin_chunk_z"))throw new IllegalArgumentException("legacy_scene_origin_incomplete");return present(row,"origin_chunk_x")?new Terrain.SceneHome(Json.integer(row,"origin_chunk_x",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK),Json.integer(row,"origin_chunk_z",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK)):null;}
    private static double[] position(JsonObject row){int count=0;for(String key:List.of("x","y","z"))if(present(row,key))count++;if(count==0)return null;if(count!=3)throw new IllegalArgumentException("legacy_scene_position_incomplete");return new double[]{Json.number(row,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.number(row,"y",-szserver.world.MapBounds.SCENE,szserver.world.MapBounds.SCENE),Json.number(row,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL)};}
    private static float[] matrix(JsonObject row,double[] position,SceneObject object){
        float[] result=new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};if(present(row,"transform_json")){JsonArray array=encoded(row,"transform_json").getAsJsonArray();if(array.size()!=16)throw new IllegalArgumentException("legacy_scene_matrix_size");for(int i=0;i<16;i++){var v=array.get(i);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_scene_matrix");result[i]=v.getAsFloat();if(!Float.isFinite(result[i])||Math.abs(result[i])>30000000)throw new IllegalArgumentException("legacy_scene_matrix");}
        }else{byte[] nativeBody=object.record();int at=SceneObject.prefix(nativeBody).transformAt();int[] indexes={0,1,2,4,5,6,8,9,10};for(int i=0;i<9;i++)result[indexes[i]]=ByteBuffer.wrap(nativeBody).getFloat(at+i*4);for(int i=0;i<3;i++)result[12+i]=(float)position[i];}
        for(int i=0;i<3;i++)if(result[12+i]!=(float)position[i])throw new IllegalArgumentException("legacy_scene_position_transform_mismatch");return result;
    }
    private static double[] metadataPosition(JsonObject row){int count=0;for(String key:List.of("x","y","z"))if(present(row,key))count++;if(count==0)return null;if(count!=3)throw new IllegalArgumentException("legacy_scene_position_incomplete");return new double[]{Json.number(row,"x",-30000000,30000000),Json.number(row,"y",-30000000,30000000),Json.number(row,"z",-30000000,30000000)};}
    private static Terrain.SceneHome metadataOrigin(JsonObject row){if(present(row,"origin_chunk_x")!=present(row,"origin_chunk_z"))throw new IllegalArgumentException("legacy_scene_origin_incomplete");return present(row,"origin_chunk_x")?new Terrain.SceneHome(Json.integer(row,"origin_chunk_x",-1875000,1875000),Json.integer(row,"origin_chunk_z",-1875000,1875000)):null;}
    private static PortalMetadata metadata(JsonObject row){
        double[] position=metadataPosition(row),matrix=null;if(present(row,"transform_json")){JsonArray values=encoded(row,"transform_json").getAsJsonArray();if(values.size()!=16)throw new IllegalArgumentException("legacy_scene_matrix_size");matrix=new double[16];for(int i=0;i<16;i++){var value=values.get(i);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_scene_matrix");matrix[i]=value.getAsDouble();if(!Double.isFinite(matrix[i])||i>=12&&i<=14&&Math.abs(matrix[i])>30000000)throw new IllegalArgumentException("legacy_scene_matrix");}if(position==null)position=new double[]{matrix[12],matrix[13],matrix[14]};}
        String asset=present(row,"asset_id")?Json.text(row,"asset_id",1024):"";return new PortalMetadata(identity(row,"object_id"),asset,position==null?null:new World.Vec(position[0],position[1],position[2]),PortalMetadata.matrixScale(matrix),row.get("deleted").getAsInt()!=0);
    }
    /** Existing frames can change in their original chunk, but absent native_record cannot create a destination frame. */
    private record FrameLess(Map<Terrain.SceneHome,ScenePayload.Entry> placements,boolean nativeFrame){}
    /** Apply the old per-channel projector to every physical copy. Native-less rows
     * can retain existing copies but cannot create a new destination. No map IO
     * runs on gameplay owners: this is offline prepublication validation. */
    private static FrameLess sharedPlacements(JsonObject row,RegionTerrain terrain,SceneRegistry registry)throws Exception{
        UUID id=identity(row,"object_id");Set<Long> physical=ready(()->terrain.nativeSceneHomes(id));
        if(physical.size()>256)throw new IllegalArgumentException("legacy_scene_placement_limit_256");
        Map<Terrain.SceneHome,ScenePayload.Entry> copies=new LinkedHashMap<>();
        for(long key:new TreeSet<>(physical)){Terrain.SceneHome home=new Terrain.SceneHome((int)(key>>32),(int)key);ScenePayload.Entry base=ready(()->terrain.sceneIndex(home.x(),home.z())).get(id);if(base!=null)copies.put(home,base);}
        JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject().deepCopy():new JsonObject();state.remove("parent_id");state.remove("group_id");
        String channel=state.has("_native_channel")?Json.text(state,"_native_channel",64):"";
        if(!channel.isEmpty()&&!Set.of("object_blob","raw_container").contains(channel))throw new IllegalArgumentException("legacy_scene_native_channel_required");
        double[] position=metadataPosition(row);Terrain.SceneHome destination=position==null?null:new Terrain.SceneHome((int)Math.floor(position[0]/16),(int)Math.floor(position[2]/16));boolean deleted=row.get("deleted").getAsInt()!=0,transform=present(row,"transform_json");
        Map<Terrain.SceneHome,ScenePayload.Entry> placements=new LinkedHashMap<>();boolean live=false;
        for(var copy:copies.entrySet()){
            Terrain.SceneHome home=copy.getKey();ScenePayload.Entry base=copy.getValue();ScenePayload.Entry after=null;
            List<ScenePayload.Occurrence> values=new ArrayList<>();
            if(!deleted)for(var occurrence:base.occurrences())if(!(occurrence.container()?channel.equals("object_blob"):channel.equals("raw_container"))){
                if(position==null)values.add(occurrence);
                else if(home.equals(destination)){
                    SceneObject object=occurrence.object();if(occurrence.container())object=object.shifted(id,0,0,0);
                    double[] translation=occurrence.container()&&!transform?new double[]{object.x(),object.y(),object.z()}:position;
                    SceneObject projected=LegacySceneState.apply(object,matrix(row,translation,object),occurrence.container()||transform?state:null,occurrence.container()||transform?blob(row,"full_save"):null,registry.target().names().get(object.asset()).behaviour());values.add(new ScenePayload.Occurrence(occurrence.container(),projected));
                }
            }
            if(!values.isEmpty())after=ScenePayload.Entry.of(values);
            placements.put(home,after);live|=after!=null;
        }
        byte[] record=blob(row,"native_record");SceneObject supplied=null;
        if(record!=null){
            supplied=nativeRecord(id,record,registry);
            // Existing frames retain their own assets; old projectors select them by UUID/channel.
            // Validate the supplied record's epoch independently: it is used only for a missing channel.
        }
        if(!deleted&&destination!=null&&record!=null){
            ScenePayload.Entry current=placements.get(destination);boolean raw=channel.equals("raw_container");
            // Each channel tracks UUID presence independently in the old projectors.
            if(current==null||!current.hasChannel(raw)){
                SceneObject object=supplied;var definition=registry.target().names().get(object.asset());
                if(raw)object=object.shifted(id,0,0,0);double[] translation=raw&&!transform?new double[]{object.x(),object.y(),object.z()}:position;
                if(raw&&!transform&&(object.x()!=(float)position[0]||object.y()!=(float)position[1]||object.z()!=(float)position[2]))throw new IllegalArgumentException("legacy_scene_raw_transform_required");
                SceneObject projected=LegacySceneState.apply(object,matrix(row,translation,object),raw||transform?state:null,raw||transform?blob(row,"full_save"):null,definition.behaviour());
                if(raw&&!ready(()->terrain.rawSceneContainer(destination.x(),destination.z())))throw new IllegalArgumentException("legacy_scene_raw_container_unavailable");List<ScenePayload.Occurrence> values=current==null?new ArrayList<>():new ArrayList<>(current.occurrences());values.add(new ScenePayload.Occurrence(raw,projected));placements.put(destination,ScenePayload.Entry.of(values));live=true;
            }
        }
        Terrain.SceneHome origin=present(row,"native_record")?origin(row):metadataOrigin(row);if(origin!=null&&Math.abs((long)origin.x())<=szserver.world.MapBounds.CHUNK&&Math.abs((long)origin.z())<=szserver.world.MapBounds.CHUNK)placements.putIfAbsent(origin,null);
        if(placements.size()>256)throw new IllegalArgumentException("legacy_scene_placement_limit_256");
        return new FrameLess(placements,live);
    }
    private static FrameLess frameLess(JsonObject row,RegionTerrain terrain,SceneRegistry registry)throws Exception{
        UUID id=identity(row,"object_id");Terrain.SceneHome physical=ready(()->terrain.nativeSceneHome(id));ScenePayload.Entry base=physical==null?null:ready(()->terrain.sceneIndex(physical.x(),physical.z())).get(id);Terrain.SceneHome home=physical;
        if(base==null){Terrain.SceneHome origin=metadataOrigin(row);if(origin!=null&&Math.abs((long)origin.x())<=szserver.world.MapBounds.CHUNK&&Math.abs((long)origin.z())<=szserver.world.MapBounds.CHUNK){base=ready(()->terrain.sceneIndex(origin.x(),origin.z())).get(id);if(base!=null)home=origin;}}
        if(base==null){Terrain.SceneHome origin=metadataOrigin(row);if(row.get("deleted").getAsInt()!=0&&origin!=null&&Math.abs((long)origin.x())<=szserver.world.MapBounds.CHUNK&&Math.abs((long)origin.z())<=szserver.world.MapBounds.CHUNK){Map<Terrain.SceneHome,ScenePayload.Entry> tombstone=new LinkedHashMap<>();tombstone.put(origin,null);return new FrameLess(tombstone,false);}return new FrameLess(Map.of(),false);}Map<Terrain.SceneHome,ScenePayload.Entry> placements=new LinkedHashMap<>();double[] position=metadataPosition(row);JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject().deepCopy():new JsonObject();state.remove("parent_id");state.remove("group_id");String channel=state.has("_native_channel")&&state.get("_native_channel").isJsonPrimitive()?state.get("_native_channel").getAsString():"";
        if(row.get("deleted").getAsInt()!=0||base.container()&&channel.equals("object_blob")||!base.container()&&channel.equals("raw_container")){placements.put(home,null);return new FrameLess(placements,false);}
        if(position==null)return new FrameLess(Map.of(),true);if((int)Math.floor(position[0]/16)!=home.x()||(int)Math.floor(position[2]/16)!=home.z()){placements.put(home,null);return new FrameLess(placements,false);}
        SceneObject object=base.object();boolean raw=base.container(),transform=present(row,"transform_json");if(raw)object=object.shifted(id,0,0,0);double[] translation=raw&&!transform?new double[]{object.x(),object.y(),object.z()}:position;
        SceneObject projected=LegacySceneState.apply(object,matrix(row,translation,object),raw||transform?state:null,raw||transform?blob(row,"full_save"):null,registry.target().names().get(object.asset()).behaviour());ScenePayload.Entry result=new ScenePayload.Entry(raw,projected);if(!result.equals(base))placements.put(home,result);return new FrameLess(placements,true);
    }
    /** Retain the native body plus the last Store settings even though the deleted row has no visible frame. */
    private static ScenePayload.Entry retainedDeleted(JsonObject row,RegionTerrain terrain,SceneRegistry registry)throws Exception{
        UUID id=identity(row,"object_id");Terrain.SceneHome physical=ready(()->terrain.nativeSceneHome(id));
        // Nonpending rows never override the authoritative map; do not shadow an existing live instance.
        if(row.get("projection_pending").getAsInt()==0&&physical!=null)return null;
        ScenePayload.Entry base=physical==null?null:ready(()->terrain.sceneIndex(physical.x(),physical.z())).get(id);byte[] record=blob(row,"native_record");
        if(record==null&&base==null)return null;
        SceneObject object=record==null?base.object():nativeRecord(id,record,registry);SceneRegistry.Definition definition=registry.target().names().get(object.asset());
        JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject().deepCopy():new JsonObject();state.remove("parent_id");state.remove("group_id");
        String channel=state.has("_native_channel")?Json.text(state,"_native_channel",64):base!=null&&base.container()?"raw_container":"object_blob";
        if(!Set.of("raw_container","object_blob").contains(channel))throw new IllegalArgumentException("legacy_scene_native_channel_required");boolean raw=channel.equals("raw_container");if(raw)object=object.shifted(id,0,0,0);
        double[] translation=position(row);if(present(row,"transform_json")&&translation==null){JsonArray values=encoded(row,"transform_json").getAsJsonArray();if(values.size()!=16)throw new IllegalArgumentException("legacy_scene_matrix_size");translation=new double[]{values.get(12).getAsDouble(),values.get(13).getAsDouble(),values.get(14).getAsDouble()};}
        if(translation==null||raw&&!present(row,"transform_json"))translation=new double[]{object.x(),object.y(),object.z()};
        // A later explicit matrix update will apply the retained FULL_SAVE/state even if the old row had no matrix.
        SceneObject retained=LegacySceneState.apply(object,matrix(row,translation,object),state,blob(row,"full_save"),definition.behaviour());return new ScenePayload.Entry(raw,retained);
    }
    public static Plan read(LegacySnapshot snapshot,Context context,String worldIdentity)throws Exception{return read(snapshot,context,worldIdentity,Path.of("var"));}
    public static Plan read(LegacySnapshot snapshot,Context context,String worldIdentity,Path scratch)throws Exception{
        if(TABLES.stream().mapToInt(snapshot::count).sum()==0)return new Plan(LegacyEventPlan.empty(),0,0,0,0,0,0,0,0,null);
        if(context==null||context.sourceWorld()==null||context.sourceWorld().isBlank())throw new IllegalArgumentException("legacy_scene_context_required");
        if(context.map()==null||context.registry()==null||context.codecs()==null)throw new IllegalArgumentException("legacy_scene_map_context_required");
        SceneRegistry registry=new SceneRegistry(context.map(),context.registry(),context.codecs());
        try(LegacyEventPlan.Builder out=new LegacyEventPlan.Builder(scratch,"native-scene-plan-");LegacySortedRows sortedRows=new LegacySortedRows(out.directory,1);LegacySortedRows sortedEvents=new LegacySortedRows(out.directory,3);Journal linkEvents=Journal.streaming(out.directory.resolve("links.sjournal"));Journal historicalEvents=Journal.streaming(out.directory.resolve("historical.sjournal"));Journal deletedEvents=Journal.streaming(out.directory.resolve("deleted.sjournal"));SceneEditorStateImportSeed.Validator editorValidator=new SceneEditorStateImportSeed.Validator(out.directory);SceneDeletedImportSeed.Validator deletedValidator=new SceneDeletedImportSeed.Validator(out.directory)){
        List<SceneRelationshipSeed.Links> links=new ArrayList<>();
        snapshot.visit("imeditor_objects",row->{common(row,context);UUID id=identity(row,"object_id");integer(row,"revision",1);Json.integer(row,"deleted",0,1);Json.integer(row,"projection_pending",0,1);if(present(row,"native_record")){position(row);origin(row);}else{metadataPosition(row);metadataOrigin(row);}if(present(row,"related_id"))identity(row,"related_id");JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject():new JsonObject();UUID group=nullableReference(state,"group_id");nullableReference(state,"parent_id");UUID parent=present(row,"related_id")?identity(row,"related_id"):null;if(present(row,"transform_json"))encoded(row,"transform_json").getAsJsonArray();JsonArray attributes=encoded(row,"attributes_json").getAsJsonArray();if(attributes.size()>256)throw new IllegalArgumentException("legacy_scene_attributes");for(var attribute:attributes)if(!attribute.isJsonPrimitive()||!attribute.getAsJsonPrimitive().isString())throw new IllegalArgumentException("legacy_scene_attributes");Json.text(row,"last_packet_class",64);for(String key:List.of("native_record","full_save","named_state","transform_state","last_payload"))blob(row,key);sortedRows.add(row);});
        long[] head={0};snapshot.visit("imeditor_events",row->{common(row,context);long seq=integer(row,"sequence",1);head[0]=Math.max(head[0],seq);if(present(row,"object_id"))identity(row,"object_id");if(present(row,"related_id"))identity(row,"related_id");Json.text(row,"packet_class",64);Json.text(row,"operation",64);blob(row,"payload");metadataPosition(row);encoded(row,"attributes_json").getAsJsonArray();sortedEvents.add(row);});
        Set<String> components=new HashSet<>();snapshot.visit("imeditor_object_components",row->{common(row,context);UUID id=identity(row,"object_id");String kind=Json.text(row,"packet_class",64);if(!components.add(id+":"+kind))throw new IllegalArgumentException("legacy_scene_component_duplicate");integer(row,"revision",1);Json.text(row,"operation",64);blob(row,"payload");});
        sortedEvents.visit(row->{}); // Verify unique sequence order without retaining historical rows.
        Map<Terrain.SceneHome,Map<UUID,ScenePayload.Entry>> overlays=new LinkedHashMap<>();int historical=0,projectedCount=0,relationshipCount=0,batches=0,retainedCount=0;long bytes=0;int placementsCount=0;
        try(RegionTerrain terrain=new RegionTerrain(context.map(),32,context.fallbackSurface(),registry);LegacySortedRows.Cursor rows=sortedRows.cursor()){
            if(!terrain.identity().equals(worldIdentity))throw new IllegalArgumentException("legacy_scene_target_world_mismatch");
            for(JsonObject row;(row=rows.next())!=null;){UUID id=identity(row,"object_id");JsonObject relationshipState=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject():new JsonObject();UUID rowGroup=nullableReference(relationshipState,"group_id"),rowParent=present(row,"related_id")?identity(row,"related_id"):null;if(rowParent!=null||rowGroup!=null){links.add(new SceneRelationshipSeed.Links(id,rowParent,rowGroup));relationshipCount++;if(links.size()==128){JsonObject event=SceneRelationshipSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),links);bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");linkEvents.append(event).get();links.clear();batches++;}}if(row.get("deleted").getAsInt()!=0){ScenePayload.Entry retained=retainedDeleted(row,terrain,registry);if(retained!=null){JsonObject seed=SceneDeletedImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),retained);bytes+=Json.bytes(seed).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");deletedValidator.event(seed);deletedEvents.append(seed).get();retainedCount++;}}if(row.get("projection_pending").getAsInt()==0){historical++;
                    if(row.get("deleted").getAsInt()!=0&&!present(row,"native_record")&&ready(()->terrain.nativeSceneHome(id))==null){JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject():new JsonObject();double[] sourcePosition=metadataPosition(row);JsonObject seed=SceneEditorStateImportSeed.attach(ScenePortalImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),metadata(row)),present(row,"asset_id")?Json.text(row,"asset_id",1024):null,present(row,"transform_json")?encoded(row,"transform_json").getAsJsonArray():null,sourcePosition==null?null:new World.Vec(sourcePosition[0],sourcePosition[1],sourcePosition[2]),state,blob(row,"full_save"),present(row,"related_id")?identity(row,"related_id"):null,nullableReference(state,"group_id"),true);bytes+=Json.bytes(seed).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");historicalEvents.append(seed).get();}
                    continue;}
                if(ready(()->terrain.sceneShared(id))){
                    FrameLess projected=sharedPlacements(row,terrain,registry);var placements=projected.placements();placementsCount+=placements.size();if(placementsCount>100000)throw new IllegalArgumentException("legacy_scene_overlay_limit");
                    PortalMetadata portal=metadata(row);JsonObject event=placements.isEmpty()?ScenePortalImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),portal):ScenePortalImportSeed.attach(SceneImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),id,placements),portal);
                    if(!present(row,"native_record")&&!projected.nativeFrame()){
                        JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject():new JsonObject();double[] sourcePosition=metadataPosition(row);
                        event=SceneEditorStateImportSeed.attach(event,present(row,"asset_id")?Json.text(row,"asset_id",1024):null,present(row,"transform_json")?encoded(row,"transform_json").getAsJsonArray():null,sourcePosition==null?null:new World.Vec(sourcePosition[0],sourcePosition[1],sourcePosition[2]),state,blob(row,"full_save"),present(row,"related_id")?identity(row,"related_id"):null,nullableReference(state,"group_id"),row.get("deleted").getAsInt()!=0);
                    }
                    bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");editorValidator.event(event);out.append(event);projectedCount++;for(var placement:placements.entrySet())overlays.computeIfAbsent(placement.getKey(),key->new LinkedHashMap<>()).put(id,placement.getValue());continue;
                }
                if(!present(row,"native_record")){FrameLess projected=frameLess(row,terrain,registry);Map<Terrain.SceneHome,ScenePayload.Entry> placements=projected.placements();placementsCount+=placements.size();if(placementsCount>100000)throw new IllegalArgumentException("legacy_scene_overlay_limit");PortalMetadata portal=metadata(row);JsonObject event=placements.isEmpty()?ScenePortalImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),portal):ScenePortalImportSeed.attach(SceneImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),id,placements),portal);
                    if(!projected.nativeFrame()){JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject():new JsonObject();UUID parent=present(row,"related_id")?identity(row,"related_id"):null;double[] sourcePosition=metadataPosition(row);event=SceneEditorStateImportSeed.attach(event,present(row,"asset_id")?Json.text(row,"asset_id",1024):null,present(row,"transform_json")?encoded(row,"transform_json").getAsJsonArray():null,sourcePosition==null?null:new World.Vec(sourcePosition[0],sourcePosition[1],sourcePosition[2]),state,blob(row,"full_save"),parent,nullableReference(state,"group_id"),row.get("deleted").getAsInt()!=0);}
                    bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");editorValidator.event(event);out.append(event);projectedCount++;for(var placement:placements.entrySet())overlays.computeIfAbsent(placement.getKey(),key->new LinkedHashMap<>()).put(id,placement.getValue());continue;}
                Terrain.SceneHome home=ready(()->terrain.sceneHome(id)),origin=origin(row);double[] position=position(row);Terrain.SceneHome destination=position==null?null:new Terrain.SceneHome((int)Math.floor(position[0]/16),(int)Math.floor(position[2]/16));Map<Terrain.SceneHome,ScenePayload.Entry> placements=new LinkedHashMap<>();if(home!=null)placements.put(home,null);if(origin!=null)placements.put(origin,null);if(destination!=null)placements.put(destination,null);
                if(row.get("deleted").getAsInt()==0){
                    if(position==null)throw new IllegalArgumentException("legacy_scene_position_required");ScenePayload.Entry base=home==null?null:ready(()->terrain.sceneIndex(home.x(),home.z())).get(id);SceneObject supplied=nativeRecord(id,blob(row,"native_record"),registry);SceneObject object=base==null?supplied:base.object();SceneRegistry.Definition definition=registry.target().names().get(object.asset());
                    JsonObject state=present(row,"state_json")?encoded(row,"state_json").getAsJsonObject().deepCopy():new JsonObject();state.remove("parent_id");state.remove("group_id");String channel=state.has("_native_channel")?Json.text(state,"_native_channel",64):base==null?null:base.container()?"raw_container":"object_blob";if(!Set.of("raw_container","object_blob").contains(channel==null?"":channel))throw new IllegalArgumentException("legacy_scene_native_channel_required");boolean raw=channel.equals("raw_container");if(raw)object=object.shifted(id,0,0,0);
                    if(raw&&!present(row,"transform_json")&&(object.x()!=(float)position[0]||object.y()!=(float)position[1]||object.z()!=(float)position[2]))throw new IllegalArgumentException("legacy_scene_raw_transform_required");
                    // In the old blob path a row without a matrix updates translation only; the raw path always applies its state.
                    boolean transform=present(row,"transform_json");SceneObject projected=LegacySceneState.apply(object,matrix(row,position,object),raw||transform?state:null,raw||transform?blob(row,"full_save"):null,definition.behaviour());
                    if(raw&&!ready(()->terrain.rawSceneContainer(destination.x(),destination.z())))throw new IllegalArgumentException("legacy_scene_raw_container_unavailable");placements.put(destination,new ScenePayload.Entry(raw,projected));
                }
                if(placements.isEmpty()){JsonObject event=ScenePortalImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),metadata(row));bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");editorValidator.event(event);out.append(event);projectedCount++;continue;}placementsCount+=placements.size();if(placementsCount>100000)throw new IllegalArgumentException("legacy_scene_overlay_limit");JsonObject event=SceneImportSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),integer(row,"revision",1),id,placements);bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");editorValidator.event(event);out.append(event);projectedCount++;for(var placement:placements.entrySet())overlays.computeIfAbsent(placement.getKey(),key->new LinkedHashMap<>()).put(id,placement.getValue());
            }
            // Validate the combined final chunks, not merely each individually admissible object.
            for(var overlay:overlays.entrySet()){var chunk=overlay.getKey();byte[] source=ready(()->terrain.sceneSource(chunk.x(),chunk.z())).record();byte[] projected=TerrainProjection.project(source,new BlockOverlays.Snapshot(1,Map.of(),Map.of(),Collections.unmodifiableMap(overlay.getValue())));ScenePayload.parse(projected);if(GamePackets.terrainChunk(projected).length>240000)throw new IllegalArgumentException("legacy_scene_terrain_frame_limit");}
        }
        if(!links.isEmpty()){JsonObject event=SceneRelationshipSeed.create(worldIdentity,registry.target().digest(),context.sourceWorld(),links);bytes+=Json.bytes(event).length;if(bytes>8L*1024*1024*1024)throw new IllegalArgumentException("legacy_scene_seed_bytes_limit");linkEvents.append(event).get();batches++;}
        linkEvents.prefix().get();historicalEvents.prefix().get();deletedEvents.prefix().get();
        linkEvents.replay(out::append).get();historicalEvents.replay(event->{editorValidator.event(event);out.append(event);}).get();deletedEvents.replay(out::append).get();
        return new Plan(out.seal(),projectedCount,historical,head[0],components.size(),bytes,relationshipCount,batches,retainedCount,registry);
        }
    }

    private static UUID nullableReference(JsonObject state,String key){if(!present(state,key))return null;try{return identity(state,key);}catch(RuntimeException error){throw new IllegalArgumentException("legacy_scene_relationship_uuid:"+key,error);}}
    private LegacySceneImport(){}
}
