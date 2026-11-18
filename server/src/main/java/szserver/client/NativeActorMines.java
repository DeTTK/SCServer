package szserver.client;

import szserver.world.*;
import szserver.inventory.ExplosiveProfile;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Original mine tile/contact/timer, shared across character hosts and committed with their step. */
final class NativeActorMines {
    private static final int LIMIT=8192;
    private final NativeActorMapWorld.Factory factory;
    private final Constructor<?> tileConstructor;
    private final Method tick,seed,seedX,seedZ;
    private final Field cooldown;
    private final Class<?> contactType;
    private final Constructor<?> pointConstructor;
    private final Constructor<?> bodyStoreConstructor;private final Field bodyStore;private final List<Field> bodyBoxes=new ArrayList<>();private final Method height;private final Object[] poses;
    private final Field contactPoint,contactAlive,contactInvulnerable;
    private final LinkedHashMap<Long,NativeActorMapWorld> contactWorlds=new LinkedHashMap<>(16,.75f,true);
    private final Field[] coordinates;
    private final List<Field> worlds=new ArrayList<>();
    private final int period;
    final ExplosiveProfile profile;
    private final Map<World.Block,Entry> entries=new LinkedHashMap<>();
    private final List<ActorMineTrigger> committed=new ArrayList<>();
    private int active;
    private record Entry(World.Block block,Object tile){}
    NativeActorMines(NativeActorMapWorld.Factory factory,Path runtime,Path resources,BiFunction<String,byte[],Class<?>> define)throws Exception{
        this.factory=factory;ClassLoader loader=factory.loader;
        for(var entry:Map.ofEntries(
            Map.entry("550444456","be15c5fcbc14f0b9a156f6cafa5ac00547d632ed37e0f35fdd46c9baef05a908"),
            Map.entry("806673724","23b80c2d1d527c30affdac45b07c51d8e9c20e31efb43ebf31c3379e476ae7a4"),
            Map.entry("47136656","70bdfa27c1d36f18c2b862222be3ea730bc734d2feefacaa3eb3747d15827ec1"),
            Map.entry("812318560","e5793b59e25f1e77f3cc1ac96b604625a696bd41f4e99c92f468eecc42a7e798"),
            Map.entry("843651061","0466d718441bb82810363cbe8293ffb5db5ba11efd760e9403750895e05e5a7e"),
            Map.entry("1123841866","e803d5e87b992f65a0b6be4211cc7712a47e5304261f6564e0cb11d9ee3b2624")
        ).entrySet()){
            String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(entry.getKey()+".class"))));
            if(!actual.equals(entry.getValue()))throw new IllegalStateException("native_actor_mine_release:"+entry.getKey());
        }
        Path file=resources.resolve("assets/stalker/items/other/mines.json");
        if(Files.size(file)>65536)throw new IllegalArgumentException("actor_mine_definitions_size");
        JsonObject definition=null;
        for(var value:JsonParser.parseString(Files.readString(file)).getAsJsonArray()){
            var row=value.getAsJsonObject();if(row.get("item_id").getAsInt()==4006){if(definition!=null||!row.get("type").getAsString().equals("landmine"))throw new IllegalArgumentException("actor_mine_definition");definition=row;}
        }
        if(definition==null)throw new IllegalArgumentException("actor_mine_definition_missing");
        period=definition.has("explosion_freq")?definition.get("explosion_freq").getAsInt():20;
        if(period<1||period>1_000_000)throw new IllegalArgumentException("actor_mine_period");
        Class<?> properties=Class.forName("843651061",true,loader),parser=Class.forName("com.google.gson.JsonParser",true,loader),json=Class.forName("com.google.gson.JsonObject",true,loader);
        Object parsed=parser.getMethod("parseString",String.class).invoke(null,(definition.has("explosive_props")?definition.getAsJsonObject("explosive_props"):definition).toString());
        Object nativeProfile=properties.getMethod("for",json).invoke(null,parsed.getClass().getMethod("getAsJsonObject").invoke(parsed));
        var bytes=new ByteArrayOutputStream();properties.getMethod("catch",DataOutput.class).invoke(nativeProfile,new DataOutputStream(bytes));
        profile=new ExplosiveProfile(4006,((Enum<?>)properties.getField("catch").get(nativeProfile)).name(),properties.getField("float").getFloat(nativeProfile),properties.getField("interface").getFloat(nativeProfile),properties.getField("if").getFloat(nativeProfile),properties.getField("strictfp").getFloat(nativeProfile),properties.getField("short").getBoolean(nativeProfile),properties.getField("do").getInt(nativeProfile),properties.getField("this").getBoolean(nativeProfile),bytes.toByteArray(),properties.getField("boolean").getBoolean(nativeProfile));
        Class<?> bridge=define.apply("szserver.bridge.ActorMineBlock",NativeActorBridge.mineBlock());
        Object previous=factory.blocks[4006],block;
        factory.blocks[4006]=null;
        try{block=bridge.getConstructor(int.class,String.class,int.class).newInstance(4006,definition.get("model_texture").getAsString(),period);}
        catch(Exception failure){factory.blocks[4006]=previous;throw failure;}
        Class<?> original=Class.forName("806673724",true,loader);original.getField("byte").set(block,nativeProfile);
        bridge.getField("offsets").set(block,(Function<int[],float[]>)p->{try{
            var scope=factory.activeScope.get();if(scope==null)throw new IllegalStateException("actor_mine_seed_scope");
            return offsets(new World.Block(p[0]+(int)scope.origin().x(),p[1],p[2]+(int)scope.origin().z()));
        }catch(Exception failure){throw new IllegalStateException("actor_mine_seed",failure);}});
        Class<?> tile=Class.forName("47136656",true,loader),base=Class.forName("1123841866",true,loader),hash=Class.forName("812318560",true,loader);
        tileConstructor=tile.getConstructor();tick=tile.getMethod("strictfp");cooldown=tile.getField("boolean");
        coordinates=new Field[]{base.getField("else"),base.getField("float"),base.getField("super")};
        for(var field:base.getFields())if(!Modifier.isStatic(field.getModifiers())&&field.getType()==factory.worldBase){field.setAccessible(true);worlds.add(field);}
        seed=hash.getMethod("assert",int.class,int.class,int.class);seedX=hash.getMethod("boolean",long.class);seedZ=hash.getMethod("const",long.class);
        contactType=define.apply("szserver.bridge.ActorMineContact",NativeActorBridge.mineContact());pointConstructor=Class.forName("153304200",true,loader).getConstructor(double.class,double.class,double.class);
        contactPoint=contactType.getField("point");contactAlive=contactType.getField("alive");contactInvulnerable=contactType.getField("invulnerable");
        Class<?> store=Class.forName("284743",true,loader),living=Class.forName("820497227",true,loader),pose=Class.forName("430277821",true,loader);
        bodyStoreConstructor=store.getConstructor();bodyStore=living.getDeclaredField("const");bodyStore.setAccessible(true);
        for(var field:store.getDeclaredFields())if(field.getType()==factory.boxType){field.setAccessible(true);bodyBoxes.add(field);}
        poses=pose.getEnumConstants();height=Class.forName("1943200621",true,loader).getMethod("const",pose);
        tileConstructor.newInstance();
    }
    private float[] offsets(World.Block p)throws Exception{
        long value=(long)seed.invoke(null,p.x(),p.y(),p.z());
        return new float[]{((float)seedX.invoke(null,value)-.5f)*.5f+.5f,((float)seedZ.invoke(null,value)-.5f)*.5f+.5f};
    }
    final class Transaction {
        final Map<Entry,Integer> previous=new LinkedHashMap<>();final List<World.Block> added=new ArrayList<>();
        Object tile(World.Block p,int x,int y,int z,Object world)throws Exception{
            Entry entry=entries.get(p);
            if(entry==null){
                if(entries.size()>=LIMIT)entries.entrySet().removeIf(e->{try{return cooldown.getInt(e.getValue().tile())==0;}catch(Exception failure){throw new IllegalStateException(failure);}});
                if(entries.size()>=LIMIT)throw new IllegalStateException("actor_mine_limit");
                entry=new Entry(p,tileConstructor.newInstance());entries.put(p,entry);added.add(p);
            }
            previous.putIfAbsent(entry,cooldown.getInt(entry.tile()));
            for(int i=0;i<3;i++)coordinates[i].setInt(entry.tile(),new int[]{x,y,z}[i]);
            for(var field:worlds)field.set(entry.tile(),world);
            return entry.tile();
        }
        void rollback()throws Exception{for(var row:previous.entrySet())cooldown.setInt(row.getKey().tile(),row.getValue());for(var p:added)entries.remove(p);clearBindings();}
        private void clearBindings()throws Exception{for(var entry:previous.keySet())for(var field:worlds)field.set(entry.tile(),null);}
        void finish(NativeActorDispatcher.Session session)throws Exception{finish(factory.activeScope.get().globalFrame(session).position());}
        void finish(World.Vec contactPosition)throws Exception{
            var triggers=new ArrayList<ActorMineTrigger>();
            for(var row:previous.entrySet())if(row.getValue()==0&&cooldown.getInt(row.getKey().tile())>0){
                if(cooldown.getInt(row.getKey().tile())!=period)throw new IllegalStateException("actor_mine_native_clock");
                var p=row.getKey().block();float[] offset=offsets(p);
                var scope=factory.activeScope.get();var trigger=new ActorMineTrigger(UUID.randomUUID(),p,new World.Vec((double)p.x()+offset[0],p.y(),(double)p.z()+offset[1]),profile);
                triggers.add(scope.prepareMine(trigger,contactPosition));
            }
            if(committed.size()+triggers.size()>512)throw new IllegalStateException("actor_mine_pending_limit");
            clearBindings();committed.addAll(triggers);active+=triggers.size();
        }
    }
    Transaction transaction(){return new Transaction();}
    void step()throws Exception{for(var entry:entries.values())if(cooldown.getInt(entry.tile())>0){tick.invoke(entry.tile());if(cooldown.getInt(entry.tile())==0)active--;}}
    int activeCount(){return active;}
    List<ActorMineState> capture()throws Exception{
        var result=new ArrayList<ActorMineState>();for(var entry:entries.values()){int value=cooldown.getInt(entry.tile());if(value>0)result.add(new ActorMineState(entry.block(),value));}
        result.sort(Comparator.comparingInt((ActorMineState s)->s.block().x()).thenComparingInt(s->s.block().y()).thenComparingInt(s->s.block().z()));return List.copyOf(result);
    }
    void restore(List<ActorMineState> states)throws Exception{
        if(states.size()>LIMIT)throw new IllegalArgumentException("actor_mine_count");var next=new LinkedHashMap<World.Block,Entry>();
        for(var state:states){if(state.cooldown()>period||next.containsKey(state.block()))throw new IllegalArgumentException("actor_mine_saved_clock");Object tile=tileConstructor.newInstance();cooldown.setInt(tile,state.cooldown());next.put(state.block(),new Entry(state.block(),tile));}
        entries.clear();entries.putAll(next);committed.clear();active=(int)states.stream().filter(s->s.cooldown()>0).count();
    }
    void forget(World.Block block){var entry=entries.remove(block);if(entry!=null)try{if(cooldown.getInt(entry.tile())>0)active--;}catch(Exception failure){throw new IllegalStateException(failure);}}
    List<ActorMineTrigger> drain(){var result=List.copyOf(committed);committed.clear();return result;}
    SceneCollision.Box contactBounds(World.Vec p,ActorMinePlayer player)throws Exception{
        return new SceneCollision.Box(p.x()-ActorPose.RADIUS,p.y(),p.z()-ActorPose.RADIUS,p.x()+ActorPose.RADIUS,p.y()+((Number)height.invoke(null,poses[player.locomotion()])).doubleValue(),p.z()+ActorPose.RADIUS);
    }
    Object contactBody(World.Vec p,ActorMinePlayer player)throws Exception{
        Object value=factory.allocate(contactType),store=bodyStoreConstructor.newInstance(),box=factory.box(contactBounds(p,player));
        for(var field:bodyBoxes)field.set(store,box);bodyStore.set(value,store);
        contactPoint.set(value,pointConstructor.newInstance(p.x(),p.y(),p.z()));contactAlive.setBoolean(value,player.alive());contactInvulnerable.setBoolean(value,player.invulnerable());return value;
    }
    void contact(ActorMinePlayer player,ActorWorldAccess access)throws Exception{
        var p=player.position();int x=Math.floorDiv((int)Math.floor(p.x()),16),z=Math.floorDiv((int)Math.floor(p.z()),16);
        long key=BlockOverlays.key(x,z);var map=contactWorlds.get(key);
        if(map==null){map=factory.create(new World.Vec(x*16d,0,z*16d));if(contactWorlds.size()==64)contactWorlds.remove(contactWorlds.keySet().iterator().next());contactWorlds.put(key,map);}
        map.contactMinePlayer(player,access);
    }
}
