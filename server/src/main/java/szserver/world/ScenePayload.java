package szserver.world;

import com.github.luben.zstd.Zstd;
import szserver.client.SceneLightMigration;
import szserver.net.Wire;
import java.nio.*;
import java.util.*;

/** Bounded native chunk side-channel framing. All parsing runs on loader/projection workers. */
public final class ScenePayload {
    public record Occurrence(boolean container,SceneObject object){public Occurrence{Objects.requireNonNull(object);}}
    /** One logical UUID, every physical body/channel in stable source order. */
    public static final class Entry {
        private final List<Occurrence> occurrences;
        private final long recordBytes;
        private final long metadataBytes;
        private final boolean cullingReference;
        public Entry(boolean container,SceneObject object){this(List.of(new Occurrence(container,object)));}
        private Entry(List<Occurrence> values){
            if(values.isEmpty()||values.size()>MAX_OBJECTS)throw new IllegalArgumentException("scene_occurrence_count_limit");
            UUID id=values.get(0).object().identity();for(var value:values)if(!id.equals(value.object().identity()))throw new IllegalArgumentException("scene_occurrence_identity");
            List<Occurrence> ordered=new ArrayList<>(values.size());long bytes=0,metadata=4;boolean reference=false;for(boolean container:new boolean[]{false,true})for(var value:values)if(value.container()==container){ordered.add(value);bytes+=value.object().recordSize();metadata+=9L+value.object().asset().length()*2L+value.object().recordSize();reference|=value.object().hasCullingReference();}occurrences=List.copyOf(ordered);recordBytes=bytes;metadataBytes=metadata;cullingReference=reference;
        }
        public static Entry of(List<Occurrence> values){return new Entry(values);}
        public List<Occurrence> occurrences(){return occurrences;}
        public SceneObject object(){return occurrences.get(0).object();}
        public boolean container(){return occurrences.get(0).container();}
        public Entry canonical(){return occurrences.size()==1?this:new Entry(container(),object());}
        public boolean hasContainer(){return occurrences.stream().anyMatch(Occurrence::container);}
        public boolean hasChannel(boolean container){return occurrences.stream().anyMatch(o->o.container()==container);}
        public long recordBytes(){return recordBytes;}
        long metadataBytes(){return metadataBytes;}
        public boolean hasCullingReference(){return cullingReference;}
        @Override public boolean equals(Object other){return other instanceof Entry e&&occurrences.equals(e.occurrences);}
        @Override public int hashCode(){return occurrences.hashCode();}
    }
    public static Map<UUID,Entry> index(Channels channels,SceneRegistry.Manifest registry){
        Map<UUID,List<Occurrence>> grouped=new LinkedHashMap<>();int count=0;for(boolean container:new boolean[]{false,true})for(byte[] frame:container?channels.containerObjects():channels.objects()){
            if(++count>MAX_OBJECTS)throw new IllegalArgumentException("scene_object_count_limit");int id=ByteBuffer.wrap(frame).getShort();SceneRegistry.Definition definition=registry.ids().get(id);if(definition==null)throw new IllegalArgumentException("scene_unknown_target_id");SceneObject object=new SceneObject(definition.name(),frame);grouped.computeIfAbsent(object.identity(),key->new ArrayList<>()).add(new Occurrence(container,object));
        }Map<UUID,Entry> result=new LinkedHashMap<>();grouped.forEach((id,values)->result.put(id,Entry.of(values)));return Collections.unmodifiableMap(result);
    }
    public static Channels apply(Channels base,Map<UUID,Entry> changes,int x,int z){
        if(changes.isEmpty())return base;if(changes.size()>MAX_OBJECTS)throw new IllegalArgumentException("scene_object_count_limit");List<byte[]> originals=base.objects(),originalContainers=base.containerObjects();
        // Existing off-home copies may change fields while their complete affine placement,
        // native factory, UUID, physical channel and multiplicity remain unchanged.
        Map<PlacementKey,Integer> sourceCounts=new HashMap<>();for(boolean container:new boolean[]{false,true})for(byte[] frame:container?originalContainers:originals)if(changes.containsKey(SceneObject.prefix(frame).identity()))sourceCounts.merge(placement(container,frame),1,Integer::sum);
        long count=0,blobBytes=0,containerBytes=4;int blobCount=0,rawCount=0;
        for(boolean container:new boolean[]{false,true})for(byte[] frame:container?originalContainers:originals)if(!changes.containsKey(SceneObject.prefix(frame).identity())){count++;if(container){rawCount++;containerBytes+=varintSize(frame.length)+(long)frame.length;}else{blobCount++;blobBytes+=varintSize(frame.length)+(long)frame.length;}}
        for(var entry:changes.values())if(entry!=null)for(var o:entry.occurrences()){count++;int size=o.object().recordSize();if(o.container()){rawCount++;containerBytes+=varintSize(size)+(long)size;}else{blobCount++;blobBytes+=varintSize(size)+(long)size;}}
        if(count>MAX_OBJECTS)throw new IllegalArgumentException("scene_object_count_limit");if(blobBytes+varintSize(blobCount)>MAX_RAW)throw new IllegalArgumentException("scene_expansion_limit");if(containerBytes>MAX_RECORD)throw new IllegalArgumentException("scene_chunk_size");if(!base.raw()&&rawCount>0)throw new IllegalArgumentException("scene_nbt_container_edit_unsupported");
        for(var row:changes.entrySet())if(row.getValue()!=null)for(var occurrence:row.getValue().occurrences()){
            SceneObject o=occurrence.object();if(!row.getKey().equals(o.identity()))throw new IllegalArgumentException("scene_edit_identity");
            if(Math.floor(o.x()/16)!=x||Math.floor(o.z()/16)!=z){PlacementKey key=placement(occurrence.container(),o.record());int available=sourceCounts.getOrDefault(key,0);if(available==0)throw new IllegalArgumentException("scene_edit_chunk_mismatch");sourceCounts.put(key,available-1);}
        }
        List<byte[]> objects=new ArrayList<>(),containerObjects=new ArrayList<>();
        for(boolean container:new boolean[]{false,true}){
            Map<UUID,ArrayDeque<SceneObject>> pending=new TreeMap<>();
            for(var row:changes.entrySet()){ArrayDeque<SceneObject> queue=new ArrayDeque<>();if(row.getValue()!=null)for(var o:row.getValue().occurrences())if(o.container()==container)queue.add(o.object());pending.put(row.getKey(),queue);}
            List<byte[]> output=container?containerObjects:objects;
            for(byte[] frame:container?originalContainers:originals){UUID id=SceneObject.prefix(frame).identity();ArrayDeque<SceneObject> replacement=pending.get(id);if(replacement==null)output.add(frame);else if(!replacement.isEmpty())output.add(replacement.removeFirst().record());}
            for(var queue:pending.values())while(!queue.isEmpty())output.add(queue.removeFirst().record());
        }
        if(!base.raw()&&!containerObjects.isEmpty())throw new IllegalArgumentException("scene_nbt_container_edit_unsupported");if(count>MAX_OBJECTS)throw new IllegalArgumentException("scene_object_count_limit");
        return new Channels(objects,base.raw(),base.enabled(),containerObjects,base.containerNbt(),base.mesh());
    }
    private static int varintSize(int value){int size=1;while((value>>>=7)!=0)size++;return size;}
    private record PlacementKey(boolean container,int networkId,UUID identity,ByteBuffer affine){}
    private static PlacementKey placement(boolean container,byte[] raw){var prefix=SceneObject.prefix(raw);return new PlacementKey(container,ByteBuffer.wrap(raw).getShort()&65535,prefix.identity(),ByteBuffer.wrap(raw,prefix.transformAt(),48).slice().asReadOnlyBuffer());}
    public static final int MAX_RECORD=4*1024*1024,MAX_RAW=8*1024*1024,MAX_OBJECTS=8192,MAX_OBJECT=128*1024;
    public record Channels(List<byte[]> objects,boolean raw,boolean enabled,List<byte[]> containerObjects,byte[] containerNbt,byte[] mesh){
        public Channels {objects=objects.stream().map(byte[]::clone).toList();containerObjects=containerObjects.stream().map(byte[]::clone).toList();containerNbt=containerNbt.clone();mesh=mesh.clone();}
        @Override public List<byte[]> objects(){return objects.stream().map(byte[]::clone).toList();}
        @Override public List<byte[]> containerObjects(){return containerObjects.stream().map(byte[]::clone).toList();}
        @Override public byte[] containerNbt(){return containerNbt.clone();}
        @Override public byte[] mesh(){return mesh.clone();}
        public byte[] encode(){byte[] blob=objects.isEmpty()?new byte[0]:Zstd.compress(frames(objects,false),3);return Wire.bytes(out->{Wire.varint(out,blob.length);out.write(blob);out.writeBoolean(raw);out.writeBoolean(enabled);if(raw)out.write(frames(containerObjects,true));else out.write(containerNbt);out.write(mesh);});}
    }
    public record Layout(int terrainEnd,Channels channels){}
    public record NormalizedLights(Channels value,int migrated){}
    private record NormalizedFrames(List<byte[]> frames,int migrated){}
    private static NormalizedFrames normalizeLights(List<byte[]> frames,SceneRegistry.Manifest registry){
        List<byte[]> changed=null;
        int count=0;
        for(int i=0;i<frames.size();i++){
            byte[] frame=frames.get(i);
            var definition=registry.ids().get(ByteBuffer.wrap(frame).getShort()&65535);
            if(definition==null)continue;
            byte[] normalized=SceneLightMigration.frame(frame,definition.behaviour());
            if(normalized!=frame){
                if(changed==null)changed=new ArrayList<>(frames);
                changed.set(i,normalized);
                count++;
            }
        }
        return new NormalizedFrames(changed==null?frames:changed,count);
    }
    public static NormalizedLights normalizeLights(Channels source,SceneRegistry.Manifest registry){
        var objects=normalizeLights(source.objects,registry);
        var container=normalizeLights(source.containerObjects,registry);
        int count=objects.migrated()+container.migrated();
        if(count==0)return new NormalizedLights(source,0);
        return new NormalizedLights(new Channels(objects.frames(),source.raw,source.enabled,container.frames(),
            source.containerNbt,source.mesh),count);
    }
    public static Layout parse(byte[] record){
        return parse(record,0);
    }
    /** Versioned storage only. Network and authored-index parsing retain their original bounds. */
    public static Layout parsePersistent(byte[] record){return parse(record,6);}
    public static Layout parseHashWire(byte[] record){return parse(record,SceneHashWire.GROWTH);}
    private static Layout parse(byte[] record,int frameExtra){
        if(record.length<9||record.length>MAX_RECORD+MAX_OBJECTS*frameExtra+(frameExtra==0?0:32))throw new IllegalArgumentException("scene_chunk_size");ByteBuffer in=ByteBuffer.wrap(record);int x=in.getInt(),z=in.getInt(),terrain=in.get()&255;if(terrain>1)throw new IllegalArgumentException("scene_terrain_flag");
        if(terrain==1){take(in,24);int arrays=in.getInt(),length=in.getInt();if(arrays<0||arrays>TilePayload.MAX_RAW||length<4)throw new IllegalArgumentException("scene_terrain_layout");take(in,length);}
        int end=in.position();return new Layout(end,parseChannels(record,end,x,z,frameExtra));
    }
    /** Accepts the old loader's explicitly located terrain end, including legacy extra header bytes. */
    public static Channels parseChannels(byte[] record,int at,int x,int z){
        return parseChannels(record,at,x,z,0);
    }
    private static Channels parseChannels(byte[] record,int at,int x,int z,int frameExtra){
        if(record.length>MAX_RECORD+MAX_OBJECTS*frameExtra+(frameExtra==0?0:32)||at<9||at>record.length)throw new IllegalArgumentException("scene_chunk_size");ByteBuffer in=ByteBuffer.wrap(record);in.position(at);
        int length=varint(in);if(length>MAX_RECORD+MAX_OBJECTS*frameExtra+(frameExtra==0?0:32))throw new IllegalArgumentException("scene_blob_size");byte[] compressed=take(in,length);List<byte[]> objects=List.of();
        if(length!=0){long expanded=Zstd.decompressedSize(compressed);if(expanded<1||expanded>MAX_RAW+MAX_OBJECTS*frameExtra)throw new IllegalArgumentException("scene_expansion_limit");ByteBuffer blob=ByteBuffer.wrap(Zstd.decompress(compressed,(int)expanded));objects=readFrames(blob,false,frameExtra);if(blob.hasRemaining())throw new IllegalArgumentException("scene_blob_trailing");}
        boolean raw=bool(in),enabled=bool(in);List<byte[]> containerObjects=List.of();byte[] nbt=new byte[0];if(raw)containerObjects=readFrames(in,true,frameExtra);else {int begin=in.position();namedNbt(in);nbt=Arrays.copyOfRange(record,begin,in.position());}
        if((long)objects.size()+containerObjects.size()>MAX_OBJECTS)throw new IllegalArgumentException("scene_object_count_limit");
        // Captures encode an absent mesh as (0,0,0), including nonzero chunk coordinates.
        // Concrete mesh data must still belong to the addressed chunk. Preserve sentinel bytes.
        int meshAt=in.position(),meshX=in.getInt(),meshZ=in.getInt(),points=in.getInt();if((meshX!=x||meshZ!=z)&&!(meshX==0&&meshZ==0&&points==0))throw new IllegalArgumentException("scene_mesh_chunk_mismatch");if(points<0||points>32768||points>in.remaining()/4)throw new IllegalArgumentException("scene_mesh_count_limit");for(int n=0;n<points;n++){take(in,3);varint(in);}if(in.hasRemaining())throw new IllegalArgumentException("scene_chunk_trailing");
        return new Channels(objects,raw,enabled,containerObjects,nbt,Arrays.copyOfRange(record,meshAt,record.length));
    }
    public static List<byte[]> readFrames(ByteBuffer in,boolean intCount){
        return readFrames(in,intCount,0);
    }
    private static List<byte[]> readFrames(ByteBuffer in,boolean intCount,int frameExtra){
        int count=intCount?in.getInt():varint(in);if(count<0||count>MAX_OBJECTS||count>in.remaining()/3)throw new IllegalArgumentException("scene_object_count_limit");List<byte[]> result=new ArrayList<>(count);
        for(int n=0;n<count;n++){int length=varint(in);if(length<2+frameExtra||length>MAX_OBJECT+frameExtra)throw new IllegalArgumentException("scene_object_length_limit");result.add(take(in,length));}return List.copyOf(result);
    }
    public static byte[] frames(List<byte[]> records,boolean intCount){return Wire.bytes(out->{if(intCount)out.writeInt(records.size());else Wire.varint(out,records.size());for(byte[] record:records){Wire.varint(out,record.length);out.write(record);}});}
    public static byte[] take(ByteBuffer in,int size){if(size<0||size>in.remaining())throw new IllegalArgumentException("scene_truncated");byte[] result=new byte[size];in.get(result);return result;}
    public static int varint(ByteBuffer in){try{int n=Wire.varint(in);if(n<0)throw new IllegalArgumentException("scene_varint_overflow");return n;}catch(java.io.IOException|BufferUnderflowException error){throw new IllegalArgumentException("scene_varint",error);}}
    public static boolean bool(ByteBuffer in){int value=in.get()&255;if(value>1)throw new IllegalArgumentException("scene_boolean");return value==1;}
    public static byte[] utf(ByteBuffer in){int begin=in.position(),size=in.getShort()&65535;if(size>16384)throw new IllegalArgumentException("scene_string_limit");take(in,size);return Arrays.copyOfRange(in.array(),begin,in.position());}
    public static int namedNbt(ByteBuffer in){int type=in.get()&255;if(type==0)return in.position();if(type!=10)throw new IllegalArgumentException("scene_nbt_root");utf(in);nbt(in,10,0,new int[]{0});return in.position();}
    private static int count(ByteBuffer in){int size=in.getInt();if(size<0||size>262144)throw new IllegalArgumentException("scene_nbt_array_limit");return size;}
    private static void nbt(ByteBuffer in,int type,int depth,int[] nodes){
        if(depth>16||++nodes[0]>16384)throw new IllegalArgumentException("scene_nbt_complexity");switch(type){
            case 1->take(in,1);case 2->take(in,2);case 3->take(in,4);case 4->take(in,8);case 5->{if(!Float.isFinite(in.getFloat()))throw new IllegalArgumentException("scene_nbt_nonfinite");}case 6->{if(!Double.isFinite(in.getDouble()))throw new IllegalArgumentException("scene_nbt_nonfinite");}
            case 7->take(in,count(in));case 8->utf(in);case 11->take(in,Math.multiplyExact(count(in),4));
            case 9->{int child=in.get()&255,size=count(in);if(size>16384||child==0&&size!=0)throw new IllegalArgumentException("scene_nbt_list");for(int n=0;n<size;n++)nbt(in,child,depth+1,nodes);}
            case 10->{Set<String> names=new HashSet<>();for(int child;(child=in.get()&255)!=0;){if(!names.add(Base64.getEncoder().encodeToString(utf(in))))throw new IllegalArgumentException("scene_nbt_duplicate_key");nbt(in,child,depth+1,nodes);}}
            default->throw new IllegalArgumentException("scene_nbt_type");
        }
    }
}
