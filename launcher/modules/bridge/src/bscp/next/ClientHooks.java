package bscp.next;

import java.lang.instrument.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import jdk.internal.org.objectweb.asm.*;

/** Adds one timestamp at the existing swap call, without touching input or physics. */
final class ClientHooks implements ClassFileTransformer {
    private static final String PROTOS_HANDSHAKE = "798464807";

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if (PROTOS_HANDSHAKE.equals(name)) {
            return preserveHandshakeId(bytes);
        }
        if(name==null||name.startsWith("java/")||name.startsWith("jdk/")||name.startsWith("sun/")||name.startsWith("org/lwjgl/")||name.startsWith("bscp/")||!name.equals("StandardJvmBootstrap")&&!new String(bytes,StandardCharsets.ISO_8859_1).contains("glfwSwapBuffers")&&!(Bridge.config.containsKey("relayRemoteBase")&&new String(bytes,StandardCharsets.ISO_8859_1).contains("java/net/InetSocketAddress")))return null;
        try{ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] count={0};reader.accept(new ClassVisitor(Opcodes.ASM8,writer){
            public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){return new MethodVisitor(Opcodes.ASM8,super.visitMethod(access,method,desc,signature,exceptions)){
                public void visitCode(){super.visitCode();if(name.equals("StandardJvmBootstrap")&&method.equals("main")&&desc.equals("([Ljava/lang/String;)V")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","clientArguments","([Ljava/lang/String;)[Ljava/lang/String;",false);super.visitVarInsn(Opcodes.ASTORE,0);count[0]++;}if(name.equals("StandardJvmBootstrap")&&method.equals("headlessControlTick")&&desc.equals("(Ljava/lang/Object;)V")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","frontend","(Ljava/lang/Object;)V",false);count[0]++;}}
                public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){if(Bridge.config.containsKey("relayRemoteBase")&&owner.equals("java/net/InetSocketAddress")&&((method.equals("<init>")&&(desc.equals("(Ljava/lang/String;I)V")||desc.equals("(Ljava/net/InetAddress;I)V")||desc.equals("(I)V")))||(method.equals("createUnresolved")&&desc.equals("(Ljava/lang/String;I)Ljava/net/InetSocketAddress;")))){super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","relayPort","(I)I",false);count[0]++;}if(Boolean.getBoolean("bscp.qa.passiveObserver")&&owner.equals("StandardJvmBootstrap")&&method.equals("buildControlState")&&desc.equals("(Ljava/lang/Object;J)Ljava/lang/String;")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","passiveControlState",desc,false);count[0]++;return;}if(owner.equals("org/lwjgl/glfw/GLFW")&&method.equals("glfwSwapBuffers")&&desc.equals("(J)V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","frame","()V",false);count[0]++;}super.visitMethodInsn(opcode,owner,method,desc,itf);}
            };}
        },0);if(count[0]==0)return null;Bridge.event("frame-hook",name+" calls="+count[0]);return writer.toByteArray();}catch(Throwable error){Bridge.event("frame-hook-unavailable",name+" "+error);return null;}
    }

    private static byte[] preserveHandshakeId(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            boolean[] matched = new boolean[2];
            reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if (name.equals("strictfp") && descriptor.equals("I") && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == 0) {
                        matched[0] = true;
                    }
                    return super.visitField(access, name, descriptor, signature, value);
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!name.equals("transient") || !descriptor.equals("(I)V") || (access & Opcodes.ACC_STATIC) != 0) {
                        return method;
                    }
                    matched[1] = true;
                    return new MethodVisitor(Opcodes.ASM8, method) {
                        @Override public void visitCode() {
                            super.visitCode();
                            // The retry path reads this field before the challenge has supplied a server ID.
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ILOAD, 1);
                            visitFieldInsn(Opcodes.PUTFIELD, PROTOS_HANDSHAKE, "strictfp", "I");
                        }
                    };
                }
            }, 0);
            if (matched[0] && matched[1] && PROTOS_HANDSHAKE.equals(reader.getClassName())) {
                Bridge.event("handshake-hook", "preserve initial UDP connection ID during retries");
                return writer.toByteArray();
            }
            Bridge.event("handshake-hook-unavailable", "unexpected client class shape");
        } catch (RuntimeException error) {
            Bridge.event("handshake-hook-unavailable", error.toString());
        }
        return null;
    }
}
