package replay;

import java.util.*;
import java.util.concurrent.*;

/** CPU decoding only. Native cells and their GPU buffers remain owned by the game thread. */
final class SceneTerrainPreparation implements AutoCloseable {
    final IdentityHashMap<SceneTracks.Bytes,Long> keys=new IdentityHashMap<>();
    final ConcurrentMap<Long,SceneTracks.Bytes> inputs=new ConcurrentHashMap<>();
    final SceneAsyncFrames<SceneTerrainCodec.Mesh> frames=new SceneAsyncFrames<>("Replay terrain preparation",32,key->SceneTerrainCodec.decode(inputs.get(key)),mesh->{});
    long key(SceneTracks.Bytes bytes){Long key=keys.get(bytes);if(key==null){key=(long)keys.size();keys.put(bytes,key);inputs.put(key,bytes);}return key;}
    void reserve(Collection<SceneTracks.Bytes> bytes){List<Long> required=new ArrayList<>(bytes.size());for(var value:bytes)required.add(key(value));frames.reserve(required);}
    SceneTerrainCodec.Mesh request(SceneTracks.Bytes bytes,boolean urgent)throws Exception {
        return frames.request(key(bytes),urgent);
    }
    public void close()throws Exception {try{frames.close();}finally{keys.clear();inputs.clear();}}
}
