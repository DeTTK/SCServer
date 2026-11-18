package replay;

import java.util.*;

/** Composes independent actor/map presentation in one publication. The enclosing
 * player still requires verified coverage of effects, audio and all other domains. */
final class NativeSceneRenderer implements SceneTrackPlayer.Renderer,AutoCloseable {
    final NativeSceneSelection actors;
    final NativeSceneMapSelection map;
    boolean closed;
    NativeSceneRenderer(NativePackets.World replay)throws Exception {
        NativeSceneSelection selected=new NativeSceneSelection(replay);
        try{map=new NativeSceneMapSelection(selected.world);actors=selected;}
        catch(Exception failure){try{selected.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public boolean present(SceneTracks.Frame frame)throws Exception {
        if(closed)throw new IllegalStateException("Closed native scene renderer");
        return actors.present(frame,map::prepare);
    }
    Map<String,Object> diagnostic(){return Map.of("actors",actors.diagnostic(),"map",map.diagnostic(),"packet_prefix_replayed",false,"simulation_steps",0,"full_scene_ready",false);}
    public void close()throws Exception {
        if(closed)return;closed=true;
        try{actors.close();}finally{map.close();}
    }
}
