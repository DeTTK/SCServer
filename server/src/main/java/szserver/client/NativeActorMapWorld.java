package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Native block/chunk queries over resident server data. No chunk loading or native NBT on a frame. */
final class NativeActorMapWorld {
    static final class Factory {
        final ClassLoader loader;final Object unsafe;final Method allocate,box,collect,bodyCollect,face,contact;
        final Class<?> worldType,chunkType,treeType,forestType,worldBase,blockBase,boxType;
        final Object[] blocks,sides;final Constructor<?> bounds,carpenter,builder;final Field[] coordinates=new Field[6];
        final boolean[] callbacks;
        final Object[] prototypes;final Field[] profileBounds=new Field[6];
        final Field tileData,tileCover,tileExtension;final NativeActorMotion motion;final NativeActorBodies bodies;
        final NativeActorSceneFeedback.Factory feedback;
        final NativeActorCarousel carousels;
        final NativeActorMines mines;
        final ThreadLocal<NativeActorMapWorld> activeScope=new ThreadLocal<>();
        Factory(Path runtime,Path profiles,Path resources,ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate,NativeActorMotion motion,NativeActorBodies bodies)throws Exception {
            this.loader=loader;this.unsafe=unsafe;this.allocate=allocate;this.motion=motion;this.bodies=bodies;
            worldType=define.apply("szserver.bridge.DispatcherWorld",NativeActorBridge.dispatcherWorld());chunkType=define.apply("szserver.bridge.DispatcherChunk",NativeActorBridge.dispatcherChunk());treeType=define.apply("szserver.bridge.DispatcherTree",NativeActorBridge.dispatcherTree());
            forestType=Class.forName("szserver.bridge.ActorForest",true,loader);worldBase=type("1500514786");blockBase=type("1959919792");boxType=type("242639986");Class<?> actor=type("1217489680");
            box=boxType.getMethod("synchronized",double.class,double.class,double.class,double.class,double.class,double.class);bounds=type("1994198637").getConstructor(boxType);
            String[] names={"return","void","long","class","final","default"};for(int i=0;i<6;i++)coordinates[i]=bounds.getDeclaringClass().getField(names[i]);
            collect=worldBase.getMethod("const",actor,boxType,List.class);bodyCollect=worldBase.getMethod("package",actor,boxType,List.class);
            contact=blockBase.getMethod("instanceof",worldBase,int.class,int.class,int.class,actor);
            Class<?> side=type("434349477");sides=side.getEnumConstants();face=blockBase.getMethod("int",worldBase,int.class,int.class,int.class,side);
            blocks=(Object[])blockBase.getField("switch").get(null);prepareBlocks(runtime,profiles,define);
            mines=new NativeActorMines(this,runtime,resources,define);
            callbacks=new boolean[blocks.length];for(int id=1;id<blocks.length;id++)if(blocks[id]!=null){String callback=blocks[id].getClass().getMethod("instanceof",worldBase,int.class,int.class,int.class,actor).getDeclaringClass().getName();callbacks[id]=callback.equals("1959919792")||callback.equals("1912983241")||callback.equals("1820749314")||callback.equals("806673724");}
            prototypes=Arrays.stream(blocks).filter(Objects::nonNull).toArray();String[] profileFields={"new","finally","this","continue","public","throws"};for(int n=0;n<6;n++)profileBounds[n]=blockBase.getField(profileFields[n]);
            carpenter=type("1218866699").getConstructor();builder=type("132153080").getConstructor();tileData=carpenter.getDeclaringClass().getField("continue");tileCover=carpenter.getDeclaringClass().getField("strictfp");tileExtension=builder.getDeclaringClass().getDeclaredField("enum");tileExtension.setAccessible(true);
            // Construct both tile kinds before a world owner can query them.
            carpenter.newInstance();builder.newInstance();
            feedback=new NativeActorSceneFeedback.Factory(loader,unsafe,allocate);
            carousels=new NativeActorCarousel(loader,motion,resources);
        }
        private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
        private void prepareBlocks(Path runtime,Path path,BiFunction<String,byte[],Class<?>> define)throws Exception {
            if(Files.size(path)>2*1024*1024)throw new IllegalArgumentException("actor_map_profiles_size");
            var data=com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for(var row:data.getAsJsonObject("nativeClasses").entrySet()){
                if(!row.getKey().matches("[0-9]+"))throw new IllegalArgumentException("actor_map_profile_class");
                var file=runtime.resolve("classes").resolve(row.getKey()+".class");String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                if(!actual.equals(row.getValue().getAsString()))throw new IllegalStateException("actor_map_profile_release:"+row.getKey());
            }
            Class<?> material=type("393979275"),special=type("683052146");Object stone=material.getField("this").get(null);
            for(var entry:Map.of("h",4025,"a",4018).entrySet()){
                int id=entry.getValue();Class<?> kind=type(id==4025?"973969552":"384046726");Object value=blocks[id];if(value==null)value=kind.getConstructor(int.class,material).newInstance(id,stone);
                if(!kind.isInstance(value))throw new IllegalStateException("actor_map_cover_conflict");special.getField(entry.getKey()).set(null,value);
            }
            type("1648977992").getMethod("throws").invoke(null);Class<?> factory=type("1872807016");factory.getMethod("class").invoke(null);factory.getMethod("continue").invoke(null);
            Class<?> resource=type("127720696");Method profile=resource.getMethod("if",float.class,float.class,float.class,float.class,float.class,float.class);int[] render=(int[])blockBase.getField("z").get(null);
            for(var item:data.getAsJsonObject("capture").getAsJsonArray("blocks")){
                var row=item.getAsJsonObject();int id=row.get("id").getAsInt();if(id<=0||id>=blocks.length||!row.get("class").getAsString().equals(resource.getName()))throw new IllegalArgumentException("actor_map_resource_binding");
                if(blocks[id]!=null)throw new IllegalStateException("actor_map_resource_conflict:"+id);
                Object value=resource.getConstructor(int.class,boolean.class).newInstance(id,row.get("collides").getAsBoolean());resource.getField("finally").setBoolean(value,row.get("rotate").getAsBoolean());resource.getField("public").setBoolean(value,row.get("opaque").getAsBoolean());render[id]=row.get("renderType").getAsInt();
                if(row.getAsJsonArray("profile").size()!=6)throw new IllegalArgumentException("actor_map_profile_shape");
                Object[] args=new Object[6];for(int n=0;n<6;n++){float coordinate=row.getAsJsonArray("profile").get(n).getAsFloat();if(!Float.isFinite(coordinate))throw new IllegalArgumentException("actor_map_profile_nonfinite");args[n]=coordinate;}profile.invoke(value,args);
            }
            NativeActorCustomBlocks.prepare(runtime,path.resolveSibling("native-custom-blocks.json"),loader,unsafe,allocate,blocks);
        }
        NativeActorMapWorld create(World.Vec origin)throws Exception{return new NativeActorMapWorld(this,origin);}
        Object allocate(Class<?> type)throws Exception{return allocate.invoke(unsafe,type);}
        Object box(SceneCollision.Box b)throws Exception{return box.invoke(null,b.x(),b.y(),b.z(),b.xx(),b.yy(),b.zz());}
        SceneCollision.Box bounds(Object value)throws Exception{Object b=bounds.newInstance(value);return new SceneCollision.Box(coordinates[0].getDouble(b),coordinates[1].getDouble(b),coordinates[2].getDouble(b),coordinates[3].getDouble(b),coordinates[4].getDouble(b),coordinates[5].getDouble(b));}
    }
    private final Factory factory;private final Object world,solidTree,emptyTree;private final NativeActorSceneFeedback feedback;
    private NativeActorCarousel.Transaction carouselTransaction;
    private NativeActorMines.Transaction mineTransaction;
    void close(NativeActorDispatcher.Session session)throws Exception{factory.carousels.close(session);}
    private final int originX,originZ;private ActorWorldAccess source;private RuntimeException failure;private Thread owner;
    private final double[] profileValues;
    private final Map<Long,Object> chunks=new LinkedHashMap<>();private final Map<World.Block,Cell> cells=new HashMap<>();private final IdentityHashMap<TilePayload.Tile,Object> tiles=new IdentityHashMap<>();private final Map<ActorBody,Object> bodies=new HashMap<>();
    private final Map<Object,Object> queryCells=new IdentityHashMap<>();
    private record Cell(int packed,TilePayload.Tile tile){}
    private interface Read<T>{T get()throws Exception;}
    private <T>T query(Read<T> operation){
        if(source==null)throw new IllegalStateException("actor_map_scope_closed");
        try{return operation.get();}catch(InvocationTargetException e){return failed(e.getCause());}catch(InterruptedException e){Thread.currentThread().interrupt();return failed(new ActorScene.Deferred());}catch(Exception e){return failed(e);}
    }
    private <T>T failed(Throwable cause){RuntimeException error=cause instanceof RuntimeException r?r:new IllegalStateException("native_actor_map",cause);if(failure==null)failure=error;throw error;}
    NativeActorMapWorld(Factory f,World.Vec origin)throws Exception {
        factory=f;profileValues=new double[f.prototypes.length*6];if(origin==null||origin.y()!=0||origin.x()%16!=0||origin.z()%16!=0||Math.abs(origin.x())>30_000_000||Math.abs(origin.z())>30_000_000)throw new IllegalArgumentException("actor_map_origin");originX=(int)origin.x();originZ=(int)origin.z();feedback=new NativeActorSceneFeedback(f.feedback,originX,originZ);
        world=f.allocate(f.worldType);Object forest=f.allocate(f.forestType);solidTree=f.allocate(f.treeType);emptyTree=f.allocate(f.treeType);
        f.worldType.getField("contactRandom").set(world,new NativeActorRandom(0));
        f.treeType.getField("query").set(emptyTree,(BiConsumer<Object,Collection<Object>>)(a,out)->query(()->{source.use();return null;}));
        f.treeType.getField("query").set(solidTree,(BiConsumer<Object,Collection<Object>>)(a,out)->query(()->{
            SceneCollision.Box local;if(a instanceof double[] p){try{local=new SceneCollision.Box(p[0],p[1],p[2],p[3],p[4],p[5]);}catch(IllegalArgumentException error){throw new IllegalArgumentException("native_actor_scene_query:"+Arrays.toString(p),error);}}else local=f.bounds(a);
            source.use();out.addAll(f.motion.dispatcherCells(source.scene(global(local)),originX,0,originZ,queryCells));out.addAll(f.motion.dispatcherCells(source.ladders(global(local)),originX,0,originZ,queryCells));return null;
        }));
        Field objectRegistry=f.forestType.getSuperclass().getDeclaredField("import");objectRegistry.setAccessible(true);
        objectRegistry.set(forest,new AbstractMap<UUID,Object>(){
            public Set<Entry<UUID,Object>> entrySet(){return Set.of();}
            public Object get(Object key){if(!(key instanceof UUID id))return null;return query(()->{source.use();Object prepared=source.object(id);return prepared==null?null:factory.motion.localMapObject(prepared,originX,originZ);});}
        });
        f.forestType.getField("tree").set(forest,solidTree);for(Field field:f.forestType.getSuperclass().getDeclaredFields())if(field.getType().getName().equals("1581944406")){field.setAccessible(true);field.set(forest,emptyTree);}f.worldType.getField("forest").set(world,forest);
        Object feedbackTree=f.allocate(f.treeType);Field feedbackTreeField=f.forestType.getSuperclass().getDeclaredField("super");feedbackTreeField.setAccessible(true);feedbackTreeField.set(forest,feedbackTree);
        f.treeType.getField("query").set(feedbackTree,(BiConsumer<Object,Collection<Object>>)(a,out)->query(()->{
            SceneCollision.Box local=a instanceof double[] p?new SceneCollision.Box(p[0],p[1],p[2],p[3],p[4],p[5]):f.bounds(a);
            source.use();out.addAll(feedback.query(source.feedback(global(local))));return null;
        }));
        f.worldType.getField("ids").set(world,(ToIntFunction<int[]>)p->query(()->cell(p[0],p[1],p[2]).packed()>>>4));
        f.worldType.getField("metas").set(world,(ToIntFunction<int[]>)p->query(()->cell(p[0],p[1],p[2]).packed()&15));
        f.worldType.getField("tiles").set(world,(Function<int[],Object>)p->query(()->tile(p[0],p[1],p[2])));
        f.worldType.getField("resident").set(world,(ToIntFunction<int[]>)p->query(()->resident(p[0],p[1])?1:0));
        // 490238687 encodes Z after subtracting Integer.MIN_VALUE.
        // The native try(chunk) means suspended/blacklisted, not ready. Known resident chunks are not suspended.
        f.worldType.getField("suspended").set(world,(LongPredicate)key->query(()->{resident((int)(key>>32),(int)key+Integer.MIN_VALUE);return false;}));
        f.worldType.getField("chunks").set(world,(Function<int[],Object>)p->query(()->chunk(p[0],p[1])));
        f.worldType.getField("bodies").set(world,(Function<Object,List<Object>>)a->query(()->{
            source.use();List<Object> found=new ArrayList<>();for(var body:source.bodies(global(f.bounds(a)))){if(body.id().equals(source.actorId()))continue;Object value=bodies.get(body);if(value==null){value=f.bodies.prepare(body,f.box(local(body.bounds())));bodies.put(body,value);}found.add(value);}return found;
        }));
        f.worldType.getField("query").set(world,(BiFunction<Object,Object,List<Object>>)(actor,area)->query(()->{source.use();List<Object> values=new ArrayList<>();f.collect.invoke(world,actor,area,values);f.bodyCollect.invoke(world,actor,area,values);return values;}));
        f.worldType.getField("faces").set(world,(ToIntFunction<int[]>)p->query(()->{
            long x=p[0]+(long)originX,z=p[2]+(long)originZ;if(x<-30_000_000||x>=30_000_000||z<-30_000_000||z>=30_000_000)return p[4];
            int id=cell(p[0],p[1],p[2]).packed()>>>4;return id==0?0:(Boolean)f.face.invoke(f.blocks[id],world,p[0],p[1],p[2],f.sides[p[3]])?1:0;
        }));
        Class<?> mode=Class.forName("969130755",true,f.loader);Object context=f.allocate(mode);Field modeWorld=mode.getField("instanceof");modeWorld.setAccessible(true);modeWorld.set(context,world);f.worldBase.getMethod("assert",mode).invoke(world,context);
    }
    Object nativeWorld(){return world;}
    ActorMineTrigger prepareMine(ActorMineTrigger trigger,World.Vec position){return source.prepareMine(trigger,position);}
    void contactMinePlayer(ActorMinePlayer player,ActorWorldAccess access)throws Exception{
        if(source!=null)throw new IllegalStateException("actor_map_nested_scope");
        mineTransaction=factory.mines.transaction();saveProfiles();begin(access);
        try{
            var p=local(player.position());var body=factory.mines.contactBounds(p,player);Object point=null;
            for(int x=(int)Math.floor(body.x()+1e-7);x<=(int)Math.floor(body.xx()-1e-7);x++)for(int y=Math.max(0,(int)Math.floor(body.y()+1e-7));y<=Math.min(255,(int)Math.floor(body.yy()-1e-7));y++)for(int z=(int)Math.floor(body.z()+1e-7);z<=(int)Math.floor(body.zz()-1e-7);z++)if(cell(x,y,z).packed()>>>4==4006){
                if(point==null)point=factory.mines.contactBody(p,player);
                factory.contact.invoke(factory.blocks[4006],world,x,y,z,point);
            }
            query(()->{source.use();return null;});check();mineTransaction.finish(player.position());
        }catch(Exception failure){mineTransaction.rollback();restoreProfiles();if(this.failure!=null)throw this.failure;throw failure;}
        finally{mineTransaction=null;end();}
    }
    Map<ActorContacts.Key,ActorContacts.Obstacle> contacts(NativeActorDispatcher.Session session)throws Exception{return feedback.active(session.actor());}
    void restoreContacts(NativeActorDispatcher.Session session,Map<ActorContacts.Key,ActorContacts.Obstacle> saved)throws Exception{if(source!=null)throw new IllegalStateException("actor_feedback_restore_scope");feedback.restore(session.actor(),saved);}
    void seedMigratedContacts(NativeActorDispatcher.Session session)throws Exception{if(source!=null)throw new IllegalStateException("actor_feedback_restore_scope");feedback.seedMigratedConstraint(session.actor());}
    void finishBootstrap(){if(source!=null||owner!=null&&owner!=Thread.currentThread())throw new IllegalStateException("actor_map_bootstrap_owner");owner=null;}
    NativeActorDispatcher.Session create(NativeActorComponents components,World.Vec globalSpawn)throws Exception{return components.create(local(globalSpawn),world);}
    ActorControllerState capture(NativeActorDispatcher.Session session,long incarnation)throws Exception {
        if(source!=null||session.nativeWorld()!=world)throw new IllegalStateException("actor_map_snapshot_scope");
        return ActorControllerState.of(session.identity(),incarnation,originX,originZ,session.durableBytes()).withCarousels(factory.carousels.capture(session));
    }
    NativeActorDispatcher.Session restore(NativeActorComponents components,ActorControllerState snapshot)throws Exception {
        if(source!=null||snapshot.originX()!=originX||snapshot.originZ()!=originZ)throw new IllegalArgumentException("actor_map_snapshot_origin");
        var session=components.create(new World.Vec(0,1,0),world,UUID.fromString(snapshot.id()));session.restoreDurable(snapshot.bytes());factory.carousels.restore(session,originX,originZ,snapshot.carousels());return session;
    }
    World.Vec origin(){return new World.Vec(originX,0,originZ);}
    NativeActorDispatcher.Frame globalFrame(NativeActorDispatcher.Session session)throws Exception{if(session.nativeWorld()!=world)throw new IllegalArgumentException("actor_map_session_host");return global(session.frame());}
    World.Vec local(World.Vec p){return new World.Vec(p.x()-originX,p.y(),p.z()-originZ);}
    private SceneCollision.Box global(SceneCollision.Box b){return shift(b,originX,originZ);}
    private SceneCollision.Box local(SceneCollision.Box b){return shift(b,-originX,-originZ);}
    private static SceneCollision.Box shift(SceneCollision.Box b,int x,int z){return new SceneCollision.Box(b.x()+x,b.y(),b.z()+z,b.xx()+x,b.yy(),b.zz()+z);}
    private void begin(ActorWorldAccess access){if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("actor_map_owner");if(source!=null||factory.activeScope.get()!=null)throw new IllegalStateException("actor_map_nested_scope");source=Objects.requireNonNull(access);failure=null;factory.activeScope.set(this);}
    NativeActorDispatcher.Frame step(NativeActorDispatcher.Session session,NativeActorDispatcher.Input intent,float seconds,ActorWorldAccess access)throws Exception {
        return transaction(session,access,()->{
            session.advanceBuffs(access.simulationTick()<0?session.durationClock(seconds):access.simulationTick());
            // A deleted/disabled/moved source must not leave the saved native plane hanging in air.
            // Feed the original ladder dismount action; pose, cooldown and fall remain native.
            var applied=!intent.crouchHeld()&&(!session.ladderSourcePresent()||!session.climbSourcePresent())?new NativeActorDispatcher.Input(intent.yaw(),intent.pitch(),intent.heading(),intent.gait(),intent.jump(),true,intent.proneToggle(),intent.zipline(),intent.condition(),intent.seat()):intent;
            UUID attached=session.ziplineIdentity();
            if(attached!=null&&source.object(attached)==null)applied=new NativeActorDispatcher.Input(applied.yaw(),applied.pitch(),applied.heading(),applied.gait(),applied.jump(),applied.crouchHeld(),applied.proneToggle(),new ActorControllerEngine.Zipline(attached,true),applied.condition(),applied.seat());
            var seated=session.traversal().seat();var request=applied.seat();Object prepared=seated==null?null:source.object(seated.object());
            if(seated!=null&&request==null){boolean present=false;if(prepared!=null)for(var unit:factory.motion.seatGuides(prepared))if(unit.index()==seated.index()&&unit.available()&&Math.abs(unit.point().x()-originX-seated.point().x())<1e-6&&Math.abs(unit.point().y()-seated.point().y())<1e-6&&Math.abs(unit.point().z()-originZ-seated.point().z())<1e-6&&Math.abs(unit.facing()-seated.facing())<1e-5){present=true;break;}if(!present)request=new ActorControllerEngine.Seat(seated.object(),seated.index(),true);}
            Object action=request==null?null:factory.motion.seatAction(request.leave()?null:source.object(request.object()),request,seated,session.frame().position(),world,originX,originZ,source);
            factory.carousels.step(session,originX,originZ,source,carouselTransaction);
            session.step(applied,seconds,action);
            if(source.livingTick())session.livingStep(seconds);
            return global(session.frame());
        });
    }
    void prepare(NativeActorDispatcher.Session session,ActorWorldAccess access)throws Exception{transaction(session,access,()->{session.prepareLegacy();return null;});}
    private <T> T transaction(NativeActorDispatcher.Session session,ActorWorldAccess access,java.util.concurrent.Callable<T> operation)throws Exception {
        if(source!=null)throw new IllegalStateException("actor_map_nested_scope");if(session.nativeWorld()!=world)throw new IllegalArgumentException("actor_map_session_host");
        var checkpoint=session.checkpoint();var feedbackCheckpoint=feedback.checkpoint();carouselTransaction=factory.carousels.transaction();mineTransaction=factory.mines.transaction();saveProfiles();begin(access);
        try{var result=operation.call();query(()->{source.use();return null;});check();feedback.finish(session.actor());carouselTransaction.finish();mineTransaction.finish(session);return result;}
        catch(Exception error){try{checkpoint.restore();feedbackCheckpoint.restore();carouselTransaction.rollback();mineTransaction.rollback();}finally{restoreProfiles();}if(failure!=null)throw failure;throw error;}
        finally{carouselTransaction=null;mineTransaction=null;factory.feedback.clearScratch();end();}
    }
    private NativeActorDispatcher.Frame global(NativeActorDispatcher.Frame frame){
        if(originX==0&&originZ==0)return frame;World.Vec p=frame.position();
        return new NativeActorDispatcher.Frame(new World.Vec(p.x()+originX,p.y(),p.z()+originZ),frame.velocity(),frame.pose(),frame.previous(),frame.gait(),frame.grounded(),frame.water(),frame.airborneTime(),frame.time(),frame.since(),frame.energy(),frame.body().stream().map(this::global).toList());
    }
    private void check(){if(failure!=null)throw failure;}
    private void end(){factory.activeScope.remove();source=null;cells.clear();tiles.clear();bodies.clear();queryCells.clear();failure=null;}
    private void saveProfiles()throws Exception{for(int i=0;i<factory.prototypes.length;i++)for(int n=0;n<6;n++)profileValues[i*6+n]=factory.profileBounds[n].getDouble(factory.prototypes[i]);}
    private void restoreProfiles()throws Exception{for(int i=0;i<factory.prototypes.length;i++)for(int n=0;n<6;n++)factory.profileBounds[n].setDouble(factory.prototypes[i],profileValues[i*6+n]);}
    private boolean resident(int x,int z){source.use();int globalX=x+originX/16,globalZ=z+originZ/16;if(!source.resident(globalX,globalZ))throw new ActorScene.Deferred();return true;}
    private Cell cell(int x,int y,int z)throws InterruptedException {
        if(y<0||y>255||x+(long)originX<-30_000_000||x+(long)originX>=30_000_000||z+(long)originZ<-30_000_000||z+(long)originZ>=30_000_000)return new Cell(0,null);
        World.Block key=new World.Block(x+originX,y,z+originZ);Cell value=cells.get(key);if(value!=null)return value;
        resident(Math.floorDiv(x,16),Math.floorDiv(z,16));source.use();int packed=source.voxel(key),id=packed>>>4;
        if(packed<0||id>=factory.blocks.length||id!=0&&factory.blocks[id]==null)throw new IllegalStateException("native_actor_block_unbound:"+id);
        if(id!=0&&!factory.callbacks[id])throw new IllegalStateException("native_actor_callback_unbound:"+id);
        value=new Cell(packed,source.tile(key));cells.put(key,value);return value;
    }
    private Object chunk(int x,int z)throws Exception {
        resident(x,z);long key=((long)x<<32)^(z&0xffffffffL);Object value=chunks.get(key);if(value!=null)return value;
        value=factory.allocate(factory.chunkType);factory.chunkType.getField("ids").set(value,(ToIntFunction<int[]>)p->query(()->cell((x<<4)+p[0],p[1],(z<<4)+p[2]).packed()>>>4));factory.chunkType.getField("metas").set(value,(ToIntFunction<int[]>)p->query(()->cell((x<<4)+p[0],p[1],(z<<4)+p[2]).packed()&15));
        if(chunks.size()>=128)chunks.remove(chunks.keySet().iterator().next());chunks.put(key,value);return value;
    }
    private Object tile(int x,int y,int z)throws Exception {
        var cell=cell(x,y,z);var data=cell.tile();
        // Preserve bare no-tile fixtures. Real world factories supply the original mine tile;
        // a Carpenter payload mislabeled as a mine remains invalid.
        if(cell.packed()>>>4==4006){
            if(data!=null)throw new IllegalStateException("native_actor_contact_effect_unbound:4006");
            var p=new World.Block(x+originX,y,z+originZ);
            return source.mineTile(p)?mineTransaction.tile(p,x,y,z,world):null;
        }
        if(data==null)return null;
        Object result=tiles.get(data);if(result!=null)return result;
        result=(data.type()==1?factory.builder:factory.carpenter).newInstance();factory.tileData.setInt(result,data.shapeData());factory.tileCover.setShort(result,(short)data.centralCoverId());if(data.type()==1)factory.tileExtension.setInt(result,data.shapeExtension());
        Class<?> base=Class.forName("1123841866",true,factory.loader);base.getField("else").setInt(result,x);base.getField("float").setInt(result,y);base.getField("super").setInt(result,z);
        for(Field field:base.getFields())if(!Modifier.isStatic(field.getModifiers())&&field.getType()==factory.worldBase){field.setAccessible(true);field.set(result,world);}tiles.put(data,result);return result;
    }
}
