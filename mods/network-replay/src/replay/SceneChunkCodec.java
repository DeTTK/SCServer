package replay;

import java.io.*;
import java.util.*;

/** A single complete chunk value. No network delta, world snapshot or runtime handle. */
final class SceneChunkCodec {
    static final int MAGIC=0x53434348, VERSION=1, MAX_TILES=65536;
    record Chunk(int x,int z,int sections,int highIds,byte[] contents,List<byte[]> tiles) {
        Chunk {
            if((sections&~65535)!=0||(highIds&~sections)!=0||contents.length!=length(sections,highIds))throw new IllegalArgumentException("Invalid complete chunk layout");
            contents=contents.clone();List<byte[]> owned=new ArrayList<>();long total=contents.length;
            if(tiles.size()>MAX_TILES)throw new IllegalArgumentException("Too many chunk tiles");
            for(byte[] tile:tiles){if(tile.length==0||(total+=tile.length)>32*1024*1024)throw new IllegalArgumentException("Invalid chunk tile data");owned.add(tile.clone());}tiles=List.copyOf(owned);
        }
        public byte[] contents(){return contents.clone();}
        public List<byte[]> tiles(){return tiles.stream().map(byte[]::clone).toList();}
    }
    static int length(int sections,int high){return 256+Integer.bitCount(sections)*12288+Integer.bitCount(high)*2048;}
    static SceneTracks.Bytes encode(Chunk chunk)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(chunk.x());out.writeInt(chunk.z());out.writeInt(chunk.sections());out.writeInt(chunk.highIds());out.writeInt(chunk.contents.length);out.write(chunk.contents);out.writeInt(chunk.tiles.size());
            for(byte[] tile:chunk.tiles){out.writeInt(tile.length);out.write(tile);}
        }return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Chunk decode(SceneTracks.Bytes value)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(value.data()))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported scene chunk");
            int x=in.readInt(),z=in.readInt(),sections=in.readInt(),high=in.readInt(),size=in.readInt();
            if((sections&~65535)!=0||(high&~sections)!=0||size!=length(sections,high)||size>in.available())throw new IOException("Invalid scene chunk contents");
            byte[] contents=in.readNBytes(size);int count=in.readInt();if(count<0||count>MAX_TILES)throw new IOException("Invalid scene tile count");List<byte[]> tiles=new ArrayList<>();
            for(int i=0;i<count;i++){int n=in.readInt();if(n<1||n>in.available())throw new IOException("Invalid scene tile length");tiles.add(in.readNBytes(n));}
            if(in.available()!=0)throw new IOException("Trailing scene chunk data");return new Chunk(x,z,sections,high,contents,tiles);
        }catch(IllegalArgumentException failure){throw new IOException("Invalid scene chunk",failure);}
    }
    static SceneTracks.Bytes capture(Object chunk)throws Exception {
        if(!Native.type("812508934").isInstance(chunk))throw new IOException("Not a canonical chunk");
        Object[] parts=(Object[])Native.field(chunk,"extends");if(parts.length!=16)throw new IOException("Canonical chunk section count changed");
        int sections=0,high=0;for(int i=0;i<16;i++)if(parts[i]!=null){sections|=1<<i;if(Native.call(parts[i],"long","1425466732")!=null)high|=1<<i;}
        var bytes=new ByteArrayOutputStream();
        // Exact order consumed by 812508934.interface([B,I,I,true).
        for(Object part:parts)if(part!=null)append(bytes,(byte[])Native.call(part,"protected","[B"),4096);
        for(String method:new String[]{"implements","while","public","break"})for(Object part:parts)if(part!=null){Object nibble=Native.call(part,method,"1425466732");append(bytes,(byte[])Native.call(nibble,"switch","[B"),2048);}
        for(Object part:parts)if(part!=null){Object nibble=Native.call(part,"long","1425466732");if(nibble!=null)append(bytes,(byte[])Native.call(nibble,"switch","[B"),2048);}
        append(bytes,(byte[])Native.field(chunk,"native"),256);
        List<byte[]> tiles=new ArrayList<>();for(Object tile:(Collection<?>)Native.call(chunk,"while","java.util.Collection"))tiles.add(nbt(tile,"static"));
        // Native hash maps don't promise iteration order; stable portable values deduplicate.
        tiles.sort(Arrays::compareUnsigned);
        return encode(new Chunk(((Number)Native.field(chunk,"transient")).intValue(),((Number)Native.field(chunk,"float")).intValue(),sections,high,bytes.toByteArray(),tiles));
    }
    static void append(ByteArrayOutputStream out,byte[] bytes,int size)throws IOException {if(bytes.length!=size)throw new IOException("Canonical chunk array size changed");out.write(bytes);}
    static byte[] nbt(Object object,String method)throws Exception {Object tag=Native.type("98008865").getConstructor().newInstance();Native.call(object,method,"void",tag);return tag(tag);}
    static byte[] tag(Object tag)throws Exception {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){Native.call(Native.type("1464336358"),"char","void",tag,out);}return bytes.toByteArray();}
    static Object tag(byte[] bytes)throws Exception {try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){Object tag=Native.call(Native.type("1464336358"),"int","98008865",in);if(in.available()!=0)throw new IOException("Trailing scene NBT");return tag;}}
    /** Build in quarantine. The caller controls admission and ownership. */
    static Object create(Object world,Chunk value)throws Exception {
        Object chunk=Native.type("812508934").getConstructor(Native.type("1500514786"),int.class,int.class).newInstance(world,value.x(),value.z());
        Object previous=ReplayRender.beginSceneChunk(chunk);
        try{Native.call(chunk,"interface","void",value.contents(),value.sections(),value.highIds(),true);}
        finally{ReplayRender.endSceneChunk(previous);}
        // The provider sets this flag after admitting a loaded chunk. A complete
        // quarantined value is equally ready for reading, without registry events.
        SourceAdapters.put(chunk,"throw",true);
        for(byte[] bytes:value.tiles()){Object tile=Native.call(Native.type("1123841866"),"goto","1123841866",tag(bytes));if(tile==null)throw new IOException("Unknown selected tile template");Native.call(chunk,"enum","void",tile);}
        return chunk;
    }
}
