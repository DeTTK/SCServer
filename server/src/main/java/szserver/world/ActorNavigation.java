package szserver.world;

import java.util.*;

/** Worker A*: actor clearance, fractional native floors, headroom and swept edges. */
public final class ActorNavigation {
    /** Discrete planner edge rejected by execution, including same-cell posture transitions. */
    public record Edge(int x,int y,int z,int xx,int yy,int zz,ActorPose pose,int heading,int nextHeading,UUID object){
        public Edge(int x,int y,int z,int xx,int yy,int zz,ActorPose pose,int heading,int nextHeading){this(x,y,z,xx,yy,zz,pose,heading,nextHeading,null);}
        public Edge zipline(UUID identity){return new Edge(x,y,z,xx,yy,zz,pose,heading,nextHeading,identity);}
        public static Edge of(World.Vec from,World.Vec to,ActorPose pose){return of(from,to,pose,-1,-1);}
        public static Edge of(World.Vec from,World.Vec to,ActorPose pose,int heading,int nextHeading){return new Edge((int)Math.floor(from.x()),(int)Math.round(from.y()*1000),(int)Math.floor(from.z()),(int)Math.floor(to.x()),(int)Math.round(to.y()*1000),(int)Math.floor(to.z()),pose,heading,nextHeading);}
    }
    public record Path(List<World.Vec> points,World.Vec from,World.Vec goal,BlockReadView source,List<ActorPose> poses,List<Float> headings,float initialYaw,boolean controllerVerified,List<Optional<ActorZipline.Route>> actions){
        public Path{points=List.copyOf(points);poses=List.copyOf(poses);headings=List.copyOf(headings);actions=List.copyOf(actions);if(actions.size()!=points.size())throw new IllegalArgumentException("actor_path_action_count");if(points.size()!=poses.size()||headings.size()!=points.size()||!Float.isFinite(initialYaw)||headings.stream().anyMatch(h->!Float.isFinite(h)))throw new IllegalArgumentException("actor_path_pose_count");}
        public Path(List<World.Vec> points,World.Vec from,World.Vec goal,BlockReadView source,List<ActorPose> poses,List<Float> headings,float initialYaw,boolean verified){this(points,from,goal,source,poses,headings,initialYaw,verified,Collections.nCopies(points.size(),Optional.empty()));}
        public Path(List<World.Vec> points,World.Vec from,World.Vec goal,BlockReadView source,List<ActorPose> poses,boolean verified){this(points,from,goal,source,poses,defaultHeadings(from,points),0,verified);}
        public Path(List<World.Vec> points,World.Vec from,World.Vec goal,BlockReadView source,List<ActorPose> poses){this(points,from,goal,source,poses,false);}
        public Path(List<World.Vec> points,World.Vec from,World.Vec goal,BlockReadView source){this(points,from,goal,source,Collections.nCopies(points.size(),ActorPose.STAND));}
        public void checkCurrent(){source.checkCurrent();}
    }
    private static List<Float> defaultHeadings(World.Vec from,List<World.Vec> points){var headings=new ArrayList<Float>();float yaw=0;for(var p:points){if(Math.hypot(p.x()-from.x(),p.z()-from.z())>1e-8)yaw=(float)Math.toDegrees(Math.atan2(from.x()-p.x(),p.z()-from.z()));headings.add(yaw);from=p;}return List.copyOf(headings);}
    public static int facing(float yaw){return Math.floorMod((int)Math.floor(yaw/45d+.5),8);}
    private record Key(int x,int y,int z,ActorPose pose,int heading){}
    private record Node(Key key,World.Vec point,int heading,int cost,int score,ActorZipline.Route action){Node(Key key,World.Vec point,int heading,int cost,int score){this(key,point,heading,cost,score,null);}}
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private ActorPose pose=ActorPose.STAND;
    private double rise=1.001,drop=1.001;private boolean nativeRouting;
    private final Map<ActorZipline.Route,List<World.Vec>> cableLandingCache=new HashMap<>();
    // One captured search only: exact coordinates/posture/envelope, including
    // negative floor results. No quantization or cache across map publications.
    private record Clearance(World.Vec point,ActorPose pose){}
    private record Floor(double x,double y,double z,ActorPose pose,double rise,double drop){}
    private final Map<Clearance,Boolean> clearanceCache=new HashMap<>();
    private final Map<Floor,World.Vec> floorCache=new HashMap<>();
    private final BlockReadView source;private final BlockReadView.Cursor voxels;private final BlockShapes.Voxels shapeWorld;private final ActorScene scene;private final ActorScene.Budget budget=new ActorScene.Budget(100_000);
    private ActorNavigation(BlockReadView source,ActorScene scene){this.source=source;this.scene=scene;voxels=source.cursor();shapeWorld=new BlockShapes.Voxels(){public int voxel(World.Block p)throws InterruptedException{budget.use();return voxels.nativeVoxel(p);}public TilePayload.Tile tile(World.Block p)throws InterruptedException{return voxels.tile(p);}};}
    /** Worker-only spawn support using the same shaped blocks and scene volumes as navigation. */
    static World.Vec spawnFloor(BlockReadView source,ActorScene scene,double x,double y,double z)throws InterruptedException{
        ActorNavigation query=new ActorNavigation(source,scene);query.rise=5;query.drop=10;
        World.Vec point=query.floor(x,y,z);
        if(point==null||!scene.contacts(ActorPose.STAND.body(point.x(),point.y(),point.z()),query.budget).isEmpty())return null;
        return point;
    }
    private boolean occupied(World.Vec p)throws InterruptedException{
        return p.y()<0||physical(p);
    }
    private boolean physical(World.Vec p)throws InterruptedException{
        budget.use();var key=new Clearance(p,pose);var known=clearanceCache.get(key);if(known!=null)return known;
        boolean result=physicalUncached(p);if(clearanceCache.size()<16384)clearanceCache.put(key,result);return result;
    }
    private boolean physicalUncached(World.Vec p)throws InterruptedException{
        if(scene.overlaps(pose.body(p.x(),p.y(),p.z()),budget))return true;
        for(int x=(int)Math.floor(p.x()-ActorPose.RADIUS+1e-8);x<=(int)Math.floor(p.x()+ActorPose.RADIUS-1e-8);x++)for(int y=(int)Math.floor(p.y()+1e-8)-BlockShapes.LOWER_HALO;y<=(int)Math.floor(p.y()+pose.height()-1e-8);y++)for(int z=(int)Math.floor(p.z()-ActorPose.RADIUS+1e-8);z<=(int)Math.floor(p.z()+ActorPose.RADIUS-1e-8);z++){
            budget.use();var cell=new World.Block(x,y,z);int packed=voxels.nativeVoxel(cell);if(BlockShapes.overlaps(BlockShapes.cells(packed,BlockShapes.tileShape(packed)&&y>=0&&y<=255?voxels.tile(cell):null,cell,shapeWorld),x,y,z,pose.body(p.x(),p.y(),p.z())))return true;
        }return false;
    }
    private ActorLadderGuide.Contact ladder(World.Vec p)throws InterruptedException{
        var block=ActorLadders.routeContact(shapeWorld,null,p,budget);return block!=null?block:nativeRouting?scene.ladderContact(p,budget,1.25):null;
    }
    private World.Vec floor(double x,double y,double z)throws InterruptedException{
        budget.use();var key=new Floor(x,y,z,pose,rise,drop);if(floorCache.containsKey(key))return floorCache.get(key);
        var result=floorUncached(x,y,z);if(floorCache.size()<8192)floorCache.put(key,result);return result;
    }
    private World.Vec floorUncached(double x,double y,double z)throws InterruptedException{
        double top=y;while(occupied(new World.Vec(x,top,z))){top+=.25;if(top>254||top>y+rise)return null;}
        double delta=y-drop-top,clipped=scene.sweep(pose.body(x,top,z),1,delta,budget);
        // The wider native ledge envelope can pass completely below a suspended
        // block platform. Bracket its first overlap rather than testing only the
        // bottom endpoint. A quarter step is shorter than every primary body.
        if(drop>1.001)for(double probe=top;probe>top+clipped;){probe=Math.max(top+clipped,probe-.25);if(occupied(new World.Vec(x,probe,z))){clipped=probe-top;break;}}
        var bottom=new World.Vec(x,top+clipped,z);
        if(occupied(bottom)){double low=0,high=1;for(int n=0;n<20;n++){double mid=(low+high)*.5;if(occupied(new World.Vec(x,top+clipped*mid,z)))high=mid;else low=mid;}bottom=new World.Vec(x,top+clipped*low,z);}
        if(bottom.y()<0||bottom.y()>254||bottom.y()>y+rise||bottom.y()<y-drop||!physical(new World.Vec(x,bottom.y()-.02,z))){
            var ladder=ladder(new World.Vec(x,y,z));if(ladder==null)return null;var anchored=ladder.anchor(y);return occupied(anchored)?null:anchored;
        }return bottom;
    }
    private World.Vec ledgeFloor(double x,double y,double z)throws InterruptedException{
        // Candidate volume only: original 1309397527 expands the body search by 2.5 vertically.
        // The complete native controller, rather than this envelope, admits the traversal.
        double oldRise=rise,oldDrop=drop;rise=drop=ActorPose.STAND.height()+2.5+.001;
        try{return floor(x,y,z);}finally{rise=oldRise;drop=oldDrop;}
    }
    private World.Vec climb(World.Vec from,double dy)throws InterruptedException{
        var ladder=ladder(from);if(ladder==null)return null;var p=ladder.anchor(from.y()+dy);if(p.y()<0||p.y()>254)return null;
        for(int n=0;n<=5;n++){var step=ladder.anchor(from.y()+dy*n/5d);var contact=ladder(step);if(contact==null||Math.hypot(contact.anchor().x()-ladder.anchor().x(),contact.anchor().z()-ladder.anchor().z())>.01||occupied(step))return null;}
        if(Math.abs(scene.sweep(pose.body(ladder.anchor().x(),from.y(),ladder.anchor().z()),1,dy,budget)-dy)>1e-7)return null;return p;
    }
    private boolean edge(World.Vec a,World.Vec b)throws InterruptedException{
        // A supported landing one metre above a ladder is not a walking edge.
        // Finish the explicit vertical segment before attempting the lateral exit.
        if(Math.abs(a.y()-b.y())>.12&&(ladder(a)!=null||ladder(b)!=null))return false;
        double y=Math.max(a.y(),b.y());return edgeAt(a,b,y)||edgeAt(a,b,y+ActorPose.STEP);
    }
    private boolean edgeAt(World.Vec a,World.Vec b,double y)throws InterruptedException{
        World.Vec high=new World.Vec(a.x(),y,a.z());if(occupied(high))return false;
        double rise=y-a.y();if(rise>0&&Math.abs(scene.sweep(pose.body(a.x(),a.y(),a.z()),1,rise,budget)-rise)>1e-7)return false;
        double dx=b.x()-a.x(),dz=b.z()-a.z();
        if(Math.abs(scene.sweep(pose.body(a.x(),y,a.z()),0,dx,budget)-dx)>1e-7||Math.abs(scene.sweep(pose.body(b.x(),y,a.z()),2,dz,budget)-dz)>1e-7)return false;
        if(dx!=0&&dz!=0&&(occupied(new World.Vec(b.x(),y,a.z()))||occupied(new World.Vec(a.x(),y,b.z()))))return false;
        for(int n=1;n<=5;n++)if(occupied(new World.Vec(a.x()+(b.x()-a.x())*n/5,y,a.z()+(b.z()-a.z())*n/5)))return false;return true;
    }
    private static Key key(World.Vec p,ActorPose pose,int heading){return new Key((int)Math.floor(p.x()),(int)Math.round(p.y()*1000),(int)Math.floor(p.z()),pose,pose==ActorPose.PRONE?heading:-1);}
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to)throws InterruptedException{
        return find(source,scene,from,to,false);
    }
    /** Follow can advance along a safe partial route if the target is beyond this bounded search. */
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial)throws InterruptedException{return find(source,scene,from,to,partial,ActorPose.STAND);}
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial,ActorPose initialPose)throws InterruptedException{
        return find(source,scene,from,to,partial,initialPose,Set.of());
    }
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial,ActorPose initialPose,Set<Edge> rejected)throws InterruptedException{
        return find(source,scene,from,to,partial,initialPose,rejected,false,0);
    }
    /** PRONE is an optimistic primary-segment candidate; only native execution can certify its whole body. */
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial,ActorPose initialPose,Set<Edge> rejected,boolean allowProne,float initialYaw)throws InterruptedException{
        return find(source,scene,from,to,partial,initialPose,rejected,allowProne,initialYaw,false);
    }
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial,ActorPose initialPose,Set<Edge> rejected,boolean allowProne,float initialYaw,boolean attached)throws InterruptedException{
        return find(source,scene,from,to,partial,initialPose,rejected,allowProne,initialYaw,attached,null);
    }
    public static Path find(BlockReadView source,ActorScene scene,World.Vec from,World.Vec to,boolean partial,ActorPose initialPose,Set<Edge> rejected,boolean allowProne,float initialYaw,boolean attached,ActorPathValidator.Start riding)throws InterruptedException{
        SceneCollision.worker();source.checkCurrent();scene.checkCurrent();if(!partial&&Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())>64)return new Path(List.of(),from,to,source);
        if(initialPose==ActorPose.PRONE&&!allowProne)throw new IllegalArgumentException("native_prone_planner_required");
        ActorNavigation map=new ActorNavigation(source,scene);map.pose=initialPose;map.nativeRouting=allowProne;World.Vec start=attached?from:map.floor(Math.floor(from.x())+.5,from.y(),Math.floor(from.z())+.5);
        if(start==null)start=map.floor(from.x(),from.y(),from.z());if(start==null)return new Path(List.of(),from,to,source);
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::score).thenComparing(Comparator.comparingInt(Node::cost).reversed()));Map<Key,Integer> costs=new HashMap<>();Map<Key,Node> parents=new HashMap<>();int initialHeading=facing(initialYaw);Key first=key(start,initialPose,initialHeading);open.add(new Node(first,start,initialHeading,0,0));costs.put(first,0);Node end=null,best=null;World.Vec finalPoint=null;int visited=0;
        double bestDistance=Math.abs(start.x()-to.x())+Math.abs(start.z()-to.z())+Math.abs(start.y()-to.y());
        while(!open.isEmpty()&&visited++<(allowProne?1024:512)){
            source.checkCurrent();if(Thread.currentThread().isInterrupted())throw new InterruptedException("actor_navigation_stopped");Node node=open.remove();if(node.cost()!=costs.getOrDefault(node.key(),Integer.MAX_VALUE))continue;map.pose=node.key().pose();
            double distance=Math.abs(node.point().x()-to.x())+Math.abs(node.point().z()-to.z())+Math.abs(node.point().y()-to.y());if(distance<bestDistance){best=node;bestDistance=distance;}
            if(partial&&Math.hypot(from.x()-to.x(),from.z()-to.z())>64&&Math.hypot(node.point().x()-from.x(),node.point().z()-from.z())>=32){end=node;break;}
            boolean atGoal=node.key().x()==(int)Math.floor(to.x())&&node.key().z()==(int)Math.floor(to.z())&&Math.abs(node.point().y()-to.y())<(partial?1.1:.6);
            boolean exit=allowProne&&(map.pose!=ActorPose.STAND&&map.clear(node.point(),ActorPose.STAND)&&!rejected.contains(edge(node,node.point(),ActorPose.STAND,node.heading()))||map.pose==ActorPose.PRONE&&map.clear(node.point(),ActorPose.CROUCH)&&!rejected.contains(edge(node,node.point(),ActorPose.CROUCH,node.heading())));
            if(atGoal&&!exit){
                var landing=map.floor(to.x(),node.point().y(),to.z());
                if(landing!=null&&Math.abs(landing.y()-to.y())<(partial?1.1:.6)&&Math.hypot(landing.x()-to.x(),landing.z()-to.z())<.31&&map.edge(node.point(),landing)&&!rejected.contains(edge(node,landing,map.pose,node.heading()))){end=node;finalPoint=landing;break;}
            }
            if(allowProne&&map.pose==ActorPose.STAND){
                boolean resume=node.key().equals(first)&&riding!=null&&riding.zipline()!=null;
                for(var cable:scene.ziplines()){
                    map.budget.use();var trips=resume?Objects.equals(cable.identity(),riding.zipline())?Collections.singletonList(cable.route(riding.direction())):List.<ActorZipline.Route>of():cable.departures(node.point());
                    for(var trip:trips)if(trip!=null)for(var landing:map.cableLandings(trip))if(Math.hypot(landing.x()-node.point().x(),landing.z()-node.point().z())+Math.abs(landing.y()-node.point().y())>.5)offerZipline(open,costs,parents,node,landing,trip,to,rejected);
                }
                if(resume)continue;
            }
            if(map.pose!=ActorPose.STAND&&map.clear(node.point(),ActorPose.STAND))poseEdge(open,costs,parents,node,to,ActorPose.STAND,node.heading(),rejected);
            if(map.pose==ActorPose.PRONE){
                if(map.clear(node.point(),ActorPose.CROUCH))poseEdge(open,costs,parents,node,to,ActorPose.CROUCH,node.heading(),rejected);
                for(int turn:new int[]{-1,1})poseEdge(open,costs,parents,node,to,ActorPose.PRONE,Math.floorMod(node.heading()+turn,8),rejected);
            }else for(int dy:new int[]{1,-1}){
                World.Vec next=map.climb(node.point(),dy);if(next!=null)offer(open,costs,parents,node,next,map.pose,node.heading(),20,to,rejected);
            }
            boolean needsCrouch=false,needsProne=false;
            for(var direction:DIRECTIONS){
                double x=node.point().x()+direction[0],z=node.point().z()+direction[1];if(Math.abs(x-start.x())+Math.abs(z-start.z())>64)continue;
                int heading=facing((float)Math.toDegrees(Math.atan2(-direction[0],direction[1])));World.Vec next=map.floor(x,node.point().y(),z);
                // Replanning an attached actor starts at its exact native height.
                // Metre steps retain that fractional offset and can miss every
                // lateral exit. Offer the neighbouring landing's height as a
                // vertical ladder segment; the native oracle still executes it.
                if(next!=null&&map.pose!=ActorPose.PRONE){
                    double dy=next.y()-node.point().y();
                    if(Math.abs(dy)>.12&&Math.abs(dy)<=1.001){
                        World.Vec aligned=map.climb(node.point(),dy);
                        if(aligned!=null)offer(open,costs,parents,node,aligned,map.pose,node.heading(),Math.max(1,(int)Math.ceil(Math.abs(dy)*20)),to,rejected);
                    }
                }
                if(next==null||!map.edge(node.point(),next)||map.pose==ActorPose.PRONE&&Math.abs(next.y()-node.point().y())>ActorPose.STEP){
                    ActorPose current=map.pose;
                    if(current==ActorPose.STAND&&!needsCrouch){map.pose=ActorPose.CROUCH;var lower=map.floor(x,node.point().y(),z);needsCrouch=lower!=null&&map.edge(node.point(),lower);}
                    if(allowProne&&current!=ActorPose.PRONE&&!needsProne){map.pose=ActorPose.PRONE;var lower=map.floor(x,node.point().y(),z);needsProne=lower!=null&&map.edge(node.point(),lower);}
                    map.pose=current;
                    if(allowProne&&current!=ActorPose.PRONE){var landing=map.ledgeFloor(x,node.point().y(),z);if(landing!=null&&Math.abs(landing.y()-node.point().y())>1.001&&map.edge(node.point(),landing))offer(open,costs,parents,node,landing,current,heading,22+(int)Math.round(Math.abs(landing.y()-node.point().y())*20),to,rejected);}
                    continue;
                }
                int cost=(direction[0]!=0&&direction[1]!=0?14:10)+(map.pose==ActorPose.CROUCH?2:map.pose==ActorPose.PRONE?5:0)+(int)Math.round(Math.abs(next.y()-node.point().y())*20);
                offer(open,costs,parents,node,next,map.pose,heading,cost,to,rejected);
            }
            if(needsCrouch)poseEdge(open,costs,parents,node,to,ActorPose.CROUCH,node.heading(),rejected);
            if(needsProne&&map.clear(node.point(),ActorPose.PRONE))for(int heading=0;heading<8;heading++)poseEdge(open,costs,parents,node,to,ActorPose.PRONE,heading,rejected);
        }
        if(end==null&&partial)end=best;var points=new ArrayList<World.Vec>();var poses=new ArrayList<ActorPose>();var headings=new ArrayList<Float>();var actions=new ArrayList<Optional<ActorZipline.Route>>();
        for(Node cursor=end;cursor!=null&&!cursor.key().equals(first);cursor=parents.get(cursor.key())){points.add(cursor.point());poses.add(cursor.key().pose());headings.add(cursor.heading()*45f);actions.add(Optional.ofNullable(cursor.action()));}
        Collections.reverse(points);Collections.reverse(poses);Collections.reverse(headings);Collections.reverse(actions);
        if(finalPoint!=null&&(Math.hypot(finalPoint.x()-end.point().x(),finalPoint.z()-end.point().z())>1e-4||Math.abs(finalPoint.y()-end.point().y())>1e-4)){
            float yaw=(float)Math.toDegrees(Math.atan2(end.point().x()-finalPoint.x(),finalPoint.z()-end.point().z()));if(end.key().pose()==ActorPose.PRONE)yaw=facing(yaw)*45f;
            points.add(finalPoint);poses.add(end.key().pose());headings.add(yaw);actions.add(Optional.empty());
        }
        if(!points.isEmpty()&&start.y()<from.y()-.12){points.add(0,start);poses.add(0,initialPose);headings.add(0,initialYaw);actions.add(0,Optional.empty());}
        source.checkCurrent();return new Path(points,from,to,source,poses,headings,initialYaw,false,actions);
    }
    private List<World.Vec> cableLandings(ActorZipline.Route route)throws InterruptedException{
        // Candidate geometry only. The original handler supplies the release point;
        // its lateral ejection and the complete subsequent landing run in the oracle.
        var cached=cableLandingCache.get(route);if(cached!=null)return cached;
        var from=route.release();var result=new LinkedHashSet<World.Vec>();
        var center=ledgeFloor(from.x(),from.y(),from.z());if(center!=null)result.add(center);
        if(route.vertical())for(int radius:new int[]{1,3})for(var direction:DIRECTIONS){
            var landing=ledgeFloor(Math.floor(from.x())+.5+direction[0]*radius,from.y(),Math.floor(from.z())+.5+direction[1]*radius);
            if(landing!=null)result.add(landing);
        }
        var values=List.copyOf(result);cableLandingCache.put(route,values);return values;
    }
    private boolean clear(World.Vec point,ActorPose wanted)throws InterruptedException{ActorPose previous=pose;pose=wanted;try{return !occupied(point);}finally{pose=previous;}}
    private static Edge edge(Node from,World.Vec to,ActorPose pose,int heading){boolean oriented=from.key().pose()==ActorPose.PRONE||pose==ActorPose.PRONE;return Edge.of(from.point(),to,pose,oriented?from.heading():-1,oriented?heading:-1);}
    private static int heuristic(Key point,World.Vec goal){int x=Math.abs(point.x()-(int)Math.floor(goal.x())),z=Math.abs(point.z()-(int)Math.floor(goal.z()));return 10*Math.max(x,z)+4*Math.min(x,z)+(int)Math.floor(20*Math.abs(point.y()/1000d-goal.y()));}
    private static void offer(PriorityQueue<Node> open,Map<Key,Integer> costs,Map<Key,Node> parents,Node node,World.Vec point,ActorPose pose,int heading,int increment,World.Vec goal,Set<Edge> rejected){
        if(rejected.contains(edge(node,point,pose,heading)))return;Key next=key(point,pose,heading);int cost=node.cost()+increment;if(cost>=costs.getOrDefault(next,Integer.MAX_VALUE))return;costs.put(next,cost);parents.put(next,node);open.add(new Node(next,point,heading,cost,cost+heuristic(next,goal)));
    }
    private static void offerZipline(PriorityQueue<Node> open,Map<Key,Integer> costs,Map<Key,Node> parents,Node node,World.Vec point,ActorZipline.Route route,World.Vec goal,Set<Edge> rejected){
        if(rejected.contains(Edge.of(node.point(),point,ActorPose.STAND).zipline(route.object())))return;
        Key next=key(point,ActorPose.STAND,facing(route.yaw()));int increment=20+(int)Math.ceil(6*Math.sqrt(Math.pow(point.x()-node.point().x(),2)+Math.pow(point.y()-node.point().y(),2)+Math.pow(point.z()-node.point().z(),2)));int cost=node.cost()+increment;
        if(cost>=costs.getOrDefault(next,Integer.MAX_VALUE))return;costs.put(next,cost);parents.put(next,node);open.add(new Node(next,point,facing(route.yaw()),cost,cost+heuristic(next,goal),route));
    }
    private static void poseEdge(PriorityQueue<Node> open,Map<Key,Integer> costs,Map<Key,Node> parents,Node node,World.Vec to,ActorPose pose,int heading,Set<Edge> rejected){int turn=Math.abs(heading-node.heading());turn=Math.min(turn,8-turn);offer(open,costs,parents,node,node.point(),pose,heading,(node.key().pose()==pose?1:4)+turn,to,rejected);}
}
