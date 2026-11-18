package replay;

/** Absolute-time presentation. The renderer prepares resources before publishing a complete frame. */
public final class SceneTrackPlayer implements Timeline.Sink {
    public interface Renderer {
        /** Must leave the visible frame intact when false; must cancel any obsolete pending selection. */
        boolean present(SceneTracks.Frame selected)throws Exception;
    }
    public final SceneTracks tracks;private final Renderer renderer;
    public SceneTrackPlayer(SceneTracks tracks,Renderer renderer){
        if(!tracks.complete())throw new IllegalArgumentException("Scene tracks lack full presentation coverage: "+tracks.coverage);
        this.tracks=tracks;this.renderer=java.util.Objects.requireNonNull(renderer);
    }
    public boolean directTime(){return true;}
    public boolean presentTime(long time)throws Exception{return renderer.present(tracks.sample(time));}
    public void reset(){throw new UnsupportedOperationException("Indexed scenes do not reset the simulation");}
    public void apply(DemoFile.Event e){throw new UnsupportedOperationException("Indexed scenes do not dispatch packets");}
}
