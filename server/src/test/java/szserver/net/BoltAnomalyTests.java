package szserver.net;
import szserver.world.*;import szserver.inventory.ItemStack;import com.google.gson.*;import java.util.*;
public final class BoltAnomalyTests {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static SceneObject object(float[] matrix){return new SceneObject("test",Wire.bytes(out->{out.writeShort(1);out.writeInt(0);out.writeByte(4);out.writeInt(1);UUID id=UUID.randomUUID();out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());for(float f:matrix)out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0});}));}
    static float[] matrix(float scale){return new float[]{scale,0,0,0,scale,0,0,0,scale,0,0,0};}
    static AnomalyZone zone(String kind,float scale){var d=new JsonObject();d.addProperty("remove_target_distance",4.1);d.addProperty("attack_area",5);var definition=new SceneRegistry.Definition(1,"test",kind,"",d);var o=object(matrix(scale));return AnomalyZone.from(new ActorSceneObject(definition,o,AnomalyZone.indexBounds(definition,o)));}
    static void nativeThrow(){
        var bolt=new ItemStack(14987,1,0,new byte[0]);
        var rules=new GrenadeUse.Rules(0,20,0,0,false,true);
        var action=new GrenadeUse();
        check(action.start(bolt,rules,0,1,10,false,0),"equipped bolt accepts native prepare");
        check(!action.start(bolt,rules,0,1,10,false,0),"another prepare cannot replace it");
        var held=action.take();
        check(held.valid(bolt,1,10,false),"release must match the held bolt");
        check(!held.valid(bolt,1,4,false)&&!held.valid(bolt,2,10,false),"slot and life changes cancel the throw");
        UUID character=UUID.randomUUID(),session=UUID.randomUUID();
        var xyz=new World.Vec(0,0,0);
        try(var items=new WorldItems(ignored->{throw new AssertionError("no collision needed for lifecycle");},"test",()->7)){
            long now=System.currentTimeMillis();
            check(items.throwBolt(character,session,bolt,xyz,xyz,42,now,5000),"server creates a temporary bolt");
            items.sync(Map.of());
            var thrown=items.all().iterator().next();
            check(thrown.throwingSession.equals(session)&&thrown.prediction==42,"temporary bolt preserves owner prediction");
            check(items.throwBolt(character,session,bolt,xyz,xyz,43,now-1000,100),"expired bolt fixture is admitted");
            items.sync(Map.of());
            check(items.all().size()==1&&items.removed().get(0).prediction==43,"expiry removes a bolt without a persisted item");
        }
    }
    public static void main(String[] args){
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
