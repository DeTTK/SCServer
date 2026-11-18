package replay;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Independently compressed renderer frames. Selecting time never steps an effect simulation. */
final class SceneParticleStore implements AutoCloseable {
    // v7 uses the TP animation controller's held item and evaluated NPC pose.
    static final int MAGIC=0x53504658,VERSION=7,LIMIT=32*1024*1024;
    record Ref(long offset,int compressed,int size,int checksum){}
    record Frame(float[] origin,Ref[] stages){Frame{origin=origin.clone();stages=stages.clone();}}
    final Path indexPath,dataPath;final FileChannel data;
    final String eventHash,fingerprint;
    final NavigableMap<Long,Frame> frames=new TreeMap<>();final Map<String,String> assets=new TreeMap<>();
    boolean recording,closed;long bytes,decoded;
    private SceneParticleStore(Path index,Path path,FileChannel channel,String hash,String fingerprint,boolean recording){indexPath=index;dataPath=path;data=channel;eventHash=hash;this.fingerprint=fingerprint;this.recording=recording;}
    static SceneParticleStore create(Path index,String hash,String fingerprint)throws IOException {
        index=index.toAbsolutePath();Files.createDirectories(index.getParent());Path path=index.resolveSibling(index.getFileName()+"."+UUID.randomUUID()+".data");
        return new SceneParticleStore(index,path,FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE),hash,fingerprint,true);
    }
    static SceneParticleStore open(Path index,String hash,String fingerprint)throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!in.readUTF().equals(hash)||!in.readUTF().equals(fingerprint))throw new IOException("Particle index does not match recording/client");
            String filename=in.readUTF();if(!Path.of(filename).getFileName().toString().equals(filename))throw new IOException("Invalid particle data name");
            Path path=index.toAbsolutePath().resolveSibling(filename);var store=new SceneParticleStore(index.toAbsolutePath(),path,FileChannel.open(path,StandardOpenOption.READ),hash,fingerprint,false);
            try{int count=count(in.readInt(),65536);for(int n=0;n<count;n++)if(store.assets.put(in.readUTF(),in.readUTF())!=null)throw new IOException("Duplicate particle asset");
                count=count(in.readInt(),4_000_000);long previous=-1,size=store.data.size();for(int n=0;n<count;n++){long time=in.readLong();if(time<=previous)throw new IOException("Unordered particle frames");previous=time;float[] origin={in.readFloat(),in.readFloat(),in.readFloat()};for(float value:origin)if(!Float.isFinite(value))throw new IOException("Nonfinite particle origin");Ref[] refs=new Ref[3];for(int stage=0;stage<3;stage++){refs[stage]=new Ref(in.readLong(),count(in.readInt(),LIMIT+65536),count(in.readInt(),LIMIT),in.readInt());Ref ref=refs[stage];if(ref.offset()<0||ref.compressed()==0||ref.offset()>size-ref.compressed())throw new IOException("Particle frame outside data");}store.frames.put(time,new Frame(origin,refs));}
                if(in.read()!=-1)throw new IOException("Trailing particle index data");store.bytes=size;return store;
            }catch(Throwable failure){store.close();throw failure;}
        }
    }
    static int count(int n,int maximum)throws IOException {if(n<0||n>maximum)throw new IOException("Particle allocation limit");return n;}
    void append(long time,float[] origin,byte[][] stages)throws IOException {
        if(!recording||closed||time<0||!frames.isEmpty()&&time<=frames.lastKey()||origin.length!=3||stages.length!=3)throw new IOException("Invalid particle capture sequence");
        for(float n:origin)if(!Float.isFinite(n))throw new IOException("Nonfinite particle origin");Ref[] refs=new Ref[3];
        for(int n=0;n<3;n++){byte[] input=stages[n];if(input.length>LIMIT)throw new IOException("Particle frame limit");CRC32 crc=new CRC32();crc.update(input);var compressed=new ByteArrayOutputStream();var deflater=new Deflater(1);try(var out=new DeflaterOutputStream(compressed,deflater)){out.write(input);}finally{deflater.end();}byte[] block=compressed.toByteArray();long offset=data.position();ByteBuffer buffer=ByteBuffer.wrap(block);while(buffer.hasRemaining())data.write(buffer);refs[n]=new Ref(offset,block.length,input.length,(int)crc.getValue());bytes+=block.length;}
        frames.put(time,new Frame(origin,refs));
    }
    byte[] read(Ref ref)throws IOException {
        ByteBuffer compressed=ByteBuffer.allocate(ref.compressed());long offset=ref.offset();while(compressed.hasRemaining()){int read=data.read(compressed,offset);if(read<0)throw new EOFException("Truncated particle page");offset+=read;}
        byte[] result;try(var stream=new InflaterInputStream(new ByteArrayInputStream(compressed.array()))){result=stream.readNBytes(ref.size()+1);if(result.length!=ref.size()||stream.read()!=-1)throw new IOException("Particle decoded size mismatch");}CRC32 crc=new CRC32();crc.update(result);if((int)crc.getValue()!=ref.checksum())throw new IOException("Particle page checksum mismatch");decoded++;return result;
    }
    Map.Entry<Long,Frame> at(long time){return frames.floorEntry(time);}
    void finish()throws IOException {
        if(!recording)return;data.force(false);Path temporary=Files.createTempFile(indexPath.getParent(),"particle-index-",".tmp");
        try{try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(eventHash);out.writeUTF(fingerprint);out.writeUTF(dataPath.getFileName().toString());out.writeInt(assets.size());for(var asset:assets.entrySet()){out.writeUTF(asset.getKey());out.writeUTF(asset.getValue());}out.writeInt(frames.size());for(var entry:frames.entrySet()){out.writeLong(entry.getKey());for(float n:entry.getValue().origin())out.writeFloat(n);for(Ref ref:entry.getValue().stages()){out.writeLong(ref.offset());out.writeInt(ref.compressed());out.writeInt(ref.size());out.writeInt(ref.checksum());}}}Files.move(temporary,indexPath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);recording=false;}finally{Files.deleteIfExists(temporary);}
    }
    public void close()throws IOException {if(closed)return;closed=true;data.close();if(recording)Files.deleteIfExists(dataPath);}
}

