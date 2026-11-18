package replay;
import java.lang.reflect.*;import java.util.*;
/** Reuses only a consecutive identical frustum at the same displayed frame. */
final class SceneTerrainCull {
    interface Draw {void run()throws Exception;}
    private static final ClassValue<Field[]> planes=new ClassValue<>(){protected Field[] computeValue(Class<?> type){
        return Arrays.stream(type.getFields()).filter(f->!Modifier.isStatic(f.getModifiers())&&f.getType()==float.class).sorted(Comparator.comparing(Field::getName)).toArray(Field[]::new);
    }};
    Object owner,list,backing;int size;long epoch=Long.MIN_VALUE,time=Long.MIN_VALUE;int[] signature;
    long evaluations,reuses;
    void invalidate(){owner=null;signature=null;}
    void update(Object grid,Object view)throws Exception {
        Object values=Native.field(grid,"catch");Object array=Native.field(values,"const");int length=((Number)Native.field(values,"try")).intValue();
        Object frustum=Native.call(view,"private","1598224942");
        update(grid,values,array,length,frustum,ReplayRender.sceneRenderEpoch,ReplayClock.presentationNanos(),()->Native.call(grid,"short","void",view));
    }
    void update(Object grid,Object values,Object array,int length,Object frustum,long frame,long selectedTime,Draw draw)throws Exception {
        Field[] fields=planes.get(frustum.getClass());int[] next=new int[fields.length];for(int n=0;n<fields.length;n++)next[n]=Float.floatToRawIntBits(fields[n].getFloat(frustum));
        if(owner==grid&&list==values&&backing==array&&size==length&&epoch==frame&&time==selectedTime&&Arrays.equals(signature,next)){reuses++;return;}
        draw.run();evaluations++;owner=grid;list=values;backing=array;size=length;epoch=frame;time=selectedTime;signature=next;
    }
}
