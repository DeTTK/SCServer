package replay;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;

/** Sound trajectories and resource/group names only. No process-local FMOD handles. */
final class SceneAudioArchive {
    static final int MAGIC=0x53415544,VERSION=2,MAX_CLIPS=1_000_000,MAX_SAMPLES=16_000_000;
    record Voice(boolean event,String resource,String group,double origin,long born,long dead,ReplayAudio.Window window,List<NativeSceneAudio.Sample> samples){
        Voice {samples=List.copyOf(samples);}
    }
    record Data(long duration,List<Voice> voices,List<String> errors){Data {voices=List.copyOf(voices);errors=List.copyOf(errors);}}
    static Path beside(Path demo){return demo.resolveSibling(demo.getFileName()+".audio-tracks.gz");}
    static void requirePrepared(Path demo,String eventHash,String fingerprint,long duration)throws IOException {
        try(var in=new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(beside(demo)))))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!in.readUTF().equals(eventHash)||!in.readUTF().equals(fingerprint)||in.readLong()!=duration)
                throw new IOException("Звуковая дорожка не подготовлена для этой сборки. Подготовка записи выполняется отдельно от просмотра.");
        }
    }
    static void finite(float n)throws IOException{if(!Float.isFinite(n))throw new IOException("Nonfinite audio trajectory");}
    static void validate(Voice voice,long duration)throws IOException{
        if(voice.resource()==null||voice.resource().isBlank()||voice.group()==null||!voice.event()&&voice.group().isBlank()||!Double.isFinite(voice.origin())||voice.dead()<voice.born()||voice.dead()>duration+1||voice.samples().isEmpty())throw new IOException("Invalid recorded sound voice");
        ReplayAudio.Window w=voice.window();if(w!=null&&(w.length()<0||w.loopStart()<0||w.loopEnd()<w.loopStart()||w.loopEnd()>w.length()||w.repeats()<-1))throw new IOException("Invalid audio loop window");
        long previous=-1;for(var s:voice.samples()){
            if(s.time()<0||s.time()>duration||s.time()<=previous||s.millis()<0||s.spatial().length!=(voice.event()?12:6))throw new IOException("Invalid audio sample sequence");previous=s.time();
            finite(s.pitch());finite(s.volume());finite(s.frequency());finite(s.minDistance());finite(s.maxDistance());for(float n:s.spatial())finite(n);
        }
    }
    static void write(Path path,String eventHash,String fingerprint,Data data)throws IOException{
        if(data.duration()<0||data.duration()==Long.MAX_VALUE||data.voices().size()>MAX_CLIPS||data.errors().size()>1024)throw new IOException("Audio archive limits");
        Path tmp=Files.createTempFile(path.toAbsolutePath().getParent(),"audio-tracks-",".tmp");long samples=0;
        try{
            try(var out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))){
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(eventHash);out.writeUTF(fingerprint);out.writeLong(data.duration());out.writeInt(data.errors().size());for(String error:data.errors())out.writeUTF(error);
                out.writeInt(data.voices().size());for(var v:data.voices()){
                    validate(v,data.duration());if((samples+=v.samples().size())>MAX_SAMPLES)throw new IOException("Audio sample allocation limit");
                    out.writeBoolean(v.event());out.writeUTF(v.resource());out.writeUTF(v.group());out.writeDouble(v.origin());out.writeLong(v.born());out.writeLong(v.dead());out.writeBoolean(v.window()!=null);
                    if(v.window()!=null){out.writeInt(v.window().length());out.writeInt(v.window().loopStart());out.writeInt(v.window().loopEnd());out.writeInt(v.window().repeats());}
                    out.writeInt(v.samples().size());for(var s:v.samples()){
                        out.writeLong(s.time());out.writeInt(s.millis());out.writeFloat(s.pitch());out.writeFloat(s.volume());out.writeFloat(s.frequency());out.writeInt(s.mode());
                        for(float n:s.spatial())out.writeFloat(n);out.writeFloat(s.minDistance());out.writeFloat(s.maxDistance());
                    }
                }
            }
            try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    static int count(int n,int max)throws IOException{if(n<0||n>max)throw new IOException("Audio archive allocation limit");return n;}
    static Data read(Path path,String eventHash,String fingerprint,long duration)throws IOException{
        if(Files.size(path)>512L*1024*1024)throw new IOException("Audio archive size limit");
        try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!in.readUTF().equals(eventHash)||!in.readUTF().equals(fingerprint)||in.readLong()!=duration)throw new IOException("Audio archive recording/client mismatch");
            int errors=count(in.readInt(),1024);var failures=new ArrayList<String>();for(int n=0;n<errors;n++)failures.add(in.readUTF());
            int clips=count(in.readInt(),MAX_CLIPS);var voices=new ArrayList<Voice>();long total=0;
            for(int n=0;n<clips;n++){
                boolean event=in.readBoolean();String resource=in.readUTF(),group=in.readUTF();double origin=in.readDouble();long born=in.readLong(),dead=in.readLong();
                ReplayAudio.Window window=in.readBoolean()?new ReplayAudio.Window(in.readInt(),in.readInt(),in.readInt(),in.readInt()):null;
                int size=count(in.readInt(),MAX_SAMPLES);if((total+=size)>MAX_SAMPLES)throw new IOException("Audio sample allocation limit");var samples=new ArrayList<NativeSceneAudio.Sample>();
                for(int i=0;i<size;i++){
                    long time=in.readLong();int millis=in.readInt();float pitch=in.readFloat(),volume=in.readFloat(),frequency=in.readFloat();int mode=in.readInt();float[] spatial=new float[event?12:6];for(int j=0;j<spatial.length;j++)spatial[j]=in.readFloat();
                    samples.add(new NativeSceneAudio.Sample(time,millis,pitch,volume,frequency,mode,spatial,in.readFloat(),in.readFloat()));
                }
                var voice=new Voice(event,resource,group,origin,born,dead,window,samples);validate(voice,duration);voices.add(voice);
            }
            if(in.read()!=-1)throw new IOException("Trailing audio trajectory data");return new Data(duration,voices,failures);
        }
    }
}
