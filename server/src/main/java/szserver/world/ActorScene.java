package szserver.world;

import java.util.*;

/** Bounded, immutable local native geometry. No disk reads or native codecs in actor queries. */
public final class ActorScene implements AutoCloseable {
    public static final int MAX_CELLS=4096;
    public static final class Budget {
        private int remaining;
        public Budget(int count){remaining=count;}
        void use(){NavigationBudget.check();if(--remaining<0)throw new Deferred();}
    }
    public static final class Deferred extends RuntimeException {public Deferred(){super("actor_collision_budget",null,false,false);}}
    private record Node(SceneCollision.Box bounds,Node left,Node right,ActorGeometry[] cells){}
    private final SceneCollision.Box area;private final Runnable guard;private final Node root;private final int cells;
    private final Map<UUID,ActorSceneObject> objects;private final Map<UUID,Object> nativeObjects=new HashMap<>();private List<ActorZipline> ziplines=List.of();private List<ActorSeat> seats=List.of();
    private final List<ActorContacts.Volume> feedback;private final List<ActorGeometry> ladders;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();private Runnable release=()->{};
    ActorScene(SceneCollision.Box area,List<ActorGeometry> geometry,Runnable guard){this(area,geometry,List.of(),guard);}
    ActorScene(SceneCollision.Box area,List<ActorGeometry> geometry,List<ActorContacts.Volume> feedback,Runnable guard){this(area,geometry,feedback,List.of(),guard);}
    ActorScene(SceneCollision.Box area,List<ActorGeometry> geometry,List<ActorContacts.Volume> feedback,List<ActorGeometry> ladders,Runnable guard){this(area,geometry,feedback,ladders,List.of(),guard);}
    ActorScene(SceneCollision.Box area,List<ActorGeometry> geometry,List<ActorContacts.Volume> feedback,List<ActorGeometry> ladders,List<ActorSceneObject> sources,Runnable guard){SceneCollision.worker();
        Map<UUID,ActorSceneObject> unique=new HashMap<>();for(var descriptor:sources){var before=unique.putIfAbsent(descriptor.source().identity(),descriptor);if(before!=null&&!before.source().equals(descriptor.source()))throw new IllegalArgumentException("actor_scene_object_ambiguous");}objects=Map.copyOf(unique);
this.ladders=List.copyOf(ladders);this.area=area;this.guard=guard;cells=geometry.size();if(cells+feedback.size()+ladders.size()+objects.size()>MAX_CELLS)throw new IllegalArgumentException("actor_scene_capacity");this.feedback=List.copyOf(feedback);root=build(geometry.toArray(ActorGeometry[]::new),0);}
    public SceneCollision.Box area(){checkCurrent();return area;}
    public void checkCurrent(){if(closed.get())throw new IllegalArgumentException("actor_scene_closed");guard.run();}
    public long retainedBytes(){return 4096L+1024L*(cells+ladders.size())+256L*feedback.size()+objects.values().stream().mapToLong(ActorSceneObject::retainedBytes).sum();}
    /** Native AABB_FEEDBACK uses world-aligned bounds, including for rotated objects. */
    public Map<ActorContacts.Key,ActorContacts.Obstacle> contacts(SceneCollision.Box body,Budget budget){
        checkCurrent();var query=new SceneCollision.Box(body.x()-.001,body.y()-.001,body.z()-.001,body.xx()+.001,body.yy()+.001,body.zz()+.001);
        if(!covers(query))throw new Deferred();Map<ActorContacts.Key,ActorContacts.Obstacle> found=new LinkedHashMap<>();
        for(var zone:feedback){budget.use();if(intersects(query,zone.bounds()))found.put(zone.key(),zone.obstacle());}return Map.copyOf(found);
    }
    public List<ActorContacts.Volume> feedback(SceneCollision.Box query,Budget budget){
        checkCurrent();if(!covers(query))throw new Deferred();var found=new ArrayList<ActorContacts.Volume>();
        for(var zone:feedback){budget.use();if(intersects(query,zone.bounds()))found.add(zone);}return List.copyOf(found);
    }
    void releaseWith(Runnable action){release=action;}
    @Override public void close(){if(closed.compareAndSet(false,true))release.run();}
    public void prepareMotion(ActorMotion motion){SceneCollision.worker();prepare(root,motion);for(var cell:ladders){cell.nativeCell=motion.prepareLadder(cell.descriptor);cell.ladderGuide=motion.ladderGuide(cell.nativeCell);}var guides=new ArrayList<ActorZipline>();var units=new ArrayList<ActorSeat>();for(var source:objects.values()){if(szserver.client.AnomalySettings.supported(source.definition().behaviour())&&(!source.definition().behaviour().equals("carousel")||!motion.mapObjectsEnabled()))continue;Object value=motion.prepareMapObject(source.definition(),source.source());nativeObjects.put(source.source().identity(),value);if(source.definition().behaviour().equals("seat"))units.addAll(motion.seatGuides(value));else if(!source.definition().behaviour().equals("carousel"))guides.add(motion.ziplineGuide(value));}ziplines=List.copyOf(guides);seats=List.copyOf(units);}
    public List<UUID> carousels(){checkCurrent();return objects.values().stream().filter(o->o.definition().behaviour().equals("carousel")).map(o->o.source().identity()).toList();}
    public Object nativeObject(UUID identity,Budget budget){checkCurrent();budget.use();if(objects.containsKey(identity)&&!nativeObjects.containsKey(identity))throw new IllegalStateException("native_actor_object_unprepared");return nativeObjects.get(identity);}
    public List<ActorZipline> ziplines(){checkCurrent();return ziplines;}
    public List<ActorSeat> seats(){checkCurrent();return seats;}
    public List<ActorSceneObject> objectDescriptors(){checkCurrent();return List.copyOf(objects.values());}
    public ActorLadderGuide.Contact ladderContact(World.Vec point,Budget budget){return ladderContact(point,budget,.46);}
    public ActorLadderGuide.Contact ladderContact(World.Vec point,Budget budget,double reach){
        checkCurrent();ActorLadderGuide.Contact best=null;double nearest=Double.POSITIVE_INFINITY;
        for(var cell:ladders){budget.use();if(cell.ladderGuide==null)throw new IllegalStateException("native_actor_ladder_unprepared");var b=cell.ladderGuide.bounds();if(point.y()+ActorLadders.EYE<b.y()-.01||point.y()+ActorLadders.EYE>b.yy()+.01||point.x()<b.x()-reach||point.x()>b.xx()+reach||point.z()<b.z()-reach||point.z()>b.zz()+reach)continue;
            var found=cell.ladderGuide.approach(point,reach);if(found==null)continue;double distance=Math.hypot(found.anchor().x()-point.x(),found.anchor().z()-point.z());if(distance<nearest){nearest=distance;best=found;}}
        return best;
    }
    private static void prepare(Node node,ActorMotion motion){if(node==null)return;if(node.cells!=null){for(var cell:node.cells)cell.nativeCell=motion.prepare(cell.descriptor);}else{prepare(node.left,motion);prepare(node.right,motion);}}
    public List<Object> nativeCells(SceneCollision.Box area,Budget budget){List<Object> values=new ArrayList<>();collect(root,area,budget,values);return values;}
    private static void collect(Node node,SceneCollision.Box area,Budget budget,List<Object> values){if(node==null||!intersects(node.bounds,area))return;budget.use();if(node.cells==null){collect(node.left,area,budget,values);collect(node.right,area,budget,values);}else for(var cell:node.cells)if(intersects(cell.bounds(),area)){budget.use();if(cell.nativeCell==null)throw new IllegalStateException("native_actor_scene_unprepared");values.add(cell.nativeCell);}}
    public List<Object> nativeLadders(SceneCollision.Box area,Budget budget){checkCurrent();if(!covers(area))throw new Deferred();var result=new ArrayList<Object>();for(var cell:ladders){budget.use();if(intersects(cell.bounds(),area)){if(cell.nativeCell==null)throw new IllegalStateException("native_actor_ladder_unprepared");result.add(cell.nativeCell);}}return List.copyOf(result);}
    public List<ActorMotion.Cell> ladderDescriptors(SceneCollision.Box area,Budget budget){checkCurrent();if(!covers(area))throw new Deferred();var result=new ArrayList<ActorMotion.Cell>();for(var cell:ladders){budget.use();if(intersects(cell.bounds(),area))result.add(cell.descriptor);}return List.copyOf(result);}
    public int cells(){return cells;}
    /** Ordinary descriptors can be prepared by an isolated worker loader without mutating live cells. */
    public List<ActorMotion.Cell> descriptors(SceneCollision.Box area,Budget budget){checkCurrent();if(!covers(area))throw new Deferred();var result=new ArrayList<ActorMotion.Cell>();descriptors(root,area,budget,result);return List.copyOf(result);}
    private static void descriptors(Node node,SceneCollision.Box area,Budget budget,List<ActorMotion.Cell> result){if(node==null||!intersects(node.bounds,area))return;budget.use();if(node.cells==null){descriptors(node.left,area,budget,result);descriptors(node.right,area,budget,result);}else for(var cell:node.cells)if(intersects(cell.bounds(),area)){budget.use();result.add(cell.descriptor);}}
    /** Point segment against the same affine native cells, with a caller-owned bounded budget. */
    public boolean pointClear(World.Vec a,World.Vec b,Budget budget){
        var area=new SceneCollision.Box(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),Math.max(a.y(),b.y()),Math.max(a.z(),b.z()));
        return !pointHit(root,area,a,new World.Vec(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()),budget);
    }
    private static boolean pointHit(Node node,SceneCollision.Box area,World.Vec start,World.Vec delta,Budget budget){
        if(node==null||!intersects(node.bounds,area))return false;budget.use();if(node.cells==null)return pointHit(node.left,area,start,delta,budget)||pointHit(node.right,area,start,delta,budget);
        for(var cell:node.cells)if(intersects(cell.bounds(),area)){budget.use();if(cell.pointHit(start,delta))return true;}return false;
    }
    public boolean covers(World.Vec position){return covers(position,0);}
    /** Refresh margin concerns possible future motion; y<0 is the world's known solid boundary. */
    public boolean covers(World.Vec position,double margin){return position.x()-.6-margin>=area.x()&&position.x()+.6+margin<=area.xx()&&Math.max(0,position.y()-.5-margin)>=area.y()&&position.y()+2.3+margin<=area.yy()&&position.z()-.6-margin>=area.z()&&position.z()+.6+margin<=area.zz();}
    public boolean covers(SceneCollision.Box swept){return swept.x()>=area.x()&&swept.xx()<=area.xx()&&Math.max(0,swept.y())>=area.y()&&swept.yy()<=area.yy()&&swept.z()>=area.z()&&swept.zz()<=area.zz();}
    public static SceneCollision.Box body(double x,double y,double z){return ActorPose.STAND.body(x,y,z);}
    static boolean intersects(SceneCollision.Box a,SceneCollision.Box b){return a.x()<=b.xx()&&a.xx()>=b.x()&&a.y()<=b.yy()&&a.yy()>=b.y()&&a.z()<=b.zz()&&a.zz()>=b.z();}
    private static Node build(ActorGeometry[] cells,int depth){
        if(cells.length==0)return null;double x=Double.POSITIVE_INFINITY,y=x,z=x,xx=Double.NEGATIVE_INFINITY,yy=xx,zz=xx;
        for(var cell:cells){var b=cell.bounds();x=Math.min(x,b.x());y=Math.min(y,b.y());z=Math.min(z,b.z());xx=Math.max(xx,b.xx());yy=Math.max(yy,b.yy());zz=Math.max(zz,b.zz());}
        var bounds=new SceneCollision.Box(x,y,z,xx,yy,zz);if(cells.length<=8)return new Node(bounds,null,null,cells);
        int axis=depth%3;Arrays.sort(cells,Comparator.comparingDouble(c->{var b=c.bounds();return axis==0?b.x()+b.xx():axis==1?b.y()+b.yy():b.z()+b.zz();}));int middle=cells.length/2;
        return new Node(bounds,build(Arrays.copyOfRange(cells,0,middle),depth+1),build(Arrays.copyOfRange(cells,middle,cells.length),depth+1),null);
    }
    public boolean overlaps(SceneCollision.Box body,Budget budget){return overlaps(root,body,budget);}
    private static boolean overlaps(Node node,SceneCollision.Box body,Budget budget){
        if(node==null||!intersects(node.bounds,body))return false;budget.use();if(node.cells==null)return overlaps(node.left,body,budget)||overlaps(node.right,body,budget);
        for(var cell:node.cells)if(intersects(cell.bounds(),body)){budget.use();if(cell.overlaps(body))return true;}return false;
    }
    public double sweep(SceneCollision.Box body,int axis,double delta,Budget budget){
        if(delta==0)return 0;var area=new SceneCollision.Box(body.x()+Math.min(0,axis==0?delta:0),body.y()+Math.min(0,axis==1?delta:0),body.z()+Math.min(0,axis==2?delta:0),body.xx()+Math.max(0,axis==0?delta:0),body.yy()+Math.max(0,axis==1?delta:0),body.zz()+Math.max(0,axis==2?delta:0));return delta*sweep(root,body,area,axis,delta,budget);
    }
    private static double sweep(Node node,SceneCollision.Box body,SceneCollision.Box area,int axis,double delta,Budget budget){
        if(node==null||!intersects(node.bounds,area))return 1;budget.use();if(node.cells==null)return Math.min(sweep(node.left,body,area,axis,delta,budget),sweep(node.right,body,area,axis,delta,budget));double fraction=1;
        for(var cell:node.cells)if(intersects(cell.bounds(),area)){budget.use();fraction=Math.min(fraction,cell.sweep(body,axis,delta));}return fraction;
    }
}
