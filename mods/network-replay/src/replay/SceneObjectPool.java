package replay;

import java.util.*;

/** Presentation incarnations outlive their visible intervals. Hiding an object does
 * not normally destroy its rig. A bounded cache can reconstruct evicted variants
 * directly from scene values, without replaying packets or stepping simulation. */
final class SceneObjectPool<T> implements AutoCloseable {
    interface Resources<T> {
        default Object cacheKey(SceneTracks.ObjectState state){return state.id();}
        default boolean prepareAhead(SceneTracks.ObjectState state)throws Exception{return true;}
        T create(SceneTracks.ObjectState state,int runtimeId)throws Exception;
        boolean ready(T value,SceneTracks.ObjectState state)throws Exception;
        void dispose(T value)throws Exception;
    }
    final Resources<T> resources;
    final Map<Object,T> objects=new LinkedHashMap<>(16,.75f,true);
    final Set<T> aheadReady=Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<T> catalogued=Collections.newSetFromMap(new IdentityHashMap<>());
    final int cacheLimit;
    Map<SceneTracks.ObjectId,T> selected=Map.of();
    Map<SceneTracks.ObjectId,T> preparing=Map.of();
    int nextId=-1_800_000_000;
    boolean closed;
    SceneObjectPool(Resources<T> resources){this(resources,256);}
    SceneObjectPool(Resources<T> resources,int cacheLimit){this.resources=Objects.requireNonNull(resources);if(cacheLimit<1)throw new IllegalArgumentException("Invalid scene cache limit");this.cacheLimit=cacheLimit;}
    /** null means pending. A superseded request never replaces the visible selection. */
    Map<SceneTracks.ObjectId,T> prepare(SceneTracks.Frame frame)throws Exception {
        if(closed)throw new IllegalStateException("Closed scene pool");
        Map<SceneTracks.ObjectId,T> candidate=new TreeMap<>();boolean ready=true;
        for(var state:frame.presentationObjects()){
            if(state.type().equals("world-environment")||state.type().startsWith("world-map-"))continue;
            Object key=Objects.requireNonNull(resources.cacheKey(state));T object=objects.get(key);
            if(object==null){
                if(nextId==Integer.MIN_VALUE)throw new IllegalStateException("Scene runtime IDs exhausted");
                object=Objects.requireNonNull(resources.create(state,nextId--));objects.put(key,object);
            }
            if(!resources.ready(object,state))ready=false;
            candidate.put(state.id(),object);
        }
        preparing=Collections.unmodifiableMap(candidate);
        trim(candidate.values());
        return ready?Collections.unmodifiableMap(candidate):null;
    }
    void trim(Collection<T> pending)throws Exception {
        if(objects.size()<=cacheLimit)return;
        Set<T> protectedObjects=Collections.newSetFromMap(new IdentityHashMap<>());protectedObjects.addAll(selected.values());protectedObjects.addAll(preparing.values());protectedObjects.addAll(pending);protectedObjects.addAll(catalogued);
        Set<T> discarded=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var iterator=objects.entrySet().iterator();objects.size()>cacheLimit&&iterator.hasNext();){var entry=iterator.next();if(!protectedObjects.contains(entry.getValue())){discarded.add(entry.getValue());iterator.remove();}}
        // An aliased factory result still has one resource owner, regardless of keys.
        for(T retained:objects.values())discarded.remove(retained);
        aheadReady.removeAll(discarded);Exception failure=null;for(T value:discarded)try{resources.dispose(value);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;
    }
    void publish(Map<SceneTracks.ObjectId,T> candidate){if(closed)throw new IllegalStateException("Closed scene pool");selected=Map.copyOf(candidate);preparing=Map.of();}
    void cancelPreparation(){preparing=Map.of();}
    /** Reserve every certified recorded variant for direct/reverse selection. */
    boolean catalogue(SceneTracks.ObjectState state)throws Exception {
        if(closed)throw new IllegalStateException("Closed scene pool");
        Object key=Objects.requireNonNull(resources.cacheKey(state));T object=objects.get(key);
        if(object==null){if(nextId==Integer.MIN_VALUE)throw new IllegalStateException("Scene runtime IDs exhausted");object=Objects.requireNonNull(resources.create(state,nextId--));objects.put(key,object);}
        catalogued.add(object);if(aheadReady.contains(object))return true;
        if(!resources.ready(object,state))return false;aheadReady.add(object);return true;
    }
    /** Bounded preparation ahead of the playhead. Visible objects are never reconfigured. */
    void prepareAhead(SceneTracks.Frame frame,long deadline,int limit)throws Exception {
        if(closed)throw new IllegalStateException("Closed scene pool");
        Set<T> visible=Collections.newSetFromMap(new IdentityHashMap<>());visible.addAll(selected.values());
        List<T> pending=new ArrayList<>();int attempted=0;
        for(var state:frame.presentationObjects()){
            if(state.type().equals("world-environment")||state.type().startsWith("world-map-"))continue;
            if(attempted>=limit||System.nanoTime()>=deadline)break;
            if(!resources.prepareAhead(state))continue;
            Object key=Objects.requireNonNull(resources.cacheKey(state));T object=objects.get(key);
            if(object!=null&&(visible.contains(object)||aheadReady.contains(object)))continue;
            attempted++;
            if(object==null){if(nextId==Integer.MIN_VALUE)throw new IllegalStateException("Scene runtime IDs exhausted");object=Objects.requireNonNull(resources.create(state,nextId--));objects.put(key,object);}
            pending.add(object);if(resources.ready(object,state))aheadReady.add(object);
        }
        trim(pending);
    }
    public void close()throws Exception {
        if(closed)return;closed=true;selected=preparing=Map.of();Exception failure=null;
        // Each actual instance has one owner even if a buggy factory aliases identities.
        Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(T object:objects.values())if(seen.add(object))try{resources.dispose(object);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        objects.clear();aheadReady.clear();catalogued.clear();if(failure!=null)throw failure;
    }
}
