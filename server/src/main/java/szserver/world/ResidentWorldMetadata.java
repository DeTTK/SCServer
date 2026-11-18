package szserver.world;

import java.io.*;
import java.util.*;

/** Global map indexes. Chunk geometry and physical object bodies remain owned by chunks. */
public final class ResidentWorldMetadata {
    private static final int MAGIC=0x535a574d, VERSION=2, MAX=60*1024*1024;
    private ResidentWorldMetadata(){}
    static long entryBytes(ScenePayload.Entry entry){return entry==null?0:entry.metadataBytes();}
    private static long stringBytes(String value){return 4L+2L*value.length();}
    static long portalBytes(NativePortal p){long n=16+stringBytes(p.asset())+stringBytes(p.behaviour())+24+8+3+16+4;for(var d:p.parameters().destinations())n+=1+(d.coordinates()==null?0:24)+8+stringBytes(d.location());return n;}
    public static Snapshot capture(World world){
        if(!world.residentMetadataSnapshots())throw new IllegalStateException("resident_metadata_snapshot_required");
        return new Snapshot(world.identity(),world.sceneRegistryDigest(),world.mapCheckpointView(),world.metadataReferences(),world.residentSceneChanges());
    }
    static Snapshot capture(World.SceneMetadataView view){view.checkCurrent();return new Snapshot(view.owner().identity(),view.owner().sceneRegistryDigest(),view.root(),view.references(),view.residentScenes());}
    public static final class Snapshot {
        private final String identity,registry;
        private final World.MapCheckpointView root;
        private final Map<World.ScenePlacement,ScenePayload.Entry> references;
        private final Map<World.ScenePlacement,World.ResidentSceneChange> scenes;
        private final long bytes;
        private Snapshot(String identity,String registry,World.MapCheckpointView root,Map<World.ScenePlacement,ScenePayload.Entry> references,Map<World.ScenePlacement,World.ResidentSceneChange> scenes){
            this.identity=Objects.requireNonNull(identity);this.registry=Objects.requireNonNull(registry);this.root=new World.MapCheckpointView(new BlockOverlays(),root.anchors(),root.physical(),root.edited(),root.metadata(),root.metadataIds(),root.removed(),root.metadataLive(),root.ranks(),root.nextRank(),root.epoch());this.references=references;this.scenes=scenes;
            bytes=72L+ResidentMetadataMaps.weight(scenes)+2L*(identity.length()+registry.length())+ResidentMetadataMaps.weight(root.anchors())+ResidentMetadataMaps.weight(references)+ResidentMetadataMaps.weight(root.physical())+ResidentMetadataMaps.weight(root.metadata())+ResidentMetadataMaps.weight(root.ranks())+16L*(root.edited().size()+root.metadataIds().size()+root.removed().size()+root.metadataLive().size());
            if(bytes>MAX)throw new IllegalStateException("resident_world_metadata_limit");
        }
        public ResidentOperationLog.Payload payload(){return new ResidentOperationLog.Payload(bytes,this::encode);}
        public long epoch(){return root.epoch();}
        public void requireBinding(String world,String sceneRegistry){if(!identity.equals(world)||!registry.equals(sceneRegistry))throw new IllegalArgumentException("resident_world_metadata_binding");}
        String identity(){return identity;}String registry(){return registry;}
        World.MapCheckpointView root(){return root;}Map<World.ScenePlacement,ScenePayload.Entry> references(){return references;}
        Snapshot select(Set<UUID> ids){
            SceneCollision.worker();var anchors=select(root.anchors(),ids);var physical=select(root.physical(),ids);var metadata=select(root.metadata(),ids);var ranks=select(root.ranks(),ids);Map<World.ScenePlacement,ScenePayload.Entry> refs=new HashMap<>();references.forEach((key,value)->{if(ids.contains(key.id()))refs.put(key,value);});Map<World.ScenePlacement,World.ResidentSceneChange> selectedScenes=new HashMap<>();scenes.forEach((key,value)->{if(ids.contains(key.id()))selectedScenes.put(key,value);});
            return new Snapshot(identity,registry,new World.MapCheckpointView(new BlockOverlays(),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(anchors,(id,v)->25+entryBytes(v.entry()))),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(physical,(id,v)->portalBytes(v))),select(root.edited(),ids),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(metadata,(id,v)->portalBytes(v))),select(root.metadataIds(),ids),select(root.removed(),ids),select(root.metadataLive(),ids),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(ranks,(id,v)->25)),root.nextRank(),root.epoch()),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(refs,(id,v)->25+entryBytes(v))),sceneMap(selectedScenes));
        }
        private static <V> Map<UUID,V> select(Map<UUID,V> values,Set<UUID> ids){Map<UUID,V> result=new HashMap<>();for(UUID id:ids)if(values.containsKey(id))result.put(id,values.get(id));return result;}
        private static Set<UUID> select(Set<UUID> values,Set<UUID> ids){Set<UUID> result=new HashSet<>(ids);result.retainAll(values);return Set.copyOf(result);}
        boolean sameIndexes(Snapshot other){var b=other.root;return identity.equals(other.identity)&&registry.equals(other.registry)&&root.anchors().equals(b.anchors())&&references.equals(other.references)&&scenes.equals(other.scenes)&&root.physical().equals(b.physical())&&root.metadata().equals(b.metadata())&&root.edited().equals(b.edited())&&root.metadataIds().equals(b.metadataIds())&&root.removed().equals(b.removed())&&root.metadataLive().equals(b.metadataLive())&&root.ranks().equals(b.ranks());}
        Snapshot replace(Set<UUID> ids,Snapshot replacement){
            SceneCollision.worker();if(!identity.equals(replacement.identity)||!registry.equals(replacement.registry))throw new IllegalArgumentException("resident_world_metadata_binding");var b=replacement.root;
            Map<World.ScenePlacement,ScenePayload.Entry> refs=new HashMap<>(references);refs.keySet().removeIf(key->ids.contains(key.id()));refs.putAll(replacement.references);Map<World.ScenePlacement,World.ResidentSceneChange> mergedScenes=new HashMap<>(scenes);mergedScenes.keySet().removeIf(key->ids.contains(key.id()));mergedScenes.putAll(replacement.scenes);
            var merged=new World.MapCheckpointView(new BlockOverlays(),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(merge(root.anchors(),b.anchors(),ids),(id,v)->25+entryBytes(v.entry()))),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(merge(root.physical(),b.physical(),ids),(id,v)->portalBytes(v))),merge(root.edited(),b.edited(),ids),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(merge(root.metadata(),b.metadata(),ids),(id,v)->portalBytes(v))),merge(root.metadataIds(),b.metadataIds(),ids),merge(root.removed(),b.removed(),ids),merge(root.metadataLive(),b.metadataLive(),ids),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(merge(root.ranks(),b.ranks(),ids),(id,v)->25)),Math.max(root.nextRank(),b.nextRank()),Math.addExact(root.epoch(),1));
            return new Snapshot(identity,registry,merged,ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(refs,(id,v)->25+entryBytes(v))),sceneMap(mergedScenes));
        }
        private static <V> Map<UUID,V> merge(Map<UUID,V> base,Map<UUID,V> values,Set<UUID> ids){Map<UUID,V> result=new HashMap<>(base);ids.forEach(result::remove);result.putAll(values);return result;}
        private static Set<UUID> merge(Set<UUID> base,Set<UUID> values,Set<UUID> ids){Set<UUID> result=new HashSet<>(base);result.removeAll(ids);result.addAll(values);return Set.copyOf(result);}
        public byte[] encode() throws IOException {
            var buffer=new ByteArrayOutputStream((int)Math.min(bytes,1024*1024));var out=new DataOutputStream(buffer);
            out.writeInt(MAGIC);out.writeInt(VERSION);string(out,identity);string(out,registry);out.writeLong(root.epoch());out.writeLong(root.nextRank());
            out.writeInt(root.anchors().size());for(var row:root.anchors().entrySet()){uuid(out,row.getKey());out.writeInt(row.getValue().x());out.writeInt(row.getValue().z());entry(out,row.getValue().entry());}
            out.writeInt(references.size());for(var row:references.entrySet()){out.writeInt(row.getKey().x());out.writeInt(row.getKey().z());uuid(out,row.getKey().id());entry(out,row.getValue());}
            portals(out,root.physical());portals(out,root.metadata());ids(out,root.edited());ids(out,root.metadataIds());ids(out,root.removed());ids(out,root.metadataLive());
            out.writeInt(root.ranks().size());for(var row:root.ranks().entrySet()){uuid(out,row.getKey());out.writeLong(row.getValue().value());out.writeBoolean(row.getValue().created());}
            out.writeInt(scenes.size());for(var row:scenes.entrySet()){out.writeInt(row.getKey().x());out.writeInt(row.getKey().z());uuid(out,row.getKey().id());entry(out,row.getValue().entry());}
            out.flush();if(buffer.size()!=bytes)throw new IOException("resident_world_metadata_size");return buffer.toByteArray();
        }
        /** Bootstrap only. Runtime replacement must prepare on a worker and publish on WorldLoop. */
        public void restoreInto(World world){binding(world);world.restoreResidentMetadata(root,references);world.residentSceneChangesForRestore(scenes);}
        public World.SceneMetadataPrepared prepareRestore(World.SceneMetadataView capture){
            SceneCollision.worker();capture.checkCurrent();binding(capture.owner());var replacement=new World.MapCheckpointView(root.overlays(),root.anchors(),root.physical(),root.edited(),root.metadata(),root.metadataIds(),root.removed(),root.metadataLive(),root.ranks(),root.nextRank(),Math.addExact(capture.epoch(),1));World next=capture.owner().restoredMapCheckpoint(replacement);
            next.editedReferencesForRestore(references);next.residentSceneChangesForRestore(scenes);next.enableResidentMetadataSnapshots();capture.checkCurrent();return new World.SceneMetadataPrepared(capture,next);
        }
        private void binding(World world){if(!identity.equals(world.identity())||!registry.equals(world.sceneRegistryDigest()))throw new IllegalArgumentException("resident_world_metadata_binding");}
    }
    public static Snapshot decode(byte[] bytes) throws IOException {
        if(bytes.length<68||bytes.length>MAX)throw new IOException("resident_world_metadata_size");
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC)throw new IOException("resident_world_metadata_version");int version=in.readInt();if(version<1||version>VERSION)throw new IOException("resident_world_metadata_version");String identity=string(in),registry=string(in);long epoch=in.readLong(),next=in.readLong();if(epoch<0||next<SceneCatalog.MAX_IDENTITIES)throw new IOException("resident_world_metadata_epoch");
            Map<UUID,World.SceneLocation> anchors=new HashMap<>();for(int n=count(in,100000);n>0;n--){UUID id=uuid(in);int x=in.readInt(),z=in.readInt();unique(anchors,id,new World.SceneLocation(x,z,entry(in,id)));}
            Map<World.ScenePlacement,ScenePayload.Entry> refs=new HashMap<>();for(int n=count(in,200000);n>0;n--){int x=in.readInt(),z=in.readInt();UUID id=uuid(in);ScenePayload.Entry value=entry(in,id);if(value==null||!value.hasCullingReference())throw new IOException("resident_world_metadata_reference");unique(refs,new World.ScenePlacement(x,z,id),value);}
            Map<UUID,NativePortal> physical=portals(in),metadata=portals(in);Set<UUID> edited=ids(in),metadataIds=ids(in),removed=ids(in),live=ids(in);Map<UUID,World.PortalRank> ranks=new HashMap<>();Set<Long> orders=new HashSet<>();
            for(int n=count(in,400000);n>0;n--){UUID id=uuid(in);long order=in.readLong();boolean recreated=bool(in);if(order<SceneCatalog.MAX_IDENTITIES||order>=next||!orders.add(order))throw new IOException("resident_world_metadata_rank");unique(ranks,id,new World.PortalRank(order,recreated));}
            Map<World.ScenePlacement,World.ResidentSceneChange> scenes=new HashMap<>();if(version>=2)for(int n=count(in,SceneLimits.PLACEMENTS);n>0;n--){int x=in.readInt(),z=in.readInt();UUID id=uuid(in);unique(scenes,new World.ScenePlacement(x,z,id),new World.ResidentSceneChange(entry(in,id)));}
            if(in.available()!=0||!metadataIds.containsAll(metadata.keySet())||!live.containsAll(metadata.keySet())||!metadataIds.containsAll(live)||!edited.containsAll(metadataIds)||!edited.containsAll(physical.keySet())||!ranks.keySet().containsAll(metadata.keySet()))throw new IOException("resident_world_metadata_indexes");
            var root=new World.MapCheckpointView(new BlockOverlays(),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(anchors,(id,v)->25+entryBytes(v.entry()))),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(physical,(id,v)->portalBytes(v))),Set.copyOf(edited),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(metadata,(id,v)->portalBytes(v))),Set.copyOf(metadataIds),Set.copyOf(removed),Set.copyOf(live),ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(ranks,(id,v)->25)),next,epoch);
            Snapshot result=new Snapshot(identity,registry,root,ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(refs,(id,v)->25+entryBytes(v))),sceneMap(scenes));if(result.bytes-(version==1?4:0)!=bytes.length)throw new IOException("resident_world_metadata_size");return result;
        } catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("resident_world_metadata_invalid",invalid);}
    }
    private static Map<World.ScenePlacement,World.ResidentSceneChange> sceneMap(Map<World.ScenePlacement,World.ResidentSceneChange> source){return ResidentMetadataMaps.snapshot(ResidentMetadataMaps.mutable(source,(p,v)->25+entryBytes(v.entry())));}
    private static <K,V> void unique(Map<K,V> map,K key,V value)throws IOException{if(map.putIfAbsent(key,value)!=null)throw new IOException("resident_world_metadata_duplicate");}
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void string(DataOutputStream out,String value)throws IOException{out.writeInt(value.length());for(int i=0;i<value.length();i++)out.writeChar(value.charAt(i));}
    private static String string(DataInputStream in)throws IOException{int n=count(in,1024*1024);if(n>in.available()/2)throw new EOFException();char[] chars=new char[n];for(int i=0;i<n;i++)chars[i]=in.readChar();return new String(chars);}
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("resident_world_metadata_count");return n;}
    private static boolean bool(DataInputStream in)throws IOException{int n=in.readUnsignedByte();if(n>1)throw new IOException("resident_world_metadata_bool");return n==1;}
    private static void entry(DataOutputStream out,ScenePayload.Entry value)throws IOException{out.writeBoolean(value!=null);if(value==null)return;out.writeInt(value.occurrences().size());for(var o:value.occurrences()){out.writeBoolean(o.container());string(out,o.object().asset());byte[] raw=o.object().record();out.writeInt(raw.length);out.write(raw);}}
    private static ScenePayload.Entry entry(DataInputStream in,UUID id)throws IOException{if(!bool(in))return null;int n=count(in,ScenePayload.MAX_OBJECTS);if(n==0)throw new IOException("resident_world_metadata_entry");List<ScenePayload.Occurrence> rows=new ArrayList<>();boolean container=false;for(int i=0;i<n;i++){boolean c=bool(in);if(container&&!c)throw new IOException("resident_world_metadata_channel_order");container=c;String asset=string(in);int size=count(in,ScenePayload.MAX_OBJECT);if(size>in.available())throw new EOFException();SceneObject object=new SceneObject(asset,in.readNBytes(size));if(!id.equals(object.identity()))throw new IOException("resident_world_metadata_identity");rows.add(new ScenePayload.Occurrence(c,object));}return ScenePayload.Entry.of(rows);}
    private static void ids(DataOutputStream out,Set<UUID> ids)throws IOException{out.writeInt(ids.size());for(UUID id:ids)uuid(out,id);}
    private static Set<UUID> ids(DataInputStream in)throws IOException{Set<UUID> result=new HashSet<>();for(int n=count(in,400000);n>0;n--)if(!result.add(uuid(in)))throw new IOException("resident_world_metadata_duplicate");return result;}
    private static void vec(DataOutputStream out,World.Vec v)throws IOException{out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());}
    private static World.Vec vec(DataInputStream in)throws IOException{double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IOException("resident_world_metadata_position");return new World.Vec(x,y,z);}
    private static void portals(DataOutputStream out,Map<UUID,NativePortal> rows)throws IOException{out.writeInt(rows.size());for(var p:rows.values()){uuid(out,p.id());string(out,p.asset());string(out,p.behaviour());vec(out,p.position());out.writeDouble(p.scale());var a=p.parameters();out.writeBoolean(a.interaction());out.writeBoolean(a.horizontalOffset());out.writeBoolean(a.verticalOffset());out.writeDouble(a.size());out.writeDouble(a.cooldown());out.writeInt(a.destinations().size());for(var d:a.destinations()){out.writeBoolean(d.coordinates()!=null);if(d.coordinates()!=null)vec(out,d.coordinates());out.writeDouble(d.weight());string(out,d.location());}}}
    private static Map<UUID,NativePortal> portals(DataInputStream in)throws IOException{Map<UUID,NativePortal> rows=new HashMap<>();for(int n=count(in,400000);n>0;n--){UUID id=uuid(in);String asset=string(in),behaviour=string(in);World.Vec pos=vec(in);double scale=in.readDouble();boolean interaction=bool(in),horizontal=bool(in),vertical=bool(in);double size=in.readDouble(),cooldown=in.readDouble();if(asset.isEmpty()||!NativePortal.behaviour(behaviour)||!Double.isFinite(scale)||scale<=0||!Double.isFinite(size)||size<0||!Double.isFinite(cooldown)||cooldown<0)throw new IOException("resident_world_metadata_portal");List<NativePortal.Destination> dest=new ArrayList<>();for(int d=count(in,65536);d>0;d--){World.Vec coordinates=bool(in)?vec(in):null;double weight=in.readDouble();if(!Double.isFinite(weight)||weight<0)throw new IOException("resident_world_metadata_weight");dest.add(new NativePortal.Destination(coordinates,weight,string(in)));}unique(rows,id,new NativePortal(id,asset,behaviour,pos,scale,new NativePortal.Parameters(interaction,size,horizontal,vertical,cooldown,dest)));}return rows;}
}
