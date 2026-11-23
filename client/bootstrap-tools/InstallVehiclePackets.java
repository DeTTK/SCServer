import java.nio.file.*;
import org.objectweb.asm.*;

/** Adds two bridge types without changing any native game class. */
public final class InstallVehiclePackets implements Opcodes {
    public static void main(String[] args)throws Exception{for(boolean reply:new boolean[]{false,true})write(Path.of(args[0]),reply);}
    private static void write(Path root,boolean reply)throws Exception{
        String n="szmods/bridge/Vehicle"+(reply?"Snapshot":"Input");ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>",reply?"()V":"([B)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);if(!reply){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,reply?"extends":"switch",reply?"(Ljava/io/DataInput;)V":"(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});m.visitCode();if(reply){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/VehiclePackets","read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}else{m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        if(reply){m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/VehiclePackets","receive","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);}else{m=w.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);}m.visitMaxs(0,0);m.visitEnd();w.visitEnd();Path out=root.resolve(n+".class");Files.createDirectories(out.getParent());Files.write(out,w.toByteArray());
    }
}
