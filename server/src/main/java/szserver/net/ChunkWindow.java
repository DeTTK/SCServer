package szserver.net;

import szserver.world.BlockOverlays;
import java.util.*;

/** Transport-owned, lazy center-first catalogue. Work and outstanding demand are bounded. */
public final class ChunkWindow {
    public static final int RADIUS=8,MAX_OUTSTANDING=64,MAX_RADIUS=30_000_000/16,WORK_BUDGET=256;
    private int x=Integer.MIN_VALUE,z,radius=RADIUS,limit=MAX_OUTSTANDING;
    private Integer secondaryX;private int secondaryZ,secondaryRadius;
    private Iterator<Long> cursor=Collections.emptyIterator();
    private final ChunkCoverage seen=new ChunkCoverage();private final Set<Long> outstanding=new HashSet<>();
    private final LinkedHashSet<Long> retryKeys=new LinkedHashSet<>();
    private final LinkedHashSet<Long> faultKeys=new LinkedHashSet<>();private int faultTurn;
    public ChunkWindow(){}
    public ChunkWindow(int radius,int limit){if(limit<64||limit>256)throw new IllegalArgumentException("chunk_outstanding_limit");this.limit=limit;distance(radius);}
    public boolean distance(int value){if(value<1||value>MAX_RADIUS)throw new IllegalArgumentException("chunk_distance_out_of_bounds");if(radius==value)return false;radius=value;restart();return true;}
    public int radius(){return radius;}
    public int limit(){return limit;}
    /** Change both views atomically, so trimming never observes a temporary third window. */
    public boolean focus(int nextX,int nextZ,Integer otherX,int otherZ,int otherRadius){
        if(otherX!=null&&(otherRadius<1||otherRadius>MAX_RADIUS))throw new IllegalArgumentException("chunk_distance_out_of_bounds");
        if(x==nextX&&z==nextZ&&Objects.equals(secondaryX,otherX)&&(otherX==null||secondaryZ==otherZ&&secondaryRadius==otherRadius))return false;
        x=nextX;z=nextZ;secondaryX=otherX;secondaryZ=otherZ;secondaryRadius=otherRadius;restart();return true;
    }
    public boolean secondary(Integer nextX,int nextZ,int nextRadius){
        if(nextX!=null&&(nextRadius<1||nextRadius>MAX_RADIUS))throw new IllegalArgumentException("chunk_distance_out_of_bounds");
        if(Objects.equals(nextX,secondaryX)&&(nextX==null||nextZ==secondaryZ&&nextRadius==secondaryRadius))return false;
        secondaryX=nextX;secondaryZ=nextZ;secondaryRadius=nextRadius;restart();return true;
    }
    public boolean center(int nextX,int nextZ){
        if(x==nextX&&z==nextZ)return false;x=nextX;z=nextZ;
        restart();return true;
    }
    private void restart(){outstanding.removeIf(key->!inside(key));seen.trim(this,WORK_BUDGET);cursor=x==Integer.MIN_VALUE?Collections.emptyIterator():views();}
    public boolean inside(long key){return inside((int)(key>>32),(int)key,x,z,radius)||secondaryX!=null&&inside((int)(key>>32),(int)key,secondaryX,secondaryZ,secondaryRadius);}
    private static boolean inside(int px,int pz,int cx,int cz,int r){return Math.abs((long)px-cx)<=r&&Math.abs((long)pz-cz)<=r;}
    /** Up to two retained x intervals on this row, merged when the views overlap. */
    int[] intersections(int row,int from,int to){
        int[] a=Math.abs((long)row-z)<=radius?clip(from,to,x,radius):new int[0];int[] b=secondaryX!=null&&Math.abs((long)row-secondaryZ)<=secondaryRadius?clip(from,to,secondaryX,secondaryRadius):new int[0];
        if(a.length==0)return b;if(b.length==0)return a;if(a[0]>b[0]){int[] t=a;a=b;b=t;}
        return (long)a[1]+1>=b[0]?new int[]{a[0],Math.max(a[1],b[1])}:new int[]{a[0],a[1],b[0],b[1]};
    }
    private static int[] clip(int from,int to,int center,int radius){long low=Math.max((long)from,(long)center-radius),high=Math.min((long)to,(long)center+radius);return low>high?new int[0]:new int[]{(int)low,(int)high};}
    public List<Long> claim(int maximum){
        if(maximum<0||maximum>128)throw new IllegalArgumentException("chunk_manifest_batch_limit");seen.trim(this,WORK_BUDGET);
        int budget=Math.min(maximum,limit-outstanding.size());List<Long> result=new ArrayList<>();
        int faultBudget=maximum==1?(faultTurn++%4==0?1:0):Math.max(1,maximum/4),faults=0;
        for(int n=0;n<WORK_BUDGET&&result.size()<budget&&(!retryKeys.isEmpty()||!faultKeys.isEmpty()||cursor.hasNext());n++){long key;
            if(!retryKeys.isEmpty()){var iterator=retryKeys.iterator();key=iterator.next();iterator.remove();}
            else if(!faultKeys.isEmpty()&&(faults<faultBudget||!cursor.hasNext())){var iterator=faultKeys.iterator();key=iterator.next();iterator.remove();faults++;}
            else if(cursor.hasNext())key=cursor.next();else break;
            if(!inside(key)||!seen.add(key))continue;outstanding.add(key);result.add(key);
        }
        return List.copyOf(result);
    }
    public void complete(long key){outstanding.remove(key);}
    public void retry(Collection<Long> keys){for(long key:keys){seen.remove(key);outstanding.remove(key);if(inside(key)&&retryKeys.size()<4096)retryKeys.add(key);else if(inside(key))cursor=views();}}
    public void faultRetry(Collection<Long> keys){if(keys.size()>faultCapacity())throw new IllegalArgumentException("chunk_fault_queue_limit");for(long key:keys)if(inside(key)){seen.remove(key);outstanding.remove(key);faultKeys.add(key);}}
    public int faultCapacity(){return 4096-faultKeys.size();}
    public int faultPending(){return faultKeys.size();}
    public void delivered(long key){if(inside(key)){seen.add(key);outstanding.remove(key);retryKeys.remove(key);faultKeys.remove(key);}}
    public void reset(){x=Integer.MIN_VALUE;z=0;secondaryX=null;seen.clear();outstanding.clear();retryKeys.clear();faultKeys.clear();faultTurn=0;cursor=Collections.emptyIterator();}
    public int outstanding(){return outstanding.size();}
    public long known(){return seen.size();}
    public int coverageRows(){return seen.rows();}
    public int deferred(){return retryKeys.size();}
    public boolean exhausted(){return retryKeys.isEmpty()&&faultKeys.isEmpty()&&!cursor.hasNext();}
    private Iterator<Long> views(){
        Iterator<Long> primary=coordinates(x,z,radius),secondary=secondaryX==null?Collections.emptyIterator():coordinates(secondaryX,secondaryZ,secondaryRadius);
        return new Iterator<>(){public boolean hasNext(){return primary.hasNext()||secondary.hasNext();}public Long next(){return primary.hasNext()?primary.next():secondary.next();}};
    }
    static Iterator<Long> coordinates(int x,int z,int radius){
        return new Iterator<>(){
            int distance,t,slot;
            public boolean hasNext(){return distance<=radius;}
            public Long next(){
                if(!hasNext())throw new NoSuchElementException();if(distance==0){distance=1;return BlockOverlays.key(x,z);}int d=distance,dx,dz;
                if(t==0){dx=switch(slot){case 1->-d;case 2->d;default->0;};dz=switch(slot){case 0->-d;case 3->d;default->0;};}
                else if(t==d){dx=(slot&1)==0?-d:d;dz=slot<2?-d:d;}
                else{dx=switch(slot){case 0,6->-t;case 1,7->t;case 2,4->-d;default->d;};dz=switch(slot){case 0,1->-d;case 2,3->-t;case 4,5->t;default->d;};}
                if(++slot==(t==0||t==d?4:8)){slot=0;if(++t>d){t=0;distance++;}}return BlockOverlays.key(x+dx,z+dz);
            }
        };
    }
}
