package replay;

import java.util.*;

/** Reuses an exact immutable definition comparison, never a name or 32-bit hash match. */
final class SceneSkeletonIdentity {
    static final class Pair {
        final SceneTracks.Bytes nativeValue,recordedValue;
        Pair(SceneTracks.Bytes nativeValue,SceneTracks.Bytes recordedValue){this.nativeValue=nativeValue;this.recordedValue=recordedValue;}
        public int hashCode(){return 31*System.identityHashCode(nativeValue)+System.identityHashCode(recordedValue);}
        public boolean equals(Object object){return object instanceof Pair other&&nativeValue==other.nativeValue&&recordedValue==other.recordedValue;}
    }
    final int limit;final LinkedHashMap<Pair,String> verified=new LinkedHashMap<>(16,.75f,true);
    SceneSkeletonIdentity(int limit){if(limit<1)throw new IllegalArgumentException("Skeleton cache limit");this.limit=limit;}
    String identity(SceneTracks.Bytes nativeValue,SceneTracks.Value recordedValue)throws Exception {
        if(nativeValue==null||!(recordedValue instanceof SceneTracks.Bytes recorded))return null;
        Pair key=new Pair(nativeValue,recorded);String identity=verified.get(key);if(identity!=null)return identity;
        if(!nativeValue.equals(recorded))return null;
        identity=NativeScenePresenter.sha(nativeValue);verified.put(key,identity);
        while(verified.size()>limit)verified.remove(verified.keySet().iterator().next());return identity;
    }
    boolean matches(SceneTracks.Bytes nativeValue,SceneTracks.Value recordedValue,String expected)throws Exception {return Objects.equals(expected,identity(nativeValue,recordedValue))&&expected!=null;}
    void clear(){verified.clear();}
}
