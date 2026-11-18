package replay;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Final native FP bone tracks. No world checkpoint or native handles are persisted. */
final class SceneFpPoses {
    final Map<String,List<SceneTracks.Key>> keys=new TreeMap<>();
    final Map<String,List<SceneTracks.Track>> tracks=new TreeMap<>();
    final List<SceneTracks.Key> aimKeys=new ArrayList<>();SceneTracks.Track aimTrack;
    final List<SceneTracks.Key> motionKeys=new ArrayList<>();SceneTracks.Track motionTrack;
    final List<SceneTracks.Key> itemKeys=new ArrayList<>();SceneTracks.Track itemTrack;
    SceneTracks.Bytes currentItem;boolean itemCut;
    long bytes;
    void add(String role,long time,SceneTracks.Bones pose){
        bytes+=pose.byteSize();if(bytes>256L*1024*1024)throw new IllegalStateException("FP pose index exceeds 256 MiB");
        var rows=keys.computeIfAbsent(role,unused->new ArrayList<>());boolean cut=!rows.isEmpty()&&!same((SceneTracks.Bones)rows.get(rows.size()-1).value(),pose);
        rows.add(new SceneTracks.Key(time,pose,cut||itemCut));
    }
    static boolean same(SceneTracks.Bones a,SceneTracks.Bones b){return a.boneCount()==b.boneCount()&&a.curveCount()==b.curveCount();}
    void finish(){
        if(!aimKeys.isEmpty())aimTrack=new SceneTracks.Track(SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.LINEAR,aimKeys);
        if(!motionKeys.isEmpty())motionTrack=new SceneTracks.Track(SceneTracks.Domain.TRANSFORMS,SceneTracks.Blend.HOLD,motionKeys);
        if(!itemKeys.isEmpty())itemTrack=new SceneTracks.Track(SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,itemKeys);
        tracks.clear();for(var entry:keys.entrySet()){
            var segments=new ArrayList<SceneTracks.Track>();var rows=new ArrayList<SceneTracks.Key>();
            for(var key:entry.getValue()){if(key.cut()&&!rows.isEmpty()){segments.add(new SceneTracks.Track(SceneTracks.Domain.POSES,SceneTracks.Blend.BONES,rows));rows=new ArrayList<>();}rows.add(key);}
            if(!rows.isEmpty())segments.add(new SceneTracks.Track(SceneTracks.Domain.POSES,SceneTracks.Blend.BONES,rows));tracks.put(entry.getKey(),List.copyOf(segments));
        }
    }
    void aim(long time,float value){aimKeys.add(new SceneTracks.Key(time,new SceneTracks.Numbers(value),false));}
    float aim(long time){var value=aimTrack==null?null:aimTrack.sample(time);return value instanceof SceneTracks.Numbers n?(float)n.data()[0]:0;}
    void motion(long time,SceneTracks.Numbers value){if(value!=null)motionKeys.add(new SceneTracks.Key(time,value,false));}
    SceneTracks.Numbers motion(long time){return motionTrack==null?null:(SceneTracks.Numbers)motionTrack.sample(time);}
    void item(long time,SceneTracks.Bytes value){itemCut=!Objects.equals(currentItem,value);currentItem=value;if(itemCut||itemKeys.isEmpty())itemKeys.add(new SceneTracks.Key(time,value,false));}
    SceneTracks.Bytes item(long time){return itemTrack==null?null:(SceneTracks.Bytes)itemTrack.sample(time);}
    SceneTracks.Bones sample(String role,long time){
        var segments=tracks.getOrDefault(role,List.of());int lo=0,hi=segments.size();while(lo<hi){int mid=(lo+hi)>>>1;if(segments.get(mid).timeAt(0)<=time)lo=mid+1;else hi=mid;}
        return lo==0?null:(SceneTracks.Bones)segments.get(lo-1).sample(time);
    }
    void write(Path path,String recording,String client,SceneTracks.ObjectId actor)throws IOException {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.createDirectories(path.getParent());
        try(var out=new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))))){
            out.writeInt(0x46505053);out.writeInt(4);out.writeUTF(recording);out.writeUTF(client);out.writeInt(actor.session());out.writeInt(actor.networkId());out.writeInt(actor.generation());out.writeInt(aimKeys.size());for(var key:aimKeys){out.writeLong(key.timeNs());out.writeFloat((float)((SceneTracks.Numbers)key.value()).data()[0]);}out.writeInt(motionKeys.size());for(var key:motionKeys){out.writeLong(key.timeNs());for(double value:((SceneTracks.Numbers)key.value()).data())out.writeDouble(value);}out.writeInt(itemKeys.size());for(var key:itemKeys){byte[] value=((SceneTracks.Bytes)key.value()).data();out.writeLong(key.timeNs());out.writeInt(value.length);out.write(value);}out.writeInt(keys.size());
            for(var row:keys.entrySet()){out.writeUTF(row.getKey());out.writeInt(row.getValue().size());for(var key:row.getValue()){var pose=(SceneTracks.Bones)key.value();out.writeLong(key.timeNs());out.writeBoolean(key.cut());for(float[] values:new float[][]{pose.positions(),pose.rotations(),pose.curves()}){out.writeInt(values.length);for(float v:values)out.writeFloat(v);}}}
        }catch(Throwable failure){Files.deleteIfExists(temporary);throw failure;}
        Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
    }
    static SceneFpPoses read(Path path,String recording,String client,SceneTracks.ObjectId actor)throws IOException {
        if(Files.size(path)>256L*1024*1024)throw new IOException("Oversized FP pose archive");var result=new SceneFpPoses();
        try(var in=new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))){
            if(in.readInt()!=0x46505053||in.readInt()!=4||!in.readUTF().equals(recording)||!in.readUTF().equals(client)||in.readInt()!=actor.session()||in.readInt()!=actor.networkId()||in.readInt()!=actor.generation())throw new IOException("FP pose identity changed");
            int aims=in.readInt();if(aims<0||aims>300000)throw new IOException("FP aim frame count");long previousAim=-1;for(int n=0;n<aims;n++){long ns=in.readLong();float value=in.readFloat();if(ns<=previousAim||!Float.isFinite(value)||value<0||value>1)throw new IOException("FP aim value/order");previousAim=ns;result.aim(ns,value);}
            int motions=in.readInt();if(motions<0||motions>300000)throw new IOException("FP motion frame count");long previousMotion=-1;for(int n=0;n<motions;n++){long ns=in.readLong();if(ns<=previousMotion)throw new IOException("FP motion frame order");previousMotion=ns;double[] values=new double[5];for(int k=0;k<5;k++)values[k]=in.readDouble();result.motion(ns,new SceneTracks.Numbers(values));}
            int items=in.readInt();if(items<0||items>300000)throw new IOException("FP item frame count");long previousItem=-1;for(int n=0;n<items;n++){long ns=in.readLong();int length=in.readInt();if(ns<=previousItem||length<1||length>65536)throw new IOException("FP item value/order");previousItem=ns;byte[] value=new byte[length];in.readFully(value);result.item(ns,new SceneTracks.Bytes(value));}result.itemCut=false;
            int roles=in.readInt();if(roles<0||roles>16)throw new IOException("FP pose role count");
            if(roles==0&&(result.itemKeys.isEmpty()||result.itemKeys.stream().anyMatch(k->{byte[] b=((SceneTracks.Bytes)k.value()).data();return b.length!=1||b[0]==0;})))throw new IOException("Armed FP archive has no pose roles");
            for(int r=0;r<roles;r++){String role=in.readUTF();if(result.keys.containsKey(role))throw new IOException("Duplicate FP pose role");int frames=in.readInt();if(frames<1||frames>300000)throw new IOException("FP pose frame count");long previous=-1;
                for(int n=0;n<frames;n++){long ns=in.readLong();boolean cut=in.readBoolean();if(ns<=previous)throw new IOException("FP pose frame order");previous=ns;float[][] arrays=new float[3][];
                    for(int a=0;a<3;a++){int count=in.readInt();if(count<0||count>262144||result.bytes+4L*count>256L*1024*1024)throw new IOException("FP pose dimensions");arrays[a]=new float[count];for(int k=0;k<count;k++)arrays[a][k]=in.readFloat();}
                    result.add(role,ns,new SceneTracks.Bones(arrays[0],arrays[1],arrays[2]));if(cut){var list=result.keys.get(role);list.set(list.size()-1,new SceneTracks.Key(ns,list.get(list.size()-1).value(),true));}
                }
            }
            if(in.read()!=-1)throw new IOException("Trailing FP pose data");result.finish();return result;
        }catch(RuntimeException failure){throw new IOException("Invalid FP pose archive",failure);}
    }
}
