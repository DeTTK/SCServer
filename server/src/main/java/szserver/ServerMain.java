package szserver;

import szserver.client.GameItems;
import szserver.client.NativeSceneDefaults;
import szserver.client.SceneItemValues;
import szserver.commands.CommandService;
import szserver.http.AdminHttpServer;
import szserver.persistence.Journal;
import szserver.world.*;
import szserver.net.GameServer;
import szserver.net.DevelopmentBackend;
import szserver.net.DevelopmentJsonServer;
import szserver.net.PlayerRules;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    public static void main(String[] args)throws Exception{
        Path configPath=args.length==0?Path.of("server.properties"):Path.of(args[0]);
        Properties p=new Properties();try(var in=Files.newInputStream(configPath)){p.load(in);}
        PortableWorld.configure(p,configPath.toAbsolutePath().getParent());
        MapLimits mapLimits=MapLimits.configured(p);
        var automaticCheckpointSettings=szserver.persistence.AutomaticCheckpoint.Settings.configured(p);
        Path root=configPath.toAbsolutePath().getParent();
        Path runtime=Path.of(p.getProperty("client.runtime")),registry=Path.of(p.getProperty("item.registry"));
        Path bundledConfigs=root.resolve("resources/configs.xeon");
        if(p.containsKey("assets.configs")||Files.isRegularFile(bundledConfigs)){
            Path overrides=Path.of(p.getProperty("assets.overrides",root.resolve("resources/overrides").toString()));
            Path configs=Path.of(p.getProperty("assets.configs",bundledConfigs.toString()));
            for(String key:List.of("world.objectResources","world.actorResources"))if(p.containsKey(key))szserver.util.GameResources.mount(Path.of(p.getProperty(key)),configs,overrides.resolve("configs"));
            Path itemConfigs=root.resolve("resources/configs");
            if(!szserver.util.GameResources.mounted(itemConfigs))szserver.util.GameResources.mount(itemConfigs,configs,overrides.resolve("configs"));
            if(p.containsKey("assets.boxes"))szserver.util.GameResources.mount(Path.of(p.getProperty("world.collisionBoxesRoot")),Path.of(p.getProperty("assets.boxes")),overrides.resolve("boxes"));
        }

        Path data=root.resolve(p.getProperty("data.directory","var")).normalize();Files.createDirectories(data);
        CountDownLatch stop=new CountDownLatch(1),closed=new CountDownLatch(1);
        var lifecycle=new szserver.util.ServerLifecycle(stop);
        Thread hook=new Thread(()->{lifecycle.signal();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"shutdown-request");
        Runtime.getRuntime().addShutdownHook(hook);
        try(var dataLease=szserver.util.ServerDataLease.acquire(data,configPath);var storage=szserver.util.StorageHealth.configured(data,p)){
        storage.requireReady();
        szserver.inventory.NativeGridMigration.repair(data,root.resolve("fixtures/item-definitions.json"),runtime,registry);
        var launcherSettings=szserver.net.LauncherBackend.Settings.configured(p,root,data);
        if(launcherSettings.enabled()&&!Boolean.parseBoolean(p.getProperty("game.enabled","false")))throw new IllegalArgumentException("launcher_requires_native_backend");
        Path importMarker=data.resolve("legacy-import.json");com.google.gson.JsonObject imported=null;
        if(Files.exists(importMarker)){if(Files.size(importMarker)>65536)throw new IllegalArgumentException("legacy_import_marker_limit");imported=szserver.util.Json.parse(Files.readAllBytes(importMarker));if(!imported.has("status")||!imported.get("status").getAsString().equals("PASS"))throw new IllegalArgumentException("legacy_import_not_complete");}
        Path tokenFile=data.resolve("admin-token.txt");
        if(!Files.exists(tokenFile)){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);Files.writeString(tokenFile,HexFormat.of().formatHex(bytes),StandardOpenOption.CREATE_NEW);}
        String token=Files.readString(tokenFile).trim();if(token.length()<32)throw new IllegalArgumentException("admin_token_too_short");
        String mode=p.getProperty("world.mode","flat");
        if(!Set.of("flat","regions").contains(mode))throw new IllegalArgumentException("unknown_world_mode");
        String missingChunks=p.getProperty("world.missingChunks","blocked");if(!Set.of("blocked","flat").contains(missingChunks))throw new IllegalArgumentException("world_missing_chunks_mode");
        var startup=new szserver.util.StartupStages();
        try(SceneItemValues sceneItems=mode.equals("regions")&&Boolean.parseBoolean(p.getProperty("world.sceneObjects","false"))?startup.prepare("sceneItems",()->new SceneItemValues(runtime,registry,root.resolve(p.getProperty("client.manifest","results/runtime-manifest.json")))):null){
        SceneRegistry sceneRegistry=mode.equals("regions")&&Boolean.parseBoolean(p.getProperty("world.sceneObjects","false"))?startup.prepare("sceneRegistry",()->new SceneRegistry(Path.of(p.getProperty("world.regions")),Path.of(p.getProperty("world.objectRegistry")),Path.of(p.getProperty("world.objectCodecs")),p.containsKey("world.objectResources")?Path.of(p.getProperty("world.objectResources")):null)):null;
        Terrain terrain=startup.prepare("terrain",()->mode.equals("regions")?regionTerrain(p,sceneRegistry):Terrain.flat(1));
        if(!mode.equals("regions")&&!p.getProperty("world.objectTemplateMap","").isBlank())throw new IllegalArgumentException("scene_template_requires_region_world");
        if(imported!=null&&!imported.get("worldIdentity").getAsString().equals(terrain instanceof RegionTerrain regions?regions.identity():"flat:arena-v1")){terrain.close();throw new IllegalArgumentException("legacy_import_world_identity_mismatch");}
        double[] spawn={Double.parseDouble(p.getProperty("world.spawn.x","0.5")),Double.parseDouble(p.getProperty("world.spawn.y",mode.equals("regions")?"64":"1")),Double.parseDouble(p.getProperty("world.spawn.z","0.5"))};
        for(double value:spawn)if(!Double.isFinite(value)||Math.abs(value)>MapBounds.HORIZONTAL)throw new IllegalArgumentException("invalid_world_spawn");
        if(spawn[1]<1||spawn[1]>254)throw new IllegalArgumentException("invalid_world_spawn_height");
        if(terrain instanceof RegionTerrain){
            if(!prepareSpawn(terrain,spawn,10000)){terrain.close();throw new IllegalArgumentException("spawn_terrain_unavailable");}
            for(int bx=(int)Math.floor(spawn[0]-.3);bx<=(int)Math.floor(spawn[0]+.3);bx++)for(int bz=(int)Math.floor(spawn[2]-.3);bz<=(int)Math.floor(spawn[2]+.3);bz++)for(int by=(int)Math.floor(spawn[1]);by<spawn[1]+1.8;by++)
                if(terrain.solid(bx,by,bz)){terrain.close();throw new IllegalArgumentException("spawn_inside_terrain_configure_world_spawn");}
        }
        Set<UUID> worldEditors=new HashSet<>();for(String value:p.getProperty("game.worldEditCharacters","").split(","))if(!value.isBlank())worldEditors.add(UUID.fromString(value.strip()));
        try(terrain;GameItems items=startup.prepare("gameItems",()->new GameItems(runtime,registry,root.resolve(p.getProperty("client.manifest","results/runtime-manifest.json"))));Journal journal=startup.prepare("worldJournal",()->p.containsKey("world.directory")?szserver.persistence.PortableReceipts.open(data):Journal.streaming(data.resolve("world.sjournal")));szserver.client.NativeActorMotion actorMotion=startup.prepare("actorMotion",()->new szserver.client.NativeActorMotion(runtime));WorldLoop world=new WorldLoop(initialWorld(terrain,mapLimits,actorMotion,p,root,startup,items));NativeSceneDefaults sceneDefaults=sceneRegistry!=null&&Boolean.parseBoolean(p.getProperty("world.sceneDefaults","true"))?new NativeSceneDefaults(runtime,p.containsKey("world.objectResources")?Path.of(p.getProperty("world.objectResources")):NativeSceneDefaults.resourceRoot(Path.of(p.getProperty("world.objectRegistry"))),sceneRegistry.target(),root.resolve(p.getProperty("world.sceneDefaultBindings","fixtures/native-scene-behaviour-bindings.json"))):null;szserver.commands.ResidentServerMap residentMap=startup.prepare("worldRecovery",()->szserver.commands.ResidentServerMap.open(data,p,world,journal,items,sceneDefaults,actorMotion,sceneRegistry==null?null:sceneRegistry.target()));CommandService commands=residentMap.commands()){
            commands.configureActorEquipment(root);
            // Finish all durable/backend/native preparation before any simulation or listener starts.
            try(GameServer game=Boolean.parseBoolean(p.getProperty("game.enabled","false"))?startup.prepare("native",()->new GameServer(root,data,runtime,registry,Integer.parseInt(p.getProperty("game.httpPort","29646")),Integer.parseInt(p.getProperty("game.gatewayPort","29647")),Integer.parseInt(p.getProperty("game.worldPort","29648")),world,terrain,spawn,commands,worldEditors,PlayerRules.configured(p),szserver.net.ConnectionRoutes.configured(p,Integer.parseInt(p.getProperty("game.gatewayPort","29647")),Integer.parseInt(p.getProperty("game.worldPort","29648"))),szserver.net.ChunkStreamingSettings.configured(p),szserver.net.FpvPlacementSettings.configured(p,registry),false)):null;MapObstructionAccess actorMap=game==null?standaloneActorMap(world,terrain,p,registry):null;szserver.net.LauncherBackend launcher=launcherSettings.enabled()?startup.prepare("launcher",()->new szserver.net.LauncherBackend(game.developmentAccounts(),launcherSettings,false)):null;DevelopmentBackend development=Boolean.parseBoolean(p.getProperty("development.enabled","false"))?startup.prepare("developmentStore",()->new DevelopmentBackend(game,root,data,terrain instanceof RegionTerrain regions?regions.identity():"flat:arena-v1",commands)):null;DevelopmentJsonServer developer=development==null?null:startup.prepare("developmentTransport",()->new DevelopmentJsonServer(new InetSocketAddress(p.getProperty("development.host","127.0.0.1"),Integer.parseInt(p.getProperty("development.port","29649"))),development.service(),false));szserver.persistence.AutomaticCheckpoint automaticCheckpoint=new szserver.persistence.AutomaticCheckpoint(commands,automaticCheckpointSettings,false);AdminHttpServer http=new AdminHttpServer(new InetSocketAddress(p.getProperty("http.host","127.0.0.1"),Integer.parseInt(p.getProperty("http.port","29645"))),token,commands,world,()->{
                Map<String,Object> health=new LinkedHashMap<>();
                boolean healthy=commands.healthy()&&(launcher==null||launcher.healthy())&&Boolean.TRUE.equals(world.metrics().get("running"))&&Boolean.TRUE.equals(world.metrics().get("ownerServiceHealthy"))&&(game==null||game.healthy())&&(development==null||development.healthy())&&(developer==null||developer.healthy())&&(!(terrain instanceof RegionTerrain regions)||regions.healthy());
                var storageStatus=storage.metrics();
                health.put("healthy",healthy);health.put("ready",healthy&&Boolean.TRUE.equals(storageStatus.get("ready"))&&(game==null||game.ready())&&(!(terrain instanceof RegionTerrain regions)||regions.ready()));
                health.put("storage",storageStatus);
                health.put("server",lifecycle.status());if(lifecycle.stopping())health.put("ready",false);
                health.put("startup",startup.metrics());health.put("automaticCheckpoint",automaticCheckpoint.metrics());
                if(launcher!=null)health.put("launcher",launcher.metrics());
                if(developer!=null){health.put("development",development.metrics());health.put("developmentTransport",developer.metrics());}
                health.put("protocol","admin-api-v1");health.put("nativeClientLogin",game!=null);if(game!=null)health.put("game",game.metrics());health.put("world",world.metrics());health.put("commands",commands.metrics());health.put("journal",journal.metrics());if(sceneItems!=null)health.put("sceneItems",sceneItems.metrics());
                if(terrain instanceof RegionTerrain regions){health.put("terrain",regions.metrics());health.put("sceneObjects",regions.sceneMetrics());}return health;
            },launcher)){
                http.bindLifecycle(lifecycle);
                if(game!=null)http.bindScreenshots(game);
                if(game!=null)http.bindAccounts(game.developmentAccounts());
                if(actorMap!=null)world.bootstrap(w->{w.actorMaps(actorMap);return true;});
                if(residentMap.enabled()){startup.start("residentPreparation",world::startPrepared);world.ownerServiceReady().get(60,java.util.concurrent.TimeUnit.SECONDS);}
                startup.prepare("actorControllerGeometry",()->prepareActorControllers(world,p));
                startup.prepare("actorNavigation",()->(world.beforeStart()?world.bootstrap(World::prepareActorNavigation):world.submit(World::prepareActorNavigation).thenCompose(future->future)).get(60,java.util.concurrent.TimeUnit.SECONDS));
                startup.start("world",()->{if(residentMap.enabled())world.resumeSimulation();else world.start();});
                if(game!=null)startup.start("native",game::start);
                if(launcher!=null)startup.start("launcher",launcher::start);
                if(developer!=null)startup.start("development",developer::start);
                String historyPacking=p.getProperty("world.history.pack.enabled","true").strip();if(!Set.of("true","false").contains(historyPacking))throw new IllegalArgumentException("invalid_world_history_pack_enabled");
                startup.start("worldHistoryPacking",()->residentMap.activeJournal(journal).configurePacking(Boolean.parseBoolean(historyPacking),Long.parseLong(p.getProperty("world.history.pack.thresholdBytes","33554432"))));
                startup.start("automaticCheckpoint",automaticCheckpoint::start);
                if(Boolean.parseBoolean(p.getProperty("world.nativeCommit.enabled","true")))startup.start("nativeMapCommits",commands::startNativeMapCommits);
                startup.start("admin",http::start);startup.running();System.out.println("SZ Java world server listening on "+p.getProperty("http.host","127.0.0.1")+":"+http.port());
                System.out.println("Admin token file: "+tokenFile+" (token is not printed)");
                System.out.println("World simulation: "+p.getProperty("world.mode","flat")+", 20 Hz; native arena enabled="+(game!=null));
                if(developer!=null)System.out.println("Development JSON loopback TCP="+developer.port());
                stop.await();
            }
        }
        }
        }finally{closed.countDown();try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}}
    }
    static World initialWorld(Terrain terrain,MapLimits limits,szserver.client.NativeActorMotion motion,Properties p,Path root,szserver.util.StartupStages startup,GameItems items)throws Exception{
        String mode=p.getProperty("world.actorController","composed").strip();if(!Set.of("composed","native").contains(mode))throw new IllegalArgumentException("invalid_world_actor_controller");
        World world=new World(terrain,limits);world.actorMotion(motion);
        if(mode.equals("native")){
            Path resources=p.containsKey("world.actorResources")?Path.of(p.getProperty("world.actorResources")):p.containsKey("world.objectResources")?Path.of(p.getProperty("world.objectResources")):NativeSceneDefaults.resourceRoot(Path.of(p.getProperty("world.objectRegistry")));
            Path definitions=root.resolve(p.getProperty("world.actorItemDefinitions","fixtures/item-definitions.json"));
            var engine=startup.prepare("actorController",()->motion.controllerEngine(resources,definitions,Path.of(p.getProperty("item.registry"))));
            engine.legacyEquipmentStats(presentation->items.actorStats(root,presentation));world.actorControllers(engine);
        }return world;
    }
    static int prepareActorControllers(WorldLoop world,Properties p)throws Exception{
        long timeout=Long.parseLong(p.getProperty("world.actorMigrationTimeoutMillis","30000"));if(timeout<100||timeout>120000)throw new IllegalArgumentException("invalid_actor_migration_timeout");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
        for(;;){int pending=world.beforeStart()?world.bootstrap(World::prepareActorControllers):world.submit(World::prepareActorControllers).get(5,java.util.concurrent.TimeUnit.SECONDS);if(pending==0)return 0;if(System.nanoTime()>=deadline)throw new IllegalStateException("actor_controller_migration_geometry_unavailable:"+pending);Thread.sleep(25);}
    }
    static MapObstructionAccess standaloneActorMap(WorldLoop world,Terrain terrain,Properties p,Path registry){var settings=szserver.net.FpvPlacementSettings.configured(p,registry);return new MapObstructionAccess(world,terrain,settings.boxes(),settings.baseBytes(),settings.editedBytes());}
    static RegionTerrain regionTerrain(Properties p,SceneRegistry scenes)throws Exception{
        String alternate=p.getProperty("world.objectTemplateMap","").strip();RegionTerrain.CatalogSource source=null;
        if(!alternate.isEmpty()){
            if(scenes==null)throw new IllegalArgumentException("scene_template_requires_scene_objects");
            Path path=Path.of(alternate);source=new RegionTerrain.CatalogSource(path,new SceneRegistry(path,Path.of(p.getProperty("world.objectRegistry")),Path.of(p.getProperty("world.objectCodecs"))));
        }
        return new RegionTerrain(Path.of(p.getProperty("world.regions")),Integer.parseInt(p.getProperty("world.cacheChunks","256")),p.getProperty("world.missingChunks","blocked").equals("flat")?Integer.parseInt(p.getProperty("world.flatSurface","63")):null,scenes,source,p.getProperty("world.bindingNamespace"));
    }
    /** Warm every chunk touched by the player's body, including chunk-border spawns. */
    static boolean prepareSpawn(Terrain terrain,double[] spawn,long timeoutMillis)throws InterruptedException{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(true){
            boolean ready=true;
            for(int x=(int)Math.floor(spawn[0]-.3);x<=(int)Math.floor(spawn[0]+.3);x++)for(int z=(int)Math.floor(spawn[2]-.3);z<=(int)Math.floor(spawn[2]+.3);z++)
                if(!terrain.ready(x,z))ready=false;
            if(ready)return true;
            if(System.nanoTime()>=deadline)return false;
            Thread.sleep(10);
        }
    }
}
