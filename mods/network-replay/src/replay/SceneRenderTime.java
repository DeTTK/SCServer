package replay;
import java.util.*;

/** Reversible render clocks; wall time belongs to UI and resource workers. */
final class SceneRenderTime {
    record Origin(double seconds,long selectedNs){}
    final Map<Object,Origin> origins=new IdentityHashMap<>();
    double sample(Object timer,double original,long selectedNs){
        Origin origin=origins.computeIfAbsent(timer,unused->new Origin(original,selectedNs));
        return origin.seconds()+(selectedNs-origin.selectedNs())/1e9;
    }
    void clear(){origins.clear();}
}
