package szserver.net;

import java.util.*;
import scvehicles.protocol.*;

/** Relay of an authenticated pedestrian's contact in vehicle coordinates. No physics. */
public final class VehicleContacts<O> {
    public record Link<O>(O owner,VehicleWire state,long updated) {}
    private final Map<O,Link<O>> active=new IdentityHashMap<>();
    private final Map<O,Integer> sequences=new IdentityHashMap<>();
    public Collection<Link<O>> all(){return List.copyOf(active.values());}
    public VehicleWire update(O owner,VehicleWire request,Vehicles.Car<O> car,double[] player,long now){
        Integer last=sequences.get(owner);if(last!=null&&request.sequence-last<=0)return null;
        if(!request.id.equals(VehicleWire.ZERO)){
            if(car==null||car.driver==owner)throw new Vehicles.Rejected(2);
            int model=car.state.model;
            if(Math.abs(request.x)>VehicleTypes.WIDTH[model]*.5+.45||Math.abs(request.z)>VehicleTypes.LENGTH[model]*.5+.45||request.y<-.4||request.y>VehicleTypes.HEIGHT[model]+.45)throw new Vehicles.Rejected(2);
            // Allow the observer's bounded vehicle interpolation delay, but never
            // allow attaching another character or attaching across the map.
            double radius=Math.min(20,Math.max(5,car.state.speed*.5+2))+VehicleTypes.LENGTH[model]*.5;
            if(Vehicles.distance(car.state,player)>radius*radius)throw new Vehicles.Rejected(2);
        }
        Link<O> prior=active.get(owner);
        if(prior!=null&&prior.state.id.equals(request.id)&&now-prior.updated<25_000_000L)return null;
        sequences.put(owner,request.sequence);
        VehicleWire result=new VehicleWire();result.id=request.id;result.sequence=request.sequence;
        result.x=request.x;result.y=request.y;result.z=request.z;result.op=VehicleWire.CONTACT_SNAPSHOT;
        if(car!=null)result.model=car.state.model;
        if(result.id.equals(VehicleWire.ZERO))active.remove(owner);else active.put(owner,new Link<>(owner,result.copy(),now));
        return result;
    }
    public VehicleWire detach(O owner){
        Link<O> previous=active.remove(owner);if(previous==null)return null;
        VehicleWire result=new VehicleWire();result.op=VehicleWire.CONTACT_SNAPSHOT;result.sequence=previous.state.sequence;return result;
    }
    public void forget(O owner){active.remove(owner);sequences.remove(owner);}
    public List<Link<O>> expired(long now,java.util.function.Predicate<Link<O>> valid){
        List<Link<O>> expired=new ArrayList<>();
        for(Link<O> link:all())if(now-link.updated>1_500_000_000L||!valid.test(link)){active.remove(link.owner);expired.add(link);}
        return expired;
    }
}
