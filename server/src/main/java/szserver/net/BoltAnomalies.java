package szserver.net;

import szserver.world.AnomalyZone;
import szserver.world.World;
import java.util.*;

/** Thrown bolts trigger the first anomaly whose native shape contains the bolt point. */
public final class BoltAnomalies {
    private static final double WIDTH=.15,HEIGHT=.4;
    private BoltAnomalies(){}
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec point){return containing(zones,point,point);}
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec from,World.Vec to){
        World.Vec low=new World.Vec(from.x(),from.y()-HEIGHT,from.z()),high=new World.Vec(to.x(),to.y()+HEIGHT,to.z());
        for(AnomalyZone zone:zones)if(zone.touches(low,high,WIDTH,HEIGHT))return zone;return null;
    }
}
