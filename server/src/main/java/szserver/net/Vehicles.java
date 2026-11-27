package szserver.net;

import scvehicles.protocol.VehicleWire;
import scvehicles.protocol.VehicleSeats;
import java.util.*;

/** Lifecycle and driver ownership only. No competing server vehicle physics. */
public final class Vehicles<O> {
    public static final String INPUT="szmods.bridge.VehicleInput",SNAPSHOT="szmods.bridge.VehicleSnapshot";
    public static final class Car<O>{public O creator;public O driver,simulator;public final Map<Integer,O> passengers=new LinkedHashMap<>();private final Map<Integer,Long> passengerSeen=new HashMap<>();public VehicleWire state;public long updated;Car(O creator,VehicleWire state,long now){this.creator=creator;this.state=state.copy();updated=now;}public List<O> occupants(){List<O> out=new ArrayList<>();if(driver!=null)out.add(driver);out.addAll(passengers.values());return out;}}
    private final Map<UUID,Car<O>> cars=new LinkedHashMap<>();
    private final Set<O> seatClients=Collections.newSetFromMap(new IdentityHashMap<>());private int seatSequence;
    public void seatSupport(O owner,boolean enabled){if(enabled)seatClients.add(owner);else seatClients.remove(owner);}
    public boolean seatSupport(O owner){return seatClients.contains(owner);}
    public int nextSeatSequence(){return ++seatSequence;}
    public Collection<Car<O>> all(){return List.copyOf(cars.values());}
    public Car<O> get(UUID id){return cars.get(id);}
    /** Existing movement/weapon/FPV gates treat every occupant as seated. */
    public Car<O> driving(O owner){for(Car<O> car:cars.values())if(seat(car,owner)>=0)return car;return null;}
    public static int seat(Car<?> car,Object owner){if(car==null||owner==null)return -1;if(car.driver==owner)return 0;for(var e:car.passengers.entrySet())if(e.getValue()==owner)return e.getKey();return -1;}
    public Car<O> spawn(O owner,VehicleWire s,double[] at,boolean administrator,long now){
        if(!administrator)throw new Rejected(1);
        Car<O> prior=cars.get(s.id);if(prior!=null){if(prior.creator!=owner)throw new Rejected(2);return prior;}
        if(s.id.equals(VehicleWire.ZERO)||cars.size()>=32||cars.values().stream().anyMatch(c->c.creator==owner)||distance(s,at)>64)throw new Rejected(2);
        VehicleWire initial=new VehicleWire();initial.id=s.id;initial.model=s.model;initial.x=s.x;initial.y=s.y;initial.z=s.z;initial.yaw=s.yaw;
        Car<O> car=new Car<>(owner,initial,now);cars.put(s.id,car);return car;
    }
    public Car<O> enter(O owner,UUID id,int seat,long now){
        Car<O> car=cars.get(id);if(car==null||!VehicleSeats.valid(car.state.model,seat))throw new Rejected(2);
        Car<O> existing=driving(owner);if(existing!=null){if(existing==car&&seat(car,owner)==seat)return car;throw new Rejected(2);}
        if(seat==0?car.driver!=null:car.passengers.containsKey(seat))throw new Rejected(4);
        if(seat==0){car.state.sequence=0;car.driver=owner;car.simulator=owner;car.updated=now;}else{car.passengers.put(seat,owner);car.passengerSeen.put(seat,now);}
        return car;
    }
    /** Occupant liveness is separate from pilot-owned physics and native input. */
    public void heartbeat(O owner,VehicleWire request,long now){Car<O> car=cars.get(request.id);int index=seat(car,owner);if(index<1)throw new Rejected(2);car.passengerSeen.put(index,now);}
    public List<Car<O>> expiredPassengers(long now,java.util.function.Consumer<O> released){List<Car<O>> changed=new ArrayList<>();for(Car<O> car:all()){boolean any=false;for(var it=car.passengers.entrySet().iterator();it.hasNext();){var e=it.next();if(now-car.passengerSeen.getOrDefault(e.getKey(),0L)>3_000_000_000L){O owner=e.getValue();it.remove();car.passengerSeen.remove(e.getKey());if(car.creator==owner&&!car.occupants().isEmpty())car.creator=car.occupants().get(0);released.accept(owner);any=true;}}if(any)changed.add(car);}return changed;}
    public Car<O> state(O owner,VehicleWire s,long now){
        Car<O> car=cars.get(s.id);if(car==null||car.driver!=owner&&(car.driver!=null||car.simulator!=owner))throw new Rejected(5);
        if(s.model!=car.state.model)throw new Rejected(2);
        if(s.sequence<=car.state.sequence)return null;
        double elapsed=Math.max(0,(now-car.updated)/1e9),maximum=2+Math.min(2,elapsed)*45;
        if(Math.sqrt(distance(s,new double[]{car.state.x,car.state.y,car.state.z}))>maximum||Math.sqrt(s.vx*s.vx+s.vy*s.vy+s.vz*s.vz)>55||s.speed<0||s.speed>50||s.fuel>car.state.fuel+.0001)throw new Rejected(2);
        car.state=s.copy();car.state.driver=-1;car.updated=now;if(car.driver==null&&s.speed<.05&&(s.flags&2)!=0)car.simulator=null;return car;
    }
    public Car<O> exit(O owner,VehicleWire request){
        Car<O> car=cars.get(request.id);int index=seat(car,owner);if(index<0)throw new Rejected(2);
        double[] at={request.x,request.y,request.z},seat=scvehicles.protocol.VehicleAction.world(car.state,VehicleSeats.seat(car.state.model,index));
        double seatDistance=Math.pow(at[0]-seat[0],2)+Math.pow(at[1]-seat[1],2)+Math.pow(at[2]-seat[2],2);
        if(distance(car.state,at)>16&&seatDistance>16)throw new Rejected(3);
        if(index==0){car.driver=null;car.simulator=owner;}else{car.passengers.remove(index);car.passengerSeen.remove(index);}return car;
    }
    public Car<O> remove(O owner,UUID id){Car<O> car=cars.get(id);if(car==null||car.creator!=owner||car.driver!=null||!car.passengers.isEmpty())throw new Rejected(2);cars.remove(id);return car;}
    public void detach(O owner){for(Car<O> car:all()){if(car.driver==owner||car.driver==null&&car.simulator==owner){car.driver=null;car.simulator=null;car.state.sequence++;car.state.vx=car.state.vy=car.state.vz=car.state.speed=0;}car.passengers.values().removeIf(o->o==owner);car.passengerSeen.keySet().retainAll(car.passengers.keySet());}}
    public List<Car<O>> forget(O owner){seatClients.remove(owner);List<Car<O>> removed=new ArrayList<>();for(Car<O> car:all())if(car.creator==owner){O successor=car.occupants().stream().filter(o->o!=owner).findFirst().orElse(null);if(successor!=null)car.creator=successor;else{cars.remove(car.state.id);removed.add(car);}}detach(owner);return removed;}
    public List<Car<O>> expired(long now){return expired(now,owner->{});}
    /** Lost pilot ownership freezes a passenger car; it never deletes its riders. */
    public List<Car<O>> expired(long now,java.util.function.Consumer<O> released){List<Car<O>> changed=new ArrayList<>();for(Car<O> car:all())if(car.driver!=null&&now-car.updated>5_000_000_000L){O owner=car.driver;if(car.passengers.isEmpty())cars.remove(car.state.id);else{if(car.creator==owner)car.creator=car.passengers.values().iterator().next();detach(owner);released.accept(owner);}changed.add(car);}return changed;}
    /** Bounded ownership timeout, not a second movement simulation. */
    public List<Car<O>> stalledCoasts(long now){List<Car<O>> stopped=new ArrayList<>();for(Car<O> car:all())if(car.driver==null&&car.simulator!=null&&now-car.updated>3_000_000_000L){car.simulator=null;car.state.sequence++;car.state.vx=car.state.vy=car.state.vz=car.state.speed=0;stopped.add(car);}return stopped;}
    public static VehicleWire snapshot(Car<?> car,int op,int driver,boolean articulation){VehicleWire state=car.state.copy();state.op=op;state.driver=driver;if(car.driver!=null||!car.passengers.isEmpty())state.flags|=VehicleWire.OCCUPIED;if(!articulation)state.flags&=~VehicleWire.DOORS_OPEN;return state;}
    public static double distance(VehicleWire s,double[] p){return Math.pow(s.x-p[0],2)+Math.pow(s.y-p[1],2)+Math.pow(s.z-p[2],2);}
    public static final class Rejected extends IllegalArgumentException{public final int code;Rejected(int code){super("vehicle_rejected_"+code);this.code=code;}}
}
