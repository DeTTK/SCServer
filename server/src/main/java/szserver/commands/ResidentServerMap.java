package szserver.commands;

import com.google.gson.*;
import szserver.client.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import szserver.world.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Checked offline migration and production bootstrap. Native storage becomes owned by WorldLoop. */
public final class ResidentServerMap implements AutoCloseable {
    private final CommandService commands;private final RegionSnapshotStore storage;private final ResidentMapBootstrap.Bundle bundle;
    private Journal separatedJournal;
    private final AtomicBoolean claimed=new AtomicBoolean();private final AtomicReference<ResidentMapRuntime> runtime=new AtomicReference<>();
    public Journal activeJournal(Journal original){return separatedJournal==null?original:separatedJournal;}
    public CommandService commands(){return commands;}
    public ResidentCharacterState characters(){return bundle==null?null:bundle.characters();}
    public boolean enabled(){return storage!=null;}
    private ResidentServerMap(CommandService commands,RegionSnapshotStore storage,ResidentMapBootstrap.Bundle bundle){this.commands=commands;this.storage=storage;this.bundle=bundle;}
    public static ResidentServerMap open(Path data,Properties properties,WorldLoop world,Journal journal,GameItems items,SceneSeeds seeds,NativeActorMotion motion,SceneRegistry.Manifest registry)throws Exception{
        String flag=properties.getProperty("world.resident.enabled","false");if(!Set.of("true","false").contains(flag))throw new IllegalArgumentException("invalid_resident_enabled");
        if(flag.equals("false"))return new ResidentServerMap(new CommandService(world,journal,items,seeds),null,null);
        if(!(world.bootstrap(World::checkpointTerrain) instanceof RegionTerrain terrain))throw new IllegalArgumentException("resident_server_requires_region_map");
        Set<Journal.Prefix> cuts=new HashSet<>();
        Path marker=data.resolve("resident-map.json"),map=data.resolve("resident-map");JsonObject receipt;
        if(!Files.exists(marker)){
            try(CommandService legacy=new CommandService(world,journal,items,seeds)){
                var frozen=ResidentLegacyMap.freeze(world);String baseline=terrain.liveCommits().generation(),sourceBinding=terrain.checkpointBinding();
                try(var target=new RegionSnapshotStore(map,frozen.binding(),frozen.originalBase())){
                    if(target.publishedStateRevision()==0)legacy.migrateResidentMap(frozen,target,16*1024*1024,progress->{}).get(30,TimeUnit.MINUTES);
                }
                // Verify with a newly opened root; migration reads must never permit fallback after exposure.
                try(var target=new RegionSnapshotStore(map,frozen.binding(),frozen.originalBase());var checked=ResidentMapBootstrap.load(target,data,terrain.identity(),terrain.sceneRegistryDigest(),journal)){
                    receipt=Json.tree(Map.of("format","resident-server-map-v1","binding",frozen.binding(),"world",terrain.identity(),"registry",terrain.sceneRegistryDigest(),"sourceBinding",sourceBinding,"nativeBaseline",baseline,"migrationRevision",checked.history().revision()));
                }
                durableMarker(marker,receipt);
            }
            world.replaceBootstrap(world.bootstrap(World::residentBootstrapBase));
        }else{
            if(Files.size(marker)>65536)throw new IllegalArgumentException("resident_map_marker_limit");receipt=Json.parse(Files.readAllBytes(marker));
            if(!Json.text(receipt,"format",64).equals("resident-server-map-v1")||!Json.text(receipt,"world",4096).equals(terrain.identity())||!text(receipt,"registry",4096).equals(terrain.sceneRegistryDigest()))throw new IllegalArgumentException("resident_map_marker_binding");
            String baseline=text(receipt,"nativeBaseline",64);
            if(!baseline.isEmpty()){
                if(terrain.nativeCommitBinding()==null)throw new IllegalArgumentException("resident_baseline_unavailable");
                try(var commits=new NativeChunkCommits(data,terrain.nativeCommitBinding(),terrain.identity(),journal)){var pinned=commits.indexAt(baseline);cuts.add(commits.readView().prefix());cuts.add(pinned.prefix());terrain.prepareLive(pinned).get(60,TimeUnit.SECONDS).publish();terrain.archiveCommits(pinned);}
            }
            if(!Objects.equals(Json.text(receipt,"sourceBinding",4096),terrain.checkpointBinding()))throw new IllegalArgumentException("resident_authored_source_changed");
        }
        RegionSnapshotStore disk=new RegionSnapshotStore(map,Json.text(receipt,"binding",4096),ResidentLegacyMap.originalBase(terrain));ResidentMapBootstrap.Bundle loaded=null;CommandService service=null;Journal activeJournal=journal;
        try{
            loaded=ResidentMapBootstrap.load(disk,data,terrain.identity(),terrain.sceneRegistryDigest(),journal);
            if(properties.containsKey("world.directory")&&!properties.getProperty("world.bindingNamespace","").startsWith("world-v2/")&&journal.receipts()==null&&journal.durablePrefix().get().records()>0){
                cuts.add(loaded.binding().source());
                var previous=disk.previousState(ResidentCommandCheckpoint::binding);
                if(previous!=null)cuts.add(previous.source());
                cuts.add(terrain.liveCommits().prefix());
                activeJournal=szserver.persistence.PortableReceipts.separate(data,journal,cuts,loaded.binding().source());
            }
            service=new CommandService(world,activeJournal,items,32L*1024*1024,seeds,loaded);
            var result=new ResidentServerMap(service,disk,loaded);if(activeJournal!=journal)result.separatedJournal=activeJournal;
            service.enableResidentRuntimeHistory(loaded.receipts(),result.runtime::get).get(10,TimeUnit.SECONDS);Set<String> names=new HashSet<>();for(String name:disk.stateIndex().files().keySet())if(name.equals(ResidentCommandCheckpoint.ROOT)||name.equals(ResidentCommandIndex.ROOT)||name.startsWith("command-page-")||name.startsWith("command-index-page-"))names.add(name);
            var settings=new ResidentMapRuntime.Settings(Integer.parseInt(properties.getProperty("world.resident.chunks","2048")),Long.parseLong(properties.getProperty("world.resident.bytes","536870912")),Integer.parseInt(properties.getProperty("world.resident.radius","8")),8,4,TimeUnit.SECONDS.toNanos(Long.parseLong(properties.getProperty("world.resident.saveSeconds","300"))),TimeUnit.SECONDS.toNanos(Long.parseLong(properties.getProperty("world.resident.idleSeconds","60"))));
            var codec=motion.residentChunkCodec();var historyRoot=loaded.history();CommandService ownerCommands=service;
            world.ownerService(owner->{var history=new ResidentOperationLog(historyRoot,64L*1024*1024);var cohort=new ResidentCommandCohort(history,ownerCommands,names,result.characters());var active=new ResidentMapRuntime(disk,codec,settings,delta->owner.validateBlock(delta.position(),delta.packed()>>>4,delta.packed()&15),change->{},history,historyRoot.revision(),registry,cohort);cohort.install(active,world);result.runtime.set(active);result.claimed.set(true);return active;});
            return result;
        }catch(Throwable error){if(service!=null)try{service.close();}catch(Throwable close){error.addSuppressed(close);}if(loaded!=null)loaded.close();if(activeJournal!=journal)activeJournal.close();disk.close();throw error;}
    }
    private static void durableMarker(Path marker,JsonObject receipt)throws Exception{
        Path temporary=marker.resolveSibling("resident-map.pending.json");byte[] bytes=Json.bytes(receipt);try(var file=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE);PortableWorld.syncDirectory(marker.getParent());
    }
    private static String text(JsonObject object,String name,int max){var field=object.get(name);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isString())throw new IllegalArgumentException("resident_marker_string:"+name);String value=field.getAsString();if(value.length()>max||value.indexOf('\0')>=0)throw new IllegalArgumentException("resident_marker_string:"+name);return value;}
    @Override public void close()throws Exception{if(separatedJournal!=null)separatedJournal.close();if(bundle!=null)bundle.close();if(storage!=null&&!claimed.get())storage.close();}
}
