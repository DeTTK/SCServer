package replay;

import java.nio.charset.StandardCharsets;

/** A returning rig is a discontinuity even when it reuses the same skeleton. */
final class SceneRigTracks {
    static void put(SceneTrackBuilder.ObjectRow row,String prefix,long time,String skeletonId,SceneTracks.Bytes definition,SceneTracks.Bones bones){
        String layout=bones==null?"":skeletonId+":"+bones.curves().length;
        SceneTracks.Bytes descriptor=new SceneTracks.Bytes(layout.getBytes(StandardCharsets.UTF_8));
        var previous=row.channels.get(prefix+"rig-layout");boolean cut=previous==null||!descriptor.equals(previous.last);
        if(bones!=null){
            row.put(prefix+"skeleton/"+skeletonId,SceneTracks.Domain.POSES,SceneTracks.Blend.HOLD,time,definition,false);
            row.put(prefix+"bones/"+layout,SceneTracks.Domain.POSES,SceneTracks.Blend.BONES,time,bones,cut);
        }
        row.put(prefix+"rig-layout",SceneTracks.Domain.POSES,SceneTracks.Blend.HOLD,time,descriptor,false);
    }
}
