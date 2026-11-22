package szserver.world;

import java.util.*;
import java.util.concurrent.*;

/** Mutable state has exactly one owner: WorldLoop. Units: blocks, seconds. */
public final class World implements BlockShapes.Voxels {
    public record Vec(double x,double y,double z) {}
    public record Block(int x,int y,int z) {}
    public record View(String id,String kind,double x,double y,double z,double vx,double vy,double vz,
                       boolean grounded,String navigation,int pathLength,Vec goal,long incarnation,float health,int patrolPoint,int patrolPoints,Follow follow,long recoveryPlans,boolean ladder,boolean inWater,float stamina,int jumps,int posture,int movementMode,int locomotion,int previousLocomotion,List<SceneCollision.Box> bodyParts,float yaw,ActorTraversal traversal,boolean aiming,ActorVitals vitals,ActorPresentation presentation,Map<String,Float> stats,List<ActorBuff.View> buffs) {
        public View{bodyParts=List.copyOf(bodyParts);stats=Map.copyOf(stats);buffs=List.copyOf(buffs);}
        public float maximumHealth(){return vitals==null?100:vitals.maximum();}
        /** Exact published native segments. The union in body() is only a broad-phase bound. */
        public List<SceneCollision.Box> collisionParts(){return bodyParts.isEmpty()?List.of((posture==1?ActorPose.CROUCH:ActorPose.STAND).body(x,y,z)):bodyParts;}
        public SceneCollision.Box body(){if(bodyParts.isEmpty())return (posture==1?ActorPose.CROUCH:ActorPose.STAND).body(x,y,z);double ax=Double.POSITIVE_INFINITY,ay=ax,az=ax,bx=Double.NEGATIVE_INFINITY,by=bx,bz=bx;for(var b:bodyParts){ax=Math.min(ax,b.x());ay=Math.min(ay,b.y());az=Math.min(az,b.z());bx=Math.max(bx,b.xx());by=Math.max(by,b.yy());bz=Math.max(bz,b.zz());}return new SceneCollision.Box(ax,ay,az,bx,by,bz);}
    }
    public record Follow(UUID character,double distance){public Follow{Objects.requireNonNull(character);if(!Double.isFinite(distance)||distance<1||distance>6)throw new IllegalArgumentException("follow_distance_limit");}}
    public record Damage(float applied,float remaining,boolean killed){}
    public record Snapshot(long tick,List<View> entities,int blockOverrides,long simulationFailures,int projectiles,int pendingExplosions,int mineCooldowns) {
        public Snapshot(long tick,List<View> entities,int blockOverrides,long simulationFailures,int projectiles,int pendingExplosions){this(tick,entities,blockOverrides,simulationFailures,projectiles,pendingExplosions,0);}
    }
    private static final double WIDTH=ActorPose.RADIUS,HEIGHT=ActorPose.STAND.height(),DT=.05;
    static final class Entity {
        final String id,kind;final long incarnation;float health=100;double x,y,z,vx,vy,vz,airborneTime;boolean grounded,ladder,inWater;
        BlockReadView pathSource;Vec goal;List<Vec> path=List.of();int pathAt;long nextPlan;String navigation="idle";
        List<Vec> patrol=List.of();int patrolAt;long patrolDeadline,patrolResume;
        Follow follow;boolean holding;long followRefresh,progressAt,recoveryPlans;Vec progress;
        double inputX,inputZ,blockedX,blockedZ;long inputExpires;
        boolean inputJump,jumpIntent;ActorJump.State jumpState=ActorJump.State.initial();
        ActorPose pose=ActorPose.STAND;List<ActorPose> pathPoses=List.of();List<Float> pathHeadings=List.of();Float routeYaw;Boolean routePronePending;
        int locomotion,previousLocomotion;double poseSince;
        ActorContacts.State contacts=ActorContacts.State.EMPTY;
        ActorVitals vitals;
        List<Optional<ActorZipline.Route>> pathActions=List.of();int ziplinePathAt=-1;
        float anomalySlow=1;long anomalySlowUntil;ActorControllerEngine.Session controller;ActorControllerEngine.Input controllerInput;long controllerExpires;float controllerYaw;boolean aiming;ActorTraversal traversal=ActorTraversal.EMPTY;List<SceneCollision.Box> bodyParts=List.of();
        boolean transientActor;Vec directSample;long directSampleTick,directRetry;boolean followingDetour;
        ActorScene scene;CompletableFuture<ActorScene> scenePending;CompletableFuture<ActorNavigation.Path> pathPending;long sceneRetry,pathVersion,pendingVersion;
        Entity(String id,String kind,Vec p,long incarnation){this.id=id;this.kind=kind;this.incarnation=incarnation;x=p.x;y=p.y;z=p.z;}
        View view(){return new View(id,kind,x,y,z,vx,vy,vz,grounded,navigation,Math.max(0,path.size()-pathAt),goal,incarnation,health,patrolAt,patrol.size(),follow,recoveryPlans,ladder,inWater,jumpState.stamina(),jumpState.jumps(),pose.nativeId(),jumpState.mode(),locomotion,previousLocomotion,bodyParts,controllerYaw,traversal,aiming,vitals,controller==null?ActorPresentation.EMPTY:controller.presentation(),controller==null?Map.of():controller.stats(),controller==null?List.of():controller.buffs());}
    }
    private volatile Terrain terrain;
    /** Startup handoff only after legacy physical layers were imported and retired. */
    void residentTerrain(ResidentMapRuntime runtime){if(terrain.residentReads()==runtime.reads())return;if(overlays.checkpointChunks().iterator().hasNext())throw new IllegalStateException("resident_legacy_overlays_not_retired");terrain=new ResidentTerrainView<>(runtime.reads(),terrain);enableResidentMetadataSnapshots();}
    private volatile ActorMotion actorMotion; public void actorMotion(ActorMotion value){actorMotion=Objects.requireNonNull(value);if(actorMaps instanceof MapObstructionAccess maps)maps.actorMotion(value);}
    public ActorMotion actorMotion(){return actorMotion;}
    public CompletableFuture<Void> prepareActorNavigation(){return actorMaps==null?CompletableFuture.completedFuture(null):actorMaps.prepareNavigation(actorControllers!=null);}
    private ActorControllerEngine actorControllers;
    private final ArrayDeque<ActorMineTrigger> mineEffects=new ArrayDeque<>();
    private Map<UUID,ActorMinePlayer> minePlayers=Map.of();
    public void minePlayers(Map<UUID,ActorMinePlayer> players){if(players.size()>512)throw new IllegalArgumentException("mine_player_capacity");minePlayers=Map.copyOf(players);}
    private static final class MineScene {ActorScene scene;CompletableFuture<ActorScene> pending;long retry;}
    private final Map<UUID,MineScene> mineScenes=new HashMap<>();
    private static void closeMineScene(MineScene value){if(value.scene!=null)value.scene.close();if(value.pending!=null)value.pending.thenAccept(ActorScene::close);}
    private void releaseMineScenes(){mineScenes.values().forEach(World::closeMineScene);mineScenes.clear();}
    public List<ActorMineTrigger> mineExplosions(){return List.copyOf(mineEffects);}
    public void acknowledgeMine(UUID id){mineEffects.removeIf(e->e.id().equals(id));}
    /** Explicit pre-bootstrap opt-in. Production remains on the existing engine until parity gates pass. */
    public void actorControllers(ActorControllerEngine value){if(tick!=0||!entities.isEmpty()||actorControllers!=null)throw new IllegalStateException("actor_controller_late_configuration");actorControllers=Objects.requireNonNull(value);}
    public void finishActorBootstrap(){if(actorControllers!=null)actorControllers.finishBootstrap();}
    void endActorBootstrapAction(){if(actorControllers!=null)actorControllers.endBootstrapAction();}
    public void controllerInput(String id,ActorControllerEngine.Input input){if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");Entity e=required(id);e.controllerInput=Objects.requireNonNull(input);e.controllerExpires=tick+5;}
    /** Short-lived native control intent. Durable special-state requests cannot be overwritten. */
    public void control(String id,ActorControllerEngine.Input input){
        if(actorControllers==null)throw new IllegalArgumentException("actor_control_native_required");Entity e=required(id);
        if(!e.kind.equals("BOT")||e.health<=0||pendingControl(e))throw new IllegalArgumentException("actor_control_unavailable");
        if(input.condition()!=null||input.seat()!=null||input.zipline()!=null)throw new IllegalArgumentException("actor_control_requires_durable_request");
        e.inputExpires=0;e.goal=null;e.follow=null;e.patrol=List.of();e.path=List.of();e.pathVersion++;cancelPath(e);
        controllerInput(id,input);
    }
    /** Durable pending request. It survives unavailable geometry until one native step consumes it. */
    private static boolean pendingControl(Entity e){return e.controllerInput!=null&&(e.controllerInput.condition()!=null||e.controllerInput.seat()!=null);}
    public void condition(String id,ActorControllerEngine.Condition value){if(actorControllers==null)throw new IllegalArgumentException("actor_condition_native_required");Entity e=required(id);if(e.health<=0)throw new IllegalArgumentException("actor_condition_dead");if(pendingControl(e))throw new IllegalArgumentException("actor_condition_pending");e.controllerInput=new ActorControllerEngine.Input(e.controllerYaw,0,0,ActorLocomotion.Gait.NONE,false,false,false,null,Objects.requireNonNull(value));e.controllerExpires=tick+5;}
    public ActorRuntime.Snapshot conditionRuntime(String id,ActorControllerEngine.Condition value){Entity e=required(id);var old=e.controllerInput;long expires=e.controllerExpires;condition(id,value);try{return actorRuntime();}catch(RuntimeException failure){e.controllerInput=old;e.controllerExpires=expires;throw failure;}}
    public void seat(String id,ActorControllerEngine.Seat value){if(actorControllers==null)throw new IllegalArgumentException("actor_seat_native_required");Entity e=required(id);if(e.health<=0||pendingControl(e))throw new IllegalArgumentException("actor_seat_unavailable");e.controllerInput=new ActorControllerEngine.Input(e.controllerYaw,0,0,ActorLocomotion.Gait.NONE,false,false,false,null,null,Objects.requireNonNull(value));e.controllerExpires=tick+5;}
    public ActorRuntime.Snapshot seatRuntime(String id,ActorControllerEngine.Seat value){Entity e=required(id);var old=e.controllerInput;long expires=e.controllerExpires;seat(id,value);try{return actorRuntime();}catch(RuntimeException failure){e.controllerInput=old;e.controllerExpires=expires;throw failure;}}
    /** Preparation is called by a worker. Applying its result belongs to the world owner. */
    public ActorControllerEngine.PreparedMedicine prepareMedicine(int item){if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");return actorControllers.prepareMedicine(item);}
    public ActorControllerEngine.PreparedEquipment prepareEquipment(ActorEquipment settings){if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");return actorControllers.prepareEquipment(settings);}
    public void equipment(String id,long incarnation,ActorControllerEngine.PreparedEquipment prepared){Entity e=required(id);if(e.incarnation!=incarnation)throw new IllegalArgumentException("actor_equipment_incarnation");if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");controller(e);e.controller.equipment(prepared);e.vitals=e.controller.vitals();if(e.vitals!=null)e.health=e.vitals.health();if(e.goal!=null)aim(e,e.goal);}
    public void aiming(String id,boolean value){if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");Entity e=required(id);controller(e);e.controller.aiming(value);e.aiming=value;}
    public void medicine(String id,long incarnation,ActorControllerEngine.PreparedMedicine value){
        Entity e=required(id);if(e.incarnation!=incarnation)throw new IllegalArgumentException("actor_medicine_incarnation");if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");controller(e);e.controller.medicine(value,tick);e.vitals=e.controller.vitals();e.health=e.vitals.health();if(e.goal!=null)aim(e,e.goal);
    }
    public void removeBuff(String id,long incarnation,int effect){
        Entity e=required(id);if(e.incarnation!=incarnation)throw new IllegalArgumentException("actor_medicine_incarnation");if(actorControllers==null)throw new IllegalStateException("actor_controller_unavailable");controller(e);e.controller.removeBuff(effect,tick);e.vitals=e.controller.vitals();e.health=e.vitals.health();if(e.goal!=null)aim(e,e.goal);
    }
    private ActorPose activePose=ActorPose.STAND;
    private ActorMaps actorMaps;private ActorScene activeScene;private ActorScene.Budget actorBudget;private int sceneRequestBudget;
    private ActorBody.Frame dynamicBodies=ActorBody.Frame.EMPTY,stepBodies=ActorBody.Frame.EMPTY;
    /** Simulation-owner publication from entity components. Humanoid occupancy is not a solid collider. */
    public void dynamicBodies(List<ActorBody> bodies){dynamicBodies=new ActorBody.Frame(bodies);}
    private long actorSceneRequests,actorSceneWaits,actorPathRequests,actorDeferred;
    /** Install on the sole world owner (or before start); shared catalog remains worker-owned. */
    public void actorMaps(ActorMaps maps){releaseMineScenes();actorMaps=Objects.requireNonNull(maps);if(maps instanceof MapObstructionAccess access)access.actorMotion(actorMotion);if(maps instanceof ProjectileMaps projectiles){grenadeCollision=new GrenadeCollision(projectiles,this::grenadeBlocksClear,this::grenadeTerrainReady);grenades.geometry(grenadeCollision);}for(Entity e:entities.values()){releaseScene(e);if(!e.path.isEmpty()||e.pathPending!=null)e.nextPlan=tick;cancelPath(e);e.path=List.of();}}
    private GrenadeCollision grenadeCollision;
    public Map<String,Object> grenadeGeometryMetrics(){return grenadeCollision==null?Map.of("enabled",false):grenadeCollision.metrics();}
    private static void releaseScene(Entity e){cancelPath(e);if(e.scene!=null)e.scene.close();e.scene=null;if(e.scenePending!=null)e.scenePending.thenAccept(ActorScene::close);e.scenePending=null;}
    public void releaseActorScenes(){for(Entity e:entities.values())releaseScene(e);releaseMineScenes();}
    ActorContacts.State actorContacts(String id){return required(id).contacts;}
    public CompletableFuture<ActorScene> actorSceneRequest(Vec p){valid(p);return actorMaps==null?CompletableFuture.completedFuture(null):actorMaps.actorScene(p);}
    public Map<String,Object> actorMetrics(){return Map.ofEntries(Map.entry("nativeSceneGeometry",actorMaps!=null),Map.entry("nativeActorSolver",actorMotion!=null),Map.entry("nativeVelocityController",actorMotion instanceof ActorDynamics),Map.entry("nativeJumpController",actorMotion instanceof ActorJump),Map.entry("completeNativeDispatcher",actorControllers!=null),Map.entry("sceneRequests",actorSceneRequests),Map.entry("waitingSteps",actorSceneWaits),Map.entry("pathRequests",actorPathRequests),Map.entry("budgetDeferrals",actorDeferred),Map.entry("dynamicBodies",dynamicBodies.size()),Map.entry("maxLocalCells",ActorScene.MAX_CELLS),Map.entry("collisionBudgetPerTick",16384),Map.entry("sceneRequestsPerTick",4));}
    // Published independently of the periodic simulation snapshot; only this owner increments it.
    private volatile long portalEpoch;
    public long portalEpoch(){return portalEpoch;}
    private final Grenades grenades;
    private final Map<String,Entity> entities=new LinkedHashMap<>();
    private Map<UUID,Vec> players=Map.of();
    /** Owner receives one immutable, coalesced publication; no journal/queued jobs per bot. */
    public void players(Map<UUID,Vec> values){players=Map.copyOf(values);}
    public Vec playerPosition(UUID character){return players.get(character);}
    private final BlockOverlays overlays;
    public MapLimits mapLimits(){return overlays.limits();}
    private Map<Long,Map<UUID,NativePortal>> portalOverrides=new HashMap<>();
    private Map<UUID,NativePortal> physicalPortals=new HashMap<>();
    private Set<UUID> portalEdited=new HashSet<>();
    private Map<UUID,NativePortal> metadataPortals=new HashMap<>();
    private Set<UUID> metadataPortalIds=new HashSet<>(),portalRemoved=new HashSet<>(),metadataSceneLive=new HashSet<>();
    private Map<Long,Map<UUID,NativePortal>> metadataPortalChunks=new HashMap<>();
    record PortalRank(long value,boolean created){}
    private Map<UUID,PortalRank> portalRanks=new HashMap<>();private long nextPortalRank=SceneCatalog.MAX_IDENTITIES;
    public BlockOverlays overlayVersions(){return overlays;}
    /** Only bounded actor coordinates and the root reference cross the owner barrier. */
    public BlockReadView blockReadView(){List<BlockReadView.ActorBox> actors=new ArrayList<>(entities.size());for(Entity e:entities.values())for(var b:e.view().collisionParts())actors.add(new BlockReadView.ActorBox(b.x(),b.y(),b.z(),b.xx(),b.yy(),b.zz()));return new BlockReadView(terrain,overlays.capture(),List.copyOf(actors),terrain.residentReads()==null?null:residentSceneChanges(),portalEpoch,this::portalEpoch);}
    public BlockOverlays.Snapshot overlay(int x,int z){return overlays.snapshot(x,z);}
    public record SceneChunk(Map<UUID,ScenePayload.Entry> base,BlockOverlays.Snapshot overlay,boolean rawContainer,Terrain.SceneSource source){}
    public SceneChunk sceneChunk(int x,int z){return new SceneChunk(terrain.sceneIndex(x,z),overlays.snapshot(x,z),terrain.rawSceneContainer(x,z),terrain.sceneSource(x,z));}
    public String sceneRegistryDigest(){return terrain.sceneRegistryDigest();}
    public Terrain checkpointTerrain(){return terrain;}
    /** Fresh physical owner after checked offline import; durable actors are restored by the selected bundle. */
    public World residentBootstrapBase(){World fresh=new World(terrain,mapLimits());fresh.actorControllers=actorControllers;fresh.actorMotion=actorMotion;return fresh;}
    public String identity(){return terrain.identity();}
    record MapCheckpointView(BlockOverlays overlays,Map<UUID,SceneLocation> anchors,
        Map<UUID,NativePortal> physical,Set<UUID> edited,Map<UUID,NativePortal> metadata,Set<UUID> metadataIds,
        Set<UUID> removed,Set<UUID> metadataLive,Map<UUID,PortalRank> ranks,long nextRank,long epoch){}
    private boolean residentMetadataSnapshots;
    /** Read-only spatial-index inputs derived from committed native scene edits, including tombstones. */
    public record ResidentSceneChange(ScenePayload.Entry entry){}
    private Map<ScenePlacement,ResidentSceneChange> residentSceneChanges=new HashMap<>();
    Map<ScenePlacement,ResidentSceneChange> residentSceneChanges(){return ResidentMetadataMaps.snapshot(residentSceneChanges);}
    void residentSceneChangesForRestore(Map<ScenePlacement,ResidentSceneChange> values){residentSceneChanges=ResidentMetadataMaps.mutable(values,(p,v)->25+ResidentWorldMetadata.entryBytes(v.entry()));}
    public void prepareResidentSceneBaseline(){if(!residentMetadataSnapshots)enableResidentMetadataSnapshots();for(var row:overlays.capture().sceneChanges())residentSceneChanges.put(new ScenePlacement(row.x(),row.z(),row.id()),new ResidentSceneChange(row.value()));}
    /** Bootstrap or owner-only representation switch. Capture thereafter shares immutable metadata shards. */
    public void enableResidentMetadataSnapshots(){
        if(residentMetadataSnapshots)return;
        residentSceneChangesForRestore(residentSceneChanges);
        editedScenes=ResidentMetadataMaps.mutable(editedScenes,(id,location)->25+ResidentWorldMetadata.entryBytes(location.entry()));
        editedCullingReferences=ResidentMetadataMaps.mutable(editedCullingReferences,(placement,entry)->25+ResidentWorldMetadata.entryBytes(entry));
        physicalPortals=ResidentMetadataMaps.mutable(physicalPortals,(id,portal)->ResidentWorldMetadata.portalBytes(portal));metadataPortals=ResidentMetadataMaps.mutable(metadataPortals,(id,portal)->ResidentWorldMetadata.portalBytes(portal));
        portalRanks=ResidentMetadataMaps.mutable(portalRanks,(id,rank)->25);portalEdited=ResidentMetadataMaps.mutable(portalEdited);metadataPortalIds=ResidentMetadataMaps.mutable(metadataPortalIds);portalRemoved=ResidentMetadataMaps.mutable(portalRemoved);metadataSceneLive=ResidentMetadataMaps.mutable(metadataSceneLive);residentMetadataSnapshots=true;
    }
    boolean residentMetadataSnapshots(){return residentMetadataSnapshots;}
    Map<ScenePlacement,ScenePayload.Entry> metadataReferences(){return ResidentMetadataMaps.snapshot(editedCullingReferences);}
    /** Capture on WorldLoop only after entering the command-worker barrier. Iteration/IO is off-owner.
     * Map state has no independent mutation lane; runtime actors/grenades are deliberately excluded. */
    MapCheckpointView mapCheckpointView(){return new MapCheckpointView(overlays,ResidentMetadataMaps.snapshot(editedScenes),ResidentMetadataMaps.snapshot(physicalPortals),ResidentMetadataMaps.snapshot(portalEdited),ResidentMetadataMaps.snapshot(metadataPortals),ResidentMetadataMaps.snapshot(metadataPortalIds),ResidentMetadataMaps.snapshot(portalRemoved),ResidentMetadataMaps.snapshot(metadataSceneLive),ResidentMetadataMaps.snapshot(portalRanks),nextPortalRank,portalEpoch);}
    World restoredMapCheckpoint(MapCheckpointView saved){
        World fresh=new World(terrain,mapLimits());
        fresh.residentSceneChangesForRestore(residentSceneChanges());
        fresh.actorControllers=actorControllers;fresh.actorMotion=actorMotion;
        for(var chunk:saved.overlays().checkpointChunks()){
            fresh.overlays.restoreChunk(chunk.key(),chunk.revision());
            for(var cell:chunk.cells().entrySet()){fresh.overlays.restoreVoxel(chunk.key(),cell.getKey(),cell.getValue());}
            for(var tile:chunk.tiles().entrySet())fresh.overlays.restoreTile(chunk.key(),tile.getKey(),tile.getValue(),chunk.tileIds().get(tile.getKey()));
            for(var scene:chunk.scenes().entrySet()){fresh.overlays.restoreScene(chunk.key(),scene.getKey(),scene.getValue());if(scene.getValue()!=null&&scene.getValue().hasCullingReference())fresh.editedCullingReferences.put(new ScenePlacement((int)(chunk.key()>>32),(int)chunk.key(),scene.getKey()),scene.getValue());}
        }
        fresh.editedScenes.putAll(saved.anchors());for(var portal:saved.physical().entrySet())fresh.indexPhysicalPortal(portal.getKey(),portal.getValue());
        fresh.portalEdited.addAll(saved.edited());fresh.metadataPortals.putAll(saved.metadata());fresh.metadataPortalIds.addAll(saved.metadataIds());fresh.portalRemoved.addAll(saved.removed());fresh.metadataSceneLive.addAll(saved.metadataLive());
        for(var portal:saved.metadata().values())fresh.metadataPortalChunks.computeIfAbsent(portalChunk(portal),key->new HashMap<>()).put(portal.id(),portal);
        fresh.portalRanks.putAll(saved.ranks());fresh.nextPortalRank=saved.nextRank();fresh.portalEpoch=saved.epoch();fresh.overlays.finishRestore();if(residentMetadataSnapshots)fresh.enableResidentMetadataSnapshots();return fresh;
    }
    void restoreResidentMetadata(MapCheckpointView saved,Map<ScenePlacement,ScenePayload.Entry> references){World next=restoredMapCheckpoint(saved);next.editedCullingReferences=ResidentMetadataMaps.mutable(references,(placement,entry)->25+ResidentWorldMetadata.entryBytes(entry));next.enableResidentMetadataSnapshots();enableResidentMetadataSnapshots();publishSceneMetadata(new SceneMetadataPrepared(sceneMetadataView(),next));}
    void editedReferencesForRestore(Map<ScenePlacement,ScenePayload.Entry> references){editedCullingReferences=ResidentMetadataMaps.mutable(references,(placement,entry)->25+ResidentWorldMetadata.entryBytes(entry));}
    public String sceneBehaviour(String asset){return terrain.sceneBehaviour(asset);}
    public SceneRegistry.Definition sceneDefinition(String asset){return terrain.sceneDefinition(asset);}
    public boolean sceneShared(UUID id){return terrain.sceneShared(id);}
    public Map<UUID,Set<Long>> scenePlacementHomes(Set<UUID> ids){
        if(ids.size()>128)throw new IllegalArgumentException("scene_placement_identity_limit");
        Map<UUID,Set<Long>> result=new HashMap<>();int count=0;
        for(UUID id:ids){
            Set<Long> source=terrain.nativeSceneHomes(id);
            // The loader's immutable set may contain a large authored group. Bound it
            // before copying or iterating on the simulation owner.
            if(source.size()>256-count)throw new IllegalArgumentException("scene_placement_limit_256");
            SceneLocation edited=editedScenes.get(id);Long extra=edited==null?null:BlockOverlays.key(edited.x(),edited.z());
            int size=source.size()+(extra!=null&&!source.contains(extra)?1:0);
            if(size>256-count)throw new IllegalArgumentException("scene_placement_limit_256");
            Set<Long> homes=new HashSet<>(source);if(extra!=null)homes.add(extra);
            count+=size;result.put(id,Set.copyOf(homes));
        }
        return Map.copyOf(result);
    }
    public record SceneLocation(int x,int z,ScenePayload.Entry entry){}
    public record SceneLookup(SceneLocation location,boolean pending){}
    private Map<UUID,SceneLocation> editedScenes=new java.util.concurrent.ConcurrentHashMap<>();
    public record ScenePlacement(int x,int z,UUID id){}
    private Map<ScenePlacement,ScenePayload.Entry> editedCullingReferences=new java.util.concurrent.ConcurrentHashMap<>();
    /** Capture is shallow. Only the serialized command worker iterates these roots. */
    public record SceneMetadataView(World owner,long epoch,BlockOverlays.Capture blocks,MapCheckpointView root,Map<ScenePlacement,ScenePayload.Entry> references,Map<ScenePlacement,ResidentSceneChange> residentScenes){
        public void checkCurrent(){if(owner.portalEpoch!=epoch)throw new IllegalArgumentException("scene_metadata_publication_stale");}
        public String behaviour(String asset){checkCurrent();return owner.terrain.sceneBehaviour(asset);}
        public Set<Long> homes(UUID id){checkCurrent();Set<Long> source=owner.terrain.nativeSceneHomes(id);SceneLocation edited=root.anchors().get(id);if(edited==null)return source;Set<Long> result=new HashSet<>(source);result.add(BlockOverlays.key(edited.x(),edited.z()));checkCurrent();return result;}
    }
    public SceneMetadataView sceneMetadataView(){return new SceneMetadataView(this,portalEpoch,overlays.capture(),mapCheckpointView(),metadataReferences(),residentSceneChanges());}
    /** Drop acknowledged physical frame copies; keep logical SQL markers and portal insertion order. */
    public static SceneMetadataPrepared prepareNativeSceneRetirement(SceneMetadataView capture,BlockOverlays.Prepared retired,java.util.function.Function<UUID,NativePortal> nativePortals){
        SceneCollision.worker();capture.checkCurrent();capture.blocks().checkCurrent();World live=capture.owner(),next=new World(live.terrain,live.mapLimits());
        next.residentSceneChangesForRestore(capture.residentScenes());
        var root=capture.root();next.editedScenes=new java.util.concurrent.ConcurrentHashMap<>(root.anchors());next.editedCullingReferences=new java.util.concurrent.ConcurrentHashMap<>(capture.references());
        next.physicalPortals=new HashMap<>(root.physical());next.portalOverrides=portalChunks(root.physical());next.portalEdited=new HashSet<>(root.edited());
        next.metadataPortals=new HashMap<>(root.metadata());next.metadataPortalIds=new HashSet<>(root.metadataIds());next.portalRemoved=new HashSet<>(root.removed());next.metadataSceneLive=new HashSet<>(root.metadataLive());next.metadataPortalChunks=portalChunks(root.metadata());next.portalRanks=new HashMap<>(root.ranks());next.nextPortalRank=root.nextRank();next.portalEpoch=Math.addExact(capture.epoch(),1);
        for(var placement:retired.retiredScenes())next.editedCullingReferences.remove(placement);
        for(UUID id:retired.retiredIdentities()){var anchor=next.editedScenes.get(id);if(anchor!=null&&anchor.entry()!=null)next.editedScenes.remove(id);
            if(!next.metadataPortalIds.contains(id)&&Objects.equals(next.physicalPortals.get(id),nativePortals.apply(id))){next.indexPhysicalPortal(id,null);next.portalEdited.remove(id);}
        }
        capture.checkCurrent();capture.blocks().checkCurrent();if(live.residentMetadataSnapshots)next.enableResidentMetadataSnapshots();return new SceneMetadataPrepared(capture,next);
    }
    public record SceneMetadataPrepared(SceneMetadataView capture,World detached,ResidentMetadataChanges history){
        public SceneMetadataPrepared(SceneMetadataView capture,World detached){this(capture,detached,null);}
        public void validate(World live){if(capture.owner()!=live)throw new IllegalArgumentException("scene_metadata_publication_owner");capture.checkCurrent();}
    }
    private static Map<Long,Map<UUID,NativePortal>> copyPortalChunks(Map<Long,Map<UUID,NativePortal>> source){Map<Long,Map<UUID,NativePortal>> copy=new HashMap<>();source.forEach((key,rows)->copy.put(key,new HashMap<>(rows)));return copy;}
    private static Map<Long,Map<UUID,NativePortal>> portalChunks(Map<UUID,NativePortal> source){Map<Long,Map<UUID,NativePortal>> result=new HashMap<>();for(var portal:source.values())result.computeIfAbsent(portalChunk(portal),ignored->new HashMap<>()).put(portal.id(),portal);return result;}
    /** A complete metadata root is assembled on the worker, before durable journal admission. */
    public static SceneMetadataPrepared prepareSceneMetadata(SceneMetadataView capture,List<BlockOverlays.SceneChange> changes,Map<UUID,SceneLocation> locations,Map<UUID,Boolean> existed,boolean finish,List<MetadataPortalChange> portals){
        SceneCollision.worker();capture.checkCurrent();World live=capture.owner(),next=new World(live.terrain,live.mapLimits());
        next.residentSceneChangesForRestore(capture.residentScenes());if(live.residentMetadataSnapshots)for(var row:changes)next.residentSceneChanges.put(new ScenePlacement(row.x(),row.z(),row.id()),new ResidentSceneChange(row.value()));
        var root=capture.root();next.portalEpoch=capture.epoch();next.nextPortalRank=root.nextRank();next.editedScenes.putAll(root.anchors());next.editedCullingReferences.putAll(capture.references());
        next.portalOverrides=portalChunks(root.physical());next.physicalPortals=new HashMap<>(root.physical());next.portalEdited=new HashSet<>(root.edited());next.metadataPortals=new HashMap<>(root.metadata());
        next.metadataPortalIds=new HashSet<>(root.metadataIds());next.portalRemoved=new HashSet<>(root.removed());next.metadataSceneLive=new HashSet<>(root.metadataLive());next.metadataPortalChunks=portalChunks(root.metadata());next.portalRanks=new HashMap<>(root.ranks());
        for(var change:changes)next.sceneMetadata(change.x(),change.z(),change.id(),change.value());next.scenePlacements(locations);if(finish)next.finishSceneMutation(existed);if(!portals.isEmpty())next.applyMetadataPortals(portals);
        capture.checkCurrent();ResidentMetadataChanges history=null;if(live.residentMetadataSnapshots){next.enableResidentMetadataSnapshots();Set<UUID> ids=new HashSet<>(locations.keySet());ids.addAll(existed.keySet());changes.forEach(row->ids.add(row.id()));portals.forEach(row->ids.add(row.metadata().id()));if(!ids.isEmpty())history=ResidentMetadataChanges.prepare(capture,next,ids);}return new SceneMetadataPrepared(capture,next,history);
    }
    /** Constant work on the owner: root references and the final epoch publish together with overlays. */
    public void publishSceneMetadata(SceneMetadataPrepared prepared){
        prepared.validate(this);World next=prepared.detached();residentSceneChanges=next.residentSceneChanges;editedScenes=next.editedScenes;editedCullingReferences=next.editedCullingReferences;portalOverrides=next.portalOverrides;physicalPortals=next.physicalPortals;portalEdited=next.portalEdited;
        metadataPortals=next.metadataPortals;metadataPortalIds=next.metadataPortalIds;portalRemoved=next.portalRemoved;metadataSceneLive=next.metadataSceneLive;metadataPortalChunks=next.metadataPortalChunks;portalRanks=next.portalRanks;nextPortalRank=next.nextPortalRank;residentMetadataSnapshots=next.residentMetadataSnapshots;portalEpoch=next.portalEpoch;
    }
    public static List<MetadataPortalChange> compileMetadataPortals(SceneMetadataView capture,List<PortalMetadata> rows){SceneCollision.worker();capture.checkCurrent();var result=capture.owner().compileMetadataPortals(rows);capture.checkCurrent();return result;}
    public record SceneCullingIndex(Map<UUID,SceneCullingVolume> base,Map<UUID,Set<Long>> references,Set<Long> unreviewedReferences,Map<UUID,SceneLocation> edits,Map<ScenePlacement,ScenePayload.Entry> physicalReferences,long epoch){}
    /** Read-only concurrent view for the serialized command worker. CAS/epoch validates its plan.
     * Do not iterate the global catalog or copy all edited records on the world owner. */
    public SceneCullingIndex sceneCullingIndex(){return new SceneCullingIndex(terrain.sceneCullingVolumes(),terrain.sceneCullingReferences(),terrain.sceneCullingUnreviewedReferences(),ResidentMetadataMaps.snapshot(editedScenes),ResidentMetadataMaps.snapshot(editedCullingReferences),portalEpoch);}
    public record SceneEditLookup(Map<UUID,SceneLocation> objects,Map<String,ScenePayload.Entry> templates,boolean pending,Set<Long> examined){}
    /** Indexed edited UUIDs also resolve after earlier queued edits move an object beyond the captured view. */
    public SceneEditLookup findSceneEdits(Set<UUID> ids,Set<String> assets,List<Long> chunks){
        if(ids.size()>128||assets.size()>128||chunks.size()>289)throw new IllegalArgumentException("scene_editor_lookup_limit");Map<UUID,SceneLocation> objects=new HashMap<>();Map<String,ScenePayload.Entry> templates=new HashMap<>();Set<Long> examined=new HashSet<>();for(UUID id:ids){SceneLocation edited=editedScenes.get(id);if(edited!=null)objects.put(id,edited);}boolean pending=false;
        if(terrain.visibleScenesFirst())for(long key:chunks){int x=(int)(key>>32),z=(int)key;Map<UUID,ScenePayload.Entry> cached=terrain.cachedSceneIndex(x,z);for(UUID id:ids)if(!objects.containsKey(id)){ScenePayload.Entry entry=overlays.hasScene(x,z,id)?overlays.scene(x,z,id):cached.get(id);if(entry!=null)objects.put(id,new SceneLocation(x,z,entry));}}
        Set<Long> search=new LinkedHashSet<>();boolean catalogReady=terrain.globalScenes();
        for(UUID id:ids)if(!objects.containsKey(id))try{Terrain.SceneHome remembered=terrain.visibleScenesFirst()?terrain.rememberedSceneHome(id):null;Terrain.SceneHome home=remembered!=null?remembered:terrain.sceneHome(id);if(home!=null)search.add(BlockOverlays.key(home.x(),home.z()));}catch(IllegalArgumentException error){if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;pending=true;catalogReady=false;}
        for(String asset:assets)try{ScenePayload.Entry template=terrain.globalSceneTemplate(asset);if(template!=null)templates.put(asset,template);}catch(IllegalArgumentException error){if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;pending=true;catalogReady=false;}
        // A completed SZR catalog knows absence as well as presence. Avoid warming 289 unrelated chunks.
        if(!terrain.globalScenes())search.addAll(chunks);
        if(objects.keySet().containsAll(ids)&&templates.keySet().containsAll(assets))return new SceneEditLookup(Map.copyOf(objects),Map.copyOf(templates),false,Set.of());
        for(long key:search){int x=(int)(key>>32),z=(int)key;try{Map<UUID,ScenePayload.Entry> base=terrain.sceneIndex(x,z);for(UUID id:ids)if(!objects.containsKey(id)){ScenePayload.Entry value=overlays.hasScene(x,z,id)?overlays.scene(x,z,id):base.get(id);if(value!=null)objects.put(id,new SceneLocation(x,z,value));}if(!terrain.globalScenes())for(String asset:assets)if(!templates.containsKey(asset)){ScenePayload.Entry value=terrain.sceneTemplate(x,z,asset);if(value!=null)templates.put(asset,value);}examined.add(key);}catch(IllegalArgumentException error){if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;pending=true;}}
        return new SceneEditLookup(Map.copyOf(objects),Map.copyOf(templates),pending,Set.copyOf(examined));
    }
    public SceneLookup findScene(UUID id,List<Long> chunks){
        if(chunks.size()>289)throw new IllegalArgumentException("scene_configuration_visible_chunks");SceneLocation edited=editedScenes.get(id);if(edited!=null)return new SceneLookup(edited.entry()==null?null:edited,false);
        SceneEditLookup found=findSceneEdits(Set.of(id),Set.of(),chunks);SceneLocation location=found.objects().get(id);return new SceneLookup(location,location==null&&found.pending());
    }
    public void scene(int x,int z,UUID id,ScenePayload.Entry value){if(value!=null&&!id.equals(value.object().identity()))throw new IllegalArgumentException("scene_edit_identity");overlays.putScene(x,z,id,value);sceneMetadata(x,z,id,value);}
    /** Owner-only metadata update after the combined voxel/tile/scene root was published. */
    public void sceneMetadata(int x,int z,UUID id,ScenePayload.Entry value){portalEpoch++;forgetMetadataPortal(id);ScenePlacement placement=new ScenePlacement(x,z,id);if(value!=null&&value.hasCullingReference())editedCullingReferences.put(placement,value);else editedCullingReferences.remove(placement);SceneLocation previous=editedScenes.get(id);boolean anchored=value!=null&&Math.floor(value.object().x()/16)==x&&Math.floor(value.object().z()/16)==z;boolean previousAnchored=previous!=null&&previous.entry()!=null&&Math.floor(previous.entry().object().x()/16)==previous.x()&&Math.floor(previous.entry().object().z()/16)==previous.z();if(value!=null&&(anchored||!previousAnchored)||value==null&&(previous==null||previous.x()==x&&previous.z()==z))editedScenes.put(id,new SceneLocation(x,z,value));NativePortal portal=NativePortal.from(value,terrain::sceneBehaviour);indexPhysicalPortal(id,portal);portalEdited.add(id);if(portal!=null&&!portalRanks.containsKey(id))portalRanks.put(id,new PortalRank(nextPortalRank++,false));}
    /** Worker selects from the complete transaction's after states. An old
     * anchored placement being removed must not hide restored off-home copies. */
    public void scenePlacements(Map<UUID,SceneLocation> locations){if(locations.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_placement_limit_256");if(!locations.isEmpty())portalEpoch++;editedScenes.putAll(locations);}
    /** Final mutation state determines insertion order: move retains it; remove+recreate appends it.
     * Called once after the complete native/mixed scene batch, including bootstrap replay. */
    public void finishSceneMutation(Map<UUID,Boolean> existed){if(!existed.isEmpty())portalEpoch++;for(var change:existed.entrySet()){UUID id=change.getKey();SceneLocation location=editedScenes.get(id);NativePortal portal=location==null?null:NativePortal.from(location.entry(),terrain::sceneBehaviour);indexPhysicalPortal(id,portal);if(portal==null){portalRanks.remove(id);portalRemoved.add(id);}else if(portalRemoved.remove(id))portalRanks.put(id,new PortalRank(nextPortalRank++,true));}}
    private static long portalChunk(NativePortal portal){return BlockOverlays.key((int)Math.floor(portal.position().x()/16),(int)Math.floor(portal.position().z()/16));}
    /** Index by the physical portal coordinates, independently of the generic primary's storage home. */
    private void indexPhysicalPortal(UUID id,NativePortal portal){
        NativePortal old=physicalPortals.remove(id);if(old!=null){long key=portalChunk(old);var rows=portalOverrides.get(key);rows.remove(id);if(rows.isEmpty())portalOverrides.remove(key);}
        if(portal!=null){physicalPortals.put(id,portal);portalOverrides.computeIfAbsent(portalChunk(portal),key->new HashMap<>()).put(id,portal);}
    }
    private long portalOrder(UUID id){PortalRank rank=portalRanks.get(id);if(rank!=null&&rank.created())return rank.value();long base=terrain.portalOrder(id);return base!=Long.MAX_VALUE?base:rank==null?Long.MAX_VALUE:rank.value();}
    private static final NativePortal.Parameters EMPTY_PORTAL_PARAMETERS=new NativePortal.Parameters(false,1,false,false,1.5,List.of());
    public record MetadataPortalChange(PortalMetadata metadata,NativePortal portal){}
    /** Immutable catalog lookups only. Matrix/NBT decoding happens before this owner call. */
    public List<MetadataPortalChange> compileMetadataPortals(List<PortalMetadata> rows){
        if(rows.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_portal_metadata_batch_limit");
        if(!terrain.portalIndexReady(0,0))throw new IllegalArgumentException("terrain_not_ready_retry");
        Set<UUID> added=new HashSet<>();for(var row:rows)if(!metadataPortalIds.contains(row.id()))added.add(row.id());if((long)metadataPortalIds.size()+added.size()>200000)throw new IllegalArgumentException("scene_portal_metadata_identity_limit");
        List<MetadataPortalChange> result=new ArrayList<>();
        for(var row:rows){NativePortal portal=null;if(!row.removed()){
            NativePortal base=terrain.basePortal(row.id());String asset=row.asset().isEmpty()&&base!=null?base.asset():row.asset();NativePortal template=base!=null?base:terrain.portalTemplate(asset);String behaviour=terrain.sceneBehaviour(asset);World.Vec position=row.position()!=null?row.position():template==null?null:template.position();
            if(!terrain.sceneAssetKnown(asset)&&template!=null)behaviour=template.behaviour();
            if(position!=null&&NativePortal.behaviour(behaviour))portal=new NativePortal(row.id(),asset,behaviour,position,row.scale()>0?row.scale():template==null?1:template.scale(),template==null?EMPTY_PORTAL_PARAMETERS:template.parameters());
        }result.add(new MetadataPortalChange(row,portal));}return List.copyOf(result);
    }
    public boolean metadataSceneExists(UUID id){return metadataSceneLive.contains(id);}
    private void forgetMetadataPortal(UUID id){NativePortal old=metadataPortals.remove(id);if(old!=null){long key=BlockOverlays.key((int)Math.floor(old.position().x()/16),(int)Math.floor(old.position().z()/16));Map<UUID,NativePortal> rows=metadataPortalChunks.get(key);rows.remove(id);if(rows.isEmpty())metadataPortalChunks.remove(key);}metadataPortalIds.remove(id);metadataSceneLive.remove(id);}
    public void applyMetadataPortals(List<MetadataPortalChange> changes){
        if(!changes.isEmpty())portalEpoch++;
        for(var change:changes){UUID id=change.metadata().id();forgetMetadataPortal(id);metadataPortalIds.add(id);if(!change.metadata().removed())metadataSceneLive.add(id);portalEdited.add(id);NativePortal portal=change.portal();
            if(portal==null){portalRanks.remove(id);portalRemoved.add(id);}else{boolean recreated=portalRemoved.remove(id);if(recreated||!portalRanks.containsKey(id))portalRanks.put(id,new PortalRank(nextPortalRank++,recreated));metadataPortals.put(id,portal);long key=BlockOverlays.key((int)Math.floor(portal.position().x()/16),(int)Math.floor(portal.position().z()/16));metadataPortalChunks.computeIfAbsent(key,k->new HashMap<>()).put(id,portal);}
        }
    }
    /** Nine chunk indexes cover the old maximum 16-block trigger radius. No scene parsing or IO here. */
    public NativePortal.Activation portal(World.Vec position,boolean interaction,UUID objectId,double selector){
        szserver.persistence.CharacterLocations.validPosition(position);int x=(int)Math.floor(position.x()/16),z=(int)Math.floor(position.z()/16);NativePortal best=null;double distance=Double.POSITIVE_INFINITY;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){Map<UUID,NativePortal> base=terrain.portalIndex(x+dx,z+dz),changed=portalOverrides.getOrDefault(BlockOverlays.key(x+dx,z+dz),Map.of());
            for(NativePortal candidate:base.values())if(!portalEdited.contains(candidate.id())&&(objectId==null||objectId.equals(candidate.id()))&&candidate.contains(position,interaction)){double next=candidate.distanceSquared(position);if(next<distance||next==distance&&best!=null&&portalOrder(candidate.id())<portalOrder(best.id())){best=candidate;distance=next;}}
            for(NativePortal candidate:changed.values())if(candidate!=null&&!metadataPortalIds.contains(candidate.id())&&(objectId==null||objectId.equals(candidate.id()))&&candidate.contains(position,interaction)){double next=candidate.distanceSquared(position);if(next<distance||next==distance&&best!=null&&portalOrder(candidate.id())<portalOrder(best.id())){best=candidate;distance=next;}}
        }
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(NativePortal candidate:metadataPortalChunks.getOrDefault(BlockOverlays.key(x+dx,z+dz),Map.of()).values())if((objectId==null||objectId.equals(candidate.id()))&&candidate.contains(position,interaction)){double next=candidate.distanceSquared(position);if(next<distance||next==distance&&best!=null&&portalOrder(candidate.id())<portalOrder(best.id())){best=candidate;distance=next;}}
        if(best==null)return null;NativePortal.Destination destination=best.choose(selector);return destination==null?null:new NativePortal.Activation(best,best.target(position,destination),destination.location());
    }
    public record PortalQuery(NativePortal.Activation activation,boolean ready,long sceneEpoch){}
    /** Cold indexes are pending, not proof that no portal exists. Region IO remains async. */
    public PortalQuery portalQuery(World.Vec position,boolean interaction,UUID objectId,double selector){
        szserver.persistence.CharacterLocations.validPosition(position);int x=(int)Math.floor(position.x()/16),z=(int)Math.floor(position.z()/16);boolean ready=true;
        // Read publication state before indexes; never report empty from a pre-publication read as ready.
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)ready&=terrain.portalIndexReady(x+dx,z+dz);
        return new PortalQuery(ready?portal(position,interaction,objectId,selector):null,ready,portalEpoch);
    }
    private long tick,simulationFailures,nextIncarnation,nextTransientIncarnation;
    private int planBudget;
    public World(Terrain terrain){this(terrain,MapLimits.DEFAULT);}
    public World(Terrain terrain,MapLimits limits){BlockShapes.prepare();this.terrain=terrain;overlays=new BlockOverlays(limits);grenades=new Grenades(this::lineClear,this::grenadeTerrainReady);}
    public Grenades grenades(){return grenades;}
    public boolean contains(String id){return entities.containsKey(id);}
    public int entityCount(){return entities.size();}
    public void validateSpawn(String id,String kind,Vec p){
        if(entities.containsKey(id))throw new IllegalArgumentException("entity_exists");
        if(entities.size()>=512)throw new IllegalArgumentException("entity_limit");
        if(!Set.of("NPC","BOT").contains(kind))throw new IllegalArgumentException("server_simulates_npc_and_bot_only");
        valid(p);
        if(!ready(p))throw new IllegalArgumentException("terrain_not_ready_retry");
        if(collides(p.x,p.y,p.z))throw new IllegalArgumentException("spawn_inside_solid");
    }
    public void validateSpawn(String id,String kind,Vec p,ActorScene scene){validateSpawn(id,kind,p);if(scene!=null){scene.checkCurrent();if(!scene.covers(p))throw new IllegalArgumentException("actor_scene_out_of_view");if(scene.overlaps(ActorScene.body(p.x(),p.y(),p.z()),new ActorScene.Budget(20000)))throw new IllegalArgumentException("spawn_inside_scene");}}
    public void spawn(String id,String kind,Vec p){entities.put(id,new Entity(id,kind,p,++nextIncarnation));}
    /** Separate incarnations keep ephemeral events out of the durable actor program and checkpoints. */
    public void spawnTransient(String id,String kind,Vec p){
        validateSpawn(id,kind,p);nextTransientIncarnation=Math.incrementExact(nextTransientIncarnation);
        var entity=new Entity(id,kind,p,Long.MAX_VALUE-nextTransientIncarnation);entity.transientActor=true;entities.put(id,entity);
    }
    public record DurableEntity(String id,String kind,Vec spawn,Vec goal,long incarnation,List<Vec> patrol,Follow follow){
        public DurableEntity{patrol=List.copyOf(patrol);}
        public DurableEntity(String id,String kind,Vec spawn,Vec goal,long incarnation){this(id,kind,spawn,goal,incarnation,List.of(),null);}
        public DurableEntity(String id,String kind,Vec spawn,Vec goal,long incarnation,List<Vec> patrol){this(id,kind,spawn,goal,incarnation,patrol,null);}
    }
    public ActorRuntime.Snapshot actorRuntime(){
        var controllers=new ArrayList<ActorRuntime.Controller>();long bytes=0;
        if(actorControllers!=null)for(var e:entities.values()){if(e.transientActor)continue;controller(e);var saved=e.controller.capture();if((bytes+=saved.payload().length())>32L*1024*1024)throw new IllegalStateException("actor_controller_snapshot_limit");controllers.add(new ActorRuntime.Controller(saved,e.controllerInput,e.controllerExpires));}
        return new ActorRuntime.Snapshot(actorControllers==null?1:2,tick,nextIncarnation,entities.values().stream().filter(e->!e.transientActor).map(ActorRuntime::capture).toList(),controllers,actorControllers==null?List.of():actorControllers.mineStates());
    }
    /** Bootstrap only: validate the complete identity set before mutating any entity. */
    public void restoreActorRuntime(ActorRuntime.Snapshot snapshot){
        if(snapshot.counter()!=nextIncarnation||snapshot.actors().size()!=entities.size())throw new IllegalArgumentException("actor_runtime_program_mismatch");
        for(var a:snapshot.actors()){var e=entities.get(a.id());if(e==null||e.incarnation!=a.incarnation()||!e.kind.equals(a.kind()))throw new IllegalArgumentException("actor_runtime_program_mismatch");}
        if(snapshot.version()==2&&actorControllers==null)throw new IllegalArgumentException("actor_controller_required_for_restore");
        Map<String,ActorControllerEngine.Session> restored=new HashMap<>();Map<String,ActorRuntime.Controller> controls=new HashMap<>();
        if(snapshot.version()==1&&actorControllers!=null)for(var a:snapshot.actors()){var session=actorControllers.migrate(a);session.bindWorldClock(snapshot.tick());validateController(a,session.frame());restored.put(a.id(),session);}
        for(var c:snapshot.controllers()){var session=actorControllers.restore(c.state());if(session.effectTick()>snapshot.tick())throw new IllegalArgumentException("actor_runtime_effect_clock");session.bindWorldClock(snapshot.tick());var a=snapshot.actors().stream().filter(v->v.id().equals(c.state().id())).findFirst().orElseThrow();var f=session.frame();
            validateController(a,f);if(session.vitals()!=null){if(session.storedVitals()&&session.vitals().health()!=a.health())throw new IllegalArgumentException("actor_runtime_health_mismatch");session.health(a.health());}session.restoreContacts(contactValues(a));restored.put(a.id(),session);controls.put(a.id(),c);
        }
        Map<UUID,Set<Integer>> occupiedSeats=new HashMap<>();for(var session:restored.values()){var seat=session.frame().traversal().seat();if(seat!=null&&!occupiedSeats.computeIfAbsent(seat.object(),ignored->new HashSet<>()).add(seat.index()))throw new IllegalArgumentException("actor_runtime_duplicate_seat");}
        if(actorControllers==null&&!snapshot.mines().isEmpty())throw new IllegalArgumentException("actor_mine_controller_required");
        if(actorControllers!=null)actorControllers.restoreMines(snapshot.mines());
        for(var a:snapshot.actors()){var e=entities.get(a.id());releaseScene(e);a.apply(e,snapshot.tick());if(actorControllers!=null){e.controller=restored.get(a.id());var c=controls.get(a.id());e.controllerInput=c==null?null:c.input();e.controllerExpires=c==null?0:c.expires();controllerFrame(e,e.controller.frame());}}tick=snapshot.tick();
    }
    private static int controllerPose(ActorControllerEngine.Frame frame){var b=frame.body().get(0);return b.yy()-b.y()<=ActorPose.CROUCH.height()+1e-6?1:0;}
    private static Map<ActorContacts.Key,ActorContacts.Obstacle> contactValues(ActorRuntime saved){var result=new LinkedHashMap<ActorContacts.Key,ActorContacts.Obstacle>();for(var contact:saved.contacts())result.put(contact.key(),contact.obstacle());return result;}
    private static void validateController(ActorRuntime a,ActorControllerEngine.Frame f){
        if(!f.position().equals(a.position())||!f.velocity().equals(a.velocity())||f.pose()!=a.locomotion()||f.previous()!=a.previousLocomotion()||f.grounded()!=a.grounded()||f.water()!=a.water()||!f.jump().equals(a.jump())||f.airborneTime()!=a.airborneTime()||f.since()!=a.poseSince()||(f.pose()==5)!=a.ladder()||controllerPose(f)!=a.pose())throw new IllegalArgumentException("actor_controller_runtime_mismatch");
    }
    /** Startup owner only. Rebind geometry omitted by v1 without advancing any physics clock. */
    public int prepareActorControllers(){
        if(actorControllers==null)return 0;int pending=0;actorBudget=new ActorScene.Budget(16384);sceneRequestBudget=4;stepBodies=dynamicBodies;
        for(var e:entities.values()){
            controller(e);if(e.controller.prepared())continue;
            try{if(!ready(new Vec(e.x,e.y,e.z))||actorMaps!=null&&!actorScene(e)){pending++;continue;}controllerFrame(e,e.controller.prepare(controllerAccess(e.id,actorBudget,stepBodies)));}
            catch(ActorScene.Deferred deferred){pending++;}
        }return pending;
    }
    /** Original journal program at bootstrap, not a snapshot of moving/damaged actors. Existing
     * canonical restart respawns at the original position with full HP and the last durable goal. */
    public void restoreDurableEntities(List<DurableEntity> rows,long counter){
        if(tick!=0||nextIncarnation!=0||!entities.isEmpty()||rows.size()>512||counter<0)throw new IllegalArgumentException("checkpoint_actor_bootstrap");
        Set<String> ids=new HashSet<>();Set<Long> incarnations=new HashSet<>();for(var row:rows){if(row.id()==null||row.id().length()>36||!Set.of("NPC","BOT").contains(row.kind())||row.incarnation()<1||row.incarnation()>counter||!ids.add(row.id())||!incarnations.add(row.incarnation()))throw new IllegalArgumentException("checkpoint_actor_identity");UUID.fromString(row.id());valid(row.spawn());if(row.goal()!=null)valid(row.goal());}
        for(var row:rows){if(!row.patrol().isEmpty())validatePatrol(row.patrol());if((row.goal()!=null?1:0)+(row.patrol().isEmpty()?0:1)+(row.follow()!=null?1:0)>1)throw new IllegalArgumentException("checkpoint_actor_multiple_behaviors");}
        for(var row:rows){entities.put(row.id(),new Entity(row.id(),row.kind(),row.spawn(),row.incarnation()));if(row.follow()!=null)follow(row.id(),row.follow());else if(!row.patrol().isEmpty())patrol(row.id(),row.patrol());else if(row.goal()!=null)goal(row.id(),row.goal());}nextIncarnation=counter;
    }
    public boolean anomalySlow(String id,long incarnation,float coefficient){if(!Float.isFinite(coefficient)||coefficient<0||coefficient>1)throw new IllegalArgumentException("actor_anomaly_slow");var e=entities.get(id);if(e==null||e.incarnation!=incarnation||e.health<=0)return false;e.anomalySlow=coefficient;e.anomalySlowUntil=tick+4;return true;}
    public boolean impulse(String id,long incarnation,Vec velocity,boolean replace){
        if(!Double.isFinite(velocity.x())||!Double.isFinite(velocity.y())||!Double.isFinite(velocity.z())||Math.max(Math.abs(velocity.x()),Math.max(Math.abs(velocity.y()),Math.abs(velocity.z())))>100)throw new IllegalArgumentException("actor_impulse_range");
        var e=entities.get(id);if(e==null||e.incarnation!=incarnation||e.health<=0||actorControllers==null)return false;
        controller(e);e.controller.impulse(velocity,replace);return true;
    }
    public Damage damage(String id,long incarnation,float amount){
        if(!Float.isFinite(amount)||amount<0||amount>10000)throw new IllegalArgumentException("invalid_world_damage");
        Entity entity=entities.get(id);if(entity==null||entity.incarnation!=incarnation||entity.health<=0)return new Damage(0,entity==null?0:entity.health,false);
        if(actorControllers!=null)controller(entity);float before=entity.health,next=Math.max(0,before-amount);if(entity.controller!=null){entity.controller.health(next);entity.vitals=entity.controller.vitals();}entity.health=next;return new Damage(before-entity.health,entity.health,entity.health==0);
    }
    /** Healing follows the same equipment efficiency/maximum as players; dead or replaced NPCs stay untouched. */
    public float heal(String id,long incarnation,float raw){
        if(!Float.isFinite(raw)||raw<0||raw>10000)throw new IllegalArgumentException("invalid_world_heal");
        Entity e=entities.get(id);if(e==null||e.incarnation!=incarnation||e.health<=0)return 0;
        if(actorControllers!=null)controller(e);float before=e.health,next=szserver.net.HealthRules.instant(before,e.controller==null?Map.of():e.controller.stats(),raw);
        if(e.controller!=null){e.controller.health(next);e.vitals=e.controller.vitals();}e.health=next;return next-before;
    }
    public void remove(String id){var entity=required(id);if(entity.controller!=null)entity.controller.close();releaseScene(entity);entities.remove(id);}
    private static void cancelPath(Entity e){if(e.pathPending!=null)e.pathPending.cancel(false);e.pathPending=null;}
    private void aim(Entity e,Vec goal){e.pathVersion++;cancelPath(e);e.pathSource=null;e.goal=goal;e.path=List.of();e.pathAt=0;e.nextPlan=0;e.navigation="planning";}
    public void goal(String id,Vec goal){valid(goal);Entity e=required(id);e.follow=null;e.patrol=List.of();aim(e,goal);}
    public static void validatePatrol(List<Vec> points){
        if(points.size()<2||points.size()>16)throw new IllegalArgumentException("patrol_point_limit");
        for(int i=0;i<points.size();i++){Vec p=points.get(i);valid(p);Vec next=points.get((i+1)%points.size());double distance=Math.hypot(p.x-next.x,p.z-next.z);if(distance<.5||distance>32||Math.abs(p.y-next.y)>8)throw new IllegalArgumentException("patrol_leg_limit");}
    }
    /** Waypoint changes are simulation state: only route setup is journaled. */
    public void patrol(String id,List<Vec> points){validatePatrol(points);Entity e=required(id);e.follow=null;e.patrol=List.copyOf(points);e.patrolAt=0;e.patrolResume=0;e.patrolDeadline=tick+600;aim(e,e.patrol.get(0));}
    public void follow(String id,Follow follow){Entity e=required(id);e.follow=Objects.requireNonNull(follow);e.patrol=List.of();e.inputExpires=0;e.holding=false;e.followRefresh=0;e.progress=null;aim(e,null);e.navigation="waiting_player";}
    public void input(String id,double x,double z,boolean jump){
        if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>1||Math.abs(z)>1)throw new IllegalArgumentException("invalid_bot_control");
        Entity e=required(id);if(!e.kind.equals("BOT"))throw new IllegalArgumentException("bot_required");
        if(pendingControl(e))throw new IllegalArgumentException("actor_condition_pending");
        double length=Math.hypot(x,z);if(length>1){x/=length;z/=length;}
        e.controllerInput=null;e.inputX=x;e.inputZ=z;e.inputExpires=tick+10;e.goal=null;e.follow=null;e.patrol=List.of();e.path=List.of();e.pathVersion++;cancelPath(e);
        e.inputJump=jump;if(jump&&e.grounded&&actorControllers==null&&!(actorMotion instanceof ActorJump)){e.vy=jumpSpeed();e.grounded=false;}
    }
    public void validateBlock(Block p,int id){
        validateBlock(p,id,0);
    }
    public void validateBlock(Block p,int id,int metadata){
        if(p.x<-szserver.world.MapBounds.HORIZONTAL||p.x>szserver.world.MapBounds.HORIZONTAL||p.z<-szserver.world.MapBounds.HORIZONTAL||p.z>szserver.world.MapBounds.HORIZONTAL||p.y<0||p.y>255||id<0||id>4095)
            throw new IllegalArgumentException("invalid_block");
        if(metadata<0||metadata>15)throw new IllegalArgumentException("invalid_block_metadata");
        if(!hasOverride(p)&&overlays.voxelCount()>=mapLimits().voxels())throw new IllegalArgumentException("block_override_limit");
        double[][] resource=ResourceBlockShapes.cells(id<<4|metadata);if(resource==null)resource=BuiltinBlockShapes.cells(id<<4|metadata);if(resource==null)resource=CustomBlockShapes.cells(id<<4|metadata);
        if(id!=0 && id!=8 && id!=9 && id!=10 && id!=11&&(resource==null||resource.length!=0))for(Entity e:entities.values())
            if(p.x+1>e.x-WIDTH&&p.x<e.x+WIDTH&&p.z+1>e.z-WIDTH&&p.z<e.z+WIDTH&&p.y+1>e.y&&p.y<e.y+e.pose.height())
                throw new IllegalArgumentException("block_overlaps_entity");
    }
    public int voxel(Block p){int value=overlays.packed(p.x,p.y,p.z);return value<0?terrain.voxel(p.x,p.y,p.z):value;}
    public TilePayload.Tile tile(Block p){
        if(overlays.hasTile(p))return overlays.tile(p);TilePayload.Tile base=terrain.tile(p.x,p.y,p.z);
        return base!=null&&(voxel(p)>>>4)!=0&&(voxel(p)>>>4)==(terrain.voxel(p.x,p.y,p.z)>>>4)?base:null;
    }
    public boolean hasTileOverride(Block p){return overlays.hasTile(p);}
    public void validateTileCapacity(int additions){overlays.validateTileCapacity(additions);}
    public void validateTilePayloadCapacity(int delta){overlays.validateTilePayloadCapacity(delta);}
    public boolean hasTilePayloadOverride(Block p){return overlays.hasTile(p)&&overlays.tile(p)!=null;}
    public void validateCompleteTileBytes(long delta){overlays.validateCompleteTileBytes(delta);}
    /** Capacity counts retained overlay compounds; authored terrain compounds are not owned here. */
    public int tileOverrideCompleteBytes(Block p){TilePayload.Tile tile=overlays.hasTile(p)?overlays.tile(p):null;return tile==null?0:tile.completeStateSize();}
    public record TileChunk(Map<Integer,Terrain.TileCell> base,BlockOverlays.Snapshot overlay){}
    public TileChunk tileChunk(int x,int z){return new TileChunk(terrain.tileIndex(x,z),overlays.snapshot(x,z));}
    public void tile(Block p,TilePayload.Tile tile,int id){
        if(tile!=null&&(tile.x()!=p.x||tile.y()!=p.y||tile.z()!=p.z||id==0))throw new IllegalArgumentException("tile_position_or_block_mismatch");overlays.putTile(p,tile,id);
    }
    public boolean hasOverride(Block p){return overlays.packed(p.x,p.y,p.z)>=0;}
    public void validateEditCapacity(int additions){overlays.validateVoxelCapacity(additions);}
    public void block(Block p,int id){block(p,id,0);}
    public void block(Block p,int id,int metadata){
        // Replay also uses this mutator; enforce capacity before clearing any associated tile.
        if(!hasOverride(p))overlays.validateVoxelCapacity(1);
        if(overlays.hasTile(p)&&(id==0||overlays.tileId(p)!=id))overlays.putTile(p,null,id);overlays.put(p,id,metadata);
        if(id!=4006&&actorControllers!=null)actorControllers.forgetMine(p);
    }
    private static void valid(Vec p){if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)||Math.abs(p.x)>30_000_001||Math.abs(p.z)>30_000_001||p.y<0||p.y>255)throw new IllegalArgumentException("invalid_position");}
    private Entity required(String id){Entity e=entities.get(id);if(e==null)throw new IllegalArgumentException("entity_not_found");return e;}
    private boolean solid(int x,int y,int z){int value=overlays.packed(x,y,z);if(value<0)return terrain.solid(x,y,z);int id=value>>>4;return id!=0&&id!=8&&id!=9&&id!=10&&id!=11;}
    /** Bounded voxel traversal on the simulation owner. Unloaded chunks block hits.
     * Tied boundary crossings check every touched neighbour, including grazing edges. */
    public boolean lineClear(Vec start,Vec end){
        double[] a={start.x,start.y,start.z},b={end.x,end.y,end.z},delta=new double[3],next=new double[3],stride=new double[3];
        int[] cell=new int[3],step=new int[3];double squared=0;
        for(int axis=0;axis<3;axis++){
            if(!Double.isFinite(a[axis])||!Double.isFinite(b[axis])||Math.abs(a[axis])>30_000_001||Math.abs(b[axis])>30_000_001)throw new IllegalArgumentException("invalid_combat_ray");
            delta[axis]=b[axis]-a[axis];squared+=delta[axis]*delta[axis];cell[axis]=(int)Math.floor(a[axis]);step[axis]=delta[axis]==0?0:Double.compare(delta[axis],0);
            stride[axis]=step[axis]==0?Double.POSITIVE_INFINITY:1/Math.abs(delta[axis]);
            next[axis]=step[axis]==0?Double.POSITIVE_INFINITY:((step[axis]>0?cell[axis]+1:cell[axis])-a[axis])/delta[axis];
        }
        if(squared>1024*1024)throw new IllegalArgumentException("combat_ray_limit");
        if(!clearCell(cell[0],cell[1],cell[2]))return false;
        for(int visited=0;visited<4096;visited++){
            double t=Math.min(next[0],Math.min(next[1],next[2]));if(t>1)return true;
            int tied=0;for(int axis=0;axis<3;axis++)if(next[axis]<=t+1e-12)tied|=1<<axis;
            for(int mask=1;mask<8;mask++)if((mask&tied)==mask){
                if(!clearCell(cell[0]+((mask&1)!=0?step[0]:0),cell[1]+((mask&2)!=0?step[1]:0),cell[2]+((mask&4)!=0?step[2]:0)))return false;
            }
            for(int axis=0;axis<3;axis++)if((tied&(1<<axis))!=0){cell[axis]+=step[axis];next[axis]+=stride[axis];}
        }
        throw new IllegalStateException("combat_ray_budget");
    }
    private boolean clearCell(int x,int y,int z){return terrain.ready(x,z)&&!solid(x,y,z);}
    /** Native block/tile cells, bounded axis segments; voxel flags alone are insufficient. */
    boolean grenadeBlocksClear(Vec a,Vec b){
        if(a.y()<0||b.y()<0)return false;
        double dx=b.x()-a.x(),dy=b.y()-a.y(),dz=b.z()-a.z();if(Math.abs(dx)>10.001||Math.abs(dy)>10.001||Math.abs(dz)>10.001)throw new IllegalArgumentException("grenade_segment_budget");
        int minX=(int)Math.floor(Math.min(a.x(),b.x())),maxX=(int)Math.floor(Math.max(a.x(),b.x()));int minZ=(int)Math.floor(Math.min(a.z(),b.z())),maxZ=(int)Math.floor(Math.max(a.z(),b.z()));
        int minY=Math.max(0,(int)Math.floor(Math.min(a.y(),b.y()))-BlockShapes.LOWER_HALO),maxY=Math.min(255,(int)Math.floor(Math.max(a.y(),b.y())));Vec delta=new Vec(dx,dy,dz);
        for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
            Block p=new Block(x,y,z);int packed;try{packed=voxel(p);}catch(IllegalArgumentException missing){if(!"terrain_voxel_unavailable".equals(missing.getMessage()))throw missing;packed=solid(x,y,z)?16:0;}
            for(double[] cell:actorCells(packed,p))if(SceneCollision.ray(a,delta,new SceneCollision.Box(x+cell[0],y+cell[1],z+cell[2],x+cell[3],y+cell[4],z+cell[5]),1)!=null)return false;
        }
        return true;
    }
    private boolean grenadeTerrainReady(Vec a,Vec b){
        int halo=grenadeCollision==null?0:2;int minX=Math.floorDiv((int)Math.floor(Math.min(a.x,b.x))-halo,16),maxX=Math.floorDiv((int)Math.floor(Math.max(a.x,b.x))+halo,16);
        int minZ=Math.floorDiv((int)Math.floor(Math.min(a.z,b.z))-halo,16),maxZ=Math.floorDiv((int)Math.floor(Math.max(a.z,b.z))+halo,16);boolean ready=true;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)ready&=terrain.ready(x*16,z*16);return ready;
    }
    private boolean ready(Vec p){int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z);
        // Evaluate every corner to request all missing chunks in one pass.
        boolean ready=terrain.ready(x-1,z-1);ready&=terrain.ready(x+1,z+1);ready&=terrain.ready(x-1,z+1);ready&=terrain.ready(x+1,z-1);return ready;
    }
    private boolean collides(double x,double y,double z){
        return voxelCollides(x,y,z)||activeScene!=null&&activeScene.overlaps(activePose.body(x,y,z),actorBudget);
    }
    private boolean voxelCollides(double x,double y,double z){
        SceneCollision.Box body=activePose.body(x,y,z);
        if(body.y()<0)return true;
        for(int bx=(int)Math.floor(x-WIDTH+1e-8);bx<=(int)Math.floor(x+WIDTH-1e-8);bx++)
            for(int by=(int)Math.floor(y+1e-8)-BlockShapes.LOWER_HALO;by<=(int)Math.floor(y+activePose.height()-1e-8);by++)
                for(int bz=(int)Math.floor(z-WIDTH+1e-8);bz<=(int)Math.floor(z+WIDTH-1e-8);bz++)
                    if(by>=0&&by<=255){int packed;try{packed=voxel(new Block(bx,by,bz));}catch(IllegalArgumentException missing){if(!"terrain_voxel_unavailable".equals(missing.getMessage()))throw missing;packed=solid(bx,by,bz)?16:0;}if(BlockShapes.overlaps(actorCells(packed,new Block(bx,by,bz)),bx,by,bz,body))return true;}
        return false;
    }
    /** 0 = terrain loading, 1 = usable, 2 = blocked. Only called on WorldLoop. */
    public int placement(Vec p){valid(p);if(p.y()>254)return 2;if(!ready(p))return 0;return collides(p.x,p.y,p.z)?2:1;}
    /** Native players own their flight/vertical motion; scenes and saved positions are 3D.
     * Keep bounded voxel occupancy and terrain readiness without the simulation's floor/ceiling. */
    public int nativePlacement(Vec p){return nativePlacement(p,WIDTH,HEIGHT);}
    /** One-off resize admission uses the existing bounded native object collision worker. */
    public CompletableFuture<Integer> characterResize(Vec p,double radius,double height){
        int voxels=nativePlacement(p,radius,height);if(voxels!=1)return CompletableFuture.completedFuture(voxels);
        var result=new CompletableFuture<Integer>();var body=new SceneCollision.Box(p.x()-radius,p.y(),p.z()-radius,p.x()+radius,p.y()+height,p.z()+radius);
        actorSceneRequest(p).whenComplete((scene,error)->{
            try{if(error!=null)result.completeExceptionally(error);else if(!result.isDone())result.complete(scene!=null&&scene.overlaps(body,new ActorScene.Budget(16384))?2:1);}
            catch(Throwable failure){result.completeExceptionally(failure);}finally{if(scene!=null)scene.close();}
        });return result;
    }
    public int nativePlacement(Vec p,double radius,double height){
        if(!Double.isFinite(radius)||!Double.isFinite(height)||radius<=0||radius>1.2||height<=0||height>7.2)throw new IllegalArgumentException("placement_dimensions");
        szserver.persistence.CharacterLocations.validPosition(p);if(!ready(p))return 0;
        if(p.y()<1||p.y()+height>255)return 2;
        var body=new SceneCollision.Box(p.x()-radius,p.y(),p.z()-radius,p.x()+radius,p.y()+height,p.z()+radius);
        if(activeScene!=null&&activeScene.overlaps(body,actorBudget))return 2;
        for(int bx=(int)Math.floor(p.x()-radius+1e-8);bx<=(int)Math.floor(p.x()+radius-1e-8);bx++)
            for(int by=(int)Math.floor(p.y()+1e-8)-BlockShapes.LOWER_HALO;by<=(int)Math.floor(p.y()+height-1e-8);by++)
                for(int bz=(int)Math.floor(p.z()-radius+1e-8);bz<=(int)Math.floor(p.z()+radius-1e-8);bz++)
                    if(by>=0&&by<=255){var cell=new Block(bx,by,bz);int packed;try{packed=voxel(cell);}catch(IllegalArgumentException missing){if(!"terrain_voxel_unavailable".equals(missing.getMessage()))throw missing;packed=solid(bx,by,bz)?16:0;}if(BlockShapes.overlaps(actorCells(packed,cell),bx,by,bz,body))return 2;}
        return 1;
    }
    /** -1 = async terrain not ready; otherwise first air above the top solid voxel. */
    public int surfaceHeight(int x,int z){if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000)throw new IllegalArgumentException("teleport_out_of_bounds");if(!ready(new Vec(x+.5,1,z+.5)))return -1;for(int y=255;y>=0;y--)if(solid(x,y,z))return y+1;return 0;}
    private double axis(Entity e,int axis,double delta){
        double x=e.x,y=e.y,z=e.z;
        if(activeScene!=null)delta=activeScene.sweep(e.pose.body(x,y,z),axis,delta,actorBudget);
        if(!voxelCollides(x+(axis==0?delta:0),y+(axis==1?delta:0),z+(axis==2?delta:0)))return delta;
        double lo=0,hi=1;
        for(int i=0;i<20;i++){double middle=(lo+hi)*.5;
            if(voxelCollides(x+(axis==0?delta*middle:0),y+(axis==1?delta*middle:0),z+(axis==2?delta*middle:0)))hi=middle;else lo=middle;}
        return delta*lo;
    }
    /** Native-sized 0.5m step, settled onto actual shaped floors rather than a ballistic hop. */
    private double horizontal(Entity e,int direction,double delta){
        double clipped=axis(e,direction,delta);
        if(!e.grounded||Math.abs(clipped-delta)<1e-7)return clipped;
        double originalY=e.y,rise=axis(e,1,ActorPose.STEP);if(rise<1e-6)return clipped;e.y+=rise;
        double stepped=axis(e,direction,delta);
        if(Math.abs(stepped)<=Math.abs(clipped)+1e-7){e.y=originalY;return clipped;}
        if(direction==0)e.x+=stepped;else e.z+=stepped;
        double drop=axis(e,1,-rise);e.y+=drop;e.grounded=Math.abs(drop+rise)>1e-7;if(e.grounded)e.vy=0;
        if(direction==0)e.x-=stepped;else e.z-=stepped;
        return stepped;
    }
    public void step(){
        szserver.npc.NpcDirector.tick(this);
        tick++;stepBodies=dynamicBodies;planBudget=actorMaps==null?8:2;sceneRequestBudget=4;actorBudget=new ActorScene.Budget(16384);
        if(actorControllers!=null)actorControllers.worldTick();
        grenades.step();
        // Rotate planner eligibility so a large population cannot starve later entities.
        List<Entity> order=new ArrayList<>(entities.values());
        if(!order.isEmpty())Collections.rotate(order,-(int)(tick%order.size()));
        for(Entity e:order){
            double oldX=e.x,oldY=e.y,oldZ=e.z,oldVX=e.vx,oldVY=e.vy,oldVZ=e.vz,oldAir=e.airborneTime;
            boolean oldGround=e.grounded,oldLadder=e.ladder,oldWater=e.inWater;activeScene=null;ActorPose oldPose=e.pose;activePose=e.pose;
            ActorJump.State oldJump=e.jumpState;List<Vec> oldPath=e.path;int oldPathAt=e.pathAt;
            ActorContacts.State oldContacts=e.contacts;
            int oldLocomotion=e.locomotion,oldPreviousLocomotion=e.previousLocomotion;double oldPoseSince=e.poseSince;
            try{
                if(actorControllers!=null){controller(e);e.controller.advanceBuffs(tick);e.vitals=e.controller.vitals();if(e.vitals!=null)e.health=e.vitals.health();}
                boolean dead=e.health<=0;
                if(dead){cancelPath(e);e.vx=e.vz=0;oldVX=oldVZ=0;e.goal=null;e.path=List.of();e.patrol=List.of();}
                // Pause the physics clock while geometry is unknown. Losing velocity here
                // creates a fresh acceleration and false airborne frame on every handoff.
                if(!ready(new Vec(e.x,e.y,e.z))){e.navigation="waiting_terrain";continue;}
                if(actorMaps!=null&&!actorScene(e)){e.navigation="waiting_scene";actorSceneWaits++;continue;}
                activeScene=e.scene;
                if(actorControllers!=null){controllerStep(e,dead,oldX,oldZ);continue;}
                // A durable block edit can finish while an NPC is entering that cell.
                // Resolve penetration on the simulation owner, never inside the writer.
                if(collides(e.x,e.y,e.z)){
                    boolean resolved=resolvePenetration(e);
                    if(!resolved){e.navigation="blocked_by_world_edit";e.vx=e.vy=e.vz=0;continue;}
                }
                e.inWater=actorMotion instanceof ActorDynamics dynamics&&dynamics.water(new Vec(e.x,e.y,e.z),actorContactVoxels(),e.pose);
                if(actorMotion instanceof ActorDynamics dynamics)e.grounded=dynamics.grounded(e.grounded,e.airborneTime,()->nativeActorSupport(e));
                double previousX=e.vx,previousZ=e.vz;boolean held=e.ladder;e.ladder=false;
                e.jumpIntent=!dead&&e.kind.equals("BOT")&&tick<e.inputExpires&&e.goal==null&&e.follow==null&&e.patrol.isEmpty()&&e.inputJump;
                if(!dead)steer(e,held);
                // Restore full height only when there is room; never push a crouched actor into a ceiling.
                if(!dead&&e.pose==ActorPose.CROUCH&&e.path.isEmpty())changePose(e,ActorPose.STAND);
                if(e.pose!=oldPose&&actorMotion instanceof ActorDynamics dynamics)e.inWater=dynamics.water(new Vec(e.x,e.y,e.z),actorContactVoxels(),e.pose);
                if(e.pose==ActorPose.CROUCH&&e.jumpIntent&&!poseClear(e,ActorPose.STAND))e.jumpIntent=false;
                if(!dead&&!e.ladder&&held&&ladderAt(e)!=null){e.ladder=true;e.vy=0;}
                if(held&&!e.ladder)e.vy=0;
                if(actorMotion instanceof ActorJump jump){var attempt=jump.jumpStep(e.jumpState,e.grounded,e.ladder,e.jumpIntent,Math.hypot(e.vx,e.vz)>.001||e.ladder&&Math.abs(e.vy)>.001,e.inWater,e.vy,e.y,DT,e.pose,e.ladder?3:e.inWater?2:e.pose==ActorPose.CROUCH?3:4);e.jumpState=attempt.state();e.vy=attempt.velocityY();e.grounded=attempt.grounded();if(attempt.accepted())changePose(e,ActorPose.STAND);}
                int locomotion=e.ladder?5:e.grounded?e.pose.nativeId():6;
                if(locomotion!=e.locomotion){e.previousLocomotion=e.locomotion;e.locomotion=locomotion;e.poseSince=e.jumpState.time();}
                if(!e.ladder&&actorMotion instanceof ActorDynamics dynamics){
                    var previous=new ActorDynamics.State(previousX,e.vy,previousZ,e.grounded,e.airborneTime,e.inWater,false,e.contacts);
                    var control=new ActorLocomotion.Control(e.jumpState.mode(),e.locomotion,e.previousLocomotion,e.jumpState.time(),e.poseSince,ActorLocomotion.Profile.DEFAULT);
                    var moved=dynamics.advance(previous,new Vec(e.vx,0,e.vz),DT,velocity->nativeActorStep(e,velocity,oldX,oldZ),control);var result=moved.motion();var next=moved.state();
                    var contacts=activeScene==null?Map.<ActorContacts.Key,ActorContacts.Obstacle>of():activeScene.contacts(e.pose.body(result.x(),result.y(),result.z()),actorBudget);
                    e.contacts=e.contacts.next(contacts);
                    e.x=result.x();e.y=result.y();e.z=result.z();e.vx=next.vx();e.vy=next.vy();e.vz=next.vz();e.grounded=next.grounded();e.airborneTime=next.airborneTime();
                    if(dead)e.navigation="dead";continue;
                }
                e.airborneTime=0;
                if(e.ladder)e.grounded=false;else e.vy=Math.max(-40,e.vy-9.81*DT);
                if(actorMotion!=null){
                    Vec p=new Vec(e.x,e.y,e.z),delta=new Vec(e.vx*DT,e.vy*DT,e.vz*DT);
                    if(activeScene!=null&&!activeScene.covers(actorSweep(p,delta,e.grounded,e.pose)))throw new ActorScene.Deferred();
                    var area=new SceneCollision.Box(e.x-.4-Math.abs(delta.x()),e.y-1-Math.abs(delta.y()),e.z-.4-Math.abs(delta.z()),e.x+.4+Math.abs(delta.x()),e.y+2.5+Math.abs(delta.y()),e.z+.4+Math.abs(delta.z()));
                    var result=actorMotion.move(p,delta,e.grounded,activeScene==null?List.of():activeScene.nativeCells(area,actorBudget),actorQuery(e),e.pose);
                    e.x=result.x();e.y=result.y();e.z=result.z();e.grounded=result.grounded();if(result.verticalCollision())e.vy=0;
                    rememberBlocked(e,delta.x(),delta.z(),e.x-oldX,e.z-oldZ,result.horizontalCollision());
                    e.vx=(e.x-oldX)/DT;e.vz=(e.z-oldZ)/DT;
                    e.contacts=e.contacts.next(activeScene==null?Map.of():activeScene.contacts(e.pose.body(e.x,e.y,e.z),actorBudget));
                    if(dead)e.navigation="dead";continue;
                }
                double wantedX=e.vx*DT,wantedZ=e.vz*DT;
                int steps=Math.max(1,(int)Math.ceil(Math.max(Math.max(Math.abs(e.vx),Math.abs(e.vz)),Math.abs(e.vy))*DT/.2));
                e.grounded=false;
                for(int i=0;i<steps;i++){
                    double dy=e.vy*DT/steps,ay=axis(e,1,dy);e.y+=ay;
                    if(Math.abs(ay-dy)>1e-7){if(dy<0)e.grounded=true;e.vy=0;}
                    double dx=e.vx*DT/steps,ax=horizontal(e,0,dx);e.x+=ax;if(Math.abs(ax-dx)>1e-7)e.vx=0;
                    double dz=e.vz*DT/steps,az=horizontal(e,2,dz);e.z+=az;if(Math.abs(az-dz)>1e-7)e.vz=0;
                }
                rememberBlocked(e,wantedX,wantedZ,e.x-oldX,e.z-oldZ,true);
                e.contacts=e.contacts.next(activeScene==null?Map.of():activeScene.contacts(e.pose.body(e.x,e.y,e.z),actorBudget));
                if(dead)e.navigation="dead";
            }catch(ActorScene.Deferred deferred){e.contacts=oldContacts;e.x=oldX;e.y=oldY;e.z=oldZ;e.vx=oldVX;e.vy=oldVY;e.vz=oldVZ;e.airborneTime=oldAir;e.grounded=oldGround;e.ladder=oldLadder;e.inWater=oldWater;e.jumpState=oldJump;e.pose=oldPose;e.locomotion=oldLocomotion;e.previousLocomotion=oldPreviousLocomotion;e.poseSince=oldPoseSince;if(e.path==oldPath)e.pathAt=oldPathAt;e.navigation="waiting_collision_budget";actorDeferred++;}
            catch(RuntimeException ex){simulationFailures++;if(actorControllers!=null&&e.controller!=null)controllerFrame(e,e.controller.frame());else e.vx=e.vy=e.vz=0;e.navigation="simulation_error";}
            finally{activeScene=null;activePose=ActorPose.STAND;}
        }
        if(actorControllers!=null)for(var player:minePlayers.values())if(player.alive()&&!player.invulnerable())try{
            actorControllers.contactMines(player,new HeldActorVoxels(actorBudget,()->{if(minePlayers.get(player.character())!=player)throw new ActorScene.Deferred();}){
                public ActorMineTrigger prepareMine(ActorMineTrigger trigger,Vec position){return prepareMineBlast(trigger,null,position,this,actorBudget);}
            });
        }catch(ActorScene.Deferred loading){actorDeferred++;}catch(RuntimeException failed){simulationFailures++;if(Boolean.getBoolean("szserver.debugMineContacts"))failed.printStackTrace();}
        mineScenes.entrySet().removeIf(entry->{if(minePlayers.containsKey(entry.getKey()))return false;closeMineScene(entry.getValue());return true;});
        if(actorControllers!=null)for(var trigger:actorControllers.drainMineTriggers()){
            for(var target:trigger.targets())damage(target.id(),target.incarnation(),target.damage());
            // Presentation is transient; cooldown and resulting actor health are durable state.
            if(mineEffects.size()==512)mineEffects.removeFirst();mineEffects.addLast(trigger);
        }
    }
    private void controller(Entity e){if(e.controller==null){e.controller=actorControllers.create(e.id,e.incarnation,new Vec(e.x,e.y,e.z));e.controller.bindWorldClock(tick);e.controller.health(e.health);controllerFrame(e,e.controller.frame());}}
    private static void controllerFrame(Entity e,ActorControllerEngine.Frame frame){
        e.x=frame.position().x();e.y=frame.position().y();e.z=frame.position().z();e.vx=frame.velocity().x();e.vy=frame.velocity().y();e.vz=frame.velocity().z();e.controllerYaw=frame.yaw();
        e.grounded=frame.grounded();e.inWater=frame.water();e.ladder=frame.pose()==5;e.airborneTime=frame.airborneTime();e.jumpState=frame.jump();e.locomotion=frame.pose();e.previousLocomotion=frame.previous();e.poseSince=frame.since();e.aiming=e.controller!=null&&e.controller.aiming();
        e.pose=controllerPose(frame)==1?ActorPose.CROUCH:ActorPose.STAND;e.bodyParts=frame.body();e.traversal=frame.traversal();
        if(e.controller!=null)e.vitals=e.controller.vitals();
    }
    private void controllerStep(Entity e,boolean dead,double oldX,double oldZ){
        controller(e);e.controller.anomalySlow(tick<e.anomalySlowUntil?e.anomalySlow:1);ActorControllerEngine.Input input;Boolean routeProneRequest=null;
        if(!dead&&e.controllerInput!=null&&(pendingControl(e)||tick<e.controllerExpires)){input=e.controllerInput;e.routePronePending=null;e.navigation="controlled";}
        else{
            e.controllerInput=null;e.jumpIntent=!dead&&e.kind.equals("BOT")&&tick<e.inputExpires&&e.goal==null&&e.follow==null&&e.patrol.isEmpty()&&e.inputJump;
            if(!dead)steer(e,e.ladder);else e.vx=e.vz=0;
            boolean moving=Math.hypot(e.vx,e.vz)>1e-6;float yaw=e.locomotion==2||e.locomotion==4?e.controllerYaw:e.routeYaw!=null?e.routeYaw:moving?(float)Math.toDegrees(Math.atan2(-e.vx,e.vz)):e.controllerYaw;
            ActorPose wanted=e.pathAt<e.path.size()?routePose(e,e.pathAt):e.locomotion==7?ActorPose.PRONE:ActorPose.STAND;
            boolean prone=wanted==ActorPose.PRONE,isProne=e.locomotion==7;if(e.routePronePending!=null&&isProne==e.routePronePending)e.routePronePending=null;
            boolean toggle=!dead&&prone!=isProne&&e.routePronePending==null;
            if(toggle)routeProneRequest=prone;
            input=new ActorControllerEngine.Input(yaw,0,0,moving?szserver.npc.NpcDirector.gait(this,e.id):ActorLocomotion.Gait.NONE,e.jumpIntent&&!prone,!dead&&wanted==ActorPose.CROUCH,toggle);
            if(!dead&&!prone&&!isProne&&e.locomotion!=2&&e.locomotion!=4&&e.pathAt<e.path.size()){
                var position=new Vec(e.x,e.y,e.z);var target=e.path.get(e.pathAt);var ladder=ActorLadderControl.toward(position,target,ladderRouteAt(e,position),ladderRouteAt(e,target),e.controller.ladderFacing(),e.jumpState.armed(),e.grounded);
                if(ladder!=null)input=new ActorControllerEngine.Input(ladder.yaw(),0,ladder.heading(),ladder.moving()?szserver.npc.NpcDirector.gait(this,e.id):ActorLocomotion.Gait.NONE,ladder.jump(),ladder.crouch(),toggle);
            }
        }
        var cable=routeAction(e,e.pathAt);
        if(!dead&&e.controllerInput==null&&cable!=null&&(e.locomotion==12||e.ziplinePathAt!=e.pathAt)){
            if(e.locomotion==12)e.ziplinePathAt=e.pathAt;
            input=ActorZiplineControl.toward(cable,new Vec(e.x,e.y,e.z),e.locomotion==12,e.grounded,e.jumpState.armed());
        }
        double wishX=e.vx*DT,wishZ=e.vz*DT;
        var frame=e.controller.step(input,(float)DT,controllerAccess(e.id,actorBudget,stepBodies));controllerFrame(e,frame);
        if(e.vitals!=null)e.health=e.vitals.health();
        if(routeProneRequest!=null)e.routePronePending=routeProneRequest;
        e.contacts=e.contacts.next(e.controller.contacts());
        if(e.controllerInput!=null)e.controllerInput=e.controllerInput.consumed();rememberBlocked(e,wishX,wishZ,e.x-oldX,e.z-oldZ,true);if(dead)e.navigation="dead";
    }
    private double jumpSpeed(){return actorMotion instanceof ActorDynamics dynamics?dynamics.jumpSpeed():6.5;}
    private double travelSpeed(Entity e){return actorMotion instanceof ActorDynamics dynamics?dynamics.travelSpeed(e.pose,e.inWater):3;}
    private BlockShapes.Voxels actorContactVoxels(){return new BlockShapes.Voxels(){
        public int voxel(Block p){actorBudget.use();try{return World.this.voxel(p);}catch(IllegalArgumentException e){if("terrain_voxel_unavailable".equals(e.getMessage()))throw new ActorScene.Deferred();throw e;}}
        public TilePayload.Tile tile(Block p){return World.this.tile(p);}
    };}
    private ActorDynamics.Contact nativeActorStep(Entity e,Vec velocity,double oldX,double oldZ){
        Vec p=new Vec(e.x,e.y,e.z),delta=new Vec(velocity.x()*DT,velocity.y()*DT,velocity.z()*DT);
        if(activeScene!=null&&!activeScene.covers(actorSweep(p,delta,e.grounded,e.pose)))throw new ActorScene.Deferred();
        var area=new SceneCollision.Box(e.x-.4-Math.abs(delta.x()),e.y-1-Math.abs(delta.y()),e.z-.4-Math.abs(delta.z()),e.x+.4+Math.abs(delta.x()),e.y+2.5+Math.abs(delta.y()),e.z+.4+Math.abs(delta.z()));
        var result=actorMotion.move(p,delta,e.grounded,activeScene==null?List.of():activeScene.nativeCells(area,actorBudget),actorQuery(e),e.pose);
        rememberBlocked(e,delta.x(),delta.z(),result.x()-oldX,result.z()-oldZ,result.horizontalCollision());
        return new ActorDynamics.Contact(result,result.x()-oldX,result.z()-oldZ);
    }
    private boolean nativeActorSupport(Entity e){
        var p=new Vec(e.x,e.y,e.z);
        // Include native overlap tolerances. Unknown geometry defers the entire actor step.
        var area=new SceneCollision.Box(e.x-.1,e.y-1.5,e.z-.1,e.x+.1,e.y+.05,e.z+.1);
        if(activeScene!=null&&!activeScene.covers(area))throw new ActorScene.Deferred();
        return actorMotion.support(p,activeScene==null?List.of():activeScene.nativeCells(area,actorBudget),actorQuery(e));
    }
    private ActorMotion.Voxels actorQuery(Entity entity){
        ActorBody.Frame captured=stepBodies;return new ActorMotion.Voxels(){
            public List<SceneCollision.Box> boxes(SceneCollision.Box area){return motionVoxels(area);}
            public List<ActorBody> bodies(SceneCollision.Box area){return captured.select(entity.id,area,actorBudget);}
            public String actorId(){return entity.id;}
        };
    }
    /** Sole-owner, one-frame view for the complete native dispatcher. Keeps resident source chunks alive
     * across cache eviction; live overlays and scene publications cannot change during this view. */
    public ActorWorldAccess controllerAccess(String id,int limit){
        if(limit<1||limit>65536)throw new IllegalArgumentException("actor_controller_budget");
        return controllerAccess(id,new ActorScene.Budget(limit),dynamicBodies);
    }
    private static SceneCollision.Box blastArea(Vec start,Vec end){return new SceneCollision.Box(Math.min(start.x(),end.x()),Math.min(start.y(),end.y()),Math.min(start.z(),end.z()),Math.max(start.x(),end.x()),Math.max(start.y(),end.y()),Math.max(start.z(),end.z()));}
    private boolean mineSceneClear(ActorScene geometry,Vec start,Vec end,ActorScene.Budget budget){
        if(actorMaps==null)return true;if(geometry==null)throw new ActorScene.Deferred();
        try{geometry.checkCurrent();}catch(IllegalArgumentException stale){throw new ActorScene.Deferred();}
        if(!geometry.covers(blastArea(start,end)))throw new ActorScene.Deferred();return geometry.pointClear(start,end,budget);
    }
    private ActorScene minePlayerScene(ActorMinePlayer player,Vec start,Vec end){
        if(actorMaps==null)return null;var local=mineScenes.computeIfAbsent(player.character(),ignored->new MineScene());
        if(local.pending!=null&&local.pending.isDone()){
            ActorScene ready=null;try{ready=local.pending.join();ready.checkCurrent();if(ready.covers(blastArea(start,end))){if(local.scene!=null)local.scene.close();local.scene=ready;ready=null;}}
            catch(RuntimeException failed){local.retry=tick+2;}finally{if(ready!=null)ready.close();local.pending=null;}
        }
        if(local.scene!=null)try{local.scene.checkCurrent();if(local.scene.covers(blastArea(start,end)))return local.scene;}catch(IllegalArgumentException stale){}
        if(local.scene!=null){local.scene.close();local.scene=null;}
        if(local.pending==null&&tick>=local.retry&&sceneRequestBudget>0){sceneRequestBudget--;actorSceneRequests++;try{local.pending=actorMaps.actorScene(player.position());}catch(RuntimeException full){local.retry=tick+2;}}
        throw new ActorScene.Deferred();
    }
    private ActorMineTrigger prepareMineBlast(ActorMineTrigger trigger,Entity contact,Vec contactPosition,ActorWorldAccess source,ActorScene.Budget budget){
        source.use();var targets=new ArrayList<ActorMineTarget>();var playerHits=new ArrayList<ActorMinePlayerHit>();
        for(var target:entities.values())if(target.health>0){
            Vec p=target==contact?contactPosition:new Vec(target.x,target.y,target.z);
            float amount=szserver.client.GrenadePackets.damage(trigger.profile(),trigger.position(),new double[]{p.x(),p.y(),p.z()},target.controller==null?Map.of():target.controller.stats());
            if(amount<=0)continue;Vec end=new Vec(p.x(),p.y()+.9,p.z()),start=ActorMineBlast.departure(trigger.position(),end);
            if(ActorMineBlast.blocksClear(source,start,end)&&mineSceneClear(target.scene,start,end,budget))targets.add(new ActorMineTarget(target.id,target.incarnation,amount));
        }
        for(var player:minePlayers.values())if(player.alive()&&!player.invulnerable()){
            var p=player.position();float amount=szserver.client.GrenadePackets.damage(trigger.profile(),trigger.position(),new double[]{p.x(),p.y(),p.z()},player.stats());
            if(amount<=0)continue;Vec end=new Vec(p.x(),p.y()+.9,p.z()),start=ActorMineBlast.departure(trigger.position(),end);
            if(ActorMineBlast.blocksClear(source,start,end)&&mineSceneClear(minePlayerScene(player,start,end),start,end,budget))playerHits.add(new ActorMinePlayerHit(player.character(),player.incarnation(),player.life(),amount));
        }
        return trigger.withTargets(targets).withPlayers(playerHits);
    }
    private class HeldActorVoxels implements ActorWorldAccess {
        private final long at=tick,epoch=overlays.epoch();private final ActorScene.Budget budget;private final Runnable guard;
        private final Map<Long,Terrain.EditChunk> chunks=new HashMap<>();
        HeldActorVoxels(ActorScene.Budget budget,Runnable guard){this.budget=budget;this.guard=guard;}
        public void use(){if(tick!=at||overlays.epoch()!=epoch)throw new ActorScene.Deferred();guard.run();budget.use();}
        private Terrain.EditChunk chunk(int x,int z){
            use();long key=BlockOverlays.key(x,z);Terrain.EditChunk value=chunks.get(key);if(value!=null)return value;
            if(!terrain.ready(x*16,z*16))throw new ActorScene.Deferred();
            try{value=terrain.editChunk(x,z);}catch(IllegalArgumentException cold){if("terrain_not_ready_retry".equals(cold.getMessage()))throw new ActorScene.Deferred();throw cold;}
            chunks.put(key,value);return value;
        }
        public boolean resident(int x,int z){chunk(x,z);return true;}
        private int cell(Block p){if(p.y()<0||p.y()>255)throw new IllegalArgumentException("invalid_block");return (p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}
        public int voxel(Block p){use();Terrain.EditChunk base=chunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));int changed=overlays.packed(p.x(),p.y(),p.z());return changed<0?base.voxel(cell(p)):changed;}
        public boolean mineTile(Block p){return voxel(p)>>>4==4006;}
        public TilePayload.Tile tile(Block p){
            use();Terrain.EditChunk base=chunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));if(overlays.hasTile(p))return overlays.tile(p);
            int cell=cell(p),before=base.voxel(cell),after=overlays.packed(p.x(),p.y(),p.z()),current=after<0?before:after;return (current>>>4)!=0&&(current>>>4)==(before>>>4)?base.tile(cell):null;
        }
    }
    private ActorWorldAccess controllerAccess(String id,ActorScene.Budget budget,ActorBody.Frame bodies){
        Entity entity=required(id);ActorScene scene=entity.scene;
        return new HeldActorVoxels(budget,()->{if(entities.get(id)!=entity||entity.scene!=scene)throw new ActorScene.Deferred();}){
            public long simulationTick(){return tick;}
            public boolean seatAvailable(UUID object,int index){use();for(Entity other:entities.values()){if(other==entity)continue;var seat=other.traversal.seat();if(seat!=null&&seat.object().equals(object)&&seat.index()==index)return false;}return true;}
            public ActorMineTrigger prepareMine(ActorMineTrigger trigger,Vec position){return prepareMineBlast(trigger,entity,position,this,budget);}
            public Object object(UUID identity){use();if(actorMaps==null)return null;if(scene==null)throw new ActorScene.Deferred();return scene.nativeObject(identity,budget);}
            public List<UUID> carousels(){use();return actorMaps==null?List.of():scene==null?List.of():scene.carousels();}
            public List<Object> scene(SceneCollision.Box area){
                use();if(actorMaps==null)return List.of();if(scene==null||!scene.covers(area))throw new ActorScene.Deferred();
                try{scene.checkCurrent();}catch(IllegalArgumentException stale){if("actor_scene_closed".equals(stale.getMessage())||"block_publication_stale".equals(stale.getMessage()))throw new ActorScene.Deferred();throw stale;}
                return scene.nativeCells(area,budget);
            }
            public List<Object> ladders(SceneCollision.Box area){use();if(actorMaps==null)return List.of();if(scene==null||!scene.covers(area))throw new ActorScene.Deferred();try{return scene.nativeLadders(area,budget);}catch(IllegalArgumentException stale){if("actor_scene_closed".equals(stale.getMessage())||"block_publication_stale".equals(stale.getMessage()))throw new ActorScene.Deferred();throw stale;}}
            public List<ActorContacts.Volume> feedback(SceneCollision.Box area){
                use();if(actorMaps==null)return List.of();if(scene==null||!scene.covers(area))throw new ActorScene.Deferred();
                try{scene.checkCurrent();}catch(IllegalArgumentException stale){if("actor_scene_closed".equals(stale.getMessage())||"block_publication_stale".equals(stale.getMessage()))throw new ActorScene.Deferred();throw stale;}
                return scene.feedback(area,budget);
            }
            public List<ActorBody> bodies(SceneCollision.Box area){use();return bodies.select(id,area,budget);}
            public String actorId(){return id;}
            public boolean livingTick(){return true;}
        };
    }
    /** Full body sweep including the native grounded step, not just the current feet. */
    private static SceneCollision.Box actorSweep(Vec p,Vec d,boolean ground,ActorPose pose){var b=pose.body(p.x(),p.y(),p.z());return new SceneCollision.Box(b.x()+Math.min(0,d.x()),b.y()+Math.min(0,d.y()),b.z()+Math.min(0,d.z()),b.xx()+Math.max(0,d.x()),b.yy()+Math.max(ground?ActorPose.STEP:0,d.y()),b.zz()+Math.max(0,d.z()));}
    private static void rememberBlocked(Entity e,double dx,double dz,double movedX,double movedZ,boolean collision){
        double wanted=Math.hypot(dx,dz);if(collision&&wanted>1e-7&&(movedX*dx+movedZ*dz)/wanted<wanted*.5){e.blockedX=dx/wanted;e.blockedZ=dz/wanted;}else{e.blockedX=e.blockedZ=0;}
    }
    private boolean resolvePenetration(Entity e){
        // A native float contact can leave a few millimetres inside a shaped edge.
        // Live edits can overlap much more. Find the first clear bracket, then its
        // minimum escape height; rounding either case to a block causes visible hops.
        double lower=0;
        for(double rise=.03125;rise<=4;rise*=2){
            if(e.y+rise>254)break;
            if(!collides(e.x,e.y+rise,e.z)){
                double upper=rise;for(int n=0;n<12;n++){double middle=(lower+upper)*.5;if(collides(e.x,e.y+middle,e.z))lower=middle;else upper=middle;}
                e.y+=upper;e.vy=0;return true;
            }lower=rise;
        }return false;
    }
    private List<SceneCollision.Box> motionVoxels(SceneCollision.Box area){
        List<SceneCollision.Box> result=new ArrayList<>();
        for(int x=(int)Math.floor(area.x());x<=(int)Math.floor(area.xx());x++)for(int y=(int)Math.floor(area.y())-BlockShapes.LOWER_HALO;y<=(int)Math.floor(area.yy());y++)for(int z=(int)Math.floor(area.z());z<=(int)Math.floor(area.zz());z++){
            actorBudget.use();int packed;if(y<0)packed=16;else if(y>255)continue;else try{packed=voxel(new Block(x,y,z));}catch(IllegalArgumentException missing){if(!"terrain_voxel_unavailable".equals(missing.getMessage()))throw missing;packed=solid(x,y,z)?16:0;}
            for(double[] b:actorCells(packed,new Block(x,y,z))){actorBudget.use();var box=new SceneCollision.Box(x+b[0],y+b[1],z+b[2],x+b[3],y+b[4],z+b[5]);if(box.xx()>area.x()&&box.x()<area.xx()&&box.yy()>area.y()&&box.y()<area.yy()&&box.zz()>area.z()&&box.z()<area.zz())result.add(box);}
        }return result;
    }
    private double[][] actorCells(int packed,Block p){try{return BlockShapes.cells(packed,BlockShapes.tileShape(packed)&&p.y()>=0?tile(p):null,p,this);}catch(InterruptedException impossible){Thread.currentThread().interrupt();throw new ActorScene.Deferred();}}
    private boolean actorScene(Entity e){
        Vec p=new Vec(e.x,e.y,e.z),delta=new Vec(e.vx*DT,e.vy*DT,e.vz*DT);
        var swept=actorSweep(p,delta,e.grounded,e.pose);
        if(e.scenePending!=null&&e.scenePending.isDone()){ActorScene scene=null;try{scene=e.scenePending.join();scene.checkCurrent();if(scene.covers(p)&&scene.covers(swept)){if(e.scene!=null)e.scene.close();e.scene=scene;scene=null;}}catch(RuntimeException unavailable){e.sceneRetry=tick+2;}finally{if(scene!=null)scene.close();e.scenePending=null;}}
        boolean usable=false;
        if(e.scene!=null)try{e.scene.checkCurrent();usable=e.scene.covers(p)&&e.scene.covers(swept);}catch(RuntimeException stale){}
        if(!usable&&e.scene!=null){e.scene.close();e.scene=null;}
        // Keep the current valid scene while its successor loads. The existing
        // four-request/tick, bounded queue and geometry-memory limits still apply.
        // Prefetch for the actual native gait. A fixed 2m margin was shorter than
        // a JOG actor travels during a delayed .5s scene handoff and caused pauses.
        double refreshMargin=Math.max(2,Math.min(5.5,Math.hypot(e.vx,e.vz)*.75));
        if((!usable||!e.scene.covers(p,refreshMargin))&&e.scenePending==null&&tick>=e.sceneRetry&&sceneRequestBudget>0){
            sceneRequestBudget--;actorSceneRequests++;
            Vec ahead=new Vec(e.x+Math.max(-3,Math.min(3,e.vx*.75)),e.y+Math.max(-3,Math.min(3,e.vy*.75)),e.z+Math.max(-3,Math.min(3,e.vz*.75)));
            try{e.scenePending=actorMaps.actorScene(ahead);}catch(RuntimeException full){e.sceneRetry=tick+2;}
        }
        return usable;
    }
    private boolean closeToPlayer(Entity e,Vec p,double radius){
        double distance=Math.hypot(p.x-e.x,p.z-e.z);if(distance>radius||Math.abs(p.y-e.y)>1.1)return false;
        // Near a wall, distance alone must not stop a bot on its opposite side.
        int samples=Math.max(1,(int)Math.ceil(distance/.3));for(int i=1;i<=samples;i++)if(collides(e.x+(p.x-e.x)*i/samples,e.y,e.z+(p.z-e.z)*i/samples))return false;return true;
    }
    private boolean followPlayer(Entity e){
        Vec player=players.get(e.follow.character());
        if(player==null){if(e.goal!=null)aim(e,null);e.progress=null;e.holding=false;e.navigation="waiting_player";return false;}
        if(closeToPlayer(e,player,e.follow.distance()+(e.holding?1:0))){if(!e.holding)aim(e,null);e.holding=true;e.progress=null;e.navigation="following_nearby";return false;}
        e.holding=false;
        // Let outstanding worker work finish rather than cancelling it whenever the player moves.
        if(e.pathPending==null&&(!e.followingDetour||e.pathAt>=e.path.size())&&tick>=e.followRefresh&&(e.goal==null||Math.hypot(player.x-e.goal.x,player.z-e.goal.z)>=1||Math.abs(player.y-e.goal.y)>=1.1)){
            e.goal=player;e.pathVersion++;e.nextPlan=tick;e.followRefresh=tick+10;
        }
        if(e.progress==null){e.progress=new Vec(e.x,e.y,e.z);e.progressAt=tick;}
        if(tick-e.progressAt>=40){
            if(e.pathAt<e.path.size()&&Math.hypot(Math.hypot(e.x-e.progress.x,e.z-e.progress.z),e.y-e.progress.y)<.25){e.path=List.of();e.pathAt=0;e.pathVersion++;e.nextPlan=tick;e.recoveryPlans++;e.navigation="replanning_stuck";}
            e.progress=new Vec(e.x,e.y,e.z);e.progressAt=tick;
        }
        if(!e.path.isEmpty()&&e.pathAt>=e.path.size()&&e.pathPending==null)e.nextPlan=Math.min(e.nextPlan,tick+10);
        return true;
    }
    /** Native movement first. A* is a recovery tool after half a second without progress. */
    private boolean directFollow(Entity e){
        if(e.followingDetour){
            if(e.pathAt<e.path.size()||e.pathPending!=null)return false;
            if(!e.path.isEmpty())e.directRetry=tick;
            if(tick<e.directRetry)return false;
            e.followingDetour=false;e.path=List.of();e.pathAt=0;e.directSample=null;
        }
        if(e.directSample==null){e.directSample=new Vec(e.x,e.y,e.z);e.directSampleTick=tick;}
        if(tick-e.directSampleTick>=10){
            double moved=Math.hypot(e.x-e.directSample.x,e.z-e.directSample.z);
            e.directSample=new Vec(e.x,e.y,e.z);e.directSampleTick=tick;
            if(moved<.12&&e.grounded){
                e.followingDetour=true;e.directRetry=tick+60;cancelPath(e);e.path=List.of();e.pathAt=0;
                e.pathVersion++;e.nextPlan=tick;e.recoveryPlans++;e.navigation="following_obstacle";return false;
            }
        }
        // No synthetic jump or displacement: the original dispatcher owns acceleration,
        // diagonal headings, slopes, stairs, gravity, contacts and collisions.
        Vec target=players.get(e.follow.character());if(target==null)return true;
        double dx=target.x-e.x,dz=target.z-e.z,distance=Math.hypot(dx,dz);
        if(distance>1e-5){double speed=travelSpeed(e);e.vx=dx/distance*speed;e.vz=dz/distance*speed;}
        e.navigation="following_direct";return true;
    }
    private void steer(Entity e,boolean held){
        e.vx=e.vz=0;e.routeYaw=null;
        if(e.follow!=null){if(!followPlayer(e)){e.directSample=null;return;}if(actorControllers!=null&&directFollow(e))return;}
        if(!e.patrol.isEmpty()){
            if(tick<e.patrolResume){e.navigation="patrol_pause";return;}
            if(e.patrolResume!=0){e.patrolResume=0;e.patrolAt=(e.patrolAt+1)%e.patrol.size();aim(e,e.patrol.get(e.patrolAt));e.patrolDeadline=tick+600;}
            boolean reached=Math.hypot(e.goal.x-e.x,e.goal.z-e.z)<.3&&Math.abs(e.goal.y-e.y)<.6&&routeArrivalPose(e);
            if(reached||tick>=e.patrolDeadline){e.patrolResume=tick+20;e.path=List.of();e.navigation=reached?"patrol_pause":"patrol_skip";return;}
        }
        if(e.goal==null){e.navigation="idle";if(e.kind.equals("BOT")&&tick<e.inputExpires){double speed=travelSpeed(e);e.vx=e.inputX*speed;e.vz=e.inputZ*speed;e.navigation="controlled";}return;}
        if(Math.hypot(e.goal.x-e.x,e.goal.z-e.z)<.3 && Math.abs(e.goal.y-e.y)<(held?.12:.6)&&routeArrivalPose(e)){e.navigation="arrived";e.path=List.of();e.pathAt=0;e.nextPlan=tick+2;return;}
        if(e.pathSource!=null)try{e.pathSource.checkCurrent();}catch(IllegalArgumentException stale){e.pathSource=null;e.path=List.of();e.pathAt=0;e.pathVersion++;e.nextPlan=tick;}
        if(actorMaps==null){if(tick>=e.nextPlan&&planBudget>0){planBudget--;e.path=findPath(new Vec(e.x,e.y,e.z),e.goal,e.follow!=null);e.pathPoses=List.of();e.pathAt=0;e.nextPlan=e.path.isEmpty()?tick+20:Long.MAX_VALUE;e.navigation=e.path.isEmpty()?"unreachable":"walking";}}
        else{
            if(e.pathPending!=null&&e.pathPending.isDone()){
                try{var result=e.pathPending.join();result.checkCurrent();if(actorControllers!=null&&!result.controllerVerified())throw new IllegalArgumentException("native_route_not_validated");boolean nearStart=Math.hypot(result.from().x()-e.x,result.from().z()-e.z)<(actorControllers==null?1.5:.22)&&(actorControllers==null||Math.abs(result.from().y()-e.y)<.12);if(e.pendingVersion==e.pathVersion&&Objects.equals(result.goal(),e.goal)&&nearStart){e.path=result.points();e.pathPoses=result.poses();e.pathHeadings=result.headings();e.pathActions=result.actions();e.ziplinePathAt=-1;e.routePronePending=null;e.pathSource=result.source();e.pathAt=0;
                    // A native proof covers the executed waypoint sequence. A geometric
                    // visibility shortcut can cross a damaging non-solid contact, so it
                    // cannot rebase that proof. Replan if the live actor left its origin.
                    if(actorControllers==null){double bestDistance=Double.POSITIVE_INFINITY;for(int n=0;n<Math.min(8,e.path.size());n++){Vec point=e.path.get(n);double distance=Math.hypot(point.x-e.x,point.z-e.z);if(routingPose(e)!=ActorPose.PRONE&&result.poses().get(n)==routingPose(e)&&distance<bestDistance&&sameRoutePose(e,0,n)&&walkSegment(e,point)){bestDistance=distance;e.pathAt=n;}}}e.navigation=e.path.isEmpty()?"unreachable":"walking";e.nextPlan=e.path.isEmpty()?tick+20:Long.MAX_VALUE;}else e.nextPlan=tick;}
                catch(RuntimeException unavailable){e.navigation="waiting_navigation";e.nextPlan=tick+5;}
                finally{e.pathPending=null;}
            }
            if(e.pathPending==null&&tick>=e.nextPlan&&planBudget>0){planBudget--;actorPathRequests++;e.pendingVersion=e.pathVersion;try{Vec from=new Vec(e.x,e.y,e.z);e.pathPending=actorControllers!=null?actorMaps.actorNativePath(from,e.goal,routingPose(e),e.follow!=null,e.controller.capture()):e.follow==null?actorMaps.actorPath(from,e.goal,e.pose):actorMaps.actorFollowPath(from,e.goal,e.pose);if(e.path.isEmpty())e.navigation="planning";}catch(RuntimeException full){e.nextPlan=tick+5;e.navigation="waiting_navigation";}}
        }
        // Finish a height transition before entering the next cell. In particular,
        // a falling bot must clear the ceiling of a low doorway before moving through it.
        while(e.pathAt<e.path.size()){
            Vec point=e.path.get(e.pathAt);boolean near=Math.hypot(point.x-e.x,point.z-e.z)<.22&&Math.abs(point.y-e.y)<(held&&actorControllers==null?.003:.12);ActorPose wanted=routePose(e,e.pathAt);
            var cable=routeAction(e,e.pathAt);
            if(actorControllers!=null&&cable!=null){
                if(e.locomotion==12){e.ziplinePathAt=e.pathAt;e.routeYaw=cable.yaw();e.navigation="ziplining";return;}
                if(!near&&e.ziplinePathAt!=e.pathAt){e.routeYaw=cable.yaw();e.navigation="boarding_zipline";return;}
            }
            if(actorControllers!=null){
                boolean prone=wanted==ActorPose.PRONE||e.locomotion==7;if(prone)e.routeYaw=routeHeading(e,e.pathAt);
                boolean ready=e.locomotion==wanted.nativeId()||e.locomotion==5&&wanted!=ActorPose.PRONE;
                boolean facing=wanted!=ActorPose.PRONE||Math.abs(Math.IEEEremainder(e.controllerYaw-routeHeading(e,e.pathAt),360))<2;
                if(near&&(!ready||!facing)){e.navigation="changing_posture";return;}
            }else if(!changePose(e,wanted)&&near){e.path=List.of();e.pathAt=0;e.nextPlan=tick;e.navigation="posture_blocked";return;}
            if(near)e.pathAt++;else break;
        }
        if(e.pathAt>=e.path.size()){if(!e.path.isEmpty())e.nextPlan=Math.min(e.nextPlan,tick+2);return;}
        // Only the composed route permits geometric shortcuts. Native routes must
        // retain the sequence checked against movement, contacts and living state.
        if(actorControllers==null)for(int n=Math.min(e.path.size()-1,e.pathAt+6);n>e.pathAt;n--)if(e.locomotion!=2&&e.locomotion!=4&&e.locomotion!=5&&routingPose(e)!=ActorPose.PRONE&&sameRoutePose(e,e.pathAt,n)&&walkSegment(e,e.path.get(n))){e.pathAt=n;break;}
        Vec target=e.path.get(e.pathAt);double dx=target.x-e.x,dz=target.z-e.z,d=Math.hypot(dx,dz);
        if(actorControllers!=null&&(routePose(e,e.pathAt)==ActorPose.PRONE||e.locomotion==7)){
            e.routeYaw=d>.22?(float)Math.toDegrees(Math.atan2(-dx,dz)):routeHeading(e,e.pathAt);
            if(e.locomotion!=routePose(e,e.pathAt).nativeId()){e.navigation="changing_posture";return;}
        }
        if(Math.abs(target.y-e.y)>(held&&actorControllers==null?.003:.12)&&d<.65){var ladder=ladderAt(e);if(ladder!=null){
            double ax=ladder.x()-e.x,az=ladder.z()-e.z,alignment=Math.hypot(ax,az);
            e.vx=alignment>1e-8?ax/alignment*Math.min(ActorLadders.SPEED,alignment/DT):0;e.vz=alignment>1e-8?az/alignment*Math.min(ActorLadders.SPEED,alignment/DT):0;
            if(actorControllers==null){e.vy=alignment<.003?Math.max(-ActorLadders.SPEED,Math.min(ActorLadders.SPEED,(target.y-e.y)/DT)):0;e.ladder=true;}
            else if(alignment<.003){e.vx=-Math.sin(Math.toRadians(e.controllerYaw));e.vz=Math.cos(Math.toRadians(e.controllerYaw));}
            if(actorControllers!=null&&!e.ladder)e.jumpIntent=true;
            e.navigation="climbing";return;
        }}

        // A distant higher cell can be reached by native stepping along a slope.
        // Jump only after the actual resolver failed to advance in this direction.
        if(routePose(e,e.pathAt)!=ActorPose.PRONE&&target.y>e.y+ActorPose.STEP+1e-4&&d<1.15&&d>1e-8&&e.grounded&&(dx*e.blockedX+dz*e.blockedZ)/d>.8){if(actorControllers!=null||actorMotion instanceof ActorJump)e.jumpIntent=true;else{e.vy=jumpSpeed();e.grounded=false;}}
        if(actorControllers!=null&&e.locomotion==2&&target.y>e.y+.12)e.jumpIntent=e.jumpState.armed();
        if(d>1e-8){double speed=Math.min(travelSpeed(e),d/DT);e.vx=dx/d*speed;e.vz=dz/d*speed;e.navigation=e.locomotion==2||e.locomotion==4?"climbing_ledge":"walking";}
    }
    private boolean routeArrivalPose(Entity e){
        if(actorControllers==null)return true;
        if(e.locomotion==12)return false;
        if(!e.grounded&&e.locomotion!=5)return false;
        if(!e.path.isEmpty()){ActorPose wanted=routePose(e,e.path.size()-1);return e.locomotion==wanted.nativeId()||e.locomotion==5&&wanted!=ActorPose.PRONE;}
        return e.locomotion!=7||!poseClear(e,ActorPose.STAND)&&!poseClear(e,ActorPose.CROUCH);
    }
    private static ActorZipline.Route routeAction(Entity e,int n){return e.pathActions.size()==e.path.size()&&n>=0&&n<e.pathActions.size()?e.pathActions.get(n).orElse(null):null;}
    private static ActorPose routingPose(Entity e){return e.controller!=null&&e.locomotion==7?ActorPose.PRONE:e.pose;}
    private static float routeHeading(Entity e,int n){return e.pathHeadings.size()==e.path.size()?e.pathHeadings.get(n):e.controllerYaw;}
    private static ActorPose routePose(Entity e,int n){return e.pathPoses.size()==e.path.size()?e.pathPoses.get(n):ActorPose.STAND;}
    private static boolean sameRoutePose(Entity e,int first,int last){for(int n=first;n<=last;n++)if(routeAction(e,n)!=null||routePose(e,n)!=routingPose(e))return false;return true;}
    private boolean poseClear(Entity e,ActorPose pose){ActorPose saved=activePose;activePose=pose;try{return !collides(e.x,e.y,e.z);}finally{activePose=saved;}}
    private boolean changePose(Entity e,ActorPose pose){if(e.pose==pose)return true;if(!poseClear(e,pose))return false;e.pose=pose;activePose=pose;return true;}
    private ActorLadderGuide.Contact ladderRouteAt(Entity e,Vec point){
        try{return ActorLadders.routeContact(new BlockShapes.Voxels(){public int voxel(Block p){actorBudget.use();return World.this.voxel(p);}public TilePayload.Tile tile(Block p){return World.this.tile(p);}},e.scene,point,actorBudget);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new ActorScene.Deferred();}
    }
    private ActorLadders.Contact ladderAt(Entity e){
        try{return ActorLadders.contact(new BlockShapes.Voxels(){public int voxel(Block p){actorBudget.use();return World.this.voxel(p);}public TilePayload.Tile tile(Block p){return World.this.tile(p);}},new Vec(e.x,e.y,e.z));}
        catch(InterruptedException impossible){Thread.currentThread().interrupt();throw new ActorScene.Deferred();}
    }
    private boolean walkSegment(Entity e,Vec p){
        if(Math.abs(p.y-e.y)>.12)return false;double d=Math.hypot(p.x-e.x,p.z-e.z);if(d>6)return false;
        int samples=Math.max(1,(int)Math.ceil(d/.15));for(int i=1;i<=samples;i++){double x=e.x+(p.x-e.x)*i/samples,z=e.z+(p.z-e.z)*i/samples;if(collides(x,e.y,z)||!collides(x,e.y-.08,z))return false;}return true;
    }
    private record Node(Block cell,int g,int score){}
    private boolean stand(Block b){return b.y>=0&&b.y<=254&&!solid(b.x,b.y,b.z)&&!solid(b.x,b.y+1,b.z)&&solid(b.x,b.y-1,b.z);}
    public List<Vec> findPath(Vec from,Vec to){return findPath(from,to,false);}
    private List<Vec> findPath(Vec from,Vec to,boolean partial){
        Block start=new Block((int)Math.floor(from.x),(int)Math.round(from.y),(int)Math.floor(from.z));
        Block end=new Block((int)Math.floor(to.x),(int)Math.round(to.y),(int)Math.floor(to.z));
        if(!partial&&Math.abs(start.x-end.x)+Math.abs(start.z-end.z)>64)return List.of();
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::score));
        Map<Block,Integer> costs=new HashMap<>();Map<Block,Block> parents=new HashMap<>();
        open.add(new Node(start,0,0));costs.put(start,0);int visited=0;Block best=start;int bestScore=(Math.abs(start.x-end.x)+Math.abs(start.z-end.z))*10+Math.abs(start.y-end.y)*4;
        while(!open.isEmpty()&&visited++<512){Node node=open.remove();Block b=node.cell;
            if(node.g!=costs.getOrDefault(b,Integer.MAX_VALUE))continue;
            int score=(Math.abs(b.x-end.x)+Math.abs(b.z-end.z))*10+Math.abs(b.y-end.y)*4;if(score<bestScore){best=b;bestScore=score;}
            if(b.equals(end)||partial&&b.x==end.x&&b.z==end.z&&Math.abs(b.y-to.y)<1.1)return path(parents,start,b);
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}})for(int dy:new int[]{0,1,-1}){
                Block next=new Block(b.x+d[0],b.y+dy,b.z+d[1]);
                if(Math.abs(next.x-start.x)+Math.abs(next.z-start.z)>64||!stand(next))continue;
                if(dy==1&&solid(b.x,b.y+2,b.z))continue;
                if(d[0]!=0&&d[1]!=0&&(!stand(new Block(b.x+d[0],b.y,b.z))||!stand(new Block(b.x,b.y,b.z+d[1]))))continue;
                int cost=node.g+(d[0]!=0&&d[1]!=0?14:10)+Math.abs(dy)*20;if(cost>=costs.getOrDefault(next,Integer.MAX_VALUE))continue;
                costs.put(next,cost);parents.put(next,b);
                int hx=Math.abs(next.x-end.x),hz=Math.abs(next.z-end.z);int h=10*Math.max(hx,hz)+4*Math.min(hx,hz);
                open.add(new Node(next,cost,cost+h));break;
            }
        }
        return partial?path(parents,start,best):List.of();
    }
    private static List<Vec> path(Map<Block,Block> parents,Block start,Block end){ArrayList<Vec> path=new ArrayList<>();for(Block c=end;!c.equals(start);c=parents.get(c))path.add(new Vec(c.x+.5,c.y,c.z+.5));Collections.reverse(path);return List.copyOf(path);}
    public Snapshot snapshot(){return new Snapshot(tick,entities.values().stream().map(Entity::view).toList(),overlays.voxelCount(),simulationFailures,grenades.projectileCount(),grenades.completionCount(),actorControllers==null?0:actorControllers.mineStateCount());}
}
