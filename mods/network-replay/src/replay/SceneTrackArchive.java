package replay;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Portable property tracks, bound to the original traffic and the installed mapping release. */
public final class SceneTrackArchive {
    static final int MAGIC=0x53535452,VERSION=1,MAX_OBJECTS=100000,MAX_KEYS=2000000;
    static final long MAX_BYTES=512L*1024*1024;
    private SceneTrackArchive(){}
    public static Path beside(Path demo){return demo.resolveSibling(demo.getFileName()+".scene-tracks");}
    public static void write(Path path,SceneTracks scene)throws IOException {
        Path absolute=path.toAbsolutePath();Files.createDirectories(absolute.getParent());Path temp=Files.createTempFile(absolute.getParent(),"scene-tracks-",".tmp");
        try{
            MessageDigest digest=sha();
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                zip.putNextEntry(new ZipEntry("tracks.bin"));DataOutputStream out=new DataOutputStream(new DigestOutputStream(zip,digest));
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(scene.eventHash);out.writeUTF(scene.compatibilityFingerprint);out.writeLong(scene.durationNs);
                int mask=0;for(SceneTracks.Domain d:scene.coverage)mask|=1<<d.ordinal();out.writeInt(mask);out.writeInt(scene.objects.size());long totalKeys=0;
                if(scene.objects.size()>MAX_OBJECTS)throw new IOException("Too many scene objects");
                for(SceneTracks.ObjectTrack object:scene.objects){
                    out.writeInt(object.id().session());out.writeInt(object.id().networkId());out.writeInt(object.id().generation());out.writeUTF(object.type());out.writeLong(object.bornNs());out.writeLong(object.deadNs());
                    if(object.channels().size()>4096)throw new IOException("Too many object channels");out.writeInt(object.channels().size());
                    for(var channel:new TreeMap<>(object.channels()).entrySet()){
                        SceneTracks.Track track=channel.getValue();out.writeUTF(channel.getKey());out.writeByte(track.domain.ordinal());out.writeByte(track.blend.ordinal());out.writeInt(track.keys.size());
                        if((totalKeys+=track.keys.size())>MAX_KEYS)throw new IOException("Scene key budget exceeded");
                        for(SceneTracks.Key key:track.keys){out.writeLong(key.timeNs());out.writeBoolean(key.cut());writeValue(out,key.value());}
                    }
                }
                out.flush();if(out.size()>MAX_BYTES)throw new IOException("Scene track payload exceeds limit");zip.closeEntry();
                zip.putNextEntry(new ZipEntry("sha256"));zip.write(HexFormat.of().formatHex(digest.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));zip.closeEntry();
            }
            // A failed preparation must never replace a previously usable result.
            Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temp);}
    }
    public static SceneTracks read(Path path,DemoFile demo)throws IOException {
        SceneTracks value=read(path);
        if(value.durationNs!=demo.durationNs||!value.eventHash.equals(demo.manifest.get("event_stream_sha256").getAsString())||!value.compatibilityFingerprint.equals(demo.compatibilityFingerprint)){
            IOException failure=new IOException("Scene tracks belong to a different recording or mapping release");try{value.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
        return value;
    }
    public static SceneTracks read(Path path)throws IOException {
        try(ZipFile probe=new ZipFile(path.toFile())){if(probe.getEntry("index.bin")!=null)return ScenePagedArchive.read(path);}
        try(ZipFile zip=new ZipFile(path.toFile())){
            Set<String> names=new HashSet<>();for(var e=zip.entries();e.hasMoreElements();)if(!names.add(e.nextElement().getName()))throw new IOException("Duplicate scene archive entry");
            if(!names.equals(Set.of("tracks.bin","sha256")))throw new IOException("Invalid scene archive entries");
            ZipEntry payload=zip.getEntry("tracks.bin"),hash=zip.getEntry("sha256");if(payload.getSize()<0||payload.getSize()>MAX_BYTES||hash.getSize()!=64)throw new IOException("Scene archive size limit");
            String expected;try(InputStream in=zip.getInputStream(hash)){expected=new String(in.readNBytes(65),java.nio.charset.StandardCharsets.US_ASCII);}
            MessageDigest digest=sha();SceneTracks result;
            try(DataInputStream in=new DataInputStream(new DigestInputStream(new BufferedInputStream(zip.getInputStream(payload)),digest))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported scene tracks");String eventHash=in.readUTF(),fingerprint=in.readUTF();long duration=in.readLong();
                int mask=in.readInt();if((mask>>>SceneTracks.Domain.values().length)!=0)throw new IOException("Unknown scene domain");Set<SceneTracks.Domain> coverage=EnumSet.noneOf(SceneTracks.Domain.class);for(SceneTracks.Domain d:SceneTracks.Domain.values())if((mask&(1<<d.ordinal()))!=0)coverage.add(d);
                int objects=count(in.readInt(),MAX_OBJECTS);List<SceneTracks.ObjectTrack> rows=new ArrayList<>(objects);long totalKeys=0,totalValues=0;
                for(int n=0;n<objects;n++){
                    SceneTracks.ObjectId id=new SceneTracks.ObjectId(in.readInt(),in.readInt(),in.readInt());String type=in.readUTF();long born=in.readLong(),dead=in.readLong();Map<String,SceneTracks.Track> channels=new TreeMap<>();int channelCount=count(in.readInt(),4096);
                    for(int c=0;c<channelCount;c++){
                        String name=in.readUTF();int domain=in.readUnsignedByte(),blend=in.readUnsignedByte();if(domain>=SceneTracks.Domain.values().length||blend>=SceneTracks.Blend.values().length)throw new IOException("Unknown scene channel codec");
                        int size=count(in.readInt(),MAX_KEYS);if((totalKeys+=size)>MAX_KEYS)throw new IOException("Scene key budget exceeded");List<SceneTracks.Key> keys=new ArrayList<>(size);
                        for(int k=0;k<size;k++){long time=in.readLong();boolean cut=in.readBoolean();SceneTracks.Value v=readValue(in);totalValues+=valueBytes(v);if(totalValues>MAX_BYTES)throw new IOException("Scene value budget exceeded");keys.add(new SceneTracks.Key(time,v,cut));}
                        if(channels.put(name,new SceneTracks.Track(SceneTracks.Domain.values()[domain],SceneTracks.Blend.values()[blend],keys))!=null)throw new IOException("Duplicate scene channel");
                    }
                    rows.add(new SceneTracks.ObjectTrack(id,type,born,dead,channels));
                }
                if(in.read()!=-1)throw new IOException("Trailing scene data");result=new SceneTracks(duration,eventHash,fingerprint,coverage,rows);
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IOException("Scene track hash mismatch");return result;
        }catch(IllegalArgumentException e){throw new IOException("Invalid scene track data",e);}
    }
    private static int count(int n,int max)throws IOException {if(n<0||n>max)throw new IOException("Scene allocation limit");return n;}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static long valueBytes(SceneTracks.Value v){if(v instanceof SceneTracks.Numbers n)return 8L*n.size();if(v instanceof SceneTracks.Bytes b)return b.size();return ((SceneTracks.Bones)v).byteSize();}
    static void writeValue(DataOutput out,SceneTracks.Value value)throws IOException {
        if(value instanceof SceneTracks.Numbers n){out.writeByte(0);double[] a=n.data();out.writeInt(a.length);for(double v:a)out.writeDouble(v);}
        else if(value instanceof SceneTracks.Bytes b){out.writeByte(1);byte[] a=b.data();out.writeInt(a.length);out.write(a);}
        else {out.writeByte(2);SceneTracks.Bones b=(SceneTracks.Bones)value;for(float[] a:new float[][]{b.positions(),b.rotations(),b.curves()}){out.writeInt(a.length);for(float v:a)out.writeFloat(v);}}
    }
    static SceneTracks.Value readValue(DataInputStream in)throws IOException {
        int type=in.readUnsignedByte();
        if(type==0){double[] a=new double[count(in.readInt(),65536)];for(int n=0;n<a.length;n++)a[n]=in.readDouble();return new SceneTracks.Numbers(a);}
        if(type==1){int size=count(in.readInt(),32*1024*1024);byte[] b=in.readNBytes(size);if(b.length!=size)throw new EOFException("Truncated scene value");return new SceneTracks.Bytes(b);}
        if(type==2){float[][] a=new float[3][];for(int n=0;n<3;n++){a[n]=new float[count(in.readInt(),n==0?196608:n==1?262144:65536)];byte[] raw=in.readNBytes(a[n].length*4);if(raw.length!=a[n].length*4)throw new EOFException("Truncated scene bone array");java.nio.ByteBuffer.wrap(raw).asFloatBuffer().get(a[n]);}return new SceneTracks.Bones(a[0],a[1],a[2]);}
        throw new IOException("Unknown scene value type");
    }
}
