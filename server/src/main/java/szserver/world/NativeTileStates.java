package szserver.world;

import szserver.client.CompleteTileState;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Full compounds are a checked sidecar: the native SZR tile tail only carries its compact appearance. */
final class NativeTileStates {
    private static final int MAGIC=0x535A5453,VERSION=1;
    static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("native_archive_io_on_owner");}
    static Path path(Path root,int x,int z){return root.resolve("tile-states").resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)).resolve("c."+x+"."+z+".bin");}
    private static int crc(byte[] bytes){CRC32 sum=new CRC32();sum.update(bytes);return (int)sum.getValue();}
    static List<TilePayload.Tile> attach(Path root,int x,int z,List<TilePayload.Tile> tiles)throws IOException{
        worker();Path path=path(root,x,z);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return tiles;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path.toAbsolutePath().normalize())||Files.size(path)>32L*1024*1024+24L+TilePayload.MAX_TILES*16L)throw new IOException("native_complete_tile_file");
        Map<Integer,TilePayload.Tile> values=new HashMap<>();for(var tile:tiles)values.put(tile.cell(),tile);Set<Integer> seen=new HashSet<>();long bytes=0;
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path),65536))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=x||in.readInt()!=z)throw new IOException("native_complete_tile_header");int count=in.readInt();if(count<1||count>TilePayload.MAX_TILES)throw new IOException("native_complete_tile_count");
            for(int n=0;n<count;n++){int cell=in.readInt(),recordCrc=in.readInt(),length=in.readInt(),checksum=in.readInt();TilePayload.Tile tile=values.get(cell);if(tile==null||!seen.add(cell)||crc(tile.record())!=recordCrc||length<4||length>CompleteTileState.MAX_STATE||(bytes+=length)>32L*1024*1024)throw new IOException("native_complete_tile_reference");byte[] state=in.readNBytes(length);if(state.length!=length||crc(state)!=checksum)throw new IOException("native_complete_tile_crc");
                try{var decoded=CompleteTileState.decode(state);if(!Arrays.equals(decoded.record(),tile.record()))throw new IOException("native_complete_tile_appearance");values.put(cell,tile.withCompleteState(state));}catch(IllegalArgumentException invalid){throw new IOException("native_complete_tile_compound",invalid);}}
            if(in.read()!=-1)throw new IOException("native_complete_tile_trailing");
        }
        List<TilePayload.Tile> result=new ArrayList<>(tiles.size());for(var tile:tiles)result.add(values.get(tile.cell()));return List.copyOf(result);
    }
    static long write(Path root,int x,int z,Collection<TilePayload.Tile> tiles)throws IOException{
        worker();List<TilePayload.Tile> complete=tiles.stream().filter(t->t.completeStateSize()>0).sorted(Comparator.comparingInt(TilePayload.Tile::cell)).toList();if(complete.isEmpty())return 0;Path path=path(root,x,z);Files.createDirectories(path.getParent());long bytes=0;
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);var out=new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel),65536))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(x);out.writeInt(z);out.writeInt(complete.size());
            for(var tile:complete){byte[] state=tile.completeState();if((bytes+=state.length)>32L*1024*1024)throw new IOException("native_complete_tile_bytes");out.writeInt(tile.cell());out.writeInt(crc(tile.record()));out.writeInt(state.length);out.writeInt(crc(state));out.write(state);}out.flush();channel.force(true);
        }
        return bytes;
    }
}
