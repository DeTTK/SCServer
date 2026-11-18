package replay;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Recorded beam inputs and lifetimes, independent of the current camera and playback direction. */
final class SceneTracerIndex {
    static final int MAGIC=0x53545243,VERSION=1;
    record Style(String texture,float duration,float length,float width,float red,float green,float blue,float intensity) {
        Style {if(texture==null||texture.isBlank()||!Float.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Invalid tracer style");for(float n:new float[]{length,width,red,green,blue,intensity})if(!Float.isFinite(n))throw new IllegalArgumentException("Nonfinite tracer style");}
    }
    record Point(double x,double y,double z) {Point{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Nonfinite beam point");}}
    static final class Beam {
        final long id,born;final Style style;final Point source,target;
        final NavigableMap<Long,Point> origins=new TreeMap<>();
        Beam(long id,long born,Style style,Point source,Point target){if(id<0||born<0)throw new IllegalArgumentException("Invalid beam identity");this.id=id;this.born=born;this.style=style;this.source=source;this.target=target;origins.put(born,source);}
        long end(){return born+Math.round(style.duration()*1e9);}
        float progress(long time){return (float)Math.max(0,Math.min(1,(time-born)/(style.duration()*1e9)));}
        Point origin(long time){return origins.floorEntry(Math.max(born,time)).getValue();}
    }
    final List<Beam> beams=new ArrayList<>();final NavigableMap<Long,List<Beam>> starts=new TreeMap<>();long longest;
    void add(Beam beam){beams.add(beam);starts.computeIfAbsent(beam.born,k->new ArrayList<>()).add(beam);longest=Math.max(longest,beam.end()-beam.born);}
    List<Beam> at(long time){List<Beam> result=new ArrayList<>();for(var group:starts.subMap(Math.max(0,time-longest),true,time,true).values())for(Beam b:group)if(time<b.end())result.add(b);return result;}
    void write(Path path,String hash,String fingerprint)throws IOException {
        Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),"tracer-index-",".tmp");try{try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(hash);out.writeUTF(fingerprint);out.writeInt(beams.size());for(Beam b:beams){out.writeLong(b.id);out.writeLong(b.born);out.writeUTF(b.style.texture());for(float n:new float[]{b.style.duration(),b.style.length(),b.style.width(),b.style.red(),b.style.green(),b.style.blue(),b.style.intensity()})out.writeFloat(n);point(out,b.source);point(out,b.target);out.writeInt(b.origins.size());for(var e:b.origins.entrySet()){out.writeLong(e.getKey());point(out,e.getValue());}}}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    static SceneTracerIndex read(Path path,String hash,String fingerprint)throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!in.readUTF().equals(hash)||!in.readUTF().equals(fingerprint))throw new IOException("Tracer index does not match recording/client");var index=new SceneTracerIndex();int count=SceneParticleStore.count(in.readInt(),10_000_000);Set<Long> ids=new HashSet<>();for(int n=0;n<count;n++){long id=in.readLong(),born=in.readLong();var style=new Style(in.readUTF(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat());var beam=new Beam(id,born,style,point(in),point(in));if(!ids.add(id))throw new IOException("Duplicate beam ID");int keys=SceneParticleStore.count(in.readInt(),1_000_000);if(keys==0)throw new IOException("Missing beam origin");beam.origins.clear();long previous=-1;for(int k=0;k<keys;k++){long time=in.readLong();if(time<born||time<previous||time>beam.end()||beam.origins.put(time,point(in))!=null)throw new IOException("Invalid beam origin time");previous=time;}if(beam.origins.firstKey()!=born)throw new IOException("Missing initial beam origin");index.add(beam);}if(in.read()!=-1)throw new IOException("Trailing tracer data");return index;}catch(IllegalArgumentException e){throw new IOException("Invalid tracer data",e);}
    }
    static void point(DataOutput out,Point p)throws IOException {out.writeDouble(p.x());out.writeDouble(p.y());out.writeDouble(p.z());}
    static Point point(DataInput in)throws IOException {return new Point(in.readDouble(),in.readDouble(),in.readDouble());}
}
