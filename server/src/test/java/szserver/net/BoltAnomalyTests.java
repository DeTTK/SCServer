package szserver.net;
import szserver.world.*;import com.google.gson.*;import java.util.*;
public final class BoltAnomalyTests {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static SceneObject object(float[] matrix){return new SceneObject("test",Wire.bytes(out->{out.writeShort(1);out.writeInt(0);out.writeByte(4);out.writeInt(1);UUID id=UUID.randomUUID();out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());for(float f:matrix)out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0});}));}
    static float[] matrix(float scale){return new float[]{scale,0,0,0,scale,0,0,0,scale,0,0,0};}
    static AnomalyZone zone(String kind,float scale){var d=new JsonObject();d.addProperty("remove_target_distance",4.1);d.addProperty("attack_area",5);var definition=new SceneRegistry.Definition(1,"test",kind,"",d);var o=object(matrix(scale));return AnomalyZone.from(new ActorSceneObject(definition,o,AnomalyZone.indexBounds(definition,o)));}
    public static void main(String[] args){
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
