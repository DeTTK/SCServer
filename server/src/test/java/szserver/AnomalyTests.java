package szserver;

import szserver.client.*;
import szserver.net.*;
import szserver.world.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class AnomalyTests {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static AnomalyZone zone(String kind){return zone(kind,new AnomalySettings("test",1,false,"",60,1,false,false));}
    private static AnomalyZone zone(String kind,AnomalySettings settings){return new AnomalyZone(UUID.randomUUID(),kind,kind,new World.Vec(0,0,0),1.5,new SceneCollision.Box(-1,-1,-1,1,3,1),settings,Set.of("puh","simple_anomaly","tesla").contains(kind)?8:-1,"TEAR",10,60,kind.equals("funnel")?120:kind.equals("carousel")?300:60,2,false,false,new float[]{1,0,0,0,1,0,0,0,1,0,0,0},1,60,60,kind.equals("carousel")?5:1.5);}
    private static AnomalySimulation.Target target(String key,double x){return new AnomalySimulation.Target(key,17,new World.Vec(x,0,0),.3,1.8,100,Map.of(),false);}
    private static List<AnomalySimulation.Event> step(AnomalySimulation s,long tick,AnomalyZone z,AnomalySimulation.Target...targets){return s.step(tick,List.of(z),List.of(targets));}
    private static boolean has(List<AnomalySimulation.Event> events,String type){return events.stream().anyMatch(e->e.type().equals(type));}
    public static void main(String[] args)throws Exception{
        var settings=new AnomalySettings("tramp",.5f,true,"custom_preset",37,2.5f,true,true);
        check(settings.equals(AnomalySettings.read(settings.bytes())),"native typed DTO round trip");
        byte[] nbt=SceneNbt.write(new SceneNbt.Root("",Map.of("AnomalyGroupId",SceneNbt.string("keep"),"unknownFutureField",SceneNbt.number(3,123))));
        var applied=settings.apply(nbt,true);check(AnomalySettings.from("tramp",applied).equals(settings),"native settings persist");check(SceneNbt.read(applied).values().get("unknownFutureField").value().equals(123),"preserve unrelated fields");
        UUID id=UUID.randomUUID();byte[] save=Wire.bytes(out->{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());out.writeInt(settings.bytes().length);out.write(settings.bytes());});check(AnomalySettings.save(save).identity().equals(id),"native settings save envelope");
        try{new AnomalySettings("",Float.NaN,false,"",1,1,false,false);throw new AssertionError("NaN");}catch(IllegalArgumentException expected){checks++;}
        for(String k:AnomalySettings.BEHAVIORS){var z=zone(k);var sim=new AnomalySimulation(new Random(1),Map.of());var first=step(sim,1,z,target("a",0));check(!first.isEmpty(),k+" first contact");var disabled=zone(k,new AnomalySettings("",0,false,"",60,1,false,false));check(step(new AnomalySimulation(),1,disabled,target("a",0)).isEmpty(),k+" chance zero");}
        var electra=zone("electra");var sim=new AnomalySimulation();check(step(sim,1,electra,target("a",0),target("b",0)).stream().filter(e->e.type().equals("damage")).count()==2,"electra shared pulse all victims");check(!has(step(sim,2,electra,target("a",0)),"damage"),"electra cooldown");check(has(step(sim,61,electra,target("a",0)),"damage"),"electra recharged");
        var tramp=zone("tramp");sim=new AnomalySimulation();check(step(sim,1,tramp,target("a",0),target("b",0)).stream().filter(e->e.type().equals("velocity")).count()==1,"trampoline shared cooldown");tramp=zone("tramp",new AnomalySettings("",1,true,"",37,2.5f,true,true));check(step(new AnomalySimulation(new Random(1),Map.of()),1,tramp,target("a",0)).stream().filter(e->e.type().equals("velocity")).allMatch(e->Math.abs(e.velocity()[1]-37)<.0001&&e.set()),"native trampoline transformed impulse");
        var funnel=zone("funnel");sim=new AnomalySimulation();check(has(step(sim,1,funnel,target("a",0)),"enter"),"native funnel capture");check(has(step(sim,121,funnel,target("a",0)),"damage"),"funnel death phase");check(has(sim.step(122,List.of(),List.of(target("a",0))),"leave"),"delete releases capture");
        sim=new AnomalySimulation();step(sim,1,funnel,target("a",0));check(has(step(sim,2,funnel,target("a",10)),"leave"),"escape releases capture");
        var stasis=zone("stasis");sim=new AnomalySimulation();check(has(step(sim,1,stasis,target("a",0)),"stasis_enter"),"stasis native overlay start");check(has(step(sim,2,stasis,target("a",10)),"stasis_leave"),"stasis overlay stop");
        sim=new AnomalySimulation();step(sim,1,stasis,target("a",0));check(has(sim.step(2,List.of(stasis),List.of()),"stasis_leave"),"mode change/disconnect releases native overlay");
        sim=new AnomalySimulation();step(sim,1,funnel,target("a",0));check(has(sim.step(2,List.of(funnel),List.of()),"leave"),"removed target releases native force");
        var acid=zone("simple_anomaly");sim=new AnomalySimulation();step(sim,1,acid,target("a",-4));check(has(step(sim,2,acid,target("a",4)),"damage"),"swept fast crossing");sim=new AnomalySimulation();step(sim,1,acid,target("a",-40));check(!has(step(sim,2,acid,target("a",40)),"damage"),"teleport does not cross every hazard");
        var resistant=new AnomalySimulation.Target("a",1,new World.Vec(0,0,0),.3,1.8,100,Map.of("tear_dmg_factor",100f),false);check(step(new AnomalySimulation(),1,acid,resistant).stream().filter(e->e.type().equals("damage")).findFirst().orElseThrow().amount()==4,"native resistance channel");
        var tesla=zone("tesla");check(step(new AnomalySimulation(),1,tesla,target("a",0),target("b",0),target("c",0)).stream().filter(e->e.type().equals("damage")).count()==2,"tesla target limit");
        Path dir=Files.createTempDirectory("anomaly-stock-");try{Path stock=dir.resolve("assets/anomalies/anomaly_config.json");Files.createDirectories(stock.getParent());Files.writeString(stock,"[{\"electra_damage\":60,\"trampoline_damage\":0}]");var damage=NativeAnomalyDefaults.damage(dir);check(damage.get("electra")==60&&damage.get("tramp")==0,"read original resource damage defaults");check(NativeAnomalyDefaults.damage(dir.resolve("missing")).isEmpty(),"missing stock resource retains native fallback");}finally{try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        var otherSettings=new AnomalySettings("tramp",1,false,"",12,.5f,false,false);
        check(AnomalySettings.from("tramp",otherSettings.apply(nbt,true)).equals(otherSettings),"second trampoline keeps own settings");
        check(AnomalySettings.from("tramp",applied).equals(settings),"editing second trampoline leaves first unchanged");
        var regular=SceneNbt.read(settings.apply(nbt,false)).values();check(!regular.containsKey("trampoline_strength")&&!regular.containsKey("cooldown_tick"),"ordinary anomaly only stores its native editable fields");
        if(args.length>0)try(var nativeCodec=new ClientPackets(Path.of(args[0]))){var field=ClientPackets.class.getDeclaredField("loader");field.setAccessible(true);var loader=(ClassLoader)field.get(nativeCodec);var dto=Class.forName("1576918284",true,loader).getConstructor().newInstance();var input=new java.io.DataInputStream(new java.io.ByteArrayInputStream(settings.bytes()));dto.getClass().getMethod("extends",java.io.DataInput.class).invoke(dto,input);var output=new java.io.ByteArrayOutputStream();dto.getClass().getMethod("switch",java.io.DataOutput.class).invoke(dto,new java.io.DataOutputStream(output));check(Arrays.equals(settings.bytes(),output.toByteArray()),"actual native menu DTO reader/writer");}
        if(args.length>1){var registry=SceneRegistry.load(Path.of(args[1]));int count=0;for(var definition:registry.names().values())if(AnomalySettings.supported(definition.behaviour())){
            UUID identity=UUID.randomUUID();byte[] raw=Wire.bytes(out->{out.writeShort(definition.id());out.writeInt(0);out.writeByte(4);out.writeInt(1);out.writeLong(identity.getMostSignificantBits());out.writeLong(identity.getLeastSignificantBits());for(float f:new float[]{1,0,0,0,1,0,0,0,1,0,0,0})out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0});});
            var object=new SceneObject(definition.name(),raw);var profile=AnomalyZone.from(new ActorSceneObject(definition,object,AnomalyZone.indexBounds(definition,object)));check(profile.kind().equals(definition.behaviour())&&profile.radius()>0&&profile.cooldown()>0,"catalog variant "+definition.name());count++;
        }check(count>=30,"all catalog variants tested: "+count);}
        System.out.println("AnomalyTests: "+checks+" checks passed");
    }
}
