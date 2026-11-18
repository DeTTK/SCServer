package replay;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Recorded projectile impacts and immutable native sprite states, independent of gunshot playback. */
final class SceneBloodIndex {
    static final int MAGIC=0x53424c44,VERSION=1;
    static final long TICK=50_000_000L,LIFETIME=550_000_000L;
    record Hit(long time,int shooter,int target,double[] start,double[] end,long seed) {
        Hit {start=start.clone();end=end.clone();}
    }
    record Splash(Hit hit,List<double[][]> frames) {}
    final List<Splash> splashes=new ArrayList<>();
    static List<Hit> hits(List<DemoFile.Event> events,java.util.function.IntFunction<String> mapping)throws IOException {
        List<Hit> result=new ArrayList<>();
        for(var event:events){
            if(!event.incoming()||!mapping.apply(event.sourceId()).equals("1103215333"))continue;
            try(var in=new DataInputStream(new ByteArrayInputStream(event.payload()))){
                int flags=in.readUnsignedByte(),shooter=in.readInt();double[] start=point(in),end=point(in);
                int target=(flags&2)!=0?in.readInt():-1;in.readByte();float volume=in.readFloat();
                if(in.available()!=0||!Float.isFinite(volume))throw new IOException("Invalid projectile impact payload");
                if((flags&2)!=0)result.add(new Hit(event.timeNs(),shooter,target,start,end,event.timeNs()^((long)event.sequence()<<32)^event.connection()));
            }
        }
        return List.copyOf(result);
    }
    static double[] point(DataInputStream in)throws IOException {double[] p={in.readFloat(),in.readFloat(),in.readFloat()};for(double v:p)if(!Double.isFinite(v))throw new IOException("Nonfinite impact position");return p;}
    List<Splash> at(long time){
        int lo=0,hi=splashes.size();while(lo<hi){int mid=(lo+hi)>>>1;if(splashes.get(mid).hit.time<=time-LIFETIME)lo=mid+1;else hi=mid;}
        int first=lo;while(lo<splashes.size()&&splashes.get(lo).hit.time<=time)lo++;
        return splashes.subList(first,lo);
    }
    static int frame(long age,int count){return Math.min(count-1,(int)(age/TICK)+1);}
    static float alpha(long age){return (float)((age%TICK)/(double)TICK);}
    void write(Path path,String hash,String fingerprint,String schema)throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());Path tmp=Files.createTempFile(path.toAbsolutePath().getParent(),"blood-index-",".tmp");
        try{
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))){
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(hash);out.writeUTF(fingerprint);out.writeUTF(schema);out.writeInt(splashes.size());
                for(var s:splashes){var h=s.hit;out.writeLong(h.time);out.writeInt(h.shooter);out.writeInt(h.target);for(double n:h.start)out.writeDouble(n);for(double n:h.end)out.writeDouble(n);out.writeLong(h.seed);out.writeInt(s.frames.size());
                    for(double[][] frame:s.frames){out.writeInt(frame.length);for(double[] p:frame){out.writeBoolean(p!=null);if(p!=null){out.writeInt(p.length);for(double n:p)out.writeDouble(n);}}}
                }
            }
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    static SceneBloodIndex read(Path path,String hash,String fingerprint,String schema)throws IOException {
        try(var in=new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!in.readUTF().equals(hash)||!in.readUTF().equals(fingerprint)||!in.readUTF().equals(schema))throw new IOException("Blood index does not match recording/client");
            var result=new SceneBloodIndex();int count=limit(in.readInt(),100_000);long previous=-1;long scalars=0;
            for(int n=0;n<count;n++){
                long time=in.readLong();if(time<previous||time<0)throw new IOException("Unordered blood impacts");previous=time;
                int shooter=in.readInt(),target=in.readInt();double[] start=new double[3],end=new double[3];for(int k=0;k<3;k++)start[k]=finite(in.readDouble());for(int k=0;k<3;k++)end[k]=finite(in.readDouble());long seed=in.readLong();
                int frames=limit(in.readInt(),12);if(frames<2)throw new IOException("Missing blood history");var states=new ArrayList<double[][]>();
                for(int f=0;f<frames;f++){int particles=limit(in.readInt(),3);double[][] row=new double[particles][];for(int p=0;p<particles;p++)if(in.readBoolean()){int fields=limit(in.readInt(),128);if((scalars+=fields)>8_000_000)throw new IOException("Blood history budget exceeded");row[p]=new double[fields];for(int k=0;k<fields;k++)row[p][k]=finite(in.readDouble());}states.add(row);}
                result.splashes.add(new Splash(new Hit(time,shooter,target,start,end,seed),List.copyOf(states)));
            }
            if(in.read()!=-1)throw new IOException("Trailing blood index data");return result;
        }
    }
    static int limit(int n,int max)throws IOException {if(n<0||n>max)throw new IOException("Blood index allocation limit");return n;}
    static double finite(double n)throws IOException {if(!Double.isFinite(n))throw new IOException("Nonfinite blood state");return n;}
}
