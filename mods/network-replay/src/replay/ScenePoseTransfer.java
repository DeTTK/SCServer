package replay;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;

/** Compile public, validated native float fields into a direct array transfer.
 * Native skeleton layout and selected data are still checked before any write. */
final class ScenePoseTransfer {
    interface Writer {void write(Object[] target,SceneTracks.Bones source)throws Exception;}
    static final AtomicInteger sequence=new AtomicInteger();
    static Writer create(Class<?> type,Field[] fields,String source){
        for(Field field:fields)if(!Modifier.isPublic(field.getModifiers())||Modifier.isFinal(field.getModifiers())||!Modifier.isPublic(field.getDeclaringClass().getModifiers()))return reflected(fields,source);
        try{
            String name="replay/PoseArrayTransfer"+sequence.incrementAndGet();ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,name,null,"java/lang/Object",new String[]{"replay/ScenePoseTransfer$Writer"});
            MethodVisitor constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);constructor.visitCode();constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
            MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"write","([Ljava/lang/Object;Lreplay/SceneTracks$Bones;)V",null,null);method.visitCode();method.visitInsn(Opcodes.ICONST_0);method.visitVarInsn(Opcodes.ISTORE,3);
            Label loop=new Label(),done=new Label();method.visitLabel(loop);method.visitVarInsn(Opcodes.ILOAD,3);method.visitVarInsn(Opcodes.ALOAD,1);method.visitInsn(Opcodes.ARRAYLENGTH);method.visitJumpInsn(Opcodes.IF_ICMPGE,done);
            method.visitVarInsn(Opcodes.ALOAD,1);method.visitVarInsn(Opcodes.ILOAD,3);method.visitInsn(Opcodes.AALOAD);method.visitTypeInsn(Opcodes.CHECKCAST,type.getName().replace('.','/'));method.visitVarInsn(Opcodes.ASTORE,4);
            for(int axis=0;axis<fields.length;axis++){
                method.visitVarInsn(Opcodes.ALOAD,4);method.visitVarInsn(Opcodes.ALOAD,2);method.visitVarInsn(Opcodes.ILOAD,3);method.visitLdcInsn(fields.length);method.visitInsn(Opcodes.IMUL);method.visitLdcInsn(axis);method.visitInsn(Opcodes.IADD);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"replay/SceneTracks$Bones",source,"(I)F",false);Field field=fields[axis];method.visitFieldInsn(Opcodes.PUTFIELD,field.getDeclaringClass().getName().replace('.','/'),field.getName(),"F");
            }
            method.visitIincInsn(3,1);method.visitJumpInsn(Opcodes.GOTO,loop);method.visitLabel(done);method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();
            Class<?> generated=MethodHandles.lookup().defineHiddenClass(writer.toByteArray(),true).lookupClass();return (Writer)generated.getDeclaredConstructor().newInstance();
        }catch(ReflectiveOperationException|LinkageError failure){throw new IllegalStateException("Cannot compile validated native pose transfer",failure);}
    }
    static Writer reflected(Field[] fields,String source){return (target,value)->{for(int n=0;n<target.length;n++)for(int axis=0;axis<fields.length;axis++)fields[axis].setFloat(target[n],source.equals("position")?value.position(n*fields.length+axis):value.rotation(n*fields.length+axis));};}
}
