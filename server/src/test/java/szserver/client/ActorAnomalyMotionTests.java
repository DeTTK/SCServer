package szserver.client;
import szserver.world.*;
import java.nio.file.*;

/** Uses real native controller frames: impulses and temporary slow must not overwrite equipment speed. */
public final class ActorAnomalyMotionTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class Ground implements ActorWorldAccess {
        int budget=20000;final SceneObject scene;final Object prepared;
        Ground(){this(null,null);}Ground(SceneObject scene,Object prepared){this.scene=scene;this.prepared=prepared;}
        public boolean resident(int x,int z){return true;}public void use(){if(--budget<0)throw new ActorScene.Deferred();}void next(){budget=20000;}
        public int voxel(World.Block p){use();return p.y()==0?3500<<4:0;}public TilePayload.Tile tile(World.Block p){return null;}
        public boolean livingTick(){return true;}public java.util.List<java.util.UUID> carousels(){return scene==null?java.util.List.of():java.util.List.of(scene.identity());}
        public Object object(java.util.UUID id){return scene!=null&&scene.identity().equals(id)?prepared:null;}
    }
    private static SceneObject carousel(SceneRegistry.Definition def){return new SceneObject(def.name(),szserver.net.Wire.bytes(out->{out.writeShort(def.id());out.writeInt(0);out.writeByte(4);out.writeInt(1);out.writeLong(0);out.writeLong(0xc001);for(float f:new float[]{1,0,0,0,1,0,0,0,1,.5f,1,.5f})out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0,0,0,0});}));}
    public static void main(String[] args)throws Exception{
        try(var motion=new NativeActorMotion(Path.of(args[0]))){
            var components=motion.dispatcherComponents(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));var normalMap=motion.dispatcherWorld(new World.Vec(0,0,0));var slowMap=motion.dispatcherWorld(new World.Vec(0,0,0));
            var normal=components.create(new World.Vec(.5,1,.5),normalMap.nativeWorld());var slow=components.create(new World.Vec(.5,1,.5),slowMap.nativeWorld());
            var a=new Ground();var b=new Ground();var walk=new NativeActorDispatcher.Input(0,0,0,ActorLocomotion.Gait.WALK,false,false,false);
            slow.anomalySlow(.1f);for(int i=0;i<20;i++){a.next();b.next();normalMap.step(normal,walk,.05f,a);slowMap.step(slow,walk,.05f,b);}
            double full=normal.frame().position().z()-.5,part=slow.frame().position().z()-.5;check(full>1&&part>0&&part<full*.3,"stasis changes real NPC movement: "+full+" / "+part);
            slow.anomalySlow(1);double start=slow.frame().position().z();for(int i=0;i<20;i++){b.next();slowMap.step(slow,walk,.05f,b);}check(slow.frame().position().z()-start>full*.8,"leaving stasis restores equipment movement");
            slow.impulse(new World.Vec(0,14.8,0),true);var idle=new NativeActorDispatcher.Input(0,0,0,ActorLocomotion.Gait.NONE,false,false,false);b.next();var airborne=slowMap.step(slow,idle,.05f,b);check(airborne.position().y()>1.1,"native trampoline impulse reaches physics: "+airborne.position());
            if(args.length>4){
                var definition=SceneRegistry.load(Path.of(args[4])).names().get("carousel");var map=motion.dispatcherWorld(new World.Vec(0,0,0));var actor=components.create(new World.Vec(1.5,1,.5),map.nativeWorld());var enabled=carousel(definition);
                var settings=AnomalySettings.from("carousel",enabled.configuration());var disabled=enabled.configured(new AnomalySettings("carousel",0,false,"",settings.cooldown(),settings.strength(),false,false).apply(enabled.configuration(),false));
                var off=new Ground(disabled,motion.prepareMapObject(definition,disabled));for(int i=0;i<5;i++){off.next();map.step(actor,idle,.05f,off);}check(map.capture(actor,1).carousels().isEmpty(),"chance zero disables native NPC carousel");
                var on=new Ground(enabled,motion.prepareMapObject(definition,enabled));on.next();map.step(actor,idle,.05f,on);check(map.capture(actor,1).carousels().size()==1,"enabled native carousel captures NPC");
                off.next();map.step(actor,idle,.05f,off);check(map.capture(actor,1).carousels().isEmpty(),"changing native carousel chance releases NPC");
                on.next();map.step(actor,idle,.05f,on);actor.health(0);on.next();map.step(actor,idle,.05f,on);
                var successorMap=motion.dispatcherWorld(new World.Vec(0,0,0));var successor=components.create(new World.Vec(-.5,1,.5),successorMap.nativeWorld());var successorAccess=new Ground(enabled,motion.prepareMapObject(definition,enabled));
                for(int i=0;i<100&&successorMap.capture(successor,2).carousels().isEmpty();i++){on.next();map.step(actor,idle,.05f,on);successorAccess.next();successorMap.step(successor,idle,.05f,successorAccess);}
                check(successorMap.capture(successor,2).carousels().size()==1,"carousel remains usable after previous NPC dies");
                var saved=successorMap.capture(successor,2);var frames=new java.util.ArrayList<NativeActorDispatcher.Frame>();var health=new java.util.ArrayList<Float>();
                for(int i=0;i<20;i++){successorAccess.next();frames.add(successorMap.step(successor,idle,.05f,successorAccess));health.add(successor.vitals().health());}
                successorMap.close(successor);var restoredMap=motion.dispatcherWorld(new World.Vec(0,0,0));var restored=restoredMap.restore(components,saved);var restoredAccess=new Ground(enabled,motion.prepareMapObject(definition,enabled));
                for(int i=0;i<20;i++){restoredAccess.next();check(restoredMap.step(restored,idle,.05f,restoredAccess).equals(frames.get(i))&&restored.vitals().health()==health.get(i),"cold native capture/physics/HP continuation "+i);}

            }
        }
        System.out.println("ActorAnomalyMotionTests: native slow, restoration and impulse passed");
    }
}
