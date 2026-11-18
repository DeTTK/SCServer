package scvehicles;
import java.util.*;
import scvehicles.protocol.*;

/** Per-actor seat ownership; car poses remain one shared interpolated snapshot. */
final class VehicleOccupants {
    private record Link(VehicleWire state,long seen){}
    record Change(boolean accepted,VehicleWire previous){}
    private final Map<Integer,Link> actors=new LinkedHashMap<>();private final Map<Integer,Integer> sequences=new HashMap<>();
    Change accept(VehicleWire s,long now){
        if(s.driver<0||!s.id.equals(VehicleWire.ZERO)&&(!VehicleSeats.valid(s.model,(int)s.steer)||s.steer!=(int)s.steer))throw new IllegalArgumentException("seat_snapshot");
        Integer prior=sequences.get(s.driver);if(prior!=null&&s.sequence-prior<=0)return new Change(false,null);
        sequences.put(s.driver,s.sequence);Link old=actors.get(s.driver);if(s.id.equals(VehicleWire.ZERO))actors.remove(s.driver);else actors.put(s.driver,new Link(s.copy(),now));return new Change(true,old==null?null:old.state);
    }
    VehicleWire local(){Link l=actors.get(0);return l==null?null:l.state;}
    void despawn(UUID id){actors.values().removeIf(l->l.state.id.equals(id));}
    Collection<VehicleWire> views(Collection<VehicleWire> cars,long now){
        actors.values().removeIf(l->now-l.seen>1_500_000_000L);Map<UUID,VehicleWire> poses=new HashMap<>();for(VehicleWire c:cars)poses.put(c.id,c);
        Map<Integer,VehicleWire> out=new LinkedHashMap<>();for(var e:actors.entrySet()){VehicleWire car=poses.get(e.getValue().state.id);if(car!=null){VehicleWire copy=car.copy();copy.driver=e.getKey();out.put(e.getKey(),copy);}}
        for(VehicleWire car:cars)if(car.driver>=0&&!out.containsKey(car.driver))out.put(car.driver,car);return out.values();
    }
    void clear(){actors.clear();sequences.clear();}
    Map<String,Object> inspect(){return Map.of("actors",actors.values().stream().map(l->Map.of("actor",l.state.driver,"vehicle",l.state.id.toString(),"seat",(int)l.state.steer)).toList());}
}
