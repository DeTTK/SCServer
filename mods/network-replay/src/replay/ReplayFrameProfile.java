package replay;

import java.nio.file.*;

/** Opt-in, bounded frame measurements; write once after playback, never per frame. */
final class ReplayFrameProfile {
    final boolean enabled=Boolean.getBoolean("sz.replay.qa.frameprofile");
    final long[][] rows=enabled?new long[16384][6]:null;
    int count;long previous;boolean saved;
    long begin(boolean playing){
        if(!enabled)return 0;
        if(!playing){previous=0;return 0;}
        return System.nanoTime();
    }
    void end(long began,long position,long target,int cursor,Path directory,boolean finished)throws Exception {
        if(!enabled||began==0)return;
        long ended=System.nanoTime();
        if(count<rows.length){long[] row=rows[count++];row[0]=position;row[1]=target;row[2]=cursor;row[3]=previous==0?0:began-previous;row[4]=ended-began;row[5]=began;}
        previous=began;
        if(finished&&!saved){
            StringBuilder out=new StringBuilder("position_ns,target_ns,cursor,frame_ns,mod_work_ns,wall_ns\n");
            for(int n=0;n<count;n++){long[] row=rows[n];for(int i=0;i<row.length;i++){if(i>0)out.append(',');out.append(row[i]);}out.append('\n');}
            Files.writeString(directory.resolve("frame-profile.csv"),out.toString());saved=true;
        }
    }
}
