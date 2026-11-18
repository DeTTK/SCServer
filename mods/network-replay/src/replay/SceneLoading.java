package replay;

/** User commands never drive the private native world reconstruction clock. */
final class SceneLoading {
    final Timeline preparation,intent;
    SceneLoading(Timeline preparation){
        this.preparation=preparation;
        intent=new Timeline(preparation.events,0,preparation.duration);
        intent.playing=true;
    }
    Timeline activate(long start,long first,long duration){
        Timeline selected=new Timeline(intent.events,start,duration);
        selected.seek(intent.seeking?intent.target:first);
        selected.speed(intent.speed);
        selected.playing=intent.playing;
        return selected;
    }
}
