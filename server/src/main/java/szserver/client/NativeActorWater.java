package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.util.function.*;
import java.util.*;

/** Calls the untouched installed 448718092.new scanner over resident/captured voxels. */
final class NativeActorWater {
    private final Object world,scan,state;private final Field ids,resident,water;private final Method body,box,query;
    NativeActorWater(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate,Object state)throws Exception{
        this.state=state;Class<?> worldType=define.apply("szserver.bridge.ActorWaterWorld",NativeActorBridge.waterWorld()),actorType=define.apply("szserver.bridge.ActorWaterActor",NativeActorBridge.waterActor()),st=state.getClass(),bounds=Class.forName("242639986",true,loader),base=Class.forName("1959919792",true,loader),fluid=Class.forName("685910552",true,loader),material=Class.forName("393979275",true,loader);
        // The native scan tests only material + fluid class. No render or full block factory is needed.
        Object[] prototypes=(Object[])base.getField("switch").get(null);if(prototypes[9]==null)Class.forName("703411279",true,loader).getConstructor(int.class,fluid).newInstance(9,fluid.getConstructor(String.class).newInstance("water"));
        if(!Class.forName("1108958614",true,loader).isInstance(prototypes[9])||base.getField("enum").get(prototypes[9])!=material.getField("class").get(null))throw new IllegalStateException("native_water_prototype");
        world=allocate.invoke(unsafe,worldType);Object actor=allocate.invoke(unsafe,actorType);actorType.getField("world").set(actor,world);scan=Class.forName("448718092",true,loader).getConstructor(actorType.getSuperclass()).newInstance(actor);
        ids=worldType.getField("ids");resident=worldType.getField("resident");water=st.getField("double");box=bounds.getMethod("synchronized",double.class,double.class,double.class,double.class,double.class,double.class);body=st.getMethod("this",bounds);query=scan.getClass().getMethod("new",st);
        water(new World.Vec(.5,1,.5),new BlockShapes.Voxels(){public int voxel(World.Block p){return 9<<4;}public TilePayload.Tile tile(World.Block p){return null;}});
    }
    boolean water(World.Vec p,BlockShapes.Voxels source){return water(p,source,ActorPose.STAND);}
    boolean water(World.Vec p,BlockShapes.Voxels source,ActorPose pose){
        try{
            var bounds=pose.body(p.x(),p.y(),p.z());body.invoke(state,box.invoke(null,bounds.x(),bounds.y(),bounds.z(),bounds.xx(),bounds.yy(),bounds.zz()));
            Map<World.Block,Integer> captured=new HashMap<>();
            resident.set(world,(ToIntFunction<int[]>)range->{
                for(int n=0;n<3;n++)if(range[n+3]-range[n]<0||range[n+3]-range[n]>3)throw new IllegalStateException("native_water_range_mismatch");
                for(int x=range[0];x<range[3];x++)for(int y=range[1];y<range[4];y++)for(int z=range[2];z<range[5];z++){
                    var cell=new World.Block(x,y,z);int id=0;if(y>=0&&y<=255)try{id=source.voxel(cell)>>>4;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ActorScene.Deferred();}
                    // The unchanged scanner only inspects material identity and fluid type.
                    // Map every reviewed water registration to the warmed native water prototype.
                    captured.put(cell,NativeActorFluids.water(id)?9:0);
                }return 1;
            });
            ids.set(world,(ToIntFunction<int[]>)c->{Integer id=captured.get(new World.Block(c[0],c[1],c[2]));if(id==null)throw new IllegalStateException("native_water_uncaptured_cell");return id;});
            query.invoke(scan,state);return water.getBoolean(state);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("native_actor_water",e.getCause());}
        catch(Exception e){throw new IllegalStateException("native_actor_water",e);}
        finally{try{ids.set(world,null);resident.set(world,null);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    }
}
