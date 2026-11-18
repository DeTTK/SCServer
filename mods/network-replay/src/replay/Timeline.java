package replay;

import java.util.*;

/** Playback clock: absolute-time scene presentation, or ordered native packet reconstruction. */
public final class Timeline {
    /** The provider must own a complete world snapshot, including clocks, effects and native physics. */
    public record Checkpoint(long position,int cursor,Object state) {}
    public record TimeAdvance(long position,boolean ready) {}
    public interface Sink {
        /** A prepared scene evaluates any timestamp independently of the previous frame. */
        default boolean directTime(){return false;}
        /** Return false while assets load; the old visible frame must remain intact. */
        default boolean presentTime(long time)throws Exception {throw new UnsupportedOperationException("Absolute-time scene unavailable");}
        /** Return null when no complete checkpoint is available; partial pose caches are not checkpoints. */
        default Checkpoint checkpointBefore(long target)throws Exception {return null;}
        /** Called only after the timeline has validated the checkpoint's event boundary. */
        default void restoreCheckpoint(Checkpoint checkpoint)throws Exception {throw new UnsupportedOperationException("Checkpoint restoration unavailable");}
        default boolean ready()throws Exception {return true;}
        void reset()throws Exception;
        void apply(DemoFile.Event event)throws Exception;
        /** Finish buffered native state when a paused target has been reached. */
        default void settle()throws Exception {}
        /** Stop at a native simulation frontier before dispatching later events. */
        default TimeAdvance advanceTime(long from,long to)throws Exception {return new TimeAdvance(to,true);}
    }
    public final List<DemoFile.Event> events;
    public final long duration;
    public final long start;
    public long position,target;
    public long selectionRevision;
    public int cursor;
    public boolean playing,seeking;
    public double speed=1;
    private boolean reset=true,restoreRequested;
    private double fractionalNs;
    private boolean directPending;
    public Timeline(List<DemoFile.Event> events){this(events,0,events.isEmpty()?0:events.get(events.size()-1).timeNs());}
    public Timeline(List<DemoFile.Event> events,long start,long duration){if(start<0||duration<start)throw new IllegalArgumentException("Invalid timeline range");this.events=List.copyOf(events);this.start=start;this.duration=duration;position=target=start;}
    /** Attach controls to the exact prefix already dispatched by a world probe. */
    public static Timeline afterApplied(List<DemoFile.Event> events,int cursor){
        if(cursor<0||cursor>events.size())throw new IllegalArgumentException("Invalid applied prefix");
        Timeline value=new Timeline(events);value.cursor=cursor;value.position=value.target=cursor==0?0:events.get(cursor-1).timeNs();value.reset=false;return value;
    }
    public void speed(double value){if(!Double.isFinite(value)||Math.abs(value)<.05||Math.abs(value)>16)throw new IllegalArgumentException("Speed magnitude must be 0.05..16");if(Math.signum(value)!=Math.signum(speed))fractionalNs=0;speed=value;}
    public void togglePlayback(){
        if(playing){playing=false;fractionalNs=0;return;}
        if(!seeking&&(speed>0&&position==duration||speed<0&&position==start))seek(speed>0?start:duration);
        playing=true;
    }
    public void seek(long ns){target=Math.max(start,Math.min(duration,ns));seeking=true;restoreRequested=true;fractionalNs=0;directPending=false;}
    private int present(long elapsedNs,Sink sink)throws Exception {
        if(!playing&&!seeking&&!reset&&!directPending)return 0;
        if(playing&&!seeking){
            // Resource waits hold the requested frame. They are not playback
            // time to accumulate and skip over once that frame becomes ready.
            double movement=directPending?0:elapsedNs*speed+fractionalNs;
            long whole=(long)movement;if(!directPending)fractionalNs=movement-whole;
            if(whole>=0)target=whole>=duration-target?duration:target+whole;
            else target=whole<=start-target?start:target+whole;
            if(whole>0&&target==duration||whole<0&&target==start)fractionalNs=0;
        }else if(!seeking&&!directPending)target=position;
        if(sink.presentTime(target)){
            if(seeking)selectionRevision++;
            directPending=false;
            position=target;seeking=false;restoreRequested=false;reset=false;
            int lo=0,hi=events.size();while(lo<hi){int mid=(lo+hi)>>>1;if(events.get(mid).timeNs()<=position)lo=mid+1;else hi=mid;}cursor=lo;
            if(playing&&(speed>0&&position==duration||speed<0&&position==start)){playing=false;fractionalNs=0;}
        }else directPending=true;
        return 0;
    }
    void validateCheckpoint(Checkpoint value,long requested) {
        if(value.state()==null||value.position()<0||value.position()>requested||value.cursor()<0||value.cursor()>events.size())throw new IllegalArgumentException("Invalid replay checkpoint");
        if(value.cursor()>0&&events.get(value.cursor()-1).timeNs()>value.position())throw new IllegalArgumentException("Checkpoint includes a future event");
        if(value.cursor()<events.size()&&events.get(value.cursor()).timeNs()<=value.position())throw new IllegalArgumentException("Checkpoint does not contain its complete event prefix");
    }
    public int advance(long elapsedNs,int budget,Sink sink)throws Exception {
        return advance(elapsedNs,budget,sink,null,0);
    }
    public int advance(long elapsedNs,int budget,Sink sink,java.util.function.LongSupplier nanos,long workBudgetNs)throws Exception {
        if(budget<1||elapsedNs<0)throw new IllegalArgumentException();
        if(nanos!=null&&workBudgetNs<1)throw new IllegalArgumentException("Invalid dispatch work budget");
        if(sink.directTime())return present(elapsedNs,sink);
        // Reverse simulation would corrupt animation, physics and one-shot effects.
        // Only an absolute-time presenter may use a negative playback rate.
        if(speed<0)throw new IllegalStateException("Reverse playback requires prepared scene tracks");
        long began=nanos==null?0:nanos.getAsLong();
        if(restoreRequested){
            Checkpoint checkpoint=sink.checkpointBefore(target);
            if(checkpoint!=null){
                validateCheckpoint(checkpoint,target);
                sink.restoreCheckpoint(checkpoint);position=checkpoint.position();cursor=checkpoint.cursor();reset=false;
            }else if(target<position){cursor=0;position=0;reset=true;}
            restoreRequested=false;
        }
        if(reset){sink.reset();reset=false;}
        // Loading/budget pressure can leave a future target queued. Pausing
        // freezes the visible prefix instead of continuing towards that target.
        if(!playing&&!seeking)target=position;
        if(playing&&!seeking)target+= (long)Math.min(duration-target,elapsedNs*speed);
        int processed=0;
        boolean waitingTime=false;
        while(cursor<events.size()&&events.get(cursor).timeNs()<=target&&processed<budget){
            if(nanos!=null&&nanos.getAsLong()-began>=workBudgetNs)break;
            if(!sink.ready())break;
            DemoFile.Event event=events.get(cursor);TimeAdvance step=sink.advanceTime(position,event.timeNs());
            if(step.position()<position||step.position()>event.timeNs())throw new IllegalStateException("Invalid simulation frontier");
            position=step.position();if(!step.ready()){waitingTime=true;break;}
            if(position!=event.timeNs())throw new IllegalStateException("Ready simulation frontier did not reach the event");
            sink.apply(event);position=event.timeNs();cursor++;processed++;
        }
        if(!waitingTime&&(nanos==null||nanos.getAsLong()-began<workBudgetNs)&&(cursor==events.size()||events.get(cursor).timeNs()>target)){
            TimeAdvance step=sink.advanceTime(position,target);
            if(step.position()<position||step.position()>target)throw new IllegalStateException("Invalid final simulation frontier");
            position=step.position();if(step.ready()){if(position!=target)throw new IllegalStateException("Ready simulation frontier did not reach target");seeking=false;}
        }
        if(position==duration&&cursor==events.size()&&!seeking)playing=false;
        if(!playing&&!seeking)sink.settle();
        return processed;
    }
}
