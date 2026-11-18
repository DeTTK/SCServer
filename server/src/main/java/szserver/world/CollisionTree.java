package szserver.world;

import java.util.*;
import java.util.function.Predicate;

/** Immutable spatial index. Physical copies are packed; native double precision is preserved. */
public final class CollisionTree {
    public static final long DEFAULT_BYTES=256L*1024*1024;
    public record Item(int homeX,int homeZ,boolean container,int occurrence,SceneCollision.Shape shape,ActorContacts.Obstacle feedback,boolean ladder,ActorSceneObject object){public Item{Objects.requireNonNull(shape);}public Item(int x,int z,boolean container,int occurrence,SceneCollision.Shape shape){this(x,z,container,occurrence,shape,null,false,null);}public Item(int x,int z,boolean container,int occurrence,SceneCollision.Shape shape,ActorContacts.Obstacle feedback){this(x,z,container,occurrence,shape,feedback,false,null);}public Item(int x,int z,boolean container,int occurrence,SceneCollision.Shape shape,ActorContacts.Obstacle feedback,boolean ladder){this(x,z,container,occurrence,shape,feedback,ladder,null);}public UUID identity(){return shape.identity();}}
    private static final int PAGE=128,STRIDE=18;
    private static final class Page {
        final double[] geometry;final long[] identities;final int[] metadata;final byte[] containers;ActorContacts.Obstacle[] feedback;ActorSceneObject[] objects;
        Page(int count){geometry=new double[count*STRIDE];identities=new long[count*2];metadata=new int[count*4];containers=new byte[count];}
        long bytes(){return 128L+8L*geometry.length+8L*identities.length+4L*metadata.length+containers.length+(feedback==null?0:48L*feedback.length)+(objects==null?0:8L*objects.length);}
    }
    private final Page[] pages;private final List<List<SceneCollision.Box>> assets;private final int count;private final long retained;
    private final List<CollisionTree> children;
    private final int[] order,nodes;private final double[] bounds;
    private CollisionTree(Page[] pages,List<List<SceneCollision.Box>> assets,int count,long retained){
        children=List.of();
        this.pages=pages;this.assets=assets;this.count=count;this.retained=retained;
        order=new int[count];for(int n=0;n<count;n++)order[n]=n;int size=nodeCount(count);nodes=new int[Math.multiplyExact(size,4)];bounds=new double[Math.multiplyExact(size,6)];if(count!=0)build(0,0,count,0);
    }
    public static CollisionTree empty(){return new CollisionTree(new Page[0],List.of(),0,0);}
    private CollisionTree(List<CollisionTree> children){this.children=List.copyOf(children);pages=new Page[0];assets=List.of();count=children.stream().mapToInt(CollisionTree::size).sum();retained=children.stream().mapToLong(CollisionTree::retainedBytes).sum();order=new int[0];nodes=new int[0];bounds=new double[0];}
    /** Immutable forest; replacement keeps untouched packed trees and releases replaced pages. */
    public static CollisionTree forest(List<CollisionTree> trees){SceneCollision.worker();return new CollisionTree(trees);}
    public void forEachItem(java.util.function.Consumer<Item> visitor){SceneCollision.worker();if(!children.isEmpty())for(var tree:children)tree.forEachItem(visitor);else for(int n=0;n<count;n++)visitor.accept(item(n));}
    public int size(){return count;}public long retainedBytes(){return retained;}
    /** Primitive/page storage, excluding shared asset geometry charged by the builder. */
    public long packedBytes(){long bytes=160L+8L*pages.length+4L*order.length+4L*nodes.length+8L*bounds.length;for(Page page:pages)bytes+=page.bytes();for(var tree:children)bytes+=tree.packedBytes();return bytes;}
    public static final class Builder {
        private final long limit;private long bytes;private int count;private boolean finished;
        private final boolean sharedAssets;
        private final List<Page> pages=new ArrayList<>();private final List<List<SceneCollision.Box>> assets=new ArrayList<>();private final Map<List<SceneCollision.Box>,Integer> assetIds=new IdentityHashMap<>();
        private final Set<SceneRegistry.Definition> definitions=Collections.newSetFromMap(new IdentityHashMap<>());
        public Builder(long limit){this(limit,false);}public Builder(long limit,boolean sharedAssets){if(limit<1024||limit>1024L*1024*1024)throw new IllegalArgumentException("scene_collision_memory_limit");this.limit=limit;this.sharedAssets=sharedAssets;bytes=320;}
        public void reserve(long extra){SceneCollision.worker();open();if(extra<0||extra>limit-bytes)throw new IllegalArgumentException("scene_collision_memory_limit");bytes+=extra;}
        private void open(){if(finished)throw new IllegalStateException("scene_collision_builder_finished");}
        public void retainAsset(List<SceneCollision.Box> boxes){SceneCollision.worker();open();if(!assetIds.containsKey(boxes)){reserve(96L+(sharedAssets?0:80L*boxes.size()));assetIds.put(boxes,assets.size());assets.add(boxes);}}
        public void add(Item item){
            SceneCollision.worker();open();retainAsset(item.shape().assetBoxes());
            if(item.object()!=null){reserve(item.object().descriptorBytes());if(definitions.add(item.object().definition()))reserve(item.object().definitionBytes());}
            // Charge allocated page capacity, including unused final rows. Each row owns
            // 177 bytes of primitive arrays; 36 also covers order + the worst balanced BVH.
            if(count%PAGE==0){
                int rowBytes=213+(item.feedback()==null?0:48)+(item.object()==null?0:8);
                int capacity=(int)Math.min(PAGE,(limit-bytes-136)/rowBytes);if(capacity<1)throw new IllegalArgumentException("scene_collision_memory_limit");
                reserve(136L+213L*capacity);pages.add(new Page(capacity));
            }
            Page page=pages.get(count/PAGE);int row=count%PAGE;if(row>=page.containers.length)throw new IllegalArgumentException("scene_collision_memory_limit");
            if(item.feedback()!=null&&page.feedback==null){reserve(48L*page.containers.length);page.feedback=new ActorContacts.Obstacle[page.containers.length];}
            if(item.object()!=null&&page.objects==null){reserve(8L*page.containers.length);page.objects=new ActorSceneObject[page.containers.length];}
            item.shape().pack(page.geometry,row*STRIDE);UUID id=item.identity();page.identities[row*2]=id.getMostSignificantBits();page.identities[row*2+1]=id.getLeastSignificantBits();int offset=row*4;
            page.metadata[offset]=item.homeX();page.metadata[offset+1]=item.homeZ();page.metadata[offset+2]=item.occurrence();page.metadata[offset+3]=assetIds.get(item.shape().assetBoxes());page.containers[row]=(byte)((item.container()?1:0)|(item.ladder()?2:0));if(item.feedback()!=null)page.feedback[row]=item.feedback();if(item.object()!=null)page.objects[row]=item.object();count++;
        }
        public CollisionTree finish(){SceneCollision.worker();open();finished=true;return new CollisionTree(pages.toArray(Page[]::new),List.copyOf(assets),count,bytes);}
        public int size(){return count;}public long retainedBytes(){return bytes;}
    }
    private static int nodeCount(int size){return size==0?0:size<=8?1:1+nodeCount(size/2)+nodeCount(size-size/2);}
    private double coordinate(int index,int field){Page page=pages[index/PAGE];return page.geometry[(index%PAGE)*STRIDE+field];}
    private int build(int node,int from,int to,int depth){
        int at=node*6;for(int axis=0;axis<3;axis++){double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;for(int n=from;n<to;n++){low=Math.min(low,coordinate(order[n],12+axis));high=Math.max(high,coordinate(order[n],15+axis));}bounds[at+axis]=low;bounds[at+3+axis]=high;}
        int offset=node*4;nodes[offset]=from;nodes[offset+1]=to;if(to-from<=8){nodes[offset+2]=-1;nodes[offset+3]=-1;return node+1;}
        int middle=(from+to)>>>1;select(from,to,middle,depth%2);nodes[offset+2]=node+1;int right=build(node+1,from,middle,depth+1);nodes[offset+3]=right;return build(right,middle,to,depth+1);
    }
    private int compare(int left,int right,int axis){
        int component=axis==0?0:2;int result=Double.compare((coordinate(left,12+component)+coordinate(left,15+component))*.5,(coordinate(right,12+component)+coordinate(right,15+component))*.5);if(result!=0)return result;
        Page a=pages[left/PAGE],b=pages[right/PAGE];int ar=left%PAGE,br=right%PAGE;
        for(int field=0;field<2;field++){result=Integer.compare(a.metadata[ar*4+field],b.metadata[br*4+field]);if(result!=0)return result;}
        for(int field=0;field<2;field++){result=Long.compare(a.identities[ar*2+field],b.identities[br*2+field]);if(result!=0)return result;}
        result=Byte.compare(a.containers[ar],b.containers[br]);return result!=0?result:Integer.compare(a.metadata[ar*4+2],b.metadata[br*4+2]);
    }
    private void swap(int a,int b){int value=order[a];order[a]=order[b];order[b]=value;}
    /** Bounded partition depth and primitive heapsort fallback for adversarial ordering. */
    private void select(int from,int to,int middle,int axis){
        int budget=2*(32-Integer.numberOfLeadingZeros(to-from));
        while(to-from>1){if(budget--==0){heapSort(from,to,axis);return;}int pivot=order[(from+to)>>>1],low=from,at=from,high=to;
            while(at<high){int result=compare(order[at],pivot,axis);if(result<0)swap(low++,at++);else if(result>0)swap(at,--high);else at++;}
            if(middle<low)to=low;else if(middle>=high)from=high;else return;
        }
    }
    private void heapSort(int from,int to,int axis){int size=to-from;for(int n=size/2-1;n>=0;n--)sift(from,size,n,axis);for(int end=size-1;end>0;end--){swap(from,from+end);sift(from,end,0,axis);}}
    private void sift(int from,int size,int root,int axis){while(root*2+1<size){int child=root*2+1;if(child+1<size&&compare(order[from+child],order[from+child+1],axis)<0)child++;if(compare(order[from+root],order[from+child],axis)>=0)return;swap(from+root,from+child);root=child;}}
    private Item item(int index){Page page=pages[index/PAGE];int row=index%PAGE,offset=row*4;UUID id=new UUID(page.identities[row*2],page.identities[row*2+1]);return new Item(page.metadata[offset],page.metadata[offset+1],(page.containers[row]&1)!=0,page.metadata[offset+2],SceneCollision.Shape.unpack(id,assets.get(page.metadata[offset+3]),page.geometry,row*STRIDE),page.feedback==null?null:page.feedback[row],(page.containers[row]&2)!=0,page.objects==null?null:page.objects[row]);}
    /** Worker broad phase visits the tree, rather than decoding the complete map for each actor. */
    public void select(SceneCollision.Box area,Predicate<Item> include,java.util.function.Consumer<Item> visitor){
        SceneCollision.worker();if(!children.isEmpty()){for(var tree:children)tree.select(area,include,visitor);}else if(count!=0)select(0,area,include,visitor);
    }
    private static boolean overlap(double[] box,int at,SceneCollision.Box q){return box[at]<=q.xx()&&box[at+3]>=q.x()&&box[at+1]<=q.yy()&&box[at+4]>=q.y()&&box[at+2]<=q.zz()&&box[at+5]>=q.z();}
    private void select(int node,SceneCollision.Box area,Predicate<Item> include,java.util.function.Consumer<Item> visitor){
        if(!overlap(bounds,node*6,area))return;int at=node*4,left=nodes[at+2];if(left>=0){select(left,area,include,visitor);select(nodes[at+3],area,include,visitor);return;}
        for(int n=nodes[at];n<nodes[at+1];n++){int index=order[n];Page page=pages[index/PAGE];if(!overlap(page.geometry,index%PAGE*STRIDE+12,area))continue;Item candidate=item(index);if(include.test(candidate))visitor.accept(candidate);}
    }
    public boolean hit(World.Vec origin,World.Vec direction,double distance,Predicate<Item> include){SceneCollision.worker();Objects.requireNonNull(include);SceneCollision.ray(origin,direction,new SceneCollision.Box(0,0,0,0,0,0),distance);if(!children.isEmpty()){for(var tree:children)if(tree.hit(origin,direction,distance,include))return true;return false;}return count!=0&&hit(0,origin,direction,distance,include);}
    private static boolean intersects(double[] box,int offset,World.Vec origin,World.Vec direction,double maximum){
        double near=0,far=maximum;for(int axis=0;axis<3;axis++){double start=axis==0?origin.x():axis==1?origin.y():origin.z(),delta=axis==0?direction.x():axis==1?direction.y():direction.z(),low=box[offset+axis],high=box[offset+axis+3];if(Math.abs(delta)<1e-9){if(start<low||start>high)return false;continue;}double a=(low-start)/delta,c=(high-start)/delta;near=Math.max(near,Math.min(a,c));far=Math.min(far,Math.max(a,c));if(near>far)return false;}return true;
    }
    private boolean hit(int node,World.Vec origin,World.Vec direction,double distance,Predicate<Item> include){
        if(!intersects(bounds,node*6,origin,direction,distance))return false;int offset=node*4,left=nodes[offset+2];
        if(left>=0)return hit(left,origin,direction,distance,include)||hit(nodes[offset+3],origin,direction,distance,include);
        for(int n=nodes[offset];n<nodes[offset+1];n++){int index=order[n];Page page=pages[index/PAGE];if(page.objects!=null&&page.objects[index%PAGE]!=null||(page.containers[index%PAGE]&2)!=0||page.feedback!=null&&page.feedback[index%PAGE]!=null||!intersects(page.geometry,(index%PAGE)*STRIDE+12,origin,direction,distance))continue;Item candidate=item(index);if(include.test(candidate)&&candidate.shape().hit(origin,direction,distance))return true;}return false;
    }
}
