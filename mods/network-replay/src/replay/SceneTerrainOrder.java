package replay;

/** Reuses only the immediately preceding exact native distance order. */
final class SceneTerrainOrder {
    Object previousGrid,previousList,previousArray;int previousSize;
    long x,y,z;boolean valid;long sorts,reuses;
    void invalidate(){valid=false;}
    void update(Object grid,Object view)throws Exception {
        Object comparator=Native.field(grid,"continue"),camera=Native.call(view,"double","1738045740");
        Native.call(comparator,"static","void",camera);
        long nextX=Double.doubleToRawLongBits(((Number)Native.field(comparator,"if")).doubleValue()),nextY=Double.doubleToRawLongBits(((Number)Native.field(comparator,"interface")).doubleValue()),nextZ=Double.doubleToRawLongBits(((Number)Native.field(comparator,"super")).doubleValue());
        Object list=Native.field(grid,"catch"),array=Native.field(list,"const");int size=((Number)Native.field(list,"try")).intValue();
        if(valid&&previousGrid==grid&&previousList==list&&previousArray==array&&previousSize==size&&x==nextX&&y==nextY&&z==nextZ){reuses++;return;}
        Native.call(list,"catch","void",comparator,Native.field(grid,"byte"));sorts++;
        previousGrid=grid;previousList=list;previousArray=Native.field(list,"const");previousSize=size;x=nextX;y=nextY;z=nextZ;valid=true;
    }
}
