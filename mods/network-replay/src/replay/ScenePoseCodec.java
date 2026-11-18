package replay;

import java.lang.reflect.Field;

/** Transfer final native bone arrays; evaluating a graph or advancing physics is unnecessary. */
final class ScenePoseCodec {
    private static final class ConstructorHolder {
        static final java.lang.reflect.Constructor<?> VALUE;
        static {try{VALUE=Native.type("1118769848").getConstructor(int.class,int.class);}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    }
    static Object create(SceneTracks.Bones bones)throws Exception{ReplayPerformance.enter("scene-pose-allocate");try{return ConstructorHolder.VALUE.newInstance(bones.boneCount(),bones.curveCount());}finally{ReplayPerformance.exit("scene-pose-allocate");}}
    private static ClassValue<Field[]> fields(String...names){return new ClassValue<>(){
        protected Field[] computeValue(Class<?> type){try{
            Field[] result=new Field[names.length];for(int n=0;n<names.length;n++){
                result[n]=Native.reflectedField(type,names[n]);
                if(result[n].getType()!=float.class)throw new IllegalArgumentException("Native bone field layout changed: "+type.getName()+"."+names[n]);
            }return result;
        }catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Native bone field layout changed: "+type.getName(),failure);}catch(Exception failure){throw new IllegalArgumentException("Native bone field layout changed: "+type.getName(),failure);}}
    };}
    private static final ClassValue<Field[]> POSITIONS=fields("assert","byte","catch"),ROTATIONS=fields("public","implements","float","catch");
    private static final ClassValue<ScenePoseTransfer.Writer> POSITION_WRITERS=new ClassValue<>(){protected ScenePoseTransfer.Writer computeValue(Class<?> type){return ScenePoseTransfer.create(type,POSITIONS.get(type),"position");}};
    private static final ClassValue<ScenePoseTransfer.Writer> ROTATION_WRITERS=new ClassValue<>(){protected ScenePoseTransfer.Writer computeValue(Class<?> type){return ScenePoseTransfer.create(type,ROTATIONS.get(type),"rotation");}};
    private static Field[] validate(Object[] values,ClassValue<Field[]> schema){
        if(values.length==0)return new Field[0];
        if(values[0]==null)throw new IllegalArgumentException("Null native bone");
        Class<?> type=values[0].getClass();Field[] result=schema.get(type);
        for(Object value:values)if(value==null||value.getClass()!=type)throw new IllegalArgumentException("Heterogeneous native bone layout");
        return result;
    }
    static SceneTracks.Bones read(Object pose)throws Exception {
        Object[] p=(Object[])Native.field(pose,"for"),q=(Object[])Native.field(pose,"double");float[] c=(float[])Native.field(pose,"strictfp");float[] positions=new float[p.length*3],rotations=new float[q.length*4];
        Field[] axes=validate(p,POSITIONS),rotation=validate(q,ROTATIONS);
        for(int n=0;n<p.length;n++)for(int k=0;k<3;k++)positions[n*3+k]=axes[k].getFloat(p[n]);
        for(int n=0;n<q.length;n++)for(int k=0;k<4;k++)rotations[n*4+k]=rotation[k].getFloat(q[n]);
        return new SceneTracks.Bones(positions,rotations,c);
    }
    /** The caller owns this pose and excludes native graph workers while publishing it. */
    static void write(Object pose,SceneTracks.Bones value)throws Exception {
        ReplayPerformance.enter("scene-bone-transfer");try{
        Object[] p=(Object[])Native.field(pose,"for"),q=(Object[])Native.field(pose,"double");float[] c=(float[])Native.field(pose,"strictfp");
        if(p.length!=value.boneCount()||q.length!=value.boneCount()||c.length!=value.curveCount())throw new IllegalArgumentException("Scene/native skeleton layout mismatch");
        // Validate every writable field before changing any bone.
        Field[] axes=validate(p,POSITIONS),rotation=validate(q,ROTATIONS);
        if(p.length>0)POSITION_WRITERS.get(p[0].getClass()).write(p,value);
        if(q.length>0)ROTATION_WRITERS.get(q[0].getClass()).write(q,value);
        value.copyCurves(c);
        }finally{ReplayPerformance.exit("scene-bone-transfer");}
    }
    static double number(Object value,String field)throws Exception {return ((Number)Native.field(value,field)).doubleValue();}
}
