package szserver.net;
import java.util.*;
import scvehicles.protocol.*;

/** Only lifecycle and validation; the driver still owns vehicle physics. */
public final class VehicleActions<O> {
    public record Playing<O>(O owner,VehicleWire state,long started){}
    private final Map<O,Playing<O>> active=new IdentityHashMap<>();private int sequence;
    public Collection<Playing<O>> all(){return List.copyOf(active.values());}
    public Playing<O> get(O owner){return active.get(owner);}
    public VehicleWire clear(O owner){Playing<O> old=active.remove(owner);if(old==null)return null;VehicleWire clear=new VehicleWire();clear.op=VehicleWire.ACTION_SNAPSHOT;clear.sequence=++sequence;return clear;}
    public boolean busy(O owner,long now){Playing<O> p=active.get(owner);return p!=null&&progress(p,now)<1;}
    public boolean windowView(O owner,long now){Playing<O> p=active.get(owner);return p!=null&&(p.state.flags==VehicleAction.LEAN_OUT||p.state.flags==VehicleAction.LEAN_IN&&progress(p,now)<1);}
    public boolean windowFire(O owner,long now){Playing<O> p=active.get(owner);return p!=null&&p.state.flags==VehicleAction.LEAN_OUT&&progress(p,now)>=1;}
    public static double progress(Playing<?> p,long now){return Math.max(0,Math.min(1,(now-p.started)*1e-9/p.state.speed));}
    public VehicleWire snapshot(Playing<O> p,long now){VehicleWire s=p.state.copy();s.fuel=(float)progress(p,now);return s;}
    public VehicleWire begin(O owner,VehicleWire request,Vehicles.Car<O> car,double[] localStart,long now){
        int seat=Vehicles.seat(car,owner);if(seat<0||!VehicleActionProfiles.ENABLED[car.state.model]||busy(owner,now))throw new Vehicles.Rejected(2);
        int model=car.state.model,kind=request.flags;Playing<O> prior=active.get(owner);int posture=prior==null?0:VehicleAction.posture(prior.state.flags);
        if(kind<1||kind>4||kind==VehicleAction.ENTER&&prior!=null||kind==VehicleAction.LEAN_OUT&&(!VehicleSeats.window(model,seat)||posture!=0)||kind==VehicleAction.LEAN_IN&&posture!=1)throw new Vehicles.Rejected(2);
        double[] start=prior==null?localStart:VehicleAction.anchor(prior.state,1),end=VehicleAction.target(model,kind,seat);
        // Align the source entry's final pelvis with the cushion. Its original
        // backward/outside starting point is retained, independent of E range.
        if(kind==VehicleAction.ENTER)start=VehicleEntryMotion.start(model,seat,localStart[1]);
        if(kind==VehicleAction.EXIT){end=new double[]{request.x,request.y,request.z};if(Math.abs(end[0])>VehicleTypes.WIDTH[model]*.5+1.8||Math.abs(end[2])>VehicleTypes.LENGTH[model]*.5+1.8||end[1]<-2||end[1]>VehicleTypes.HEIGHT[model]+1)throw new Vehicles.Rejected(2);}
        VehicleWire s=new VehicleWire();s.op=VehicleWire.ACTION_SNAPSHOT;s.id=car.state.id;s.model=model;s.flags=kind;s.sequence=++sequence;s.x=start[0];s.y=start[1];s.z=start[2];s.vx=(float)end[0];s.vy=(float)end[1];s.vz=(float)end[2];s.speed=(float)VehicleAction.duration(model,kind);s.fuel=0;s.yaw=(float)(VehicleSeats.SEAT[model][seat][0]>=0?1:-1);s.roll=posture;s.steer=seat;
        active.put(owner,new Playing<>(owner,s,now));return s;
    }
    /** Return to the seat if the final native exit capsule becomes blocked. */
    public VehicleWire cancelExit(O owner,long now){Playing<O> old=active.get(owner);if(old==null||old.state.flags!=VehicleAction.EXIT||progress(old,now)<1)throw new Vehicles.Rejected(2);VehicleWire s=old.state.copy();double[] seat=VehicleSeats.seat(s.model,(int)s.steer);s.flags=VehicleAction.ENTER;s.sequence=++sequence;s.x=seat[0];s.y=seat[1];s.z=seat[2];s.vx=(float)seat[0];s.vy=(float)seat[1];s.vz=(float)seat[2];s.speed=(float)VehicleAction.duration(s.model,s.flags);s.roll=0;s.fuel=1;active.put(owner,new Playing<>(owner,s,now-(long)(s.speed*1e9)));return s;}
}
