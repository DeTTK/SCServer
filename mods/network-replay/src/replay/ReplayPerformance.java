package replay;

import jdk.jfr.*;
import java.util.ArrayDeque;

/** Opt-in diagnostic spans; normal launches do not instrument native frame calls. */
public final class ReplayPerformance {
    static final boolean enabled=Boolean.getBoolean("sz.replay.qa.performance");
    static final boolean frameOnly=Boolean.getBoolean("sz.replay.qa.frameonly");
    static final int fps=Integer.getInteger("sz.replay.qa.fps",0);
    public static volatile boolean preparing;
    static long frame;
    static final ThreadLocal<ArrayDeque<Span>> stack=ThreadLocal.withInitial(ArrayDeque::new);
    @Name("replay.FramePhase") @Label("Replay frame phase") @Category("Replay") @StackTrace(false)
    public static final class Span extends Event {
        @Label("Phase") public String phase;
        public long frame;
        public long replayNs;
        public boolean active,preparing;
    }
    public static void enter(String phase){
        if(!enabled||(frameOnly&&!phase.equals("frame")&&!phase.equals("scene-sample")&&!phase.equals("scene-publish")&&!phase.equals("scene-map")&&!phase.equals("scene-prefetch")&&!phase.equals("scene-resources")&&!phase.equals("scene-poses")&&!Character.isDigit(phase.charAt(0))))return;
        GpuProfile.begin(phase);
        if(phase.equals("frame")){frame++;stack.get().clear();}
        Span event=new Span();event.phase=phase;event.frame=frame;event.replayNs=(long)(ReplayClock.seconds*1e9);event.active=ReplayClock.active;event.preparing=preparing;
        event.begin();stack.get().push(event);
    }
    public static void exit(String phase){
        if(!enabled||(frameOnly&&!phase.equals("frame")&&!phase.equals("scene-sample")&&!phase.equals("scene-publish")&&!phase.equals("scene-map")&&!phase.equals("scene-prefetch")&&!phase.equals("scene-resources")&&!phase.equals("scene-poses")&&!Character.isDigit(phase.charAt(0))))return;
        GpuProfile.end(phase);
        ArrayDeque<Span> events=stack.get();
        while(!events.isEmpty()){Span event=events.pop();event.end();if(event.phase.equals(phase)){event.commit();return;}}
    }
    public static double interval(double original){return fps>0?1d/fps:original;}
    @Name("replay.GpuPhase") @Label("Replay GPU phase") @Category("Replay") @StackTrace(false)
    public static final class GpuSpan extends Event {public long frame,replayNs,nanos;public String phase;}
    /** Delayed timestamp queries. Never wait for the driver or alter rendering. */
    static final class GpuProfile {
        static boolean enabled=Boolean.getBoolean("sz.replay.qa.gpu");
        record Query(int start,int end,long frame,long time,String phase){}
        static final ArrayDeque<Query> pending=new ArrayDeque<>(),free=new ArrayDeque<>(),active=new ArrayDeque<>();
        static Object call(String owner,String method,String type,Object...args)throws Exception{return Native.call(Native.type(owner),method,type,args);}
        static void begin(String phase){
            if(!enabled||!phase.equals("1535940098.package")&&!phase.startsWith("3D/"))return;
            try{
                while(!pending.isEmpty()&&((Number)call("org.lwjgl.opengl.GL15","glGetQueryObjecti","int",pending.peek().end,34919)).intValue()!=0){
                    Query q=pending.remove();long start=((Number)call("org.lwjgl.opengl.GL33","glGetQueryObjectui64","long",q.start,34918)).longValue(),end=((Number)call("org.lwjgl.opengl.GL33","glGetQueryObjectui64","long",q.end,34918)).longValue();
                    GpuSpan event=new GpuSpan();event.frame=q.frame;event.replayNs=q.time;event.phase=q.phase;event.nanos=end-start;event.commit();free.add(q);
                }
                if(!ReplayClock.active||pending.size()>=256)return;
                Query q=free.poll();if(q==null)q=new Query(((Number)call("org.lwjgl.opengl.GL15","glGenQueries","int")).intValue(),((Number)call("org.lwjgl.opengl.GL15","glGenQueries","int")).intValue(),0,0,phase);
                Query current=new Query(q.start,q.end,frame,(long)(ReplayClock.seconds*1e9),phase);active.push(current);call("org.lwjgl.opengl.GL33","glQueryCounter","void",q.start,36392);
            }catch(Exception unavailable){enabled=false;active.clear();}
        }
        static void end(String phase){if(!enabled||active.isEmpty()||!active.peek().phase.equals(phase))return;try{Query current=active.pop();call("org.lwjgl.opengl.GL33","glQueryCounter","void",current.end,36392);pending.add(current);}catch(Exception unavailable){enabled=false;active.clear();}}
    }
}
