package replay;

import java.nio.file.*;
import java.util.*;

/** Publishes the stock tracer renderer's inputs; it never starts a gunshot or an effect simulation. */
final class NativeSceneTracers implements ReplayRender.IndexedTracers,AutoCloseable {
    final SceneTracerIndex index;final Path path;final String hash,fingerprint;final boolean cached;
    final IdentityHashMap<Object,SceneTracerIndex.Beam> recorded=new IdentityHashMap<>(),selected=new IdentityHashMap<>();
    final Map<Long,Object> active=new HashMap<>();final Map<SceneTracerIndex.Style,Object> styles=new HashMap<>();
    Map<Object,List<Object>> textures=Map.of();long time;boolean recording=true;long publications;
    NativeSceneTracers(Path path,String hash,String fingerprint,boolean reuse)throws Exception {this.path=path;this.hash=hash;this.fingerprint=fingerprint;cached=reuse;index=reuse?SceneTracerIndex.read(path,hash,fingerprint):new SceneTracerIndex();ReplayRender.indexedTracers=this;}
    static double number(Object object,String name)throws Exception{return ((Number)Native.field(object,name)).doubleValue();}
    static SceneTracerIndex.Point point(Object v)throws Exception{return new SceneTracerIndex.Point(number(v,"case"),number(v,"void"),number(v,"import"));}
    public synchronized void born(Object tracer)throws Exception {
        if(!recording||cached||recorded.containsKey(tracer))return;Object resource=Native.field(tracer,"public");
        var style=new SceneTracerIndex.Style(Native.call(resource,"byte","779455578").toString(),((Number)Native.call(resource,"void","float")).floatValue(),((Number)Native.call(resource,"throws","float")).floatValue(),((Number)Native.call(resource,"enum","float")).floatValue(),((Number)Native.call(resource,"transient","float")).floatValue(),((Number)Native.call(resource,"if","float")).floatValue(),((Number)Native.call(resource,"native","float")).floatValue(),((Number)Native.call(resource,"char","float")).floatValue());
        var beam=new SceneTracerIndex.Beam(index.beams.size(),Math.max(0,Math.round(ReplayClock.seconds*1e9)),style,point(Native.field(tracer,"for")),point(Native.field(tracer,"native")));index.add(beam);recorded.put(tracer,beam);
    }
    public synchronized void origin(Object tracer,float x,float y,float z){var beam=recorded.get(tracer);if(recording&&beam!=null){long now=Math.max(beam.born,Math.round(ReplayClock.seconds*1e9));if(now<beam.end())beam.origins.put(now,new SceneTracerIndex.Point(x,y,z));}}
    public Set<?> textures(Set<?> original){return recording?original:textures.keySet();}
    public List<?> beams(Object texture,List<?> original){return recording?original:textures.getOrDefault(texture,List.of());}
    public Float progress(Object tracer){var beam=selected.get(tracer);return beam==null?null:beam.progress(time);}
    void finish()throws Exception {if(!cached)index.write(path,hash,fingerprint);recording=false;recorded.clear();}
    void present(long time)throws Exception {
        this.time=time;var visible=index.at(time);var ids=new HashSet<Long>();var next=new LinkedHashMap<Object,List<Object>>();selected.clear();
        for(var beam:visible){ids.add(beam.id);Object resource=styles.get(beam.style);if(resource==null){var s=beam.style;Object texture=Native.type("779455578").getConstructor(String.class).newInstance(s.texture());resource=Native.type("1341358987").getConstructor(byte.class,Native.type("779455578"),float.class,float.class,float.class,float.class,float.class,float.class,float.class).newInstance((byte)0,texture,s.duration(),s.length(),s.width(),s.intensity(),s.red(),s.green(),s.blue());styles.put(s,resource);}
            Object nativeBeam=active.get(beam.id);if(nativeBeam==null){nativeBeam=Native.type("715287958").getConstructor(Native.type("1341358987"),Native.type("153304200"),Native.type("153304200"),boolean.class).newInstance(resource,vector(beam.source),vector(beam.target),true);active.put(beam.id,nativeBeam);}
            var p=beam.origin(time);Native.call(nativeBeam,"abstract","void",(float)p.x(),(float)p.y(),(float)p.z());selected.put(nativeBeam,beam);next.computeIfAbsent(Native.call(resource,"byte","779455578"),k->new ArrayList<>()).add(nativeBeam);
        }
        active.keySet().retainAll(ids);var frozen=new LinkedHashMap<Object,List<Object>>();next.forEach((k,v)->frozen.put(k,List.copyOf(v)));textures=Collections.unmodifiableMap(frozen);publications++;
    }
    static Object vector(SceneTracerIndex.Point p)throws Exception{return Native.type("153304200").getConstructor(double.class,double.class,double.class).newInstance(p.x(),p.y(),p.z());}
    Map<String,Object> diagnostic(){return Map.of("recorded_beams",index.beams.size(),"visible_beams",selected.size(),"publications",publications,"cache_reused",cached);}
    public void close(){if(ReplayRender.indexedTracers==this)ReplayRender.indexedTracers=null;recorded.clear();selected.clear();active.clear();textures=Map.of();}
}
