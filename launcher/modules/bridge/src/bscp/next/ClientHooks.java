package bscp.next;

import java.lang.instrument.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import jdk.internal.org.objectweb.asm.*;

/** Adds one timestamp at the existing swap call, without touching input or physics. */
final class ClientHooks implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(name==null||name.startsWith("org/lwjgl/")||name.startsWith("bscp/")||!name.equals("StandardJvmBootstrap")&&!new String(bytes,StandardCharsets.ISO_8859_1).contains("glfwSwapBuffers"))return null;
        try{ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] count={0};reader.accept(new ClassVisitor(Opcodes.ASM8,writer){
            public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){return new MethodVisitor(Opcodes.ASM8,super.visitMethod(access,method,desc,signature,exceptions)){
                public void visitCode(){super.visitCode();if(name.equals("StandardJvmBootstrap")&&method.equals("main")&&desc.equals("([Ljava/lang/String;)V")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","clientArguments","([Ljava/lang/String;)[Ljava/lang/String;",false);super.visitVarInsn(Opcodes.ASTORE,0);count[0]++;}if(name.equals("StandardJvmBootstrap")&&method.equals("headlessControlTick")&&desc.equals("(Ljava/lang/Object;)V")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","frontend","(Ljava/lang/Object;)V",false);count[0]++;}}
                public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){if(Boolean.getBoolean("bscp.qa.passiveObserver")&&owner.equals("StandardJvmBootstrap")&&method.equals("buildControlState")&&desc.equals("(Ljava/lang/Object;J)Ljava/lang/String;")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","passiveControlState",desc,false);count[0]++;return;}if(owner.equals("org/lwjgl/glfw/GLFW")&&method.equals("glfwSwapBuffers")&&desc.equals("(J)V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","frame","()V",false);count[0]++;}super.visitMethodInsn(opcode,owner,method,desc,itf);}
            };}
        },0);if(count[0]==0)return null;Bridge.event("frame-hook",name+" calls="+count[0]);return writer.toByteArray();}catch(Throwable error){Bridge.event("frame-hook-unavailable",name+" "+error);return null;}
    }
}
