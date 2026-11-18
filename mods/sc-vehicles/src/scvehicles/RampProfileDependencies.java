package scvehicles;
import java.util.*;

/** Invalidate only columns influenced by a changed terrain profile. */
final class RampProfileDependencies {
    record Region(double x,double z,double X,double Z) {
        boolean intersects(double x,double z,double X,double Z){return this.x<=X+1e-6&&this.X>=x-1e-6&&this.z<=Z+1e-6&&this.Z>=z-1e-6;}
    }
    private Map<Object,Region> previous=Map.of();
    List<Region> update(Map<Object,Region> next){
        List<Region> changes=new ArrayList<>();
        for(var e:previous.entrySet())if(!next.containsKey(e.getKey()))changes.add(e.getValue());
        for(var e:next.entrySet())if(!previous.containsKey(e.getKey()))changes.add(e.getValue());
        previous=Map.copyOf(next);return changes;
    }
    void clear(){previous=Map.of();}
    static boolean affected(List<Region> changes,double x,double z,double X,double Z){for(Region r:changes)if(r.intersects(x,z,X,Z))return true;return false;}
}
