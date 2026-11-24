package szserver.client;

import szserver.world.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Original NPC entity graph and native configuration. Physics, world registration and transport are host responsibilities. */
public final class NativeCreatureModel implements AutoCloseable {
    public enum Family { HUMAN, SLIME, FORGE_BOSS }
    public record Frame(UUID id,Family family,World.Vec position,SceneCollision.Box bounds,ActorBody collision,boolean inactive,boolean suppressed) {}
    public record Snapshot(int version,UUID id,Family family,byte[] nativeEntity,boolean suppressed) {
        public Snapshot{if(version!=1)throw new IllegalArgumentException("creature_snapshot_version");Objects.requireNonNull(id);Objects.requireNonNull(family);nativeEntity=nativeEntity.clone();SceneNbt.readTile(nativeEntity);}
        @Override public byte[] nativeEntity(){return nativeEntity.clone();}
    }
    public record Spawn(int type,byte[] data){public Spawn{if(type<1||type>127||data.length>1024*1024)throw new IllegalArgumentException("creature_spawn");data=data.clone();}@Override public byte[] data(){return data.clone();}}
    private static final Map<Family,String> TYPES=Map.of(Family.HUMAN,"1848416318",Family.SLIME,"1863161907",Family.FORGE_BOSS,"1271645164");
    private final ClientSceneCodec.Loader loader;
    private final NativeSceneClock clock;
    private final Class<?> entity,creature,compound;
    private final Object world;
    private final Method position,identity,collider,bounds,readNbt,writeNbt;
    private final Constructor<?> box;
    private final Field[] coordinates=new Field[6];
    private final Map<UUID,Session> sessions=new LinkedHashMap<>();
    private Thread owner=Thread.currentThread();
    private boolean closed;
    private long clockMillis=System.currentTimeMillis(),clockTick;

    public NativeCreatureModel(Path root,Path resources)throws Exception {
        SceneCollision.worker();
        Path runtime=root.getParent().resolve("runtime");var manifest=JsonParser.parseString(RuntimeFiles.readString(root.resolve("fixtures/native-creature-model.json"))).getAsJsonObject();
        if(manifest.get("version").getAsInt()!=1)throw new IOException("creature_release_version");
        for(var e:manifest.getAsJsonObject("classesSha256").entrySet()){
            if(!e.getKey().matches("[0-9]+"))throw new IOException("creature_release_class");
            byte[] raw=RuntimeFiles.read(runtime.resolve("classes/"+e.getKey()+".class"));
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(e.getValue().getAsString()))throw new IOException("creature_release_changed:"+e.getKey());
        }
        var urls=new ArrayList<URL>();for(String directory:List.of("classes","bootstrap","libs"))urls.add(RuntimeFiles.location(runtime.resolve(directory)).toUri().toURL());
        try(var files=Files.list(runtime.resolve("dependencies"))){for(var f:files.filter(p->p.toString().endsWith(".jar")).toList())urls.add(f.toUri().toURL());}urls.add(resources.toUri().toURL());
        loader=new ClientSceneCodec.Loader(urls.toArray(URL[]::new));
        try{
            clock=new NativeSceneClock(loader,runtime,root.resolve("fixtures/native-scene-clock-context.json"));enter();
            try{
                type("600030218").getField("volatile").set(null,type("1974661934").getField("RU").get(null));
                type("1993742576").getMethod("interface").invoke(null);type("964863275");type("1455189279").getEnumConstants();
                type("423259911").getMethod("assert").invoke(null);
                NativeCreatureServerFactory.install(runtime,loader::define);
                type("220710426").getMethod("throw",type("1553900928")).invoke(null,type("1553900928").getField("SERVER").get(null));
                items(root);
                Class<?> worldType=loader.define("szserver.bridge.DispatcherWorld",NativeActorBridge.dispatcherWorld());
                Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
                world=unsafeType.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),worldType);
                Class<?> base=type("1500514786");set(base,"volatile",world,type("740754547").getMethod("instanceof",int.class).invoke(null,0));
                set(base,"d",world,type("249504617").getConstructor(String.class).newInstance("server-creature-model"));
                set(type("1652718504"),"enum",world,type("1722186864").getField("extends").get(null));
                entity=type("1217489680");creature=type("1422788721");compound=type("98008865");
                position=entity.getMethod("throw",double.class,double.class,double.class);identity=entity.getMethod("float",UUID.class);
                collider=method(creature,"enum",type("242639986"));bounds=method(creature,"static",type("242639986"));
                box=type("1994198637").getConstructor(type("242639986"));String[] names={"return","void","long","class","final","default"};for(int n=0;n<6;n++)coordinates[n]=box.getDeclaringClass().getField(names[n]);
                readNbt=type("1464336358").getMethod("int",DataInput.class);writeNbt=type("1464336358").getMethod("char",compound,DataOutput.class);
                // Constructors and optional family components initialize away from a gameplay owner.
                for(Family family:Family.values()){var warm=create(UUID.randomUUID(),family,new World.Vec(0,1,0));warm.close();}
            }finally{clock.exit();}
        }catch(Exception|Error failure){loader.close();throw failure;}
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private static void set(Class<?> type,String name,Object target,Object value)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Method method(Class<?> type,String name,Class<?> result)throws Exception{return Arrays.stream(type.getMethods()).filter(m->m.getName().equals(name)&&m.getReturnType()==result&&m.getParameterCount()==0).findFirst().orElseThrow();}
    private void check(){if(closed)throw new IllegalStateException("creature_model_closed");if(owner==null)owner=Thread.currentThread();if(owner!=Thread.currentThread())throw new IllegalStateException("creature_model_owner");}
    /** A serialized ownership handoff, only while no model operation is running. */
    public void releaseOwner(){check();owner=null;}
    public void worldClock(long millis,long tick){check();if(millis<0||tick<0||tick>Long.MAX_VALUE/20)throw new IllegalArgumentException("creature_clock");clockMillis=millis;clockTick=tick;}
    private void enter()throws Exception{clock.enter(clockMillis,clockTick*.05);}
    public List<ActorBody> collisions()throws Exception{check();var result=new ArrayList<ActorBody>();for(var session:sessions.values()){var body=session.frame().collision();if(body!=null)result.add(body);}return List.copyOf(result);}
    public Session create(UUID id,Family family,World.Vec at)throws Exception {
        check();Objects.requireNonNull(id);Objects.requireNonNull(family);valid(at);if(sessions.containsKey(id)||sessions.size()>=512)throw new IllegalArgumentException("creature_identity_or_capacity");
        enter();try{
            Object actor=type(TYPES.get(family)).getConstructor(type("1500514786")).newInstance(world);identity.invoke(actor,id);position.invoke(actor,at.x(),at.y(),at.z());
            Object display=creature.getField("double").get(actor);display.getClass().getField("do").set(display,type("1754310268").getField(switch(family){case HUMAN->"HumanMale";case SLIME->"Slime";case FORGE_BOSS->"ForgeBoss";}).get(null));method(creature,"g",void.class).invoke(actor);
            // A newly authored neutral NPC has no dialogue/reputation overrides yet.
            // These are real native components; the server owns this initial policy.
            Object relation=type("1296044766").getConstructor().newInstance(),relations=type("1145974218").getConstructor().newInstance();
            relations.getClass().getField("super").set(relations,new HashMap<>());relation.getClass().getField("new").set(relation,relations);
            relation.getClass().getField("throws").set(relation,"");relation.getClass().getField("case").set(relation,List.of());creature.getField("enum").set(actor,relation);
            Object logic=creature.getField("native").get(actor);logic.getClass().getField("hostTick").set(logic,(Runnable)()->{});logic.getClass().getField("hostDispose").set(logic,(Runnable)()->{});
            Session session=new Session(id,family,actor,logic);sessions.put(id,session);return session;
        }finally{clock.exit();}
    }
    public Session restore(Snapshot saved)throws Exception {
        check();var session=create(saved.id(),saved.family(),new World.Vec(0,0,0));
        try{
            var in=new DataInputStream(new ByteArrayInputStream(saved.nativeEntity()));Object data=readNbt.invoke(null,in);if(in.available()!=0)throw new IOException("creature_snapshot_trailing");
            enter();try{
                type("820497227").getMethod("throw",compound).invoke(session.actor,data);
                if(!saved.id().equals(method(entity,"interface",UUID.class).invoke(session.actor)))throw new IOException("creature_snapshot_identity");
                Object flag=creature.getField("strictfp").get(session.actor);flag.getClass().getMethod("assert",Object.class).invoke(flag,saved.suppressed());
            }finally{clock.exit();}
            session.frame();return session;
        }catch(Exception|Error failure){try{session.close();}catch(Exception close){failure.addSuppressed(close);}throw failure;}
    }
    private static void valid(World.Vec at){if(at==null||!Double.isFinite(at.x())||!Double.isFinite(at.y())||!Double.isFinite(at.z())||Math.abs(at.x())>MapBounds.HORIZONTAL||Math.abs(at.z())>MapBounds.HORIZONTAL||at.y()<-64||at.y()>4096)throw new IllegalArgumentException("creature_position");}
    private SceneCollision.Box box(Object nativeBox)throws Exception{Object value=box.newInstance(nativeBox);return new SceneCollision.Box(coordinates[0].getDouble(value),coordinates[1].getDouble(value),coordinates[2].getDouble(value),coordinates[3].getDouble(value),coordinates[4].getDouble(value),coordinates[5].getDouble(value));}
    public final class Session implements AutoCloseable {
        private final UUID id;private final Family family;private final Object actor,logic;private boolean disposed;
        private Session(UUID id,Family family,Object actor,Object logic){this.id=id;this.family=family;this.actor=actor;this.logic=logic;}
        private void active(){check();if(disposed||sessions.get(id)!=this)throw new IllegalStateException("creature_session_closed");}
        /** Authoring/export worker only. The original display writer carries these settings to clients. */
        public void appearance(String name,String skin,String animations)throws Exception {
            active();SceneCollision.worker();
            if(name==null||name.isBlank()||name.length()>64||skin==null||!skin.matches("[a-zA-Z0-9_]{1,64}")||animations==null||!animations.matches("[a-zA-Z0-9_]{1,64}"))throw new IllegalArgumentException("creature_appearance");
            Object display=creature.getField("double").get(actor);Class<?> d=display.getClass();
            d.getField("assert").set(display,skin);d.getField("final").set(display,animations);
            d.getField("float").set(display,type("832843619").getMethod("float",String.class).invoke(null,name));
        }
        public Frame frame()throws Exception {
            active();var body=box(bounds.invoke(actor));Object shape=collider.invoke(actor);boolean inactive=(boolean)creature.getMethod("j").invoke(actor),suppressed=(boolean)creature.getMethod("h").invoke(actor);
            var at=new World.Vec((body.x()+body.xx())*.5,body.y(),(body.z()+body.zz())*.5);
            return new Frame(id,family,at,body,shape==null?null:new ActorBody(id.toString(),ActorBody.Kind.CREATURE,box(shape),true,inactive,suppressed),inactive,suppressed);
        }
        /** Native component reader; prepare configurations before a gameplay frame. */
        public void configuration(byte[] nbt)throws Exception {
            active();SceneCollision.worker();SceneNbt.readTile(nbt);byte[] before=configuration();
            try{configurationUnchecked(nbt);frame();}catch(Exception|Error failure){try{configurationUnchecked(before);}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
        }
        private void configurationUnchecked(byte[] nbt)throws Exception {
            var in=new DataInputStream(new ByteArrayInputStream(nbt));Object data=readNbt.invoke(null,in);if(in.available()!=0)throw new IOException("creature_configuration_trailing");
            enter();try{for(String field:List.of("double","h","float","final","case")){
                Object component=creature.getField(field).get(actor);String reader=switch(field){case "float"->"throws";case "case"->"public";default->"boolean";};component.getClass().getMethod(reader,compound).invoke(component,data);
            }}finally{clock.exit();}
        }
        /** Original component writers retain the typed values used by native readers. */
        public byte[] configuration()throws Exception {
            active();Object data=compound.getConstructor().newInstance();enter();try{
                for(String field:List.of("double","h","float","final","case")){Object component=creature.getField(field).get(actor);component.getClass().getMethod(field.equals("case")?"synchronized":"while",compound).invoke(component,data);}
                var bytes=new ByteArrayOutputStream();writeNbt.invoke(null,data,new DataOutputStream(bytes));byte[] result=bytes.toByteArray();SceneNbt.readTile(result);return result;
            }finally{clock.exit();}
        }
        public Snapshot capture()throws Exception {
            active();Object data=compound.getConstructor().newInstance();enter();try{
                type("820497227").getMethod("const",compound).invoke(actor,data);
                var out=new ByteArrayOutputStream();writeNbt.invoke(null,data,new DataOutputStream(out));return new Snapshot(1,id,family,out.toByteArray(),frame().suppressed());
            }finally{clock.exit();}
        }
        /** Original 736740078 map followed by original 936207013 writer, ready for a generic entity spawn. */
        public Spawn spawn()throws Exception {
            active();enter();try{
                Object profile=type("936207013").getConstructor().newInstance(),settings=creature.getField("h").get(actor),display=creature.getField("double").get(actor),role=creature.getField("final").get(actor);
                Class<?> p=profile.getClass();p.getField("switch").set(profile,display);p.getField("int").set(profile,id);
                p.getField("native").setInt(profile,settings.getClass().getField("d").getInt(settings));p.getField("return").setInt(profile,settings.getClass().getField("g").getInt(settings));
                Object roleKind=role.getClass().getField("static").get(role);p.getField("synchronized").set(profile,roleKind.getClass().getField("do").get(roleKind));
                p.getField("continue").setBoolean(profile,settings.getClass().getField("new").getBoolean(settings));p.getField("super").setBoolean(profile,settings.getClass().getField("i").getBoolean(settings));
                Object faction=role.getClass().getField("continue").get(role);Object factionName=faction.getClass().getField("abstract").get(faction);p.getField("boolean").set(profile,factionName.getClass().getMethod("return").invoke(factionName));
                p.getField("volatile").set(profile,creature.getField("i").get(actor));p.getField("static").set(profile,creature.getField("boolean").get(actor));
                p.getField("float").set(profile,creature.getField("continue").get(actor));p.getField("extends").set(profile,creature.getField("enum").get(actor));
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
                Field attributes=type("736740078").getDeclaredField("abstract");attributes.setAccessible(true);Object context=attributes.get(actor);
                Object value=context.getClass().getMethod("const",int.class,int.class).invoke(context,-1711588195,-679134165);Map<?,?> state=(Map<?,?>)value.getClass().getField("return").get(value);
                out.writeInt(state==null?0:state.size());if(state!=null)for(var e:state.entrySet()){out.writeUTF((String)e.getKey());e.getValue().getClass().getMethod("catch",DataOutput.class).invoke(e.getValue(),out);}
                p.getMethod("catch",DataOutput.class).invoke(profile,out);
                int type=Byte.toUnsignedInt((byte)NativeCreatureModel.this.type("423259911").getMethod("abstract",Class.class).invoke(null,actor.getClass()));return new Spawn(type,bytes.toByteArray());
            }finally{clock.exit();}
        }
        public void suppressed(boolean value)throws Exception{active();Object flag=creature.getField("strictfp").get(actor);flag.getClass().getMethod("assert",Object.class).invoke(flag,value);}
        public void position(World.Vec at)throws Exception {active();valid(at);position.invoke(actor,at.x(),at.y(),at.z());}
        public void hooks(Runnable tick,Runnable dispose)throws Exception{active();logic.getClass().getField("hostTick").set(logic,Objects.requireNonNull(tick));logic.getClass().getField("hostDispose").set(logic,Objects.requireNonNull(dispose));}
        public void logicTick()throws Exception{active();enter();try{logic.getClass().getMethod("enum").invoke(logic);}finally{clock.exit();}}
        public void close()throws Exception{if(disposed)return;active();enter();try{method(creature,"throw",void.class).invoke(actor);}finally{disposed=true;sessions.remove(id);clock.exit();}}
    }
    private void items(Path root)throws Exception {
        var selected=szserver.inventory.CatalogDefinitions.read(root.resolve("fixtures/item-definitions.json"));
        Class<?> parser=type("com.google.gson.JsonParser"),json=type("com.google.gson.JsonObject"),descriptor=type("1422486348");
        for(int id:new int[]{14987,21059,21085}){Object parsed=parser.getMethod("parseString",String.class).invoke(null,selected.getAsJsonObject(Integer.toString(id)).toString());parsed=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
            Object value=descriptor.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("creature_base_"+id,parsed,id,null,null,null);type(id==14987?"942610364":"124763289").getConstructor(descriptor).newInstance(value);
        }
    }
    public void close()throws Exception {
        if(closed)return;check();Exception failure=null;
        try{for(var session:List.copyOf(sessions.values()))try{session.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        finally{closed=true;try{loader.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        if(failure!=null)throw failure;
    }
}
