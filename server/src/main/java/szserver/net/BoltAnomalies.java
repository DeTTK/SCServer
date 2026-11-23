package szserver.net;

import szserver.world.AnomalyZone;
import szserver.world.World;
import java.util.*;

/** Thrown bolts trigger the first anomaly whose native shape contains the resting point. */
public final class BoltAnomalies {
    private static final double WIDTH=.15,HEIGHT=.15;
    private BoltAnomalies(){}
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec point){
        for(AnomalyZone zone:zones)if(zone.touches(point,point,WIDTH,HEIGHT))return zone;return null;
    }
}
