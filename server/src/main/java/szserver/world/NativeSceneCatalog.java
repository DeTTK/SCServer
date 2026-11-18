package szserver.world;

import java.util.*;
import java.security.MessageDigest;

/** Sparse immutable native geometry metadata. Full scene frames never remain in this root. */
final class NativeSceneCatalog {
    interface Reader {Map<UUID,ScenePayload.Entry> read(int x,int z)throws Exception;}
    private record Info(int count,boolean anchored,NativePortal portal,SceneCullingVolume volume){}
    private record Chunk(Map<UUID,Info> changed,Set<UUID> removed,Set<UUID> references,boolean unreviewed){
        Set<UUID> identities(){Set<UUID> ids=new HashSet<>(changed.keySet());ids.addAll(removed);return ids;}
        boolean changes(UUID id){return changed.containsKey(id)||removed.contains(id);}
    }
    private record ObjectState(Set<Long> homes,Terrain.SceneHome primary,boolean shared,NativePortal portal,SceneCullingVolume volume){}
    final NativeCommitIndex index;
    private final SceneCatalog original,portalSource;
    private final Map<Long,byte[]> frames;
    private final Map<Long,Chunk> chunks;
    private final Map<UUID,Set<Long>> copies;
    private final Map<UUID,ObjectState> objects;
    private final Map<UUID,SceneCullingVolume> volumes;
    private final Map<UUID,Set<Long>> references;
    private final Set<Long> unreviewed;
    private final Map<Long,Map<UUID,NativePortal>> portals;
    private final long bytes;
    private NativeSceneCatalog(NativeCommitIndex index,SceneCatalog original,SceneCatalog portalSource,Map<Long,byte[]> frames,Map<Long,Chunk> chunks,Map<UUID,Set<Long>> copies,Map<UUID,ObjectState> objects,Map<UUID,SceneCullingVolume> volumes,Map<UUID,Set<Long>> references,Set<Long> unreviewed,Map<Long,Map<UUID,NativePortal>> portals,long bytes){
        this.index=index.differenceView();this.original=original;this.portalSource=portalSource;this.frames=Map.copyOf(frames);this.chunks=Map.copyOf(chunks);this.copies=Map.copyOf(copies);this.objects=Map.copyOf(objects);this.volumes=Map.copyOf(volumes);this.references=Map.copyOf(references);this.unreviewed=Set.copyOf(unreviewed);this.portals=Map.copyOf(portals);this.bytes=bytes;
    }
    static NativeSceneCatalog prepare(NativeSceneCatalog before,SceneCatalog original,SceneCatalog portalSource,NativeCommitIndex next,Reader reader,SceneRegistry.Manifest target)throws Exception{
        SceneCollision.worker();original.awaitReady();portalSource.awaitReady();Map<String,SceneRegistry.Definition> definitions=target.names();Map<Long,byte[]> frames=new HashMap<>(before==null?Map.of():before.frames);Map<Long,Chunk> chunks=new HashMap<>(before==null?Map.of():before.chunks);
        Map<UUID,Set<Long>> copies=new HashMap<>(before==null?Map.of():before.copies);Map<UUID,ObjectState> objects=new HashMap<>(before==null?Map.of():before.objects);
        Map<UUID,SceneCullingVolume> volumes=new HashMap<>(before==null?original.cullingVolumes():before.volumes);Map<UUID,Set<Long>> references=new HashMap<>(before==null?original.cullingReferences():before.references);Set<Long> unreviewed=new HashSet<>(before==null?original.cullingUnreviewedReferences():before.unreviewed);
        Map<Long,Map<UUID,NativePortal>> portals=new HashMap<>(before==null?portalSource.portalChunks():before.portals);Map<Long,Map<UUID,ScenePayload.Entry>> sourceMemo=new LinkedHashMap<>();long[] memoBytes={0};
        Reader source=(x,z)->{long key=BlockOverlays.key(x,z);var entries=sourceMemo.get(key);if(entries==null){entries=reader.read(x,z);long size=entries.values().stream().flatMap(e->e.occurrences().stream()).mapToLong(o->128L+o.object().recordSize()).sum();if(size<=32L*1024*1024){while(!sourceMemo.isEmpty()&&(sourceMemo.size()>=64||memoBytes[0]+size>32L*1024*1024)){var first=sourceMemo.entrySet().iterator().next();memoBytes[0]-=first.getValue().values().stream().flatMap(e->e.occurrences().stream()).mapToLong(o->128L+o.object().recordSize()).sum();sourceMemo.remove(first.getKey());}sourceMemo.put(key,entries);memoBytes[0]+=size;}}return entries;};
        Set<UUID> touched=new HashSet<>();
        for(long key:next.changedChunksSince(before==null?NativeCommitIndex.empty():before.index)){
            int x=(int)(key>>32),z=(int)key;byte[] record=next.readChunk(x,z).record();var channels=ScenePayload.parse(record).channels();byte[] hash=MessageDigest.getInstance("SHA-256").digest(channels.encode());
            if(Arrays.equals(frames.get(key),hash))continue;frames.put(key,hash);
            Map<UUID,ScenePayload.Entry> current=ScenePayload.index(channels,target),base=source.read(x,z);Chunk old=chunks.get(key);
            Set<UUID> identities=new HashSet<>(base.keySet());identities.addAll(current.keySet());Map<UUID,Info> changed=new HashMap<>();Set<UUID> removed=new HashSet<>();
            for(UUID id:identities)if(!Objects.equals(base.get(id),current.get(id))){var entry=current.get(id);if(entry==null)removed.add(id);else changed.put(id,info(entry,x,z,definitions));}
            Set<UUID> oldReferences=old==null?references(base,definitions):old.references;Set<UUID> newReferences=references(current,definitions);Set<UUID> groups=new HashSet<>(oldReferences);groups.addAll(newReferences);
            for(UUID id:groups){Set<Long> homes=new HashSet<>(references.getOrDefault(id,Set.of()));homes.remove(key);if(newReferences.contains(id))homes.add(key);if(homes.isEmpty())references.remove(id);else references.put(id,Set.copyOf(homes));}
            boolean unknown=unreviewed(current,definitions);if(unknown)unreviewed.add(key);else unreviewed.remove(key);
            Chunk replacement=changed.isEmpty()&&removed.isEmpty()?null:new Chunk(Map.copyOf(changed),Set.copyOf(removed),newReferences,unknown);
            if(old!=null){touched.addAll(old.identities());for(UUID id:old.changed.keySet()){Set<Long> homes=new HashSet<>(copies.getOrDefault(id,Set.of()));homes.remove(key);if(homes.isEmpty())copies.remove(id);else copies.put(id,Set.copyOf(homes));}}
            if(replacement==null)chunks.remove(key);else{chunks.put(key,replacement);touched.addAll(replacement.identities());for(UUID id:changed.keySet()){Set<Long> homes=new HashSet<>(copies.getOrDefault(id,Set.of()));homes.add(key);copies.put(id,Set.copyOf(homes));}}
        }
        for(UUID id:touched){
            NativePortal previous=objects.containsKey(id)?objects.get(id).portal:portalSource.basePortal(id);Set<Long> candidates=new HashSet<>(original.nativeHomes(id));candidates.addAll(copies.getOrDefault(id,Set.of()));
            boolean changed=false;for(long key:candidates){Chunk chunk=chunks.get(key);if(chunk!=null&&chunk.changes(id)){changed=true;break;}}
            ObjectState state=null;
            if(changed){List<Long> ordered=new ArrayList<>(candidates);ordered.sort(NativeSceneCatalog::order);Set<Long> homes=new HashSet<>();Terrain.SceneHome primary=null;boolean anchored=false;int count=0;NativePortal portal=null;SceneCullingVolume volume=null;
                for(long key:ordered){int x=(int)(key>>32),z=(int)key;Chunk chunk=chunks.get(key);Info value=chunk!=null&&chunk.removed.contains(id)?null:chunk!=null&&chunk.changed.containsKey(id)?chunk.changed.get(id):info(source.read(x,z).get(id),x,z,definitions);
                    if(value==null)continue;homes.add(key);count+=value.count;if(primary==null||!anchored&&value.anchored){primary=new Terrain.SceneHome(x,z);anchored=value.anchored;}if(value.portal!=null)portal=value.portal;if(volume==null)volume=value.volume;
                }
                state=new ObjectState(Set.copyOf(homes),primary,count>1,portal,volume);objects.put(id,state);
            }else objects.remove(id);
            SceneCullingVolume volume=state==null?original.cullingVolumes().get(id):state.volume;if(volume==null)volumes.remove(id);else volumes.put(id,volume);
            NativePortal portal=state==null?portalSource.basePortal(id):state.portal;replacePortal(portals,id,previous,portal);
        }
        long bytes=frames.size()*128L+chunks.size()*160L+objects.size()*192L+references.size()*96L+unreviewed.size()*48L;
        for(var chunk:chunks.values())bytes+=chunk.changed.size()*160L+chunk.removed.size()*64L+chunk.references.size()*64L;
        for(var object:objects.values())bytes+=object.homes.size()*64L+(object.portal==null?0:128L+object.portal.asset().length()*2L+object.portal.parameters().destinations().stream().mapToLong(d->64L+d.location().length()*2L).sum());
        if(bytes>128L*1024*1024)throw new IllegalArgumentException("native_scene_catalog_memory_limit");
        return new NativeSceneCatalog(next,original,portalSource,frames,chunks,copies,objects,volumes,references,unreviewed,portals,bytes);
    }
    private static int order(long a,long b){int ax=(int)(a>>32),az=(int)a,bx=(int)(b>>32),bz=(int)b;int order=("r."+Math.floorDiv(ax,32)+"."+Math.floorDiv(az,32)+".szr").compareTo("r."+Math.floorDiv(bx,32)+"."+Math.floorDiv(bz,32)+".szr");return order!=0?order:Integer.compare(Math.floorMod(az,32)*32+Math.floorMod(ax,32),Math.floorMod(bz,32)*32+Math.floorMod(bx,32));}
    private static Info info(ScenePayload.Entry entry,int x,int z,Map<String,SceneRegistry.Definition> definitions){if(entry==null)return null;var object=entry.object();return new Info(entry.occurrences().size(),Math.floor(object.x()/16)==x&&Math.floor(object.z()/16)==z,NativePortal.from(entry,asset->definitions.get(asset).behaviour()),SceneCullingVolume.from(entry));}
    private static Set<UUID> references(Map<UUID,ScenePayload.Entry> entries,Map<String,SceneRegistry.Definition> definitions){Set<UUID> references=new HashSet<>();for(var entry:entries.values())for(var occurrence:entry.occurrences())if(occurrence.object().hasCullingReference())try{UUID id=szserver.client.SceneCulling.read(occurrence.object(),definitions.get(occurrence.object().asset()).behaviour()).volume();if(id!=null)references.add(id);}catch(IllegalArgumentException unknown){}return Set.copyOf(references);}
    private static boolean unreviewed(Map<UUID,ScenePayload.Entry> entries,Map<String,SceneRegistry.Definition> definitions){for(var entry:entries.values())for(var occurrence:entry.occurrences())if(occurrence.object().hasCullingReference())try{szserver.client.SceneCulling.read(occurrence.object(),definitions.get(occurrence.object().asset()).behaviour());}catch(IllegalArgumentException unknown){return true;}return false;}
    private static void replacePortal(Map<Long,Map<UUID,NativePortal>> chunks,UUID id,NativePortal old,NativePortal next){if(old!=null){long key=BlockOverlays.key((int)Math.floor(old.position().x()/16),(int)Math.floor(old.position().z()/16));Map<UUID,NativePortal> rows=new HashMap<>(chunks.getOrDefault(key,Map.of()));rows.remove(id);if(rows.isEmpty())chunks.remove(key);else chunks.put(key,Map.copyOf(rows));}if(next!=null){long key=BlockOverlays.key((int)Math.floor(next.position().x()/16),(int)Math.floor(next.position().z()/16));Map<UUID,NativePortal> rows=new HashMap<>(chunks.getOrDefault(key,Map.of()));rows.put(id,next);chunks.put(key,Map.copyOf(rows));}}
    boolean changedIdentity(UUID id){return objects.containsKey(id);}
    Terrain.SceneHome home(UUID id){return objects.containsKey(id)?objects.get(id).primary:original.home(id);}
    Terrain.SceneHome nativeHome(UUID id){return objects.containsKey(id)?objects.get(id).primary:original.nativeHome(id);}
    Set<Long> homes(UUID id){return objects.containsKey(id)?objects.get(id).homes:original.nativeHomes(id);}
    boolean shared(UUID id){return objects.containsKey(id)?objects.get(id).shared:original.shared(id);}
    Map<UUID,SceneCullingVolume> volumes(){return volumes;}Map<UUID,Set<Long>> references(){return references;}Set<Long> unreviewed(){return unreviewed;}
    Map<UUID,NativePortal> portals(int x,int z){return portals.getOrDefault(BlockOverlays.key(x,z),Map.of());}
    NativePortal portal(UUID id){return objects.containsKey(id)?objects.get(id).portal:portalSource.basePortal(id);}
    Map<String,Object> metrics(){return Map.of("generation",index.generation(),"changedChunks",chunks.size(),"changedIdentities",objects.size(),"retainedBytes",bytes,"retainedNativeFrames",0);}
}
