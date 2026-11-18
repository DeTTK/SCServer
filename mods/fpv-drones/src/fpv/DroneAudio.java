package fpv;

import javax.sound.sampled.*;
import szmods.api.ModContext;

/** One motor source per drone, attenuated from the operator or observer. Audio work never blocks the game/render thread. */
final class DroneAudio {
    private Session current;
    private volatile boolean failed;
    private long silentSince;
    void update(double[] motors,double speed,double volume,ModContext log) {
        if(volume<=0) {
            if(current!=null){current.state=new State(new double[4],0,0);if(silentSince==0)silentSince=System.nanoTime();if(System.nanoTime()-silentSince>500_000_000L)stop();}
            return;
        }
        silentSince=0;
        if(failed)return;
        if(current==null) {
            current=new Session(log);
            Thread worker=new Thread(current,"FPV rotor audio");
            worker.setDaemon(true); worker.start();
        }
        current.state=new State(motors.clone(),Math.min(80,Math.max(0,speed)),volume);
    }
    void stop() {
        Session old=current; current=null;
        if(old!=null) { old.active=false; }
    }
    private record State(double[] motors,double speed,double volume) {}
    private final class Session implements Runnable {
        volatile boolean active=true;
        volatile State state=new State(new double[4],0,0);
        private final ModContext log;
        Session(ModContext log) {this.log=log;}
        public void run() {
            SourceDataLine line=null;
            try {
                AudioFormat format=new AudioFormat(48000,16,2,true,false);
                line=AudioSystem.getSourceDataLine(format);
                line.open(format,4096); line.start();
                Synth synth=new Synth(); byte[] block=new byte[1920];
                while(active) {
                    State s=state;
                    synth.render(block,s.motors,s.speed,s.volume);
                    line.write(block,0,block.length);
                }
                // Short release avoids cutting a waveform at an arbitrary phase.
                for(int i=0;i<6;i++) {synth.render(block,new double[4],0,0);line.write(block,0,block.length);}
            } catch(Exception failure) {
                failed=true;
                if(log!=null)log.log("FPV audio unavailable (flight remains active): "+failure);
            } finally {
                if(line!=null) {line.stop();line.flush();line.close();}
            }
        }
    }
    /** Continuous phases and envelopes avoid clicks when RPM changes. */
    static final class Synth {
        private final double[] phase={0,.73,1.91,3.02}, rpm=new double[4];
        private double gain,wind,noiseLow;
        private final double[] wander=new double[4];
        private int random=1234567;
        void render(byte[] pcm,double[] target,double speed,double volume) {
            for(int sample=0;sample<pcm.length/4;sample++) {
                gain+=(volume-gain)*.0015;
                wind+=(Math.min(1,speed/32)-wind)*.0006;
                random^=random<<13;random^=random>>>17;random^=random<<5;
                double noise=random/(double)Integer.MAX_VALUE;
                noiseLow+=(noise-noiseLow)*.055;
                double left=0,right=0,average=0;
                for(int i=0;i<4;i++) {
                    rpm[i]+=(target[i]-rpm[i])*.001;
                    double r=rpm[i];average+=r*.25;
                    // Aperiodic RPM drift avoids the fixed beat frequency of detuned loops.
                    random^=random<<13;random^=random>>>17;random^=random<<5;
                    wander[i]+=(random/(double)Integer.MAX_VALUE-wander[i])*.00025;
                    double hz=(95+680*r)*(1+wander[i]*.35);
                    phase[i]=(phase[i]+2*Math.PI*hz/48000)%(2*Math.PI);
                    double p=phase[i];
                    double tone=(Math.sin(p)+.22*Math.sin(2*p)+.08*Math.sin(3*p))*.065*Math.pow(r,.72);
                    boolean l=i==0||i==3;
                    left+=tone*.8;right+=tone*.8;
                }
                double air=noiseLow*(.15*wind*wind+.16*Math.pow(average,1.6));
                left=(left+air)*gain;right=(right+air)*gain;
                short a=(short)(Math.tanh(left)*28000),b=(short)(Math.tanh(right)*28000);
                int offset=sample*4;
                pcm[offset]=(byte)a;pcm[offset+1]=(byte)(a>>8);
                pcm[offset+2]=(byte)b;pcm[offset+3]=(byte)(b>>8);
            }
        }
    }
}
