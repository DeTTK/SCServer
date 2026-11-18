package szserver.commands;

import szserver.world.World;
import java.util.*;
import java.util.function.*;

/** Immutable coordinate views. Cuboids and their source-first union retain no per-cell objects.
 * Ordering is exactly the old y/z/x enumeration followed by destination cells outside the source. */
abstract class BlockCells extends AbstractList<World.Block> implements RandomAccess {
    static final int MAX_CELLS=2_000_000;
    static Cuboid cuboid(int x,int y,int z,int xx,int yy,int zz){return new Cuboid(x,y,z,xx,yy,zz);}
    static BlockCells union(List<World.Block> source,List<World.Block> destination){
        if(!(source instanceof Cuboid a)||!(destination instanceof Cuboid b))throw new IllegalArgumentException("cuboid_union_required");
        return new Union(a,b);
    }
    @Override public final boolean contains(Object value){return indexOf(value)>=0;}
    @Override public final int lastIndexOf(Object value){return indexOf(value);}
    @Override public final World.Block set(int index,World.Block value){throw immutable();}
    @Override public final void add(int index,World.Block value){throw immutable();}
    @Override public final boolean addAll(Collection<? extends World.Block> values){throw immutable();}
    @Override public final boolean addAll(int index,Collection<? extends World.Block> values){throw immutable();}
    @Override public final World.Block remove(int index){throw immutable();}
    @Override public final boolean remove(Object value){throw immutable();}
    @Override public final boolean removeAll(Collection<?> values){throw immutable();}
    @Override public final boolean retainAll(Collection<?> values){throw immutable();}
    @Override public final boolean removeIf(Predicate<? super World.Block> predicate){throw immutable();}
    @Override public final void replaceAll(UnaryOperator<World.Block> operator){throw immutable();}
    @Override public final void sort(Comparator<? super World.Block> comparator){throw immutable();}
    @Override public final void clear(){throw immutable();}
    @Override protected final void removeRange(int from,int to){throw immutable();}
    private static UnsupportedOperationException immutable(){return new UnsupportedOperationException("immutable_block_cells");}
    static final class Cuboid extends BlockCells {
        final int x,y,z,xx,yy,zz,width,depth,layer,count;
        private Cuboid(int x,int y,int z,int xx,int yy,int zz){
            if(x<-szserver.world.MapBounds.HORIZONTAL||xx>szserver.world.MapBounds.HORIZONTAL||x>xx||z<-szserver.world.MapBounds.HORIZONTAL||zz>szserver.world.MapBounds.HORIZONTAL||z>zz||y<0||yy>255||y>yy)throw new IllegalArgumentException("invalid_block_bounds");
            long volume=((long)xx-x+1)*((long)zz-z+1)*(yy-y+1);
            if(volume>MAX_CELLS)throw new IllegalArgumentException("invalid_edit_count");
            this.x=x;this.y=y;this.z=z;this.xx=xx;this.yy=yy;this.zz=zz;width=xx-x+1;depth=zz-z+1;layer=width*depth;count=(int)volume;
        }
        @Override public int size(){return count;}
        @Override public World.Block get(int index){Objects.checkIndex(index,count);int row=index%layer;return new World.Block(x+row%width,y+index/layer,z+row/width);}
        @Override public int indexOf(Object value){
            if(!(value instanceof World.Block p)||p.x()<x||p.x()>xx||p.y()<y||p.y()>yy||p.z()<z||p.z()>zz)return -1;
            return (p.y()-y)*layer+(p.z()-z)*width+p.x()-x;
        }
        Cuboid translated(int dx,int dy,int dz){
            long nx=(long)x+dx,ny=(long)y+dy,nz=(long)z+dz,nxx=(long)xx+dx,nyy=(long)yy+dy,nzz=(long)zz+dz;
            if(nx<-szserver.world.MapBounds.HORIZONTAL||nxx>szserver.world.MapBounds.HORIZONTAL||ny<0||nyy>255||nz<-szserver.world.MapBounds.HORIZONTAL||nzz>szserver.world.MapBounds.HORIZONTAL)throw new IllegalArgumentException("edit_destination_out_of_bounds");
            return cuboid((int)nx,(int)ny,(int)nz,(int)nxx,(int)nyy,(int)nzz);
        }
    }
    private static final class Union extends BlockCells {
        final Cuboid a,b;
        final int ix,iy,iz,iw,ih,id,overlap,count,lowerCells,outsideLayer;
        Union(Cuboid a,Cuboid b){
            this.a=a;this.b=b;ix=Math.max(a.x,b.x);iy=Math.max(a.y,b.y);iz=Math.max(a.z,b.z);
            iw=Math.max(0,Math.min(a.xx,b.xx)-ix+1);ih=Math.max(0,Math.min(a.yy,b.yy)-iy+1);id=Math.max(0,Math.min(a.zz,b.zz)-iz+1);
            overlap=iw*ih*id;long size=(long)a.size()+b.size()-overlap;if(size>MAX_CELLS)throw new IllegalArgumentException("invalid_edit_count");count=(int)size;
            lowerCells=overlap==0?0:(iy-b.y)*b.layer;outsideLayer=b.layer-iw*id;
        }
        @Override public int size(){return count;}
        @Override public World.Block get(int index){
            Objects.checkIndex(index,count);if(index<a.size())return a.get(index);int k=index-a.size();if(overlap==0||k<lowerCells)return b.get(k);k-=lowerCells;
            int middle=ih*outsideLayer;
            if(k>=middle)return b.get((iy-b.y+ih)*b.layer+k-middle);
            int dy=k/outsideLayer,row=k%outsideLayer,prefix=(iz-b.z)*b.width,z,x;
            if(row<prefix){z=row/b.width;x=row%b.width;}
            else{
                row-=prefix;int rowWidth=b.width-iw,middleRows=id*rowWidth;
                if(row<middleRows){z=iz-b.z+row/rowWidth;int column=row%rowWidth;x=column<ix-b.x?column:column+iw;}
                else{row-=middleRows;z=iz-b.z+id+row/b.width;x=row%b.width;}
            }
            return new World.Block(b.x+x,iy+dy,b.z+z);
        }
        @Override public int indexOf(Object value){
            int original=a.indexOf(value);if(original>=0)return original;int ordinal=b.indexOf(value);if(ordinal<0)return -1;if(overlap==0)return a.size()+ordinal;
            World.Block p=(World.Block)value;int removed=clamp(p.y()-iy,ih)*iw*id;
            if(p.y()>=iy&&p.y()<iy+ih){removed+=clamp(p.z()-iz,id)*iw;if(p.z()>=iz&&p.z()<iz+id)removed+=clamp(p.x()-ix,iw);}
            return a.size()+ordinal-removed;
        }
        private static int clamp(int value,int max){return Math.max(0,Math.min(value,max));}
    }
}
