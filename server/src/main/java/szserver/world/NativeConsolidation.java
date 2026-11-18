package szserver.world;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Bounded worker planner: merge whole small surviving physical generations, never live overlays. */
final class NativeConsolidation {
    static final int CHUNKS=128,CANDIDATES=64,MIN_PATHS=16;
    static final long BYTES=32L*1024*1024;
    record Plan(Set<Long> chunks,int sources,long bytes,int candidates,String cursor,boolean again,String reason){}
    private static final Comparator<Path> ORDER=Comparator.comparing(p->p.getFileName().toString());
    private static final class Group {final Path path;int count;final List<NativeCommitIndex.Stored> rows=new ArrayList<>();Group(Path path){this.path=path;}}
    static Plan plan(NativeCommitIndex index,String cursor)throws IOException{
        NativeTileStates.worker();Comparator<Path> rotating=Comparator.comparingInt((Path p)->p.getFileName().toString().compareTo(cursor)>0?0:1).thenComparing(ORDER);TreeSet<Path> candidates=new TreeSet<>(rotating);boolean[] overflow={false};sample(index,candidates,overflow);
        if(candidates.size()<MIN_PATHS)return new Plan(Set.of(),0,0,candidates.size(),"",false,"few_physical_generations");
        Map<Path,Group> groups=new HashMap<>();for(Path path:candidates)groups.put(path,new Group(path));
        index.stored(row->{Group group=groups.get(row.generation());if(group!=null){group.count++;if(group.rows.size()<CHUNKS)group.rows.add(row);}});
        List<Group> sorted=new ArrayList<>(groups.values());sorted.sort(Comparator.comparingInt((Group g)->g.count).thenComparing(g->g.path,ORDER));
        Set<Long> selected=new LinkedHashSet<>();long bytes=0;int sources=0,metadataReads=0;
        for(Group group:sorted){
            if(group.path.getFileName().toString().equals(index.generation())||group.count>CHUNKS-selected.size()||group.count>CHUNKS-metadataReads)continue;
            long size=0;boolean fits=true;for(var row:group.rows){metadataReads++;size=Math.addExact(size,size(row));if(size>BYTES-bytes){fits=false;break;}}
            if(!fits)continue;for(var row:group.rows)selected.add(row.key());bytes+=size;sources++;
        }
        String next=candidates.last().getFileName().toString();if(sources<2)return new Plan(Set.of(),0,0,candidates.size(),next,false,"no_bounded_merge");
        boolean again=overflow[0]||candidates.size()-sources+1>=MIN_PATHS;
        return new Plan(Set.copyOf(selected),sources,bytes,candidates.size(),next,again,"selected");
    }
    private static void sample(NativeCommitIndex index,TreeSet<Path> selected,boolean[] overflow)throws IOException{
        index.stored(row->{selected.add(row.generation());if(selected.size()>CANDIDATES){selected.pollLast();overflow[0]=true;}});
    }
    /** Read a16-byte table entry and sidecar length, not chunk/compound bodies during selection. */
    private static long size(NativeCommitIndex.Stored row)throws IOException{
        int x=(int)(row.key()>>32),z=(int)row.key();Path region=row.generation().resolve("region"),file=region.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".szr");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file.toAbsolutePath().normalize()))throw new IOException("native_consolidation_region_alias");
        long bytes;try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            RegionTerrain.regionHeader(channel,Math.floorDiv(x,32),Math.floorDiv(z,32));ByteBuffer entry=ByteBuffer.allocate(16);long at=16L+16L*(Math.floorMod(z,32)*32+Math.floorMod(x,32));while(entry.hasRemaining()){int read=channel.read(entry,at+entry.position());if(read<0)throw new EOFException("native_consolidation_table");}entry.flip();long offset=entry.getLong();int length=entry.getInt();if(offset<16400||length<9||length>ScenePayload.MAX_RECORD||offset>channel.size()-length)throw new IOException("native_consolidation_record_bounds");bytes=length;
        }
        Path tile=NativeTileStates.path(region,x,z);if(Files.exists(tile,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(tile,LinkOption.NOFOLLOW_LINKS)||!tile.toRealPath().equals(tile.toAbsolutePath().normalize()))throw new IOException("native_consolidation_tile_alias");bytes=Math.addExact(bytes,Files.size(tile));}return bytes;
    }
    private NativeConsolidation(){}
}
