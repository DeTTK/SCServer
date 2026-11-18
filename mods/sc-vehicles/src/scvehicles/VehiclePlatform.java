package scvehicles;

import java.util.*;
import scvehicles.protocol.VehicleWire;
import static scvehicles.NativeAccess.*;

/** Locally controlled pedestrians ride a moving contact, while retaining their native controls. */
final class VehiclePlatform {
    private final VehicleCollision collision=new VehicleCollision();
    private final Map<UUID,VehicleWire> previous=new LinkedHashMap<>();
    private UUID standing;
    private long carriedFrames;
    private double distance;
    private double[] lastCarry=new double[3];
    private double[] feet=new double[3],anchor=new double[3];
    private boolean grounded;
    private double vertical;
    void frame(Object player,Object world,UUID driving,Collection<VehicleWire> states)throws Exception {
        if(player==null||world==null){clear();return;}
        Map<UUID,VehicleWire> current=new LinkedHashMap<>();for(VehicleWire s:states)current.put(s.id,s);
        Object motion=VehiclePlayer.state(player);double[] foot=VehiclePlayer.feet(motion);
        feet=foot;grounded=(Boolean)get(motion,"n");vertical=((Number)get(motion,"aw")).doubleValue();
        UUID support=null;
        if(driving==null&&(Boolean)get(motion,"n")&&((Number)get(motion,"aw")).doubleValue()<=1e-4) {
            if(standing!=null&&previous.containsKey(standing)&&Double.isFinite(VehicleHull.support(previous.get(standing),foot)))support=standing;
            if(support==null)for(var e:previous.entrySet())if(Double.isFinite(VehicleHull.support(e.getValue(),foot))){support=e.getKey();break;}
        }
        collision.sync(world,player,driving,states);lastCarry=new double[3];
        if(support!=null&&current.containsKey(support)) {
            VehicleWire before=previous.get(support),after=current.get(support);
            double[] target=VehicleHull.world(after,VehicleHull.local(before,foot));double length=0;
            for(int i=0;i<3;i++){lastCarry[i]=target[i]-foot[i];length+=lastCarry[i]*lastCarry[i];}
            if(length<9) {
                collision.suppress(support,true);
                try {double[] actual=VehiclePlayer.carry(player,motion,lastCarry);feet=actual;anchor=VehicleHull.local(after,actual);double moved=0;for(int i=0;i<3;i++)moved+=Math.pow(actual[i]-foot[i],2);distance+=Math.sqrt(moved);carriedFrames++;}
                finally {collision.suppress(support,false);}
            } else support=null; // A state discontinuity is a teleport, not platform motion.
        } else support=null;
        standing=support;previous.clear();for(VehicleWire s:states)previous.put(s.id,s.copy());
    }
    void suppress(UUID id,boolean value)throws Exception{collision.suppress(id,value);}
    void driver(UUID id,Object player)throws Exception{collision.driver(id,player);}
    VehicleWire contact(int sequence){
        VehicleWire contact=new VehicleWire();contact.op=VehicleWire.CONTACT;contact.sequence=sequence;
        if(standing!=null){contact.id=standing;contact.x=anchor[0];contact.y=anchor[1];contact.z=anchor[2];}
        return contact;
    }
    UUID standing(){return standing;}
    void clear()throws Exception{collision.clear();previous.clear();standing=null;lastCarry=new double[3];}
    Map<String,Object> inspect(){return Map.of("colliders",collision.size(),"standing",String.valueOf(standing),"carriedFrames",carriedFrames,"distance",distance,"lastCarry",lastCarry.clone(),"feet",feet.clone(),"anchor",anchor.clone(),"grounded",grounded,"vertical",vertical,"collision",collision.inspect());}
}
