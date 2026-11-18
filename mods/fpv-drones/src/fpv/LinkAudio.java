package fpv;
import javax.sound.sampled.*;

/** Short local receiver cue; it is never replicated as a world motor source. */
final class LinkAudio {
    private volatile long generation;
    void stop(){generation++;}
    void connect(double volume){
        long ticket=++generation;
        if(volume<=0)return;
        Thread worker=new Thread(()->{
            SourceDataLine line=null;
            try{
                AudioFormat format=new AudioFormat(22050,16,1,true,false);
                line=AudioSystem.getSourceDataLine(format);line.open(format,2048);line.start();
                int random=0x143bb;
                for(int block=0;block<36&&generation==ticket;block++){
                    byte[] pcm=new byte[882];
                    for(int i=0;i<441;i++){
                        double t=(block*441+i)/22050.;random^=random<<13;random^=random>>>17;random^=random<<5;
                        double envelope=Math.min(1,t/.012)*Math.pow(Math.max(0,1-t/.72),2);
                        double noise=random/(double)Integer.MAX_VALUE;
                        double click=Math.exp(-t*60)*Math.sin(t*700);
                        double whistle=Math.sin(2*Math.PI*(2300*t+900*t*t))*Math.exp(-t*9);
                        short sample=(short)(Math.max(-1,Math.min(1,(noise*.035+whistle*.035+click*.10)*envelope*volume))*32767);
                        pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);
                    }
                    line.write(pcm,0,pcm.length);
                }
            }catch(Exception ignored){/* Optional receiver sound cannot disable flight. */}
            finally{if(line!=null){line.stop();line.flush();line.close();}}
        },"FPV receiver power-on");worker.setDaemon(true);worker.start();
    }
}
