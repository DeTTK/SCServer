package scvehicles;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import static scvehicles.VehiclePhysics.clamp;
import static scvehicles.VehicleSoundScene.*;

/** One output device, bounded independent voices, immutable game-thread snapshots. */
final class EngineAudio implements AutoCloseable {
    static final int RATE=44100;
    private volatile Scene target=Scene.silent();
    private final ArrayBlockingQueue<OneShot> events=new ArrayBlockingQueue<>(64);
    private volatile boolean running=true;
    private volatile String error="";
    private volatile SourceDataLine line;
    private Thread worker;
    private long dropped;
    void publish(Scene next){
        if(Boolean.getBoolean("sz.client.isolatedTest")){target=Scene.silent();events.clear();return;}
        target=next;
        for(OneShot event:next.events())if(next.master()>.001&&next.effects()>.001&&!events.offer(event))dropped++;
        if(worker==null&&running&&error.isEmpty()&&next.master()>.001&&(!events.isEmpty()||next.voices().stream().anyMatch(v->v.gain()>.001&&(v.engine()>.001||v.speed()>.2)))){
            worker=new Thread(this::run,"sc-vehicle-audio");worker.setDaemon(true);worker.start();
        }
    }
    void silence(){target=Scene.silent();events.clear();}
    Map<String,Object> inspect(){return Map.of("source","multi-RPM engine banks, load/overrun layers, spatial vehicle/event mixer","sources",target.voices().size(),"master",target.master(),"gain",target.master(),"queuedEvents",events.size(),"droppedEvents",dropped,"deviceOpen",line!=null,"error",error);}
    private void run(){
        try{
            Mixer mixer=new Mixer();AudioFormat format=new AudioFormat(RATE,16,2,true,false);
            SourceDataLine output=AudioSystem.getSourceDataLine(format);line=output;
            if(!running){output.close();return;}
            output.open(format,4096);output.start();byte[] bytes=new byte[2048];
            while(running){
                var pending=new ArrayList<OneShot>();events.drainTo(pending);
                mixer.render(bytes,target,pending);output.write(bytes,0,bytes.length);
            }
        }catch(Exception e){if(running){error=e.toString();System.err.println("[sc-vehicles] Audio disabled; transport remains available: "+e);}}
        finally{SourceDataLine output=line;if(output!=null)output.close();line=null;}
    }
    static final class Bank {
        final Map<String,float[]> samples=new HashMap<>();final Map<String,float[][]> engines=new HashMap<>();
        Bank()throws Exception{
            for(String family:List.of("tsu","su-low","gv8","ds8","v12d")){float[][] bank=new float[5][];for(int i=1;i<=5;i++){load(family+"-"+i);bank[i-1]=get(family+"-"+i);}engines.put(family,bank);}
            for(String name:List.of("start","start-diesel","failed-start","stop","enter","exit","select","denied","spawn","remove","door-open","door-close","skid","gear","impact-light","impact-heavy","scrape","splash","water","mud","dirt-hit","diesel-clatter","gravel","soil","sand","snow","track","switch","brake-set","brake-release","air-brake","suspension","wind","road","transmission","toy"))load(name);
        }
        private void load(String name)throws Exception{
            try(InputStream in=EngineAudio.class.getResourceAsStream("/scvehicles/audio/fleet/"+name+".wav")){
                if(in==null)throw new FileNotFoundException(name);
                try(AudioInputStream audio=AudioSystem.getAudioInputStream(new BufferedInputStream(in))){
                    AudioFormat f=audio.getFormat();if(f.getSampleRate()!=RATE||f.getChannels()!=1||f.getSampleSizeInBits()!=16||!f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)||f.isBigEndian())throw new IOException("Unexpected fleet PCM format: "+name);
                    byte[] bytes=audio.readAllBytes();float[] a=new float[bytes.length/2];for(int i=0;i<a.length;i++)a[i]=(short)((bytes[i*2]&255)|(bytes[i*2+1]<<8))/32768f;
                    if(a.length<100)throw new IOException("Empty sound "+name);samples.put(name,a);
                }
            }
        }
        float[] get(String name){return samples.get(name);}
    }
    static final class RenderVoice {
        final UUID id;Voice target;final double[] phases=new double[14];
        double gain,pan,filter,offFilter,mechanicalFilter,engine,rpm,load,rolling,skid,scrape,surfaceBlend=1;
        String surface="road",previousSurface="road";
        RenderVoice(Voice v){id=v.id();target=v;rpm=v.rpm();int seed=Math.floorMod(id.hashCode(),10000);for(int i=0;i<phases.length;i++)phases[i]=seed+i*1721;}
    }
    static final class Shot {
        final OneShot source;final float[] pcm;final double originalGain;double phase;
        Shot(OneShot source,float[] pcm,double originalGain){this.source=source;this.pcm=pcm;this.originalGain=originalGain;}
    }
    static final class Mixer {
        final Bank bank;
        final Map<UUID,RenderVoice> voices=new LinkedHashMap<>();final List<Shot> shots=new ArrayList<>();
        double master,effects;long samplesRendered;int peakVoices,peakShots;long discarded;
        Mixer()throws Exception{bank=new Bank();}
        static double sample(float[] a,double phase){phase%=a.length;if(phase<0)phase+=a.length;int i=(int)phase;double t=phase-i;return a[i]*(1-t)+a[(i+1)%a.length]*t;}
        private double loop(RenderVoice v,int slot,String name,double pitch){float[] pcm=bank.get(name);double value=sample(pcm,v.phases[slot]);v.phases[slot]=(v.phases[slot]+pitch)%pcm.length;return value;}
        private static String shotName(OneShot e,Voice parent){return switch(e.event()){
            case ENGINE_START->parent!=null&&parent.profile().electric()?"switch":parent!=null&&parent.profile().diesel()?"start-diesel":"start";case ENGINE_STOP->parent!=null&&parent.profile().electric()?"switch":"stop";case FUEL_EMPTY->e.id()==null?"failed-start":"stop";
            case DOOR_OPEN->"door-open";case DOOR_CLOSE->"door-close";
            case GEAR_UP,GEAR_DOWN->"gear";case HANDBRAKE_SET->"brake-set";case HANDBRAKE_RELEASE->"brake-release";
            case AIR_BRAKE->"air-brake";case IMPACT,ROLLOVER->e.level()>.38?"impact-heavy":"impact-light";
            case LAND->"dirt-hit";case SUSPENSION->"suspension";case SPLASH->"splash";
            case ENTER->"enter";case EXIT->"exit";case SELECT->"select";case DENIED->"denied";case SPAWN->"spawn";case REMOVE->"remove";
        };}
        void render(byte[] out,Scene scene,List<OneShot> pending){
            Map<UUID,Voice> targets=new HashMap<>();for(Voice v:scene.voices())targets.put(v.id(),v);
            for(Voice target:scene.voices()){
                if(!voices.containsKey(target.id())&&voices.size()>=MAX_VEHICLES+2){UUID old=voices.values().stream().filter(v->!targets.containsKey(v.id)).map(v->v.id).findFirst().orElse(null);if(old!=null)voices.remove(old);else{discarded++;continue;}}
                RenderVoice voice=voices.computeIfAbsent(target.id(),id->new RenderVoice(target));voice.target=target;
                String surface=switch(target.surface()){case WATER->"water";case MUD->"mud";case DIRT->"soil";case SAND->"sand";case SNOW->"snow";case GRAVEL->"gravel";default->"road";};
                if(!surface.equals(voice.surface)){voice.previousSurface=voice.surface;voice.surface=surface;voice.phases[13]=voice.phases[6];voice.surfaceBlend=0;}
            }
            voices.values().removeIf(v->!targets.containsKey(v.id)&&v.gain<.00001);
            for(OneShot event:pending){if(shots.size()>=32){discarded++;continue;}Voice parent=targets.get(event.id());shots.add(new Shot(event,bank.get(shotName(event,parent)),parent==null?1:Math.max(.001,parent.gain())));}
            peakVoices=Math.max(peakVoices,voices.size());peakShots=Math.max(peakShots,shots.size());
            double normalization=1/Math.sqrt(Math.max(1,scene.voices().stream().mapToDouble(v->v.gain()*v.gain()).sum()));
            Arrays.fill(out,(byte)0);RenderVoice[] voiceArray=voices.values().toArray(RenderVoice[]::new);Shot[] shotArray=shots.toArray(Shot[]::new);
            for(int i=0;i<out.length;i+=4){
                master+=(scene.master()-master)*.0012;effects+=(scene.effects()-effects)*.0012;
                double left=0,right=0;
                for(RenderVoice v:voiceArray){
                    Voice t=v.target;boolean active=targets.containsKey(v.id);VehicleSoundProfile p=t.profile();
                    v.gain+=((active?t.gain():0)-v.gain)*.0015;v.pan+=(t.pan()-v.pan)*.0015;
                    v.rpm+=(t.rpm()-v.rpm)*.0007;v.load+=(t.load()-v.load)*.0007;v.engine+=(t.engine()-v.engine)*.0012;
                    v.rolling+=(t.rolling()-v.rolling)*.001;v.skid+=(t.skid()-v.skid)*.0009;v.scrape+=(t.scrape()-v.scrape)*.001;v.surfaceBlend=Math.min(1,v.surfaceBlend+1./4410);
                    double rev=clamp((v.rpm-p.idle())/(p.limit()-p.idle()),0,1),recorded;
                    if(p.electric())recorded=loop(v,0,"toy",.6+rev*1.4);
                    else{
                        double[] anchors=p.anchors();int low=0;while(low<anchors.length-2&&v.rpm>anchors[low+1])low++;
                        double blend=clamp((v.rpm-anchors[low])/(anchors[low+1]-anchors[low]),0,1);recorded=0;float[][] captures=bank.engines.get(p.bank());
                        // Separate captures at measured RPM, each with a persistent cursor.
                        for(int n=0;n<anchors.length;n++){
                            float[] pcm=captures[n];double pitch=clamp(v.rpm/anchors[n],.62,1.55)*p.tone()*t.doppler();
                            double value=sample(pcm,v.phases[n]);v.phases[n]=(v.phases[n]+pitch)%pcm.length;
                            if(n==low)recorded+=value*Math.cos(blend*Math.PI*.5);else if(n==low+1)recorded+=value*Math.sin(blend*Math.PI*.5);
                        }
                    }
                    double cutoff=Math.min(t.cutoff(),1900+rev*850+v.load*550),alpha=cutoff/(cutoff+RATE/(2*Math.PI));v.filter+=(recorded-v.filter)*alpha;
                    // Off-throttle mechanical/exhaust remains audible; load changes
                    // the spectrum and relative layers, not only pitch or volume.
                    double offAlpha=650/(650+RATE/(2*Math.PI));v.offFilter+=(recorded-v.offFilter)*offAlpha;
                    double loaded=v.filter*(.17+.16*rev),off=v.offFilter*(.12+.12*rev+.07*t.overrun());
                    double engine=(off*(1-v.load)+loaded*v.load)*v.engine;
                    if(p.diesel())engine+=loop(v,5,"diesel-clatter",clamp(v.rpm/1100,.65,1.7)*p.tone())*.045*(1-v.load*.5)*v.engine;
                    double road=0,move=clamp(t.speed()/18,0,1);
                    if(v.rolling>.00001){
                        double level=t.surface()==WheelEffects.Surface.HARD?.035:.11;
                        double ground=loop(v,6,v.surface,.65+move*.65);
                        if(v.surfaceBlend<1)ground=ground*v.surfaceBlend+loop(v,13,v.previousSurface,.65+move*.65)*(1-v.surfaceBlend);
                        road+=ground*level*v.rolling;
                        if(p.tracks())road+=loop(v,7,"track",.65+move*1.5)*.105*v.rolling;
                        if(v.skid>.00001&&!p.tracks())road+=loop(v,8,t.surface()==WheelEffects.Surface.HARD?"skid":v.surface,.8+move*.25)*v.skid*(t.surface()==WheelEffects.Surface.HARD?.10:.065);
                    }
                    if(v.scrape>.00001)road+=loop(v,9,"scrape",.85+move*.2)*.16*v.scrape;
                    double transmission=loop(v,10,"transmission",.6+clamp(t.speed()/25,0,2))*(t.reverse()>.2?.035:.008)*clamp(t.speed()/8,0,1);
                    double wind=loop(v,11,"wind",1)*.035*Math.pow(clamp(t.speed()/35,0,1),2);
                    double body=(road+transmission+wind)*effects;
                    double filteredAlpha=t.cutoff()/(t.cutoff()+RATE/(2*Math.PI));v.mechanicalFilter+=(body-v.mechanicalFilter)*filteredAlpha;
                    double value=(engine*p.volume()+v.mechanicalFilter)*v.gain;
                    double angle=(v.pan+1)*Math.PI/4;left+=value*Math.cos(angle);right+=value*Math.sin(angle);
                }
                for(Shot shot:shotArray){
                    if(shot.phase>=shot.pcm.length)continue;
                    OneShot e=shot.source;Voice parent=targets.get(e.id());double pan=parent==null?e.pan():parent.pan();
                    double follow=parent==null?1:clamp(parent.gain()/shot.originalGain,0,2);
                    double end=Math.min(1,(shot.pcm.length-shot.phase)/220.);double value=sample(shot.pcm,shot.phase)*e.level()*.4*end*follow*effects;
                    double angle=(pan+1)*Math.PI/4;left+=value*Math.cos(angle);right+=value*Math.sin(angle);shot.phase+=clamp(e.pitch(),.65,1.4);
                }
                left=soft(left*master*normalization);right=soft(right*master*normalization);
                short l=(short)(left*32767),r=(short)(right*32767);out[i]=(byte)l;out[i+1]=(byte)(l>>8);out[i+2]=(byte)r;out[i+3]=(byte)(r>>8);samplesRendered++;
            }
            shots.removeIf(s->s.phase>=s.pcm.length);
        }
        private static double soft(double x){return clamp(x/(1+Math.abs(x)*.4),-.95,.95);}
    }
    @Override public void close(){running=false;silence();SourceDataLine output=line;if(output!=null)output.close();if(worker!=null)worker.interrupt();}
}
