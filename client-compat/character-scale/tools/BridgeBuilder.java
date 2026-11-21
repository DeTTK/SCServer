import java.nio.file.*;
import jdk.internal.org.objectweb.asm.*;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

public final class BridgeBuilder {
    public static void main(String[] args)throws Exception {
        for(boolean input:new boolean[]{true,false}){
            String n="playerscale/bridge/Scale"+(input?"Input":"Snapshot");ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
            MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>",input?"([B)V":"()V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);
            if(input){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
            if(input){
                m=w.visitMethod(ACC_PUBLIC,"switch","(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
                m=w.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);m.visitMaxs(0,0);m.visitEnd();
            }else{
                m=w.visitMethod(ACC_PUBLIC,"extends","(Ljava/io/DataInput;)V",null,new String[]{"java/io/IOException"});m.visitCode();m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,n,"payload","[B");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
                m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","receive","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
            }
            w.visitEnd();Path file=Path.of(args[0]).resolve(n+".class");Files.createDirectories(file.getParent());Files.write(file,w.toByteArray());
        }
    }
}
