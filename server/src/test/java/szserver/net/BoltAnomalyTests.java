package szserver.net;
import szserver.world.*;import szserver.inventory.ItemStack;import com.google.gson.*;import java.util.*;
public final class BoltAnomalyTests {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static SceneObject object(float[] matrix){return object(matrix,new byte[]{10,0,0,0,0});}
    private static SceneObject object(float[] matrix,byte[] config){return new SceneObject("test",Wire.bytes(out->{out.writeShort(1);out.writeInt(0);out.writeByte(4);out.writeInt(1);UUID id=UUID.randomUUID();out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());for(float f:matrix)out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(config);}));}
    static float[] matrix(float scale){return new float[]{scale,0,0,0,scale,0,0,0,scale,0,0,0};}
    static AnomalyZone zone(String kind,float scale){var d=new JsonObject();d.addProperty("remove_target_distance",4.1);d.addProperty("attack_area",5);var definition=new SceneRegistry.Definition(1,"test",kind,"",d);var o=object(matrix(scale));return AnomalyZone.from(new ActorSceneObject(definition,o,AnomalyZone.indexBounds(definition,o)));}
    static void nativeThrow(){
        var bolt=new ItemStack(14987,1,0,new byte[0]);
        var custom=new ItemStack(14988,1,0,new byte[]{1});
        check(GrenadeUse.equipped(null,10,bolt)==bolt,"empty bolt slot uses the native selector default");
        check(GrenadeUse.equipped(custom,10,bolt)==custom,"equipped bolt keeps its own factory and modifiers");
        check(GrenadeUse.equipped(null,4,bolt)==null,"empty grenade slot never receives a stock bolt");
        var rules=new GrenadeUse.Rules(0,20,0,0,false,true);
        var action=new GrenadeUse();
        check(action.start(GrenadeUse.equipped(null,10,bolt),rules,0,1,10,false,0),"stock bolt accepts native prepare");
        check(!action.start(GrenadeUse.equipped(null,10,bolt),rules,0,1,10,false,0),"another prepare cannot replace it");
        var held=action.take();
        check(held.valid(GrenadeUse.equipped(null,10,bolt),1,10,false),"stock bolt persists through prepare and release");
        check(!held.valid(GrenadeUse.equipped(custom,10,bolt),1,10,false),"equipping a custom bolt cancels the held stock throw");
        check(!held.valid(bolt,1,4,false)&&!held.valid(bolt,2,10,false),"slot and life changes cancel the throw");
        UUID character=UUID.randomUUID(),session=UUID.randomUUID();
        var xyz=new World.Vec(0,0,0);
        var profile=new szserver.client.BoltProjectile(6,100,6,16,.98,.5,.6,false,xyz,new byte[]{1});
        try(var items=new WorldItems(ignored->{throw new AssertionError("no collision needed for lifecycle");},"test",()->7)){
            long now=100_000;
            check(items.throwBolt(character,session,bolt,xyz,xyz,42,now,profile),"server creates a temporary bolt");
            items.sync(Map.of(),now+4999);
            var thrown=items.all().iterator().next();
            check(thrown.throwingSession.equals(session)&&thrown.prediction==42,"temporary bolt preserves owner prediction");
            items.sync(Map.of(),now+5000);
            var removed=items.removed();
            check(items.all().isEmpty()&&removed.size()==1&&removed.get(0)==thrown,"stock lifetime retires the actor at its exact deadline");
            items.sync(Map.of(),now+5001);
            check(items.removed().isEmpty(),"expiry does not send another retirement");
        }
    }
    static void funnelContact(){
        var funnel=zone("funnel",1);
        var from=new World.Vec(-5,0,1);
        var to=new World.Vec(5,0,1);
        var hit=BoltAnomalies.funnelImpact(List.of(funnel),from,to);
        check(hit!=null&&hit.time()>0&&hit.time()<.5,"swept bolt strikes the near side of the funnel");
        check(Math.abs(Math.hypot(hit.point().x(),hit.point().z())-3.125)<1e-8,"effect uses the boundary contact point");
        check(hit.rebound().x()<0&&hit.rebound().z()>0&&hit.rebound().y()>0,"grazing bolt rebounds outwards and up");
        check(BoltAnomalies.funnelImpact(List.of(funnel),to,to)==null,"a stationary bolt cannot strike a funnel");
        check(BoltAnomalies.funnelImpact(List.of(funnel),hit.point(),new World.Vec(-6,0,2))==null,"an outbound bolt cannot hit again");
        check(BoltAnomalies.funnelImpact(List.of(funnel),new World.Vec(-5,5,0),new World.Vec(5,5,0))==null,"passing above the funnel is a miss");
        check(BoltAnomalies.funnelImpact(List.of(zone("electra",1)),from,to)==null,"other anomalies retain their own response");
    }
    static void trampolineContact(){
        var tram=zone("tramp",1);
        var from=new World.Vec(-5,0,0);
        var to=new World.Vec(5,0,0);
        var velocity=new World.Vec(15,-2,1);
        var simulation=new AnomalySimulation();
        var hit=BoltAnomalies.trampolineImpact(List.of(tram),from,to,velocity,z->simulation.trampolineReady(25,z));
        check(hit!=null&&hit.time()>0&&hit.time()<.5,"fast bolt strikes the entrance of the trampoline");
        check(Math.abs(hit.point().x()+1.625)<.001,"contact uses the swept native radius and bolt body");
        check(hit.rebound().x()==15&&Math.abs(hit.rebound().y()-12.8)<.0001,"native additive trampoline preserves forward velocity");
        check(BoltAnomalies.trampolineImpact(List.of(tram),to,to,velocity,z->true)==null,"resting bolt does not reactivate");
        check(BoltAnomalies.trampolineImpact(List.of(tram),new World.Vec(-5,4,0),new World.Vec(5,4,0),velocity,z->true)==null,
            "bolt above trampoline misses");
        check(BoltAnomalies.containing(List.of(tram),from,to)==null,"cooling trampoline cannot fall through to generic activation");

        check(simulation.activateTrampolineBolt(25,tram),"bolt begins cooldown");
        check(!simulation.activateTrampolineBolt(25,tram),"simultaneous bolts cannot both activate it");
        check(BoltAnomalies.trampolineImpact(List.of(tram),from,to,velocity,z->simulation.trampolineReady(84,z))==null,
            "bolt during cooldown crosses without impulse");
        var player=new AnomalySimulation.Target("player",1,new World.Vec(0,0,0),.3,1.8,100,Map.of(),false);
        check(simulation.step(84,List.of(tram),List.of(player)).stream().noneMatch(e->e.type().equals("velocity")),
            "bolt cooldown also suppresses the default shared player activation");
        check(simulation.step(85,List.of(tram),List.of(player)).stream().anyMatch(e->e.type().equals("velocity")),
            "trampoline rearms on its configured tick");
        check(!simulation.trampolineReady(86,tram),"player activation shares that cooldown with bolts");

        var definition=new SceneRegistry.Definition(2,"test","tramp","",new JsonObject());
        var settings=new szserver.client.AnomalySettings("",1,false,"",10,2,true,false);
        float[] axes={0,1,0,1,0,0,0,0,1,0,0,0};
        var source=object(axes,settings.apply(new byte[]{10,0,0,0},true));
        var oriented=AnomalyZone.from(new ActorSceneObject(definition,source,AnomalyZone.indexBounds(definition,source)));
        hit=BoltAnomalies.trampolineImpact(List.of(oriented),from,to,velocity,z->true);
        check(hit!=null&&Math.abs(hit.rebound().x()-29.6)<.0001&&hit.rebound().y()==0&&hit.rebound().z()==0,
            "replace mode uses the instance transform and strength");
    }
    public static void main(String[] args)throws java.io.IOException{
        byte[] vrBolt=java.nio.ByteBuffer.allocate(16).putInt(0x56524231).putFloat(180).putFloat(-10).putFloat(1.2f).array();
        var intent=szserver.client.VrBoltIntent.read(vrBolt);
        if(intent.yaw()!=180||intent.pitch()!=-10||intent.strength()!=1.2f)throw new AssertionError("VR bolt wire changed");
        java.nio.ByteBuffer.wrap(vrBolt).putFloat(12,Float.NaN);
        try{szserver.client.VrBoltIntent.read(vrBolt);throw new AssertionError("Invalid VR bolt strength admitted");}
        catch(java.io.IOException expected){}

        funnelContact();
        trampolineContact();
        nativeThrow();
        var electro=zone("electra",1);var funnel=zone("funnel",1);
        check(BoltAnomalies.containing(List.of(electro),new World.Vec(0,0,0))==electro,"bolt inside anomaly triggers it");
        check(BoltAnomalies.containing(List.of(electro),new World.Vec(10,0,0))==null,"bolt far away triggers nothing");
        check(BoltAnomalies.containing(List.of(electro),new World.Vec(0,.2,0))!=null,"small vertical offset still triggers");
        check(BoltAnomalies.containing(List.of(electro),new World.Vec(0,4,0))==null,"bolt above anomaly does not trigger");
        check(BoltAnomalies.containing(List.of(),new World.Vec(0,0,0))==null,"no zones means no trigger");
        check(BoltAnomalies.containing(List.of(electro,funnel),new World.Vec(1.4,0,0))==electro,"first matching anomaly wins");
        check(BoltAnomalies.containing(List.of(funnel),new World.Vec(2.4,0,1.2))==funnel,"native rounded shape is honoured");
        check(BoltAnomalies.containing(List.of(electro),new World.Vec(4,0,0),new World.Vec(-4,0,0))==electro,"fast bolt crossing the zone still triggers");
        System.out.println("PASS "+checks+" thrown bolt anomaly activation checks");
    }
}
