import java.nio.file.*;
import org.objectweb.asm.*;

/** Dynamic native envelope types, without new proprietary packet registry IDs. */
public final class BuildEnvironmentPackets implements Opcodes {
    public static void main(String[] args)throws Exception {
        for(boolean response:new boolean[]{false,true}){
            String name="szmods/bridge/Environment"+(response?"Snapshot":"Input");ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);out.visit(V17,ACC_PUBLIC|ACC_FINAL,name,null,"499058674",null);out.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
            MethodVisitor m=out.visitMethod(ACC_PUBLIC,"<init>",response?"()V":"([B)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);if(!response){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,name,"payload","[B");}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
            m=out.visitMethod(ACC_PUBLIC,response?"extends":"switch",response?"(Ljava/io/DataInput;)V":"(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});m.visitCode();if(response){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge","read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,name,"payload","[B");}else{m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"payload","[B");m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
            if(response){m=out.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"payload","[B");m.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge","receive","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);}else{m=out.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);}m.visitMaxs(0,0);m.visitEnd();out.visitEnd();Path file=Path.of(args[0],name+".class");Files.createDirectories(file.getParent());Files.write(file,out.toByteArray());
        }
        // Keep native initialization and codecs, but do not reload the entire bundled catalog
        // immediately before replacing it with the authoritative downloaded JSON.
        String name="szmods/bridge/EnvironmentWeatherManager";ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);out.visit(V17,ACC_PUBLIC|ACC_FINAL,name,null,"1049587979",null);
        out.visitField(ACC_PUBLIC,"fallbackStyle","L1914600486;",null,null).visitEnd();
        MethodVisitor constructor=out.visitMethod(ACC_PUBLIC,"<init>","(Z)V",null,null);constructor.visitCode();constructor.visitVarInsn(ALOAD,0);constructor.visitVarInsn(ILOAD,1);constructor.visitMethodInsn(INVOKESPECIAL,"1049587979","<init>","(Z)V",false);constructor.visitInsn(RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        MethodVisitor load=out.visitMethod(ACC_PUBLIC,"package","(Z)V",null,null);load.visitCode();load.visitInsn(RETURN);load.visitMaxs(0,0);load.visitEnd();out.visitEnd();Path file=Path.of(args[0],name+".class");Files.createDirectories(file.getParent());Files.write(file,out.toByteArray());
    }
}
