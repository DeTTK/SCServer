package replay;

import java.util.*;

/** FMOD opens samples asynchronously; foreground callers only submit and poll. */
final class SceneSoundResources {
    interface Access {long open(String path)throws Exception;int state(long sound)throws Exception;}
    static final class Pending extends Exception {Pending(){super(null,null,false,false);}}
    final Access access;final Map<String,Long> handles;
    final Set<String> ready=new HashSet<>();
    final LinkedHashSet<String> queued=new LinkedHashSet<>(),loading=new LinkedHashSet<>();
    SceneSoundResources(Access access,Map<String,Long> handles){this.access=access;this.handles=handles;}
    void request(Collection<String> paths){for(String path:paths)if(!handles.containsKey(path))queued.add(path);}
    boolean poll(String path,long handle)throws Exception {
        if(ready.contains(path))return true;
        int state=access.state(handle);
        if(state==2)throw new java.io.IOException("Audio sample failed to open: "+path);
        if(state!=0)return false;
        loading.remove(path);ready.add(path);return true;
    }
    long sound(String path)throws Exception {
        Long handle=handles.get(path);
        if(handle==null){handle=access.open(path);if(handle==0)throw new java.io.IOException("Empty audio sample handle: "+path);handles.put(path,handle);queued.remove(path);loading.add(path);}
        if(!poll(path,handle))throw new Pending();return handle;
    }
    void pump(long deadline,java.util.function.LongSupplier nanos)throws Exception {
        int submissions=0;
        while(!queued.isEmpty()&&submissions++<4&&nanos.getAsLong()<deadline){String path=queued.iterator().next();try{sound(path);}catch(Pending pending){}}
        for(String path:List.copyOf(loading)){if(nanos.getAsLong()>=deadline)break;poll(path,handles.get(path));}
    }
    boolean ready(){return queued.isEmpty()&&loading.isEmpty();}
}
