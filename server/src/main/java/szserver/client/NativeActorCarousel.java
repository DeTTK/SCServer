package szserver.client;

import szserver.world.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** One original carousel instance per world, shared by NPC hosts. All changes join the actor transaction. */
final class NativeActorCarousel {
    private final ClassLoader loader;private final NativeActorMotion motion;
    private final Class<?> objectType;private final Field behavior,world,matrix,target,phase,cooldown;
    private final Method enter,advance,leave,contains,translate;private final Constructor<?> matrixCopy;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private static final class Entry {
        final UUID id;final ActorCarouselState source;final Object object,zone;String owner;
        Entry(UUID id,ActorCarouselState source,Object object,Object zone){this.id=id;this.source=source;this.object=object;this.zone=zone;}
    }
    NativeActorCarousel(ClassLoader loader,NativeActorMotion motion,Path resources)throws Exception {
        this.loader=loader;this.motion=motion;prepareSettings(loader,resources);
        objectType=type("1886271463");Class<?> original=type("1952056351"),entity=type("1217489680");
        behavior=field(objectType,"byte");world=field(objectType,"q");matrix=field(objectType,"m");target=original.getField("goto");phase=original.getField("else");cooldown=original.getField("char");
        enter=original.getMethod("do",entity);advance=original.getMethod("if");leave=original.getMethod("goto",entity);contains=original.getMethod("protected",entity);
        Class<?> transform=type("447252483");translate=transform.getMethod("synchronized",float.class,float.class,float.class);matrixCopy=transform.getConstructor(type("1303032651"));
    }
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void prepareSettings(ClassLoader loader,Path resources)throws Exception {
        Class<?> settings=Class.forName("132688950",true,loader);if(settings.getField("new").get(null)!=null)return;
        Path path=resources.resolve("assets/anomalies/anomaly_config.json");if(Files.size(path)>65536)throw new IllegalArgumentException("actor_anomaly_settings_size");
        var rows=JsonParser.parseString(Files.readString(path)).getAsJsonArray();if(rows.size()!=1)throw new IllegalArgumentException("actor_anomaly_settings_rows");
        Class<?> parser=Class.forName("com.google.gson.JsonParser",true,loader),json=Class.forName("com.google.gson.JsonObject",true,loader),input=Class.forName("690713104",true,loader);
        Object parsed=parser.getMethod("parseString",String.class).invoke(null,rows.get(0).toString());parsed=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        Object nativeSettings=settings.getConstructor().newInstance();settings.getMethod("enum",input).invoke(nativeSettings,input.getConstructor(String.class,json).newInstance("server_anomaly_settings",parsed));
        float damage=settings.getField("int").getFloat(nativeSettings);if(!Float.isFinite(damage)||damage<0||damage>100000)throw new IllegalArgumentException("actor_carousel_damage");
    }
    final class Transaction {
        private final List<Undo> undo=new ArrayList<>();private final Set<Entry> touched=Collections.newSetFromMap(new IdentityHashMap<>());
        void touch(Entry e)throws Exception {
            if(!touched.add(e))return;
            String owner=e.owner;Object captured=target.get(e.zone),previousWorld=world.get(e.object),transform=matrixCopy.newInstance(matrix.get(e.object));int time=phase.getInt(e.zone),clock=cooldown.getInt(e.zone);
            undo.add(()->{e.owner=owner;target.set(e.zone,captured);phase.setInt(e.zone,time);cooldown.setInt(e.zone,clock);world.set(e.object,previousWorld);matrix.set(e.object,transform);});
        }
        void added(Entry e){undo.add(()->entries.remove(e.id));}
        void removed(Entry e){undo.add(()->entries.put(e.id,e));}
        void rollback()throws Exception{for(int n=undo.size()-1;n>=0;n--)undo.get(n).run();}
        void finish()throws Exception{for(var e:new ArrayList<>(entries.values()))if(target.get(e.zone)==null){removed(e);entries.remove(e.id);}}
    }
    private interface Undo{void run()throws Exception;}
    Transaction transaction(){return new Transaction();}
    private Entry adopt(UUID id,NativeActorMapObject.Prepared prepared,Transaction tx)throws Exception {
        if(prepared.carousel()==null)throw new IllegalArgumentException("actor_carousel_object_kind");
        var previous=entries.get(id);if(previous!=null&&previous.source.equals(prepared.carousel()))return previous;
        if(previous!=null){tx.touch(previous);Object actor=target.get(previous.zone);if(actor!=null)leave.invoke(previous.zone,actor);tx.removed(previous);entries.remove(id);}
        if(entries.size()>=4096)throw new ActorScene.Deferred();
        var entry=new Entry(id,prepared.carousel(),prepared.carouselOwned(),behavior.get(prepared.carouselOwned()));entries.put(id,entry);tx.added(entry);return entry;
    }
    private void bind(Entry entry,NativeActorDispatcher.Session session,int x,int z,Transaction tx)throws Exception {
        tx.touch(entry);var source=new SceneObject(entry.source.asset(),Base64.getDecoder().decode(entry.source.record()));
        world.set(entry.object,session.nativeWorld());translate.invoke(matrix.get(entry.object),source.x()-x,source.y(),source.z()-z);
    }
    void step(NativeActorDispatcher.Session session,int x,int z,ActorWorldAccess source,Transaction tx)throws Exception {
        String actor=session.identity().toString();var candidates=new LinkedHashSet<>(source.carousels());
        for(var e:entries.values())if(actor.equals(e.owner))candidates.add(e.id);
        for(UUID id:candidates){
            source.use();Object value=source.object(id);var e=entries.get(id);
            if(value==null){if(e!=null&&actor.equals(e.owner)){tx.touch(e);leave.invoke(e.zone,session.actor());}continue;}
            e=adopt(id,(NativeActorMapObject.Prepared)value,tx);
            if(e.owner!=null&&!e.owner.equals(actor))continue;
            bind(e,session,x,z,tx);
            if(target.get(e.zone)==null&&(boolean)contains.invoke(e.zone,session.actor())){
                if(capture(session).size()>=8)throw new ActorScene.Deferred();enter.invoke(e.zone,session.actor());
                if(target.get(e.zone)!=null)e.owner=actor;
            }
            if(actor.equals(e.owner))advance.invoke(e.zone);
        }
    }
    List<ActorCarouselState> capture(NativeActorDispatcher.Session session)throws Exception {
        String id=session.identity().toString();var result=new ArrayList<ActorCarouselState>();for(var e:entries.values())if(id.equals(e.owner)&&target.get(e.zone)!=null)result.add(e.source.phase(phase.getInt(e.zone)));return List.copyOf(result);
    }
    void restore(NativeActorDispatcher.Session session,int x,int z,List<ActorCarouselState> saved)throws Exception {
        SceneCollision.worker();var tx=transaction();try{
            for(var state:saved){
                if(state.phase()<0)throw new IllegalArgumentException("actor_carousel_saved_phase");var prepared=(NativeActorMapObject.Prepared)motion.restoreCarouselObject(state);var id=new SceneObject(state.asset(),Base64.getDecoder().decode(state.record())).identity();
                var existing=entries.get(id);if(existing!=null&&!session.identity().toString().equals(existing.owner))throw new IllegalArgumentException("actor_carousel_duplicate_capture");var e=adopt(id,prepared,tx);bind(e,session,x,z,tx);e.owner=session.identity().toString();target.set(e.zone,session.actor());phase.setInt(e.zone,state.phase());
            }
        }catch(Exception failure){tx.rollback();throw failure;}
    }
    void close(NativeActorDispatcher.Session session)throws Exception {
        String actor=session.identity().toString();for(var e:new ArrayList<>(entries.values()))if(actor.equals(e.owner)){Object captured=target.get(e.zone);if(captured!=null)leave.invoke(e.zone,captured);entries.remove(e.id);}
    }
}
