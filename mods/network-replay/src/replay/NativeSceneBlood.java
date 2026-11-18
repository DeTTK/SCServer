package replay;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Installed legacy blood sprites. Native motion is prepared once; viewing only selects state. */
final class NativeSceneBlood implements ReplayRender.IndexedSprites,AutoCloseable {
    final NativeScenePlayback playback;final Object world,engine;final Path path;
    final List<Field> fields=new ArrayList<>();final String schema,hash,fingerprint;
    final List<Field[]> doubleBlends=new ArrayList<>(),floatBlends=new ArrayList<>();
    final Map<SceneTracks.ObjectId,Object> actors=new HashMap<>();
    final Map<Integer,List<SceneTracks.ObjectTrack>> tracks=new HashMap<>();
    record View(Object group,Object[] particles) {}
    final Map<SceneBloodIndex.Splash,View> views=new IdentityHashMap<>();
    SceneBloodIndex index;List<SceneBloodIndex.Hit> hits;int cursor;boolean ready,cached;
    List<Object> selected=List.of();long publications;
    NativeSceneBlood(NativeScenePlayback playback)throws Exception {
        this.playback=playback;world=playback.actors.world;engine=Native.field(Native.type("1311607083"),"short");
        var demo=playback.world.packets.demo;path=demo.path.resolveSibling(demo.path.getFileName()+".blood-tracks.gz");hash=demo.manifest.get("event_stream_sha256").getAsString();fingerprint=demo.compatibilityFingerprint;
        for(String type:new String[]{"745989169","1240564301"})for(Field f:Native.type(type).getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&f.getType().isPrimitive()){f.setAccessible(true);fields.add(f);}
        fields.sort(Comparator.comparing(f->f.getDeclaringClass().getName()+"."+f.getName()));schema=fields.stream().map(f->f.getDeclaringClass().getName()+"."+f.getName()+":"+f.getType().getName()).reduce("native-blood-v1",(a,b)->a+"|"+b);
        for(String[] pair:new String[][]{{"implements","throw"},{"native","static"},{"public","case"}})doubleBlends.add(new Field[]{Native.type("745989169").getDeclaredField(pair[0]),Native.type("745989169").getDeclaredField(pair[1])});
        for(String[] pair:new String[][]{{"catch","strictfp"},{"switch","return"},{"extends","boolean"}})floatBlends.add(new Field[]{Native.type("745989169").getDeclaredField(pair[0]),Native.type("745989169").getDeclaredField(pair[1])});
        for(var track:playback.tracks.objects)if(!track.type().startsWith("world-"))tracks.computeIfAbsent(track.id().networkId(),unused->new ArrayList<>()).add(track);
        for(var entry:playback.actors.pool.objects.entrySet()){
            Object key=entry.getKey();SceneTracks.ObjectId id=key instanceof NativeSceneSelection.Appearance a?a.incarnation():key instanceof SceneTracks.ObjectId i?i:null;
            if(id!=null)actors.putIfAbsent(id,entry.getValue().entity);
        }
        if(Files.isRegularFile(path))try{index=SceneBloodIndex.read(path,hash,fingerprint,schema);cached=true;}catch(java.io.IOException stale){index=null;}
        if(index==null){index=new SceneBloodIndex();hits=SceneBloodIndex.hits(demo.events,id->{var m=demo.packets.getAsJsonObject(Integer.toString(id));return m==null||m.get("target_class").isJsonNull()?"":m.get("target_class").getAsString();});}
    }
    boolean blood(SceneBloodIndex.Hit hit)throws Exception {
        for(var track:tracks.getOrDefault(hit.target(),List.of()))if(track.alive(hit.time())){
            Object actor=actors.get(track.id());if(actor==null||!Native.type("351062380").isInstance(actor)||Native.type("1304298564").isInstance(actor)||Native.type("525612538").isInstance(actor))return false;
            return !Native.type("1422788721").isInstance(actor)||(boolean)Native.field(Native.field(actor,"double"),"const");
        }
        return false;
    }
    View create(SceneBloodIndex.Hit hit)throws Exception {
        if(Native.field(Native.type("1658859028"),"return")==null)throw new java.io.IOException("Installed blood sprite atlas is unavailable");
        // Use the canonical constructor with an isolated random stream. Do not
        // consume the replay world's RNG while restoring its presentation.
        Field random=Native.type("1500514786").getDeclaredField("for");random.setAccessible(true);Object original=random.get(world),group;
        try {random.set(world,new Random(hit.seed()));group=Native.type("468542484").getConstructor(Native.type("1500514786"),Native.type("1217489680"),boolean.class,int.class,Native.type("153304200"),Native.type("153304200")).newInstance(world,null,true,-1,vector(hit.start()),vector(hit.end()));}
        finally {random.set(world,original);}
        Object[] particles=((List<?>)Native.type("204573430").getDeclaredField("extends").get(group)).toArray();
        if(particles.length!=3)throw new java.io.IOException("Native blood particle layout changed");return new View(group,particles);
    }
    static Object vector(double[] p)throws Exception {return Native.type("153304200").getConstructor(double.class,double.class,double.class).newInstance(p[0],p[1],p[2]);}
    List<double[][]> capture(View view)throws Exception {
        var frames=new ArrayList<double[][]>();
        for(int tick=0;tick<12;tick++){
            List<?> live=(List<?>)Native.type("204573430").getDeclaredField("extends").get(view.group);double[][] values=new double[3][];
            for(int p=0;p<3;p++)if(live.contains(view.particles[p])){double[] row=new double[fields.size()];for(int k=0;k<row.length;k++){Field f=fields.get(k);row[k]=f.getType()==boolean.class?(f.getBoolean(view.particles[p])?1:0):((Number)f.get(view.particles[p])).doubleValue();}values[p]=row;}
            frames.add(values);if(tick<11)Native.call(view.group,"else","void");
        }
        return List.copyOf(frames);
    }
    boolean prepare(long deadline)throws Exception {
        if(ready)return true;
        if(cached){while(cursor<index.splashes.size()&&System.nanoTime()<deadline){var s=index.splashes.get(cursor++);View view=create(s.hit());for(var frame:s.frames())for(double[] row:frame)if(row!=null&&row.length!=fields.size())throw new java.io.IOException("Blood sprite field layout changed");views.put(s,view);}}
        else {while(cursor<hits.size()&&System.nanoTime()<deadline){var hit=hits.get(cursor++);if(!blood(hit))continue;View view=create(hit);var splash=new SceneBloodIndex.Splash(hit,capture(view));index.splashes.add(splash);views.put(splash,view);}if(cursor==hits.size())index.write(path,hash,fingerprint,schema);}
        ready=cached?cursor==index.splashes.size():cursor==hits.size();if(ready)ReplayRender.indexedSprites=this;return ready;
    }
    void present(long time)throws Exception {
        if(!ready)throw new IllegalStateException("Blood history is not prepared");var groups=new ArrayList<Object>();
        for(var splash:index.at(time)){
            long age=time-splash.hit().time();double[][] states=splash.frames().get(SceneBloodIndex.frame(age,splash.frames().size()));float alpha=SceneBloodIndex.alpha(age);View view=views.get(splash);var particles=new ArrayList<Object>();
            for(int p=0;p<states.length;p++)if(states[p]!=null){Object particle=view.particles[p];double[] row=states[p];for(int k=0;k<row.length;k++){Field f=fields.get(k);Class<?> t=f.getType();if(t==float.class)f.setFloat(particle,(float)row[k]);else if(t==double.class)f.setDouble(particle,row[k]);else if(t==int.class)f.setInt(particle,(int)row[k]);else if(t==boolean.class)f.setBoolean(particle,row[k]!=0);else throw new java.io.IOException("Unsupported blood scalar");}
                for(Field[] pair:doubleBlends){Field previous=pair[0],current=pair[1];double a=previous.getDouble(particle),b=current.getDouble(particle),value=a+(b-a)*alpha;previous.setDouble(particle,value);current.setDouble(particle,value);}
                for(Field[] pair:floatBlends){Field previous=pair[0],current=pair[1];float a=previous.getFloat(particle),b=current.getFloat(particle),value=a+(b-a)*alpha;previous.setFloat(particle,value);current.setFloat(particle,value);}
                particles.add(particle);
            }
            Native.type("204573430").getDeclaredField("extends").set(view.group,particles);if(!particles.isEmpty())groups.add(view.group);
        }
        selected=List.copyOf(groups);publications++;
    }
    public List<?> groups(Object renderer,List<?> original){
        if(renderer!=engine)return original;if(original.isEmpty())return selected;
        var all=new ArrayList<Object>();for(Object group:original)if(!group.getClass().getName().equals("468542484"))all.add(group);all.addAll(selected);return all;
    }
    Map<String,Object> diagnostic(){return Map.of("impacts",index.splashes.size(),"visible_splashes",selected.size(),"ready",ready,"cache_reused",cached,"publications",publications,"packet_prefix_replayed",false,"particle_random_origin","deterministic_event_seed");}
    public void close(){if(ReplayRender.indexedSprites==this)ReplayRender.indexedSprites=null;selected=List.of();views.clear();actors.clear();tracks.clear();}
}
