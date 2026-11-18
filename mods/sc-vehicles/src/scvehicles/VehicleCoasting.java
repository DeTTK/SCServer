package scvehicles;

import java.util.*;
import scvehicles.protocol.VehicleWire;

/** A dismounted pilot keeps simulating the same rigid body until it settles or ownership changes. */
final class VehicleCoasting {
    static final class Sim {
        final UUID id;final VehiclePhysics physics;final VehicleBody body;final int doors;int sequence;double debt;long seen;
        Sim(UUID id,VehiclePhysics physics,VehicleBody body,int sequence,int doors){this.id=id;this.physics=physics;this.body=body;this.sequence=sequence;this.doors=doors;seen=System.nanoTime();}
        VehicleWire state(){VehicleWire s=physics.state();s.id=id;s.driver=-1;s.flags|=doors;return s;}
    }
    final Map<UUID,Sim> active=new LinkedHashMap<>();
    void begin(UUID id,VehiclePhysics physics,VehicleBody body,int sequence,int doors){physics.throttle=physics.turn=0;physics.handbrake=false;active.put(id,new Sim(id,physics,body,sequence,doors));}
    void accept(VehicleWire s){Sim p=active.get(s.id);if(p==null)return;if(s.driver>=0||s.op==VehicleWire.DESPAWN)active.remove(s.id);else p.seen=System.nanoTime();}
    void tick(double dt,VehiclePlatform platform)throws Exception{
        for(Sim s:List.copyOf(active.values())){
            if(System.nanoTime()-s.seen>3_000_000_000L){active.remove(s.id);continue;}
            VehiclePhysics p=s.physics;platform.suppress(s.id,true);
            try{s.body.beginFrame();s.body.terrain(p,dt);double x=p.x,y=p.y,z=p.z;double[] rotation=p.rotation().clone();p.step(dt);double[] at=s.body.move(p,rotation,x,y,z,p.x-x,p.y-y,p.z-z);p.resolve(x,y,z,at[0],at[1],at[2],dt);s.body.contactResponse(p);}finally{platform.suppress(s.id,false);}
        }
    }
    List<VehicleWire> outgoing(double dt){List<VehicleWire> out=new ArrayList<>();for(Sim s:List.copyOf(active.values())){s.debt+=dt;if(s.debt<VehicleReplica.PERIOD)continue;int slots=(int)((s.debt+1e-9)/VehicleReplica.PERIOD);s.debt-=slots*VehicleReplica.PERIOD;s.sequence+=slots;VehicleWire wire=s.state();wire.op=VehicleWire.STATE;wire.sequence=VehicleReplica.CLOCK|s.sequence;out.add(wire);if(wire.speed<.05&&(wire.flags&2)!=0)active.remove(s.id);}return out;}
    VehicleWire render(UUID id){Sim s=active.get(id);return s==null?null:s.state();}
    void clear(){active.clear();}
}
