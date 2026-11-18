package scvehicles;
import java.util.*;
/** Small spatial buckets for the measured terrain patch around one vehicle. */
final class RampIndex<T> {
    private final Map<Long,List<T>> cells=new HashMap<>();
    private static int cell(double x){return (int)Math.floor(x/2);}
    private static long key(int x,int z){return (long)x<<32|(z&0xffffffffL);}
    void clear(){cells.clear();}
    void add(T value,double x,double z,double X,double Z){for(int ix=cell(x);ix<=cell(X);ix++)for(int iz=cell(z);iz<=cell(Z);iz++)cells.computeIfAbsent(key(ix,iz),k->new ArrayList<>()).add(value);}
    List<T> at(double x,double z){return cells.getOrDefault(key(cell(x),cell(z)),List.of());}
}
