package vr;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import static vr.NativeAccess.*;

/** Cached decoded asset bounds; raw native coordinates are checked before reuse. */
final class NativeBounds {
    private record Entry(long[] bits,double[] coordinates){}
    private final IdentityHashMap<Object,Entry> cache=new IdentityHashMap<>();
    private Field[] fields;
    double[] read(Object box)throws Exception {
        if(box==null||box.getClass()!=type("1083772398"))return null;
        if(fields==null){String[] names={"final","enum","long","continue","void","byte"};fields=new Field[names.length];for(int i=0;i<names.length;i++)fields[i]=box.getClass().getField(names[i]);}
        Entry entry=cache.get(box);boolean same=entry!=null;
        if(same)for(int i=0;i<fields.length;i++)if(Double.doubleToRawLongBits(fields[i].getDouble(box))!=entry.bits()[i]){same=false;break;}
        if(same)return entry.coordinates();
        long[] bits=new long[6];for(int i=0;i<6;i++)bits[i]=Double.doubleToRawLongBits(fields[i].getDouble(box));
        double[] coordinates={Double.longBitsToDouble(bits[0]+0xd069d003942113a7L),Double.longBitsToDouble(bits[1]+0x32e68c7cd6fd93c9L),Double.longBitsToDouble(bits[2]+0x32e68c7cd6fd93c9L),Double.longBitsToDouble(bits[3]+0xf1cd099211abde89L),Double.longBitsToDouble(bits[4]+0x8766900746bd0d71L),Double.longBitsToDouble((bits[5]+0x7a7a8ffe14d414aL)^0x78084ea73d22c536L)};
        cache.put(box,new Entry(bits,coordinates));return coordinates;
    }
    void clear(){cache.clear();}
}
