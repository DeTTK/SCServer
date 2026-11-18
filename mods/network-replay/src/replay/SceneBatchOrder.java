package replay;
import java.lang.reflect.*;import java.util.*;
/** Stable native signed-material-key ordering, with bounded reusable scratch storage. */
final class SceneBatchOrder {
    static final int MAX=262144;
    static final ThreadLocal<SceneBatchOrder> local=ThreadLocal.withInitial(SceneBatchOrder::new);
    Object[] scratch=new Object[0];long[] first=new long[0],second=new long[0];final int[] offsets=new int[256];
    static final ClassValue<Field> keys=new ClassValue<>(){protected Field computeValue(Class<?> type){try{return type.getField("volatile");}catch(NoSuchFieldException e){throw new IllegalStateException("Native material key is missing",e);}}};
    static void sort(Object[] values,int size)throws Exception {
        if(size<2)return;
        if(size>MAX){Arrays.sort(values,0,size);return;}
        Class<?> type=values[0].getClass();if(!type.getName().equals("1889305602")){Arrays.sort(values,0,size);return;}
        Field field=keys.get(type);
        SceneBatchOrder workspace=local.get();workspace.order(values,size,type,field);
    }
    void order(Object[] values,int size,Class<?> type,Field field)throws Exception {
        if(first.length<size){int capacity=Integer.highestOneBit(size-1)<<1;first=new long[capacity];second=new long[capacity];scratch=new Object[capacity];}
        boolean ordered=true;long previous=Long.MIN_VALUE,vary=0,initial=field.getLong(values[0]);
        for(int n=0;n<size;n++){
            if(values[n].getClass()!=type){Arrays.sort(values,0,size);return;}
            long value=field.getLong(values[n]);if(value<previous)ordered=false;previous=value;vary|=value^initial;first[n]=value^Long.MIN_VALUE;
        }
        if(ordered)return;
        Object[] input=values,output=scratch;long[] inputKeys=first,outputKeys=second;
        try{
            for(int shift=0;shift<64;shift+=8){
                if(((vary>>>shift)&255)==0)continue;
                Arrays.fill(offsets,0);for(int n=0;n<size;n++)offsets[(int)(inputKeys[n]>>>shift)&255]++;
                int start=0;for(int n=0;n<256;n++){int count=offsets[n];offsets[n]=start;start+=count;}
                for(int n=0;n<size;n++){long value=inputKeys[n];int at=offsets[(int)(value>>>shift)&255]++;output[at]=input[n];outputKeys[at]=value;}
                Object[] objects=input;input=output;output=objects;long[] keys=inputKeys;inputKeys=outputKeys;outputKeys=keys;
            }
            if(input!=values)System.arraycopy(input,0,values,0,size);
        }finally{Arrays.fill(scratch,0,size,null);}
    }
}
