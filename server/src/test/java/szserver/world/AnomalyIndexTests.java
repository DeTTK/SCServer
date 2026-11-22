package szserver.world;
import szserver.client.*;
import szserver.net.Wire;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Player anomalies must work without opting into native NPC simulation. */
public final class AnomalyIndexTests {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static SceneObject object(UUID id){return new SceneObject("test_electra",Wire.bytes(out->{out.writeShort(1);out.writeInt(0);out.writeByte(4);out.writeInt(1);out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());for(float f:new float[]{1,0,0,0,1,0,0,0,1,.5f,1,.5f})out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0,0,0,0});}));}
    public static void main(String[] args)throws Exception{
        var properties=new JsonObject();properties.addProperty("behaviour","electra");properties.addProperty("collision","none");var def=new SceneRegistry.Definition(1,"test_electra","electra","",properties);
        var terrain=Terrain.flat(1);var world=new World(terrain);UUID id=UUID.randomUUID();var source=object(id);world.scene(0,0,id,new ScenePayload.Entry(false,source));
        try(var loop=new WorldLoop(world);var access=new MapObstructionAccess(loop,terrain,Path.of("."),1024*1024,1024*1024,Map.of(def.name(),def))){loop.start();var area=new SceneCollision.Box(-3,0,-3,3,5,3);
            var first=access.anomalies(List.of(area)).get(10,TimeUnit.SECONDS);check(first.zones().size()==1,"anomaly indexed with native NPC dispatcher absent");check(first.zones().get(0).settings().chance()==1,"native instance defaults");
            byte[] configured=new AnomalySettings("test",0,false,"",60,1,false,false).apply(source.configuration(),false);loop.submit(w->{w.scene(0,0,id,new ScenePayload.Entry(false,source.configured(configured)));return true;}).get(10,TimeUnit.SECONDS);
            boolean stale=false;try{first.authorization().checkCurrent();}catch(RuntimeException expected){stale=true;}check(stale,"edit invalidates old geometry authorization");
            var changed=access.anomalies(List.of(area)).get(10,TimeUnit.SECONDS);check(changed.zones().size()==1&&changed.zones().get(0).settings().chance()==0,"saved parameters reach runtime index");
            loop.submit(w->{w.scene(0,0,id,null);return true;}).get(10,TimeUnit.SECONDS);check(access.anomalies(List.of(area)).get(10,TimeUnit.SECONDS).zones().isEmpty(),"deleted anomaly removed from index");
        }
        System.out.println("AnomalyIndexTests: optional NPC independence, edit invalidation, settings and deletion passed");
    }
}
