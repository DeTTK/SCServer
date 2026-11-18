package scvehicles;
import java.util.*;
import scvehicles.protocol.*;

/** Per-actor action clock; never shared between characters or reset by a heartbeat. */
final class VehicleActionsClient {
    record Sample(String clip,double phase,double[] anchor,double progress,int action,int posture,boolean mirror,boolean windowDown,double door,double[] start,double[] end,String fromClip,double fromPhase,int seat){
        Sample(String clip,double phase,double[] anchor,double progress,int action,int posture,boolean mirror,boolean windowDown,double door,double[] start,double[] end,String fromClip,double fromPhase){this(clip,phase,anchor,progress,action,posture,mirror,windowDown,door,start,end,fromClip,fromPhase,0);}
        Sample(String clip,double phase,double[] anchor,double progress,int action,int posture,boolean mirror,boolean windowDown,double door,double[] start,double[] end){this(clip,phase,anchor,progress,action,posture,mirror,windowDown,door,start,end,null,0);}
        Sample(String clip,double phase,double[] anchor,double progress,int action,int posture,boolean mirror,boolean windowDown,double door){this(clip,phase,anchor,progress,action,posture,mirror,windowDown,door,null,null);}
    }
    private static final class Track {VehicleWire state;double started;long seen;String fromClip;double fromPhase;Track(VehicleWire s,long now){state=s.copy();started=now*1e-9-s.fuel*s.speed;seen=now;}}
    private final Map<Integer,Track> actors=new HashMap<>();private final Map<Integer,Integer> sequences=new HashMap<>();
    private final Map<Integer,Integer> seats=new HashMap<>();
    void occupant(VehicleWire s){if(s.id.equals(VehicleWire.ZERO))seats.remove(s.driver);else seats.put(s.driver,(int)s.steer);}
    private long frameTime;
    void frame(long now){frameTime=now;}
    void accept(VehicleWire s,long now){
        Integer prior=sequences.get(s.driver);Track track=actors.get(s.driver);
        if(prior!=null&&(s.sequence<prior||s.sequence==prior&&(track==null||s.fuel<track.state.fuel)))return;
        sequences.put(s.driver,s.sequence);if(s.id.equals(VehicleWire.ZERO)){actors.remove(s.driver);return;}
        if(track==null||track.state.sequence!=s.sequence){Track next=new Track(s,now);if(track!=null){Sample before=sample(track,s.model,s.driver,now);next.fromClip=before.clip;next.fromPhase=before.phase;}actors.put(s.driver,next);return;}
        if(s.fuel<1&&s.speed>1){double target=now*1e-9-s.fuel*s.speed;track.started+=Math.max(-.015,Math.min(.015,target-track.started))*.15;}
        track.state=s.copy();track.seen=now;
    }
    private Track track(VehicleWire car){Track t=actors.get(car.driver);return t!=null&&t.state.id.equals(car.id)?t:null;}
    private static double phase(Track t,long now,int actor){return Math.max(0,Math.min(1,(now*1e-9-t.started-(actor==0?0:VehicleReplica.DELAY))/t.state.speed));}
    Sample sample(VehicleWire car,long now){
        if(frameTime!=0)now=frameTime;
        Track t=track(car);if(t==null){int seat=seats.getOrDefault(car.driver,0);return new Sample(null,0,VehicleSeats.seat(car.model,seat),1,0,0,false,false,0,null,null,null,0,seat);}
        Sample s=sample(t,car.model,car.driver,now);if((car.flags&VehicleArticulation.DOORS_OPEN)!=0&&(s.action==VehicleAction.ENTER||s.action==VehicleAction.EXIT))return new Sample(s.clip,s.phase,s.anchor,s.progress,s.action,s.posture,s.mirror,s.windowDown,1,s.start,s.end,s.fromClip,s.fromPhase,s.seat);return s;
    }
    List<Sample> samples(VehicleWire car,long now){List<Sample> out=new ArrayList<>();for(var e:actors.entrySet())if(e.getValue().state.id.equals(car.id)){VehicleWire copy=car.copy();copy.driver=e.getKey();out.add(sample(copy,now));}return out;}
    private Sample sample(Track t,int model,int actor,long now){
        VehicleWire a=t.state;double progress=phase(t,now,actor),clipPhase=progress;int posture=progress<1?(int)a.roll:VehicleAction.posture(a.flags);
        String clip=VehicleAction.clip(model,a.flags);
        if(a.flags==VehicleAction.ROOF_DOWN)clipPhase=1-progress;
        if(progress>=1&&a.flags!=VehicleAction.EXIT){clip=posture==1?"lean_idle":posture==2?"roof_idle":"seated";double elapsed=Math.max(0,now*1e-9-t.started-a.speed);clipPhase=(1-Math.cos(Math.PI*elapsed/VehicleMotionClips.get(clip).duration()))*.5;}
        boolean window=a.flags==VehicleAction.LEAN_OUT||a.flags==VehicleAction.LEAN_IN&&progress<1;
        double door=VehicleDoorMotion.sample(clip,clipPhase);
        boolean mirror=clip.startsWith("lean_")?a.yaw>=0:a.yaw<0;
        return new Sample(clip,Math.max(0,clipPhase),VehicleAction.anchor(a,progress),progress,a.flags,posture,mirror,window,door,new double[]{a.x,a.y,a.z},new double[]{a.vx,a.vy,a.vz},t.fromClip,t.fromPhase,(int)a.steer);
    }
    double[] anchor(VehicleWire car){return sample(car,System.nanoTime()).anchor;}
    double[] exitAnchor(VehicleWire car){Track t=actors.get(0);return t!=null&&t.state.id.equals(car.id)&&t.state.flags==VehicleAction.EXIT?VehicleAction.anchor(t.state,1):null;}
    double[] exitAnchor(VehicleWire car,int actor){Track t=actors.get(actor);return t!=null&&t.state.id.equals(car.id)&&t.state.flags==VehicleAction.EXIT?VehicleAction.anchor(t.state,1):null;}
    boolean busy(UUID car){Track t=actors.get(0);return t!=null&&t.state.id.equals(car)&&phase(t,System.nanoTime(),0)<1;}
    boolean windowView(UUID car){Track t=actors.get(0);if(t==null||!t.state.id.equals(car))return false;return t.state.flags==VehicleAction.LEAN_OUT||t.state.flags==VehicleAction.LEAN_IN&&phase(t,System.nanoTime(),0)<1;}
    boolean windowFire(UUID car){Track t=actors.get(0);return t!=null&&t.state.id.equals(car)&&t.state.flags==VehicleAction.LEAN_OUT&&phase(t,System.nanoTime(),0)>=1;}
    int posture(UUID car){Track t=actors.get(0);return t==null||!t.state.id.equals(car)?0:VehicleAction.posture(t.state.flags);}
    boolean locksDriving(UUID car){return locksDriving(car,System.nanoTime());}
    boolean locksDriving(UUID car,long now){Track t=actors.get(0);return t!=null&&t.state.id.equals(car)&&VehicleAction.locksDriving(t.state.flags,now*1e-9-t.started);}
    boolean exiting(UUID car){Track t=actors.get(0);return t!=null&&t.state.id.equals(car)&&t.state.flags==VehicleAction.EXIT;}
    void retain(Collection<VehicleWire> cars){Set<UUID> live=new HashSet<>();for(VehicleWire car:cars)live.add(car.id);long now=System.nanoTime();actors.values().removeIf(t->now-t.seen>2_000_000_000L&&!live.contains(t.state.id));}
    void clear(){actors.clear();sequences.clear();seats.clear();frameTime=0;}
    Map<String,Object> inspect(){List<Object> rows=new ArrayList<>();for(var e:actors.entrySet()){VehicleWire car=e.getValue().state.copy();car.driver=e.getKey();Sample s=sample(car,System.nanoTime());rows.add(Map.of("actor",e.getKey(),"vehicle",car.id.toString(),"action",s.action,"progress",s.progress,"posture",s.posture,"clip",s.clip,"phase",s.phase,"anchor",s.anchor,"duration",car.speed));}return Map.of("actors",rows);}
}
