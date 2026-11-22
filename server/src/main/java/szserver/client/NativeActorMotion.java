package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Calls the installed player's 251809992.else resolver, including its native step selection.
 * Bridges supply world/forest access and actor-only velocity services. Installed bytecode and live client state are never changed. */
public final class NativeActorMotion implements ActorMotion,ActorDynamics,ActorJump,AutoCloseable {
    private static final Map<String,String> REVIEWED=Map.ofEntries(Map.entry("251809992","e1cd556e0358a0d05606df2cf9468d435606693323ba914e9a45dd8b83b90ca7"),Map.entry("1217489680","04bce6308d96e693be95e16a94e9985450d1c0ffb8c459a43e6ec750d7cb005e"),Map.entry("1500514786","476d7c2863e32381f3e1cb63d130266fee8f27ee1f944a6c615c5cfee7b95fbd"),Map.entry("493563411","1cf702707b7a8f0daf928fd9a72f1750a68f15cf2ecc98926814513db093bda5"),Map.entry("1581944406","fc5a6b38319740241cd0dbb9ce78ea4268a9cb5e6a6398cdd09fbeaf0bd07b5f"),Map.entry("334744794","25cbc4e4ea6587ad0f54debb496317a876d1edb5fa7726a8fb3f53f8bc622cb4"),Map.entry("242639986","0ae4cd7b52cb476c7a7245dea8aa5e62250bb15544e5f3c220f15cfc293a22b3"),Map.entry("674216082","4a9f46a2832de7ae4597755c3cabf8925e2e3c5c3f87cafe6204b969ccfb8c8a"),Map.entry("1994198637","8d0b9df7407ff5412dc02f515e474f1029067752edd7cddb84c92a92c419b6b8"),Map.entry("1303372114","153673c22d50c3e49ce4084f0533fc26375aab06df0183512f374f86b923bba3"),Map.entry("1384438816","7bc08a9fc2f81596128570c0117d8695cec470236c54a66e0c3a0557c1acdb1f"),Map.entry("683991127","3eea8630535ebaa55b4218598d23e17f823388c7caa967c7d5da29c602525853"),Map.entry("1326697707","d91432e04439b5b00eb7a647087f79ec72ad03442a3c361340da682805e31adf"));
    private static final class Loader extends URLClassLoader{
        private Path nativeNbt;
        Loader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
        void resources(Path path)throws Exception{addURL(path.toRealPath().toUri().toURL());}
        @Override protected String findLibrary(String name){
            if(!name.equals("zstd-jni-1.5.0-5"))return null;
            try{
                if(nativeNbt==null){
                    nativeNbt=Files.createTempFile("sz-actor-zstd-"+ProcessHandle.current().pid()+"-",".dll");nativeNbt.toFile().deleteOnExit();
                    try(var input=getResourceAsStream("win/amd64/libzstd-jni-1.5.0-5.dll")){
                        if(input==null)throw new java.io.IOException("bundled_actor_jni_missing");Files.copy(input,nativeNbt,StandardCopyOption.REPLACE_EXISTING);
                    }
                    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(nativeNbt)));
                    if(!hash.equals("cd5142c5d80c839fb54e778d73178424e7900ec189eeb4fe7ef49573555050c9"))throw new java.io.IOException("bundled_actor_jni_mismatch");
                }
                return nativeNbt.toAbsolutePath().toString();
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("native_actor_nbt_library",failure);}
        }
    }
    private final Loader loader;private final Object unsafe;private final Method allocate,box,solve,supportQuery,bodyQuery;private final Constructor<?> bounds,vector,shape,transform;
    private final Class<?> cellType;private final Object actor,world,forest,tree;private final Field scene,voxels,query,bodies,cellShape,cellTransform,origin,basis;private final Field[] fields;
    private Thread owner;
    private final Field cellMode,cellOwner,objectBehavior;private final Class<?> sceneObjectType,ladderBehavior;private final Object solidMode,emptyMode;
    private final NativeActorMapObject mapObjects;
    private final NativeActorVelocity velocity;
    private final NativeFeedbackBounds feedbackBounds;
    private final NativeActorBodies bodyFactory;
    private final Path runtimeRoot;
    private NativeActorComponents dispatcherComponents;
    private NativeActorMapWorld.Factory dispatcherMaps;
    private Path dispatcherResources,dispatcherDefinitions,dispatcherRegistry;
    NativeActorMines mines(){if(dispatcherMaps==null)throw new IllegalStateException("actor_mines_not_prepared");return dispatcherMaps.mines;}
    private record Prepared(Object value,double x,double y,double z){}
    private static SceneCollision.Box shifted(SceneCollision.Box b,double x,double y,double z){return new SceneCollision.Box(b.x()+x,b.y()+y,b.z()+z,b.xx()+x,b.yy()+y,b.zz()+z);}
    public NativeActorMotion(Path runtime)throws Exception{
        runtimeRoot=runtime.toRealPath();ActorCarouselRelease.verify(runtimeRoot);
        runtime=runtime.toRealPath();TileGeometryRelease.verify(runtime);CarpenterStateRelease.verify(runtime);CarpenterWorldRelease.verify(runtime);ResourceBlockRelease.verify(runtime);BuiltinBlockRelease.verify(runtime);CustomBlockRelease.verify(runtime);ActorLadderRelease.verify(runtime);ActorDynamicsRelease.verify(runtime);ActorWaterRelease.verify(runtime);ActorJumpRelease.verify(runtime);ActorPostureRelease.verify(runtime);
        ActorLocomotionRelease.verify(runtime);
        ActorSupportRelease.verify(runtime);
        ActorFeedbackRelease.verify(runtime);
        ActorFeedbackGeometryRelease.verify(runtime);
        ActorBodyRelease.verify(runtime);
        ActorEffectsRelease.verify(runtime);ActorSceneLadderRelease.verify(runtime);ActorZiplineRelease.verify(runtime);ActorEquipmentRelease.verify(runtime);ActorSeatRelease.verify(runtime);
        for(var entry:REVIEWED.entrySet()){var bytes=Files.readAllBytes(runtime.resolve("classes").resolve(entry.getKey()+".class"));if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(entry.getValue()))throw new IllegalStateException("native_actor_release_mismatch:"+entry.getKey());}
        List<URL> urls=new ArrayList<>();for(String name:List.of("classes","bootstrap","libs"))urls.add(runtime.resolve(name).toUri().toURL());try(var files=Files.list(runtime.resolve("dependencies"))){for(Path p:files.filter(p->p.toString().endsWith(".jar")).toList())urls.add(p.toUri().toURL());}loader=new Loader(urls.toArray(URL[]::new));
        Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=f.get(null);allocate=u.getMethod("allocateInstance",Class.class);
        Class<?> w=loader.define("szserver.bridge.ActorWorld",NativeActorBridge.world()),fr=loader.define("szserver.bridge.ActorForest",NativeActorBridge.forest()),tr=loader.define("szserver.bridge.ActorTree",NativeActorBridge.tree());cellType=loader.define("szserver.bridge.ActorCell",NativeActorBridge.cell());
        Class<?> entity=Class.forName("1217489680",true,loader),aabb=Class.forName("242639986",true,loader),b=Class.forName("1994198637",true,loader),v=Class.forName("javax.vecmath.Vector3f",true,loader),sh=Class.forName("com.bulletphysics.collision.shapes.BoxShape",true,loader),tf=Class.forName("com.bulletphysics.linearmath.Transform",true,loader);
        world=allocate.invoke(unsafe,w);forest=allocate.invoke(unsafe,fr);tree=allocate.invoke(unsafe,tr);actor=allocate.invoke(unsafe,entity);w.getField("forest").set(world,forest);fr.getField("tree").set(forest,tree);Field ef=entity.getDeclaredField("interface");ef.setAccessible(true);ef.set(actor,world);
        scene=tr.getField("cells");scene.set(tree,List.of());voxels=w.getField("voxels");box=aabb.getMethod("synchronized",double.class,double.class,double.class,double.class,double.class,double.class);bounds=b.getConstructor(aabb);fields=new Field[6];String[] names={"return","void","long","class","final","default"};for(int n=0;n<6;n++)fields[n]=b.getField(names[n]);
        solve=Class.forName("251809992",true,loader).getMethod("else",entity,aabb,double.class,double.class,double.class,float.class,boolean.class);
        query=w.getField("query");bodies=w.getField("bodies");supportQuery=Class.forName("433657566",true,loader).getMethod("synchronized",w.getSuperclass(),aabb);
        bodyFactory=new NativeActorBodies(loader,loader::define,unsafe,allocate);
        bodyQuery=w.getMethod("package",entity,aabb,List.class);
        vector=v.getConstructor(float.class,float.class,float.class);shape=sh.getConstructor(v);transform=tf.getConstructor();origin=tf.getField("origin");basis=tf.getField("basis");cellShape=cellType.getField("goto");cellTransform=cellType.getField("catch");
        cellMode=cellType.getField("collisionMode");cellOwner=cellType.getField("package");cellOwner.setAccessible(true);
        sceneObjectType=Class.forName("1886271463",true,loader);objectBehavior=sceneObjectType.getDeclaredField("byte");objectBehavior.setAccessible(true);ladderBehavior=Class.forName("1395149101",true,loader);
        Class<?> collisionMode=Class.forName("1384438816",true,loader);solidMode=collisionMode.getField("OBB").get(null);emptyMode=collisionMode.getField("NONE").get(null);
        String ladderSourceKey=((String[])Class.forName("1281655698",true,loader).getField("if").get(null))[4227];if(!ladderSourceKey.equals(SceneSettings.LADDER_SOURCE_KEY))throw new IllegalStateException("native_scene_ladder_property");
        // Warm the full reviewed solver/pool path before entering the owner loop.
        move(new World.Vec(.5,1,.5),new World.Vec(.1,-.08,.1),true,List.of(),area->List.of(new SceneCollision.Box(-4,0,-4,4,1,4)));// Warm Bullet convex cast/scene contact too: never initialize its resource graph on a tick.
        Object warm=prepare(new Cell(new SceneCollision.Box(0,0,0,1,.5,3),new double[]{1,0,0,0,1,0,0,0,1},new World.Vec(2,1,0)));
        move(new World.Vec(1.65,1,.5),new World.Vec(.15,-.03,0),true,List.of(warm),area->List.of(new SceneCollision.Box(-4,0,-4,4,1,4)));
        support(new World.Vec(.5,1.1,.5),List.of(),area->List.of(new SceneCollision.Box(-4,0,-4,4,1,4)));
        support(new World.Vec(2.5,1.6,.5),List.of(warm),area->List.of());
        mapObjects=new NativeActorMapObject(loader,unsafe,allocate,loader::define);
        ActorPostureRelease.verifyValues(loader);velocity=new NativeActorVelocity(loader,loader::define,unsafe,allocate);feedbackBounds=new NativeFeedbackBounds(loader);owner=null;
    }
    /** Startup only: no resource/class graph construction on a world owner. */
    NativeActorComponents dispatcherComponents(Path resources,Path itemDefinitions)throws Exception{
        return dispatcherComponents(resources,itemDefinitions,resources.resolveSibling("client-current-item-states.json"));
    }
    NativeActorComponents dispatcherComponents(Path resources,Path itemDefinitions,Path itemRegistry)throws Exception{
        if(owner!=null||dispatcherComponents!=null)throw new IllegalStateException("actor_dispatch_prepare_order");
        loader.resources(resources);dispatcherComponents=new NativeActorComponents(runtimeRoot,itemDefinitions,itemRegistry,loader,loader::define,unsafe,allocate);dispatcherComponents.warm(world);
        dispatcherMaps=new NativeActorMapWorld.Factory(runtimeRoot,itemDefinitions.resolveSibling("native-resource-block-profiles.json"),resources,loader,loader::define,unsafe,allocate,this,bodyFactory);dispatcherResources=resources;dispatcherDefinitions=itemDefinitions;dispatcherRegistry=itemRegistry;return dispatcherComponents;
    }
    /** Explicit startup preparation; callers decide whether to enable this still incomplete world integration. */
    public ActorControllerEngine controllerEngine(Path resources,Path itemDefinitions)throws Exception{return new NativeActorControllerEngine(this,dispatcherComponents(resources,itemDefinitions));}
    public ActorControllerEngine controllerEngine(Path resources,Path itemDefinitions,Path itemRegistry)throws Exception{return new NativeActorControllerEngine(this,dispatcherComponents(resources,itemDefinitions,itemRegistry));}
    @Override public ActorPathValidator pathValidator(){if(dispatcherResources==null)throw new IllegalStateException("native_route_validator_unavailable");try{return new NativeActorPathValidator(runtimeRoot,dispatcherResources,dispatcherDefinitions,dispatcherRegistry);}catch(Exception error){throw new IllegalStateException("native_route_validator_prepare",error);}}
    NativeActorMapWorld dispatcherWorld(World.Vec origin)throws Exception{if(dispatcherMaps==null)throw new IllegalStateException("actor_dispatch_not_prepared");return dispatcherMaps.create(origin);}
    /** Reuse this runtime's already loaded block registry, never a competing copy of the game. */
    private NativeResidentChunkCodec residentCodec;
    public NativeResidentChunkCodec residentChunkCodec()throws Exception{
        if(residentCodec==null){Class<?> host=loader.define("szserver.bridge.ResidentWorld",NativeActorBridge.residentWorld());residentCodec=new NativeResidentChunkCodec(runtimeRoot,loader,host,allocate.invoke(unsafe,host));}
        return residentCodec;
    }
    @Override public List<SceneCollision.Box> feedbackBounds(float[] affine,List<SceneCollision.Box> boxes){return feedbackBounds.bounds(affine,boxes);}
    @Override public ActorContacts.Obstacle livingObstacle(SceneRegistry.Definition definition,SceneObject object){if(dispatcherComponents==null)throw new IllegalStateException("native_living_obstacle_not_prepared");try{return mapObjects.livingObstacle(definition,object);}catch(Exception error){throw new IllegalStateException("native_living_obstacle_read",error);}}
    @Override public ActorDynamics.Result advance(ActorDynamics.State state,World.Vec steering,double dt,ActorDynamics.Movement movement){return velocity.advance(state,steering,dt,movement);}
    @Override public ActorDynamics.Result advance(ActorDynamics.State state,World.Vec steering,double dt,ActorDynamics.Movement movement,ActorLocomotion.Control control){return velocity.advance(state,steering,dt,movement,control);}
    @Override public double travelSpeed(ActorPose pose,boolean water){return velocity.travelSpeed(pose,water);}
    @Override public boolean grounded(boolean previous,double airborneTime,java.util.function.BooleanSupplier support){return velocity.grounded(previous,airborneTime,support);}
    @Override public double jumpSpeed(){return velocity.jumpSpeed();}
    @Override public boolean water(World.Vec p,BlockShapes.Voxels source){return velocity.water(p,source);}
    @Override public ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt){return velocity.jumpStep(old,ground,ladder,held,moving,water,vy,y,dt);}
    @Override public boolean water(World.Vec p,BlockShapes.Voxels source,ActorPose pose){return velocity.water(p,source,pose);}
    @Override public ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt,ActorPose pose){return velocity.jumpStep(old,ground,ladder,held,moving,water,vy,y,dt,pose);}
    @Override public ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt,ActorPose pose,int mode){return velocity.jumpStep(old,ground,ladder,held,moving,water,vy,y,dt,pose,mode);}
    private Object box(SceneCollision.Box b)throws Exception{return box.invoke(null,b.x(),b.y(),b.z(),b.xx(),b.yy(),b.zz());}
    private SceneCollision.Box bounds(Object box)throws Exception{Object b=bounds.newInstance(box);return new SceneCollision.Box(fields[0].getDouble(b),fields[1].getDouble(b),fields[2].getDouble(b),fields[3].getDouble(b),fields[4].getDouble(b),fields[5].getDouble(b));}
    @Override public Object prepare(Cell cell){try{
        Object value=allocate.invoke(unsafe,cellType);cellMode.set(value,solidMode);cellOwner.set(value,allocate.invoke(unsafe,sceneObjectType));var b=cell.local();Object s=shape.newInstance(vector.newInstance((float)(b.xx()-b.x())*.5f,(float)(b.yy()-b.y())*.5f,(float)(b.zz()-b.z())*.5f));Object t=transform.newInstance();t.getClass().getMethod("setIdentity").invoke(t);double[] m=cell.matrix();Object matrix=basis.get(t);for(int row=0;row<3;row++)for(int col=0;col<3;col++)matrix.getClass().getField("m"+row+col).setFloat(matrix,(float)m[row*3+col]);double x=(b.x()+b.xx())*.5,y=(b.y()+b.yy())*.5,z=(b.z()+b.zz())*.5;Object o=origin.get(t);cellShape.set(value,s);cellTransform.set(value,t);return new Prepared(value,m[0]*x+m[1]*y+m[2]*z+cell.translation().x(),m[3]*x+m[4]*y+m[5]*z+cell.translation().y(),m[6]*x+m[7]*y+m[8]*z+cell.translation().z());
    }catch(Exception error){throw new IllegalStateException("native_actor_cell",error);}}
    @Override public Object prepareLadder(Cell cell){
        SceneCollision.worker();Prepared prepared=(Prepared)prepare(cell);try{Object behavior=allocate.invoke(unsafe,ladderBehavior);ladderBehavior.getField("package").setBoolean(behavior,true);objectBehavior.set(cellOwner.get(prepared.value()),behavior);cellMode.set(prepared.value(),emptyMode);return prepared;}catch(Exception error){throw new IllegalStateException("native_scene_ladder",error);}
    }
    @Override public ActorZipline ziplineGuide(Object value){try{return mapObjects.guide(value);}catch(Exception error){throw new IllegalStateException("native_zipline_guide",error);}}
    @Override public List<ActorSeat> seatGuides(Object value){return mapObjects.seatGuides(value);}
    Object seatAction(Object prepared,ActorControllerEngine.Seat request,ActorTraversal.Seat current,World.Vec actor,Object world,int x,int z,ActorWorldAccess access)throws Exception{return mapObjects.seatAction(prepared,request,current,actor,world,x,z,access);}
    /** Resource configuration prepared at startup; independent from the optional NPC dispatcher. */
    public Path anomalyResources(){return dispatcherResources;}
    @Override public boolean mapObjectsEnabled(){return dispatcherComponents!=null;}
    @Override public ActorSceneObject describeMapObject(SceneRegistry.Definition definition,SceneObject object){try{var prepared=mapObjects.prepare(definition,object);return prepared==null?null:new ActorSceneObject(definition,object,mapObjects.bounds(prepared,object.identity()));}catch(Exception error){throw new IllegalStateException("native_actor_map_object_bounds:"+definition.name()+":"+object.identity(),error);}}
    @Override public Object prepareMapObject(SceneRegistry.Definition definition,SceneObject object){try{return mapObjects.prepare(definition,object);}catch(Exception error){throw new IllegalStateException("native_actor_map_object_prepare",error);}}
    Object localMapObject(Object value,int x,int z)throws Exception{return mapObjects.local(value,x,z);}
    Object restoreCarouselObject(ActorCarouselState state)throws Exception{SceneCollision.worker();var definition=new SceneRegistry.Definition(state.networkId(),state.asset(),"carousel",state.profile(),com.google.gson.JsonParser.parseString(state.definition()).getAsJsonObject());return mapObjects.prepare(definition,new SceneObject(state.asset(),Base64.getDecoder().decode(state.record())));}
    private static Method scalar(Class<?> type,String name)throws NoSuchMethodException{for(var method:type.getMethods())if(method.getName().equals(name)&&method.getParameterCount()==0&&method.getReturnType()==double.class)return method;throw new NoSuchMethodException(name+":double");}
    @Override public ActorLadderGuide ladderGuide(Object prepared){
        SceneCollision.worker();try{
            Prepared cell=(Prepared)prepared;double ox=Math.floor(cell.x()/16)*16,oz=Math.floor(cell.z()/16)*16;
            Object nativeCell=dispatcherCells(List.of(prepared),ox,0,oz).get(0);
            Class<?> planeType=Class.forName("1767064101",true,loader),pointType=Class.forName("1738045740",true,loader),vectorType=Class.forName("153304200",true,loader);
            Object plane=Class.forName("1049002105",true,loader).getMethod("finally",Class.forName("334744794",true,loader),int.class,int.class,int.class,int.class).invoke(null,nativeCell,6,7,5,4);
            Method project=planeType.getMethod("long",pointType),normal=planeType.getMethod("transient",double.class,double.class,double.class),inside=planeType.getMethod("const",vectorType);Constructor<?> point=vectorType.getConstructor(double.class,double.class,double.class);
            Method getX=scalar(pointType,"super"),getY=scalar(pointType,"long"),getZ=scalar(pointType,"abstract");
            Class<?> rectangleType=Class.forName("1338767630",true,loader),point2Type=Class.forName("1362694342",true,loader);
            Constructor<?> rectangle=rectangleType.getConstructor(double.class,double.class,double.class,double.class),point2=point2Type.getConstructor(double.class,double.class);
            Method direction=rectangleType.getMethod("default",double.class,double.class),padding=Class.forName("879126158",true,loader).getMethod("default",double.class,rectangleType,point2Type,double.class,java.util.function.BiConsumer.class,double.class,double.class);
            double p0x=planeType.getField("package").getDouble(plane),p0z=planeType.getField("synchronized").getDouble(plane),p1x=planeType.getField("public").getDouble(plane),p1z=planeType.getField("strictfp").getDouble(plane);
            String[][] vertices={{"package","float","synchronized"},{"public","while","strictfp"},{"implements","int","return"},{"if","try","this"}};
            double x=Double.POSITIVE_INFINITY,y=x,z=x,xx=-x,yy=-x,zz=-x;for(var v:vertices){double a=planeType.getField(v[0]).getDouble(plane)+ox,b=planeType.getField(v[1]).getDouble(plane),c=planeType.getField(v[2]).getDouble(plane)+oz;x=Math.min(x,a);y=Math.min(y,b);z=Math.min(z,c);xx=Math.max(xx,a);yy=Math.max(yy,b);zz=Math.max(zz,c);}var bounds=new SceneCollision.Box(x,y,z,xx,yy,zz);
            return new ActorLadderGuide(){public SceneCollision.Box bounds(){return bounds;}public Contact approach(World.Vec feet,double reach){try{
                double px=feet.x()-ox,py=feet.y()+ActorLadders.EYE,pz=feet.z()-oz;Object eye=point.newInstance(px,py,pz),n=normal.invoke(plane,px,py,pz),projected=project.invoke(plane,eye);
                double ax=(double)getX.invoke(projected),ay=(double)getY.invoke(projected),az=(double)getZ.invoke(projected),nx=(double)getX.invoke(n),ny=(double)getY.invoke(n),nz=(double)getZ.invoke(n);
                double gap=Math.sqrt(Math.pow(px-ax,2)+Math.pow(py-ay,2)+Math.pow(pz-az,2));if(gap>reach||!(boolean)inside.invoke(plane,projected)||Math.hypot(nx,nz)<1e-6)return null;
                Object normalLine=rectangle.newInstance(p0x,p0z,p0x+nx,p0z+nz),edgeLine=rectangle.newInstance(p0x,p0z,p1x,p1z);
                double angle=((Number)direction.invoke(normalLine,px,pz)).doubleValue(),width=ActorPose.RADIUS*2;
                double offset=(double)padding.invoke(null,angle,edgeLine,point2.newInstance(p0x,p0z),Math.atan2(width,width),null,2d,width)+.05;
                return new Contact(new World.Vec(ax+nx*offset+ox,ay+ny*offset-ActorLadders.EYE,az+nz*offset+oz),(float)Math.toDegrees(Math.atan2(nx,-nz)));
            }catch(Exception error){throw new IllegalStateException("native_actor_ladder_guide",error);}}};
        }catch(Exception error){throw new IllegalStateException("native_actor_ladder_guide",error);}
    }
    @Override public ActorMotion.Result move(World.Vec p,World.Vec delta,boolean ground,List<Object> geometry,Voxels source){return move(p,delta,ground,geometry,source,ActorPose.STAND);}
    @Override public boolean support(World.Vec p,List<Object> geometry,Voxels source){
        if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("native_actor_motion_owner");
        double ox=Math.floor(p.x()),oy=Math.floor(p.y()),oz=Math.floor(p.z());
        try{
            // The exact narrow probe from 1704556748.class; all overlap/tolerance rules stay native.
            Object probe=box(new SceneCollision.Box(p.x()-ox-.075,p.y()-oy-1.45,p.z()-oz-.075,p.x()-ox+.075,p.y()-oy,p.z()-oz+.075));
            prepareQuery(geometry,source,ox,oy,oz);
            return (Boolean)supportQuery.invoke(null,world,probe);
        }catch(InvocationTargetException error){throw propagate(error.getCause());}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("native_actor_support",error);}finally{clearQuery();}
    }
    private void prepareQuery(List<Object> geometry,Voxels source,double ox,double oy,double oz)throws Exception{
        scene.set(tree,dispatcherCells(geometry,ox,oy,oz));
        java.util.function.Function<Object,List<Object>> capture=area->{try{List<Object> output=new ArrayList<>();for(var value:source.boxes(shifted(bounds(area),ox,oy,oz)))output.add(box(shifted(value,-ox,-oy,-oz)));return output;}catch(InvocationTargetException error){throw propagate(error.getCause());}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}};
        Map<ActorBody,Object> prepared=new HashMap<>();
        bodies.set(world,(java.util.function.Function<Object,List<Object>>)area->{try{
            List<Object> found=new ArrayList<>();for(var body:source.bodies(shifted(bounds(area),ox,oy,oz))){if(body.id().equals(source.actorId()))continue;
                Object value=prepared.get(body);if(value==null){value=bodyFactory.prepare(body,box(shifted(body.bounds(),-ox,-oy,-oz)));prepared.put(body,value);}found.add(value);
            }return found;
        }catch(InvocationTargetException error){throw propagate(error.getCause());}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("native_actor_body_query",error);}});
        query.set(world,(java.util.function.Function<Object,List<Object>>)area->{try{List<Object> output=capture.apply(area);bodyQuery.invoke(world,actor,area,output);return output;}catch(InvocationTargetException error){throw propagate(error.getCause());}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("native_actor_support_bodies",error);}});
        voxels.set(world,(BiConsumer<Object,Object>)(area,output)->((List<Object>)output).addAll(capture.apply(area)));
    }
    List<Object> dispatcherCells(List<Object> geometry,double ox,double oy,double oz)throws Exception{
        return dispatcherCells(geometry,ox,oy,oz,new IdentityHashMap<>());
    }
    private record QueryCell(Object value,double x,double y,double z){}
    List<Object> dispatcherCells(List<Object> geometry,double ox,double oy,double oz,Map<Object,Object> cache)throws Exception{
        List<Object> cells=new ArrayList<>(geometry.size());
        for(Object entry:geometry){
            Prepared c=(Prepared)entry;QueryCell saved=(QueryCell)cache.get(entry);
            if(saved==null){
                Object value=allocate.invoke(unsafe,cellType),local=transform.newInstance();local.getClass().getMethod("setIdentity").invoke(local);
                Object before=basis.get(cellTransform.get(c.value)),after=basis.get(local);
                for(int r=0;r<3;r++)for(int col=0;col<3;col++){String name="m"+r+col;after.getClass().getField(name).setFloat(after,before.getClass().getField(name).getFloat(before));}
                Object o=origin.get(local);o.getClass().getField("x").setFloat(o,(float)(c.x-ox));o.getClass().getField("y").setFloat(o,(float)(c.y-oy));o.getClass().getField("z").setFloat(o,(float)(c.z-oz));
                cellShape.set(value,cellShape.get(c.value));cellTransform.set(value,local);cellMode.set(value,cellMode.get(c.value));cellOwner.set(value,cellOwner.get(c.value));saved=new QueryCell(value,ox,oy,oz);cache.put(entry,saved);
            }else if(saved.x()!=ox||saved.y()!=oy||saved.z()!=oz)throw new IllegalArgumentException("actor_cell_cache_origin");
            cells.add(saved.value());
        }return cells;
    }
    private void clearQuery(){try{scene.set(tree,List.of());voxels.set(world,null);query.set(world,null);bodies.set(world,null);}catch(IllegalAccessException impossible){throw new IllegalStateException(impossible);}}
    @Override public ActorMotion.Result move(World.Vec p,World.Vec delta,boolean ground,List<Object> geometry,Voxels source,ActorPose pose){
        if(pose==ActorPose.PRONE)throw new IllegalArgumentException("full_native_prone_controller_required");
        if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("native_actor_motion_owner");
        // Bullet transforms use floats. Rebase every local solve to preserve centimetres at map edges.
        double ox=Math.floor(p.x()),oy=Math.floor(p.y()),oz=Math.floor(p.z());
        try{Object body=box(pose.body(p.x()-ox,p.y()-oy,p.z()-oz));prepareQuery(geometry,source,ox,oy,oz);
            int flags=(Integer)solve.invoke(null,actor,body,delta.x(),delta.y(),delta.z(),ground?(float)ActorPose.STEP:0f,false);var moved=bounds(body);return new ActorMotion.Result((moved.x()+moved.xx())*.5+ox,moved.y()+oy,(moved.z()+moved.zz())*.5+oz,(flags&1)!=0,(flags&2)!=0,(flags&4)!=0);
        }catch(InvocationTargetException error){throw propagate(error.getCause());}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("native_actor_move",error);}finally{clearQuery();}
    }
    private static RuntimeException propagate(Throwable error){return error instanceof RuntimeException runtime?runtime:new IllegalStateException("native_actor_move",error);}
    @Override public void close()throws java.io.IOException{loader.close();}
}
