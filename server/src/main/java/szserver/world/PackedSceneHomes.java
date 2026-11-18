package szserver.world;

import java.util.*;

/** Immutable sorted UUID-to-home table; no permanent UUID, hash-node or per-object home allocation. */
final class PackedSceneHomes extends AbstractMap<UUID,Terrain.SceneHome> {
    private final long[] identities,homes;
    private PackedSceneHomes(Map<UUID,Terrain.SceneHome> source){
        identities=new long[Math.multiplyExact(source.size(),2)];homes=new long[source.size()];int n=0;
        for(var entry:source.entrySet()){UUID id=entry.getKey();Terrain.SceneHome home=entry.getValue();identities[n*2]=id.getMostSignificantBits();identities[n*2+1]=id.getLeastSignificantBits();homes[n++]=BlockOverlays.key(home.x(),home.z());}
        sort(0,n,2*(32-Integer.numberOfLeadingZeros(Math.max(1,n))));
    }
    static Map<UUID,Terrain.SceneHome> freeze(Map<UUID,Terrain.SceneHome> source){return source.isEmpty()?Map.of():new PackedSceneHomes(source);}
    long storageBytes(){return 96L+8L*identities.length+8L*homes.length;}
    @Override public int size(){return homes.length;}
    @Override public Terrain.SceneHome get(Object key){if(!(key instanceof UUID id))return null;int low=0,high=homes.length;long a=id.getMostSignificantBits(),b=id.getLeastSignificantBits();while(low<high){int middle=(low+high)>>>1,c=compare(middle,a,b);if(c<0)low=middle+1;else if(c>0)high=middle;else return home(middle);}return null;}
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    private Terrain.SceneHome home(int index){long key=homes[index];return new Terrain.SceneHome((int)(key>>32),(int)key);}
    private int compare(int index,long a,long b){int c=Long.compare(identities[index*2],a);return c==0?Long.compare(identities[index*2+1],b):c;}
    private int compare(int left,int right){return compare(left,identities[right*2],identities[right*2+1]);}
    private void swap(int a,int b){long value=homes[a];homes[a]=homes[b];homes[b]=value;for(int field=0;field<2;field++){value=identities[a*2+field];identities[a*2+field]=identities[b*2+field];identities[b*2+field]=value;}}
    private void sort(int from,int to,int budget){
        while(to-from>1){if(budget--==0){heapSort(from,to);return;}int middle=(from+to)>>>1;long a=identities[middle*2],b=identities[middle*2+1];int left=from,right=to-1;
            while(left<=right){while(compare(left,a,b)<0)left++;while(compare(right,a,b)>0)right--;if(left<=right){swap(left++,right--);}}
            if(right+1-from<to-left){sort(from,right+1,budget);from=left;}else{sort(left,to,budget);to=right+1;}
        }
    }
    private void heapSort(int from,int to){int size=to-from;for(int n=size/2-1;n>=0;n--)sift(from,size,n);for(int end=size-1;end>0;end--){swap(from,from+end);sift(from,end,0);}}
    private void sift(int from,int size,int root){while(root*2+1<size){int child=root*2+1;if(child+1<size&&compare(from+child,from+child+1)<0)child++;if(compare(from+root,from+child)>=0)return;swap(from+root,from+child);root=child;}}
    @Override public Set<Entry<UUID,Terrain.SceneHome>> entrySet(){return new AbstractSet<>(){
        @Override public int size(){return homes.length;}
        @Override public Iterator<Entry<UUID,Terrain.SceneHome>> iterator(){return new Iterator<>(){int at;public boolean hasNext(){return at<homes.length;}public Entry<UUID,Terrain.SceneHome> next(){if(!hasNext())throw new NoSuchElementException();int n=at++;return Map.entry(new UUID(identities[n*2],identities[n*2+1]),home(n));}};}
    };}
}
