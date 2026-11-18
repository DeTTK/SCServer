package scvehicles;
import java.util.*;

/** Immutable voxel obstacles for one rendered frame, queried in native insertion order. */
final class VehicleVoxelIndex {
    record Key(int x,int y,int z){}
    static final class Entry {
        final Object box;final double[] bounds;final int order;long visit;
        Entry(Object box,double[] bounds,int order){this.box=box;this.bounds=bounds;this.order=order;}
    }
    final double[] region;
    final List<Entry> entries=new ArrayList<>(),large=new ArrayList<>();
    private final Map<Key,List<Entry>> cells=new HashMap<>();
    private long query;
    VehicleVoxelIndex(double[] region){this.region=region.clone();}
    boolean covers(double[] a){for(int i=0;i<3;i++)if(a[i]<region[i]-1e-7||a[i+3]>region[i+3]+1e-7)return false;return true;}
    void add(Object box,double[] bounds){
        Entry e=new Entry(box,bounds.clone(),entries.size());entries.add(e);
        int x=(int)Math.floor(bounds[0]),y=(int)Math.floor(bounds[1]),z=(int)Math.floor(bounds[2]),X=(int)Math.floor(bounds[3]),Y=(int)Math.floor(bounds[4]),Z=(int)Math.floor(bounds[5]);
        if((long)(X-x+1)*(Y-y+1)*(Z-z+1)>512){large.add(e);return;}
        for(int ix=x;ix<=X;ix++)for(int iy=y;iy<=Y;iy++)for(int iz=z;iz<=Z;iz++)cells.computeIfAbsent(new Key(ix,iy,iz),k->new ArrayList<>()).add(e);
    }
    List<Entry> at(double[] a){
        List<Entry> out=new ArrayList<>();long stamp=++query;
        for(int x=(int)Math.floor(a[0]);x<=(int)Math.floor(a[3]);x++)for(int y=(int)Math.floor(a[1]);y<=(int)Math.floor(a[4]);y++)for(int z=(int)Math.floor(a[2]);z<=(int)Math.floor(a[5]);z++){
            List<Entry> list=cells.get(new Key(x,y,z));if(list!=null)for(Entry e:list)match(e,a,stamp,out);
        }
        for(Entry e:large)match(e,a,stamp,out);
        out.sort(Comparator.comparingInt(e->e.order));return out;
    }
    private static void match(Entry e,double[] a,long stamp,List<Entry> out){
        if(e.visit==stamp)return;e.visit=stamp;double[] b=e.bounds;
        for(int i=0;i<3;i++)if(b[i]>a[i+3]+1e-7||b[i+3]<a[i]-1e-7)return;
        out.add(e);
    }
}
