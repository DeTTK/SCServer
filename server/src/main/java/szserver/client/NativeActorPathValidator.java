package szserver.client;

import szserver.world.*;
import java.nio.file.*;
import java.util.*;

/** Executes movement and the original living phase in a private worker loader.
 * A route is admissible only while the captured actor retains its starting health. */
final class NativeActorPathValidator implements ActorPathValidator {
    private final NativeActorMotion motion;private final NativeActorComponents components;private final Thread owner;
    NativeActorPathValidator(Path runtime,Path resources,Path definitions)throws Exception{
        this(runtime,resources,definitions,resources.resolveSibling("client-current-item-states.json"));
    }
    NativeActorPathValidator(Path runtime,Path resources,Path definitions,Path registry)throws Exception{
        SceneCollision.worker();owner=Thread.currentThread();motion=new NativeActorMotion(runtime);
        try{components=motion.dispatcherComponents(resources,definitions,registry);}catch(Exception failure){motion.close();throw failure;}
    }
    private void owner(){SceneCollision.worker();if(Thread.currentThread()!=owner)throw new IllegalStateException("native_route_worker_owner");}
    public Start start(ActorControllerState saved)throws Exception{owner();var map=motion.dispatcherWorld(new World.Vec(saved.originX(),0,saved.originZ()));var session=map.restore(components,saved);try{return new Start(session.frame().pose().ordinal(),session.yaw(),session.ziplineIdentity(),session.ziplineDirection());}finally{map.close(session);}}
    public ActorNavigation.Path validate(ActorNavigation.Path path,ActorControllerState saved,ActorScene scene)throws Exception{
        owner();path.checkCurrent();scene.checkCurrent();
        var map=motion.dispatcherWorld(new World.Vec(saved.originX(),0,saved.originZ()));var session=map.restore(components,saved);var mineState=motion.mines().capture();try{var frame=map.globalFrame(session);
        float startingHealth=session.vitals().health();if(startingHealth<=0)throw new IllegalArgumentException("native_route_actor_dead");
        if(distance(frame.position(),path.from())>.01)throw new IllegalArgumentException("native_route_origin_mismatch");
        if(frame.pose()!=NativeActorDispatcher.Pose.STAND&&frame.pose()!=NativeActorDispatcher.Pose.CROUCH&&frame.pose()!=NativeActorDispatcher.Pose.JUMP&&frame.pose()!=NativeActorDispatcher.Pose.LADDER&&frame.pose()!=NativeActorDispatcher.Pose.PRONE&&frame.pose()!=NativeActorDispatcher.Pose.CLIMB&&frame.pose()!=NativeActorDispatcher.Pose.CLIMB_ON&&frame.pose()!=NativeActorDispatcher.Pose.ZIPLINE)throw new IllegalArgumentException("native_route_pose_unported");
        var cursor=path.source().cursor();Map<Long,BlockReadView.ResidentChunk> chunks=new HashMap<>();
        // Prepare the route corridor before native execution. No loader wait is hidden inside a frame.
        var points=new ArrayList<>(path.points());points.add(path.from());
        for(var p:points)for(int x=Math.floorDiv((int)Math.max(-MapBounds.HORIZONTAL,Math.floor(p.x()-3)),16);x<=Math.floorDiv((int)Math.min(MapBounds.HORIZONTAL-1,Math.floor(p.x()+3)),16);x++)for(int z=Math.floorDiv((int)Math.max(-MapBounds.HORIZONTAL,Math.floor(p.z()-3)),16);z<=Math.floorDiv((int)Math.min(MapBounds.HORIZONTAL-1,Math.floor(p.z()+3)),16);z++){
            long key=key(x,z);if(!chunks.containsKey(key)){if(chunks.size()>=128)throw new IllegalArgumentException("native_route_chunk_budget");chunks.put(key,cursor.pin(x,z));}
        }
        if(path.actions().stream().anyMatch(Optional::isPresent)){
            // A cable has no intermediate walking nodes. Pin the entire already captured
            // geometry window before executing it, rather than loading chunks on a frame.
            var area=scene.area();for(int x=Math.floorDiv((int)Math.floor(area.x()),16);x<=Math.floorDiv((int)Math.floor(area.xx()),16);x++)for(int z=Math.floorDiv((int)Math.floor(area.z()),16);z<=Math.floorDiv((int)Math.floor(area.zz()),16);z++){
                long key=key(x,z);if(!chunks.containsKey(key)){if(chunks.size()>=128)throw new IllegalArgumentException("native_route_chunk_budget");chunks.put(key,cursor.pin(x,z));}
            }
        }
        var geometry=new IdentityHashMap<ActorMotion.Cell,Object>();var objects=new HashMap<UUID,Object>();for(var object:scene.objectDescriptors())objects.put(object.source().identity(),motion.prepareMapObject(object.definition(),object.source()));var sceneBudget=new ActorScene.Budget(2_000_000);
        ActorWorldAccess access=new ActorWorldAccess(){int remaining=2_000_000;
            public void use(){if(Thread.currentThread().isInterrupted())throw new ActorScene.Deferred();path.checkCurrent();scene.checkCurrent();if(--remaining<0)throw new ActorScene.Deferred();}
            BlockReadView.ResidentChunk chunk(int x,int z){use();var result=chunks.get(key(x,z));if(result==null)throw new ActorScene.Deferred();return result;}
            public boolean resident(int x,int z){chunk(x,z);return true;}
            public int voxel(World.Block p){use();return p.y()<0||p.y()>255?0:chunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)).voxel(cell(p));}
            public boolean mineTile(World.Block p){return voxel(p)>>>4==4006;}
            public TilePayload.Tile tile(World.Block p){use();return p.y()<0||p.y()>255?null:chunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)).tile(cell(p));}
            public List<Object> scene(SceneCollision.Box area){use();var result=new ArrayList<Object>();for(var descriptor:scene.descriptors(area,sceneBudget))result.add(geometry.computeIfAbsent(descriptor,motion::prepare));return result;}
            public List<Object> ladders(SceneCollision.Box area){use();var result=new ArrayList<Object>();for(var descriptor:scene.ladderDescriptors(area,sceneBudget))result.add(geometry.computeIfAbsent(descriptor,motion::prepareLadder));return result;}
            public List<ActorContacts.Volume> feedback(SceneCollision.Box area){use();return scene.feedback(area,sceneBudget);}
            public Object object(UUID identity){use();return objects.get(identity);}
            public List<UUID> carousels(){use();return scene.carousels();}
            public String actorId(){return saved.id();}
            public boolean livingTick(){return true;}
        };
        int frames=0;Boolean pendingProne=null;boolean initialProne=frame.pose()==NativeActorDispatcher.Pose.PRONE;
        for(int at=0;at<path.points().size();at++){
            var target=path.points().get(at);var wanted=path.poses().get(at);boolean crouch=wanted==ActorPose.CROUCH,prone=wanted==ActorPose.PRONE,blocked=false,reached=false;
            var cable=path.actions().get(at).orElse(null);boolean rode=frame.pose()==NativeActorDispatcher.Pose.ZIPLINE;int boardWait=0;
            boolean oriented=prone||(at==0?initialProne:path.poses().get(at-1)==ActorPose.PRONE);
            var failedEdge=ActorNavigation.Edge.of(at==0?path.from():path.points().get(at-1),target,wanted,oriented?ActorNavigation.facing(at==0?path.initialYaw():path.headings().get(at-1)):-1,oriented?ActorNavigation.facing(path.headings().get(at)):-1);
            if(cable!=null)failedEdge=failedEdge.zipline(cable.object());
            for(int step=0;step<(cable==null?120:1200);step++){
                NavigationBudget.check();
                if(++frames>3200)throw new IllegalArgumentException("native_route_frame_budget");
                frame=map.globalFrame(session);var p=frame.position();if(p.y()<-.01)break;double dx=target.x()-p.x(),dz=target.z()-p.z(),d=Math.hypot(dx,dz);
                boolean isProne=frame.pose()==NativeActorDispatcher.Pose.PRONE;if(pendingProne!=null&&isProne==pendingProne)pendingProne=null;
                boolean poseReady=frame.pose()==(prone?NativeActorDispatcher.Pose.PRONE:crouch?NativeActorDispatcher.Pose.CROUCH:NativeActorDispatcher.Pose.STAND)||!prone&&frame.pose()==NativeActorDispatcher.Pose.LADDER;
                float plannedYaw=path.headings().get(at);boolean facing=!prone||Math.abs(Math.IEEEremainder(session.yaw()-plannedYaw,360))<2;
                if(d<.22&&Math.abs(target.y()-p.y())<.12&&poseReady&&facing){reached=true;break;}
                float yaw=(prone||isProne)&&d<.22?plannedYaw:d>1e-6?(float)Math.toDegrees(Math.atan2(-dx,dz)):plannedYaw;
                boolean vertical=Math.abs(target.y()-p.y())>.12&&d<.65;
                if(vertical&&frame.pose()==NativeActorDispatcher.Pose.LADDER)yaw=session.yaw();
                if(frame.pose()==NativeActorDispatcher.Pose.CLIMB||frame.pose()==NativeActorDispatcher.Pose.CLIMB_ON)yaw=session.yaw();
                boolean jump=!prone&&(frame.pose()==NativeActorDispatcher.Pose.CLIMB&&session.jumpState().armed()||target.y()>p.y()+ActorPose.STEP+1e-4&&d<1.15&&frame.grounded()&&blocked);
                boolean toggle=prone!=isProne&&pendingProne==null;boolean moving=(d>.22||vertical)&&(!(prone||isProne)||poseReady);
                float heading=0;boolean inputCrouch=crouch;
                if(!prone&&!isProne&&frame.pose()!=NativeActorDispatcher.Pose.CLIMB&&frame.pose()!=NativeActorDispatcher.Pose.CLIMB_ON){
                    var source=ActorLadders.routeContact(access,scene,p,sceneBudget);var destination=ActorLadders.routeContact(access,scene,target,sceneBudget);
                    var ladder=ActorLadderControl.toward(p,target,source,destination,session.ladderFacing(),session.jumpState().armed(),frame.grounded());
                    if(ladder!=null){yaw=ladder.yaw();heading=ladder.heading();moving=ladder.moving();jump=ladder.jump();inputCrouch=ladder.crouch();}
                }
                if(cable!=null&&(frame.pose()==NativeActorDispatcher.Pose.ZIPLINE||!rode)){
                    boolean attached=frame.pose()==NativeActorDispatcher.Pose.ZIPLINE;if(!attached&&!rode&&++boardWait>30)break;
                    var controls=ActorZiplineControl.toward(cable,p,attached,frame.grounded(),session.jumpState().armed());
                    map.step(session,new NativeActorDispatcher.Input(controls.yaw(),controls.pitch(),controls.heading(),controls.gait(),controls.jump(),controls.crouch(),controls.proneToggle(),controls.zipline()),.05f,access);
                    if(!motion.mines().drain().isEmpty())throw new ActorPathValidator.Blocked(failedEdge,"native mine pressure contact");
                    checkHealth(session,startingHealth,failedEdge);
                    var nextFrame=session.frame();if(Boolean.getBoolean("szserver.debugNativeRoutes")&&(!rode&&nextFrame.pose()==NativeActorDispatcher.Pose.ZIPLINE||attached&&nextFrame.pose()!=NativeActorDispatcher.Pose.ZIPLINE))System.err.println("Native cable transition: "+frame.pose()+" -> "+nextFrame+" direction="+session.ziplineDirection());
                    rode|=nextFrame.pose()==NativeActorDispatcher.Pose.ZIPLINE;continue;
                }
                var before=p;map.step(session,new NativeActorDispatcher.Input(yaw,0,heading,moving?ActorLocomotion.Gait.JOG:ActorLocomotion.Gait.NONE,jump,inputCrouch,toggle),.05f,access);if(toggle)pendingProne=prone;
                if(!motion.mines().drain().isEmpty())throw new ActorPathValidator.Blocked(failedEdge,"native mine pressure contact");
                checkHealth(session,startingHealth,failedEdge);
                var after=map.globalFrame(session).position();blocked=d>.08&&Math.hypot(after.x()-before.x(),after.z()-before.z())<.015;
            }
            if(!reached)throw new ActorPathValidator.Blocked(failedEdge,"target="+target+" final="+map.globalFrame(session));
        }
        path.checkCurrent();scene.checkCurrent();return new ActorNavigation.Path(path.points(),path.from(),path.goal(),path.source(),path.poses(),path.headings(),path.initialYaw(),true,path.actions());
        }finally{try{map.close(session);}finally{motion.mines().restore(mineState);}}
    }
    private static void checkHealth(NativeActorDispatcher.Session session,float startingHealth,ActorNavigation.Edge edge)throws Exception{
        var current=session.vitals();if(current.health()<startingHealth)throw new ActorPathValidator.Blocked(edge,"health="+current.health()+" from="+startingHealth+" air="+current.air());
    }
    private static double distance(World.Vec a,World.Vec b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static int cell(World.Block p){return (p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}
    public void close()throws Exception{motion.close();}
}
