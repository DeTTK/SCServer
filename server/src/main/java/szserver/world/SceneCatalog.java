package szserver.world;

import szserver.util.Workers;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

/** Read-only global SZR scene index, independent of the terrain cache. Never scans on gameplay owners. */
final class SceneCatalog implements AutoCloseable {
    static final int MAX_REGIONS=65536,MAX_IDENTITIES=1_000_000;
    static final long MAX_TEMPLATE_BYTES=64L*1024*1024;
    private record Index(Map<UUID,Terrain.SceneHome> homes,Map<String,ScenePayload.Entry> templates,Set<UUID> shared,long bytes,Map<Long,Map<UUID,NativePortal>> portals,long portalBytes,Map<UUID,Long> portalRanks,long sqlRows,Map<UUID,NativePortal> portalIds,Map<String,NativePortal> portalTemplates,Map<UUID,Terrain.SceneHome> nativeHomes,Map<UUID,SceneCullingVolume> cullingVolumes,Map<UUID,Set<Long>> cullingReferences,Set<Long> cullingUnreviewedReferences,long cullingReferenceBytes){}
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.szr");
    private final ThreadPoolExecutor loader=Workers.pool("scene-catalog",1,1);
    private final SceneRegistry registry;
    private final AtomicLong chunks=new AtomicLong(),objects=new AtomicLong();
    private volatile Index index;
    private final CompletableFuture<Boolean> loaded=new CompletableFuture<>();
    private volatile String error="";
    private volatile boolean stopped;
    private volatile boolean failed;
    SceneCatalog(Path root,SceneRegistry registry){this.registry=registry.fork();loader.execute(()->scan(root,this.registry));}
    Terrain.SceneHome home(UUID id){return current().homes.get(id);}
    Terrain.SceneHome nativeHome(UUID id){return current().nativeHomes.get(id);}
    Map<UUID,SceneCullingVolume> cullingVolumes(){return current().cullingVolumes;}
    Map<UUID,Set<Long>> cullingReferences(){return current().cullingReferences;}
    Set<Long> cullingUnreviewedReferences(){return current().cullingUnreviewedReferences;}
    boolean portalReady(){if(!error.isEmpty())throw new IllegalArgumentException("scene_catalog_failed:"+error);return index!=null;}
    Map<UUID,NativePortal> portals(int x,int z){Index ready=index;return ready==null?Map.of():ready.portals.getOrDefault(BlockOverlays.key(x,z),Map.of());}
    long portalOrder(UUID id){return current().portalRanks.getOrDefault(id,Long.MAX_VALUE);}
    NativePortal basePortal(UUID id){return current().portalIds.get(id);}
    Map<Long,Map<UUID,NativePortal>> portalChunks(){return current().portals;}
    void awaitReady()throws Exception{SceneCollision.worker();loaded.get();}
    NativePortal portalTemplate(String asset){return current().portalTemplates.get(asset);}
    boolean shared(UUID id){return current().shared.contains(id);}
    Set<Long> nativeHomes(UUID id){Index ready=current();Set<Long> copies=placementCopies.get(id);if(copies!=null)return copies;Terrain.SceneHome home=ready.nativeHomes.get(id);return home==null?Set.of():Set.of(BlockOverlays.key(home.x(),home.z()));}
    private volatile Map<UUID,Set<Long>> placementCopies=Map.of();
    ScenePayload.Entry template(String asset){return current().templates.get(asset);}
    private Index current(){Index ready=index;if(ready!=null)return ready;if(stopped)throw new IllegalArgumentException("scene_catalog_stopped");if(!error.isEmpty())throw new IllegalArgumentException("scene_catalog_failed:"+error);throw new IllegalArgumentException("terrain_not_ready_retry");}
    private void scan(Path root,SceneRegistry registry){
        try{
            SqlSceneCatalog sql=SqlSceneCatalog.read(root,registry.sourceDigest());Map<String,Integer> templatePriorities=new HashMap<>();Map<UUID,Long> portalRanks=new HashMap<>();long rank=0;
            List<Path> files=new ArrayList<>();try(var paths=Files.newDirectoryStream(root,"r.*.*.szr")){for(Path file:paths){if(files.size()>=MAX_REGIONS)throw new IOException("scene_catalog_region_limit");files.add(file);}}files.sort(Comparator.comparing(path->path.getFileName().toString()));
            Map<UUID,Terrain.SceneHome> homes=new HashMap<>();Map<UUID,SceneCullingVolume> cullingVolumes=new HashMap<>();Map<UUID,Set<Long>> cullingReferences=new HashMap<>();Set<Long> cullingUnreviewedReferences=new HashSet<>();long cullingReferenceBytes=0;Map<String,ScenePayload.Entry> templates=new HashMap<>();Set<UUID> shared=new HashSet<>();long bytes=0,portalBytes=0;Map<Long,Map<UUID,NativePortal>> portals=new HashMap<>();Map<UUID,NativePortal> portalIds=new LinkedHashMap<>();
            Map<UUID,Set<Long>> copies=new HashMap<>();long placementBytes=0;
            for(Path file:files){Matcher name=REGION.matcher(file.getFileName().toString());if(!name.matches())throw new IOException("scene_catalog_region_name");int rx=Integer.parseInt(name.group(1)),rz=Integer.parseInt(name.group(2));if(rx<MapBounds.REGION_MIN||rx>MapBounds.REGION_MAX||rz<MapBounds.REGION_MIN||rz>MapBounds.REGION_MAX)throw new IOException("scene_catalog_region_bounds");
                try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)){
                    RegionTerrain.regionHeader(channel,rx,rz);
                    for(int cell=0;cell<1024;cell++){
                        if(Thread.currentThread().isInterrupted()||stopped)throw new InterruptedException("scene_catalog_stopped");
                        byte[] record=RegionTerrain.regionRecord(channel,cell);if(record==null)continue;
                        int x=Math.addExact(Math.multiplyExact(rx,32),cell%32),z=Math.addExact(Math.multiplyExact(rz,32),cell/32);
                        ByteBuffer header=ByteBuffer.wrap(record);if(header.getInt()!=x||header.getInt()!=z)throw new IOException("scene_catalog_chunk_coordinate");
                        ScenePayload.Channels projected=registry.project(record,RegionTerrain.capturedSceneChannels(record));
                        Map<UUID,ScenePayload.Entry> entries=ScenePayload.index(projected,registry.target());Terrain.SceneHome chunkHome=new Terrain.SceneHome(x,z);
                        for(var group:entries.values()){
                            if(group.occurrences().size()>1)shared.add(group.object().identity());
                            for(var occurrence:group.occurrences()){
                            ScenePayload.Entry entry=new ScenePayload.Entry(occurrence.container(),occurrence.object());
                            UUID id=entry.object().identity();Terrain.SceneHome home=chunkHome,previous=homes.putIfAbsent(id,home);
                            if(previous!=null&&!previous.equals(home)){shared.add(id);Set<Long> places=copies.get(id);if(places==null){places=new HashSet<>();places.add(BlockOverlays.key(previous.x(),previous.z()));copies.put(id,places);placementBytes+=64;}if(places.add(BlockOverlays.key(x,z)))placementBytes+=64;if(placementBytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_placement_memory_limit");}
                            if(homes.size()>MAX_IDENTITIES)throw new IOException("scene_catalog_identity_limit");
                            SceneCullingVolume volume=SceneCullingVolume.from(entry.object());if(volume!=null)cullingVolumes.putIfAbsent(id,volume);
                            String asset=entry.object().asset();ScenePayload.Entry selected=templates.get(asset);
                            if(entry.object().hasCullingReference()){
                                UUID reference=null;
                                try{reference=szserver.client.SceneCulling.read(entry.object(),registry.target().names().get(asset).behaviour()).volume();}
                                catch(IllegalArgumentException unreviewed){if(cullingUnreviewedReferences.add(BlockOverlays.key(x,z)))cullingReferenceBytes+=64;}
                                if(reference!=null&&cullingReferences.computeIfAbsent(reference,key->new HashSet<>()).add(BlockOverlays.key(x,z)))cullingReferenceBytes+=64;
                                if(cullingReferenceBytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_culling_reference_memory_limit");
                            }
                            NativePortal portal=sql==null?NativePortal.from(entry.object(),registry.target().names().get(asset).behaviour()):null;if(portal!=null){portalIds.put(id,portal);portalBytes+=entry.object().record().length+128L+portal.parameters().destinations().size()*64L;if(portalBytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_portal_memory_limit");if(!portalRanks.containsKey(id))portalRanks.put(id,rank++);}
                            // Stable choice even when filesystem order differs. Keep exact channel and behavior bytes.
                            if(sql==null&&(selected==null||id.compareTo(selected.object().identity())<0)){int size=entry.object().record().length;bytes+=size-(selected==null?0:selected.object().record().length);if(bytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_template_memory_limit");templates.put(asset,entry);}
                        }}
                        if(sql!=null)for(var candidate:sql.candidates.getOrDefault(BlockOverlays.key(x,z),List.of())){ScenePayload.Entry entry=entries.get(candidate.id());if(entry==null)continue;ScenePayload.Occurrence matched=entry.occurrences().stream().filter(o->o.object().asset().equals(candidate.asset())).findFirst().orElse(null);if(matched==null)throw new IOException("scene_sql_catalog_template_asset_mismatch");entry=new ScenePayload.Entry(matched.container(),matched.object());if(candidate.priority()<templatePriorities.getOrDefault(candidate.asset(),32)){ScenePayload.Entry previous=templates.put(candidate.asset(),entry);bytes+=entry.object().record().length-(previous==null?0:previous.object().record().length);if(bytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_template_memory_limit");templatePriorities.put(candidate.asset(),candidate.priority());}}
                        chunks.incrementAndGet();for(var entry:entries.values())objects.addAndGet(entry.occurrences().size());
                    }
                }
            }
            Map<UUID,Terrain.SceneHome> nativeHomes=PackedSceneHomes.freeze(homes);homes.clear();
            if(sql!=null){homes=sql.homes;for(NativePortal portal:sql.portals.values()){portalIds.put(portal.id(),portal);portalBytes+=128L+portal.asset().length()*2L;for(var destination:portal.parameters().destinations())portalBytes+=64L+destination.location().length()*2L;if(portalBytes>MAX_TEMPLATE_BYTES)throw new IOException("scene_catalog_portal_memory_limit");int x=(int)Math.floor(portal.position().x()/16),z=(int)Math.floor(portal.position().z()/16);portals.computeIfAbsent(BlockOverlays.key(x,z),key->new LinkedHashMap<>()).put(portal.id(),portal);portalRanks.put(portal.id(),rank++);}}
            // One logical portal per UUID, at its actual trigger coordinates. Source
            // storage homes can differ for repeated/secondary native occurrences.
            if(sql==null)for(NativePortal portal:portalIds.values()){int px=(int)Math.floor(portal.position().x()/16),pz=(int)Math.floor(portal.position().z()/16);portals.computeIfAbsent(BlockOverlays.key(px,pz),key->new LinkedHashMap<>()).put(portal.id(),portal);}
            Map<Long,Map<UUID,NativePortal>> immutable=new HashMap<>();portals.forEach((key,value)->immutable.put(key,Collections.unmodifiableMap(new LinkedHashMap<>(value))));
            Map<String,NativePortal> portalTemplates=new HashMap<>();for(NativePortal portal:portalIds.values())portalTemplates.putIfAbsent(portal.asset(),portal);
            Map<UUID,Set<Long>> immutableReferences=new HashMap<>();cullingReferences.forEach((id,homesForVolume)->immutableReferences.put(id,Set.copyOf(homesForVolume)));
            Map<UUID,Set<Long>> immutableCopies=new HashMap<>();copies.forEach((id,places)->immutableCopies.put(id,Set.copyOf(places)));placementCopies=Map.copyOf(immutableCopies);
            index=new Index(sql==null?nativeHomes:PackedSceneHomes.freeze(homes),Map.copyOf(templates),Set.copyOf(shared),bytes,Map.copyOf(immutable),portalBytes,Map.copyOf(portalRanks),sql==null?0:sql.rows,Map.copyOf(portalIds),Map.copyOf(portalTemplates),nativeHomes,Map.copyOf(cullingVolumes),Map.copyOf(immutableReferences),Set.copyOf(cullingUnreviewedReferences),cullingReferenceBytes);loaded.complete(true);
        }catch(Throwable failure){failed=true;error=failure.getClass().getSimpleName()+":"+failure.getMessage();loaded.completeExceptionally(failure);}
    }
    boolean healthy(){return !stopped&&!failed;}
    boolean ready(){return healthy()&&index!=null;}
    Map<String,Object> metrics(){Index ready=index;return Map.ofEntries(Map.entry("ready",ready!=null),Map.entry("scannedChunks",chunks.get()),Map.entry("scannedObjects",objects.get()),Map.entry("identities",ready==null?0:ready.homes.size()),Map.entry("homeIndexBytes",ready!=null&&ready.homes instanceof PackedSceneHomes packed?packed.storageBytes():0L),Map.entry("nativeHomeIndexBytes",ready!=null&&ready.nativeHomes instanceof PackedSceneHomes packed?packed.storageBytes():0L),Map.entry("assets",ready==null?0:ready.templates.size()),Map.entry("sharedIdentities",ready==null?0:ready.shared.size()),Map.entry("cullingVolumes",ready==null?0:ready.cullingVolumes.size()),Map.entry("cullingReferenceGroups",ready==null?0:ready.cullingReferences.size()),Map.entry("cullingReferenceBytes",ready==null?0L:ready.cullingReferenceBytes),Map.entry("cullingUnreviewedChunks",ready==null?0:ready.cullingUnreviewedReferences.size()),Map.entry("templateBytes",ready==null?0L:ready.bytes),Map.entry("portalChunks",ready==null?0:ready.portals.size()),Map.entry("portalBytes",ready==null?0L:ready.portalBytes),Map.entry("sqlRows",ready==null?0L:ready.sqlRows),Map.entry("error",error),Map.entry("projection",registry.metrics()));}
    @Override public void close()throws InterruptedException{stopped=true;Workers.stop(loader);}
}
