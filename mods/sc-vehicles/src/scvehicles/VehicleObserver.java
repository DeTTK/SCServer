package scvehicles;

import java.util.*;
import scvehicles.protocol.VehicleWire;
import static scvehicles.NativeAccess.*;

/** Remote actors and cars share one rendered pose, independent of player packet timing. */
final class VehicleObserver {
    VehicleActionsClient actions;
    interface Lookup {Object find(int id)throws Exception;}
    record Target(UUID vehicle,String kind,double[] position) {}
    private final Map<Integer,Contact> contacts=new HashMap<>();
    private final Map<Integer,Integer> sequences=new HashMap<>();
    private final Map<Object,Target> targets=new IdentityHashMap<>();
    private final Map<Object,double[]> applied=new IdentityHashMap<>();
    private long placements;private double maxError;
    private static final class Contact {
        VehicleWire previous,current;long received;
        Contact(VehicleWire s,long now){previous=current=s.copy();received=now;}
        double[] at(long now){double t=Math.max(0,Math.min(1,(now-received)/70_000_000.));return new double[]{previous.x+(current.x-previous.x)*t,previous.y+(current.y-previous.y)*t,previous.z+(current.z-previous.z)*t};}
        void accept(VehicleWire s,long now){double[] p=at(now);previous=current.copy();previous.x=p[0];previous.y=p[1];previous.z=p[2];current=s.copy();received=now;}
    }
    void accept(VehicleWire s,long now){
        if(s.driver<=0)return;
        Integer last=sequences.get(s.driver);
        if(last!=null&&(s.sequence-last<0||s.sequence==last&&!s.id.equals(VehicleWire.ZERO)))return;
        sequences.put(s.driver,s.sequence);
        if(s.id.equals(VehicleWire.ZERO)){contacts.remove(s.driver);return;}
        Contact contact=contacts.get(s.driver);
        if(contact==null||!contact.current.id.equals(s.id))contacts.put(s.driver,new Contact(s,now));else contact.accept(s,now);
    }
    void frame(Object local,Object world,Collection<VehicleWire> cars,long now)throws Exception {
        frame(local,id->world.getClass().getMethod("continue",int.class).invoke(world,id),cars,now);
    }
    void frame(Object local,Lookup lookup,Collection<VehicleWire> cars,long now)throws Exception {
        targets.clear();applied.clear();Map<UUID,VehicleWire> byId=new HashMap<>();
        for(VehicleWire car:cars)byId.put(car.id,car);
        contacts.entrySet().removeIf(e->now-e.getValue().received>1_500_000_000L||!byId.containsKey(e.getValue().current.id));
        for(var entry:contacts.entrySet()){
            Contact contact=entry.getValue();VehicleWire car=byId.get(contact.current.id);
            add(local,lookup,entry.getKey(),new Target(car.id,"platform",VehicleHull.world(car,contact.at(now))));
        }
        // Seat ownership from the car snapshot takes precedence over an older
        // contact heartbeat. A driver never follows the ordinary player smoother.
        for(VehicleWire car:cars)if(car.driver>0){
            contacts.remove(car.driver);VehicleFleet.Type type=VehicleFleet.get(car.model);
            double[] feet=VehicleHull.world(car,actions==null?type.seat:actions.anchor(car));feet[1]-=.82;
            add(local,lookup,car.driver,new Target(car.id,"seat",feet));
        }
        for(Object actor:targets.keySet())bind(actor);
    }
    private void add(Object local,Lookup lookup,int id,Target target)throws Exception {
        Object actor=lookup.find(id);
        if(actor!=null&&actor!=local&&Class.forName("200241649").isInstance(actor))targets.put(actor,target);
    }
    void bind(Object actor)throws Exception {
        Target target=targets.get(actor);if(target==null)return;
        VehiclePlayer.remotePosition(actor,target.position);
        double[] actual=VehiclePlayer.actorFeet(actor);applied.put(actor,actual);
        double error=0;for(int i=0;i<3;i++)error+=Math.pow(actual[i]-target.position[i],2);
        maxError=Math.max(maxError,Math.sqrt(error));placements++;
    }
    /** Ownership ends before the ordinary actor interpolator receives its new feet. */
    void exit(Object world,VehicleWire car,int actor,double[] anchor)throws Exception {
        if(actor<=0||anchor==null)return;
        Object entity=world.getClass().getMethod("continue",int.class).invoke(world,actor);if(entity==null)return;
        double[] feet=VehicleHull.world(car,anchor);feet[1]-=.82;
        targets.remove(entity);applied.remove(entity);contacts.remove(actor);
        VehiclePlayer.remotePosition(entity,feet);
    }
    void clear(){contacts.clear();sequences.clear();targets.clear();applied.clear();}
    Map<String,Object> inspect(){
        List<Object> bindings=new ArrayList<>();
        for(var entry:targets.entrySet()){
            Target t=entry.getValue();bindings.add(Map.of("vehicle",t.vehicle.toString(),"kind",t.kind,"target",t.position.clone(),"actual",applied.getOrDefault(entry.getKey(),new double[3]).clone()));
        }
        return Map.of("contacts",contacts.size(),"actors",targets.size(),"placements",placements,"maxPositionError",maxError,"bindings",bindings);
    }
}
