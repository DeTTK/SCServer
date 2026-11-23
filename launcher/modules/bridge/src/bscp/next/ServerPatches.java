package bscp.next;

import java.lang.instrument.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.nio.file.*;
import jdk.internal.org.objectweb.asm.*;

/** Strict patches are verified before registering; a missing match aborts this opt-in process. */
final class ServerPatches implements ClassFileTransformer {
    static final Set<String> targets=Set.of("szserver/world/CheckpointSources","szserver/world/RegionTerrain","szserver/net/PlayerRules","szserver/net/LocalAccounts","szserver/world/WorldLoop","szserver/ServerMain","szserver/client/NativeSceneIncident","szserver/client/NativeSceneEncaged","szserver/client/NativeSceneDefaults","szserver/client/ClientSceneCodec");
    static final Set<String> complete=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static void verify(String jar)throws Exception{try(var archive=new JarFile(jar)){for(String target:targets){var entry=archive.getJarEntry(target+".class");if(entry==null)throw new IllegalStateException("patch input missing: "+target);byte[] b=archive.getInputStream(entry).readAllBytes();transformClass(target,b,false);}}}
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){if(!targets.contains(name))return null;try{return transformClass(name,bytes,true);}catch(Throwable error){error.printStackTrace();Runtime.getRuntime().halt(78);return null;}}
    static byte[] transformClass(String name,byte[] bytes,boolean live)throws Exception{
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] matches={0};
        reader.accept(new ClassVisitor(Opcodes.ASM8,writer){
            @Override public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){
                if(name.endsWith("/LocalAccounts")&&method.equals("handle")&&desc.equals("(Lcom/sun/net/httpserver/HttpExchange;)V")){
                    matches[0]++;var wrapper=super.visitMethod(access,method,desc,signature,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,1);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/AccountGuard","handle","(Ljava/lang/Object;Lcom/sun/net/httpserver/HttpExchange;)V",false);wrapper.visitInsn(Opcodes.RETURN);wrapper.visitMaxs(2,2);wrapper.visitEnd();
                    return super.visitMethod(access,"bscpOriginalHandle",desc,signature,exceptions);
                }
                if(name.endsWith("/RegionTerrain")&&method.equals("identity")&&desc.equals("()Ljava/lang/String;")){
                    matches[0]++;var m=super.visitMethod(access,method,desc,signature,exceptions);m.visitCode();m.visitTypeInsn(Opcodes.NEW,"java/lang/StringBuilder");m.visitInsn(Opcodes.DUP);m.visitLdcInsn("regions:content-v2:");m.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/StringBuilder","<init>","(Ljava/lang/String;)V",false);m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,name,"checkpointBase","Ljava/lang/String;");m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","append","(Ljava/lang/String;)Ljava/lang/StringBuilder;",false);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","toString","()Ljava/lang/String;",false);m.visitInsn(Opcodes.ARETURN);m.visitMaxs(3,1);m.visitEnd();return null;
                }
                if(name.endsWith("/PlayerRules")&&method.equals("editor")&&desc.equals("(Ljava/util/UUID;Ljava/util/Set;)Z")){
                    matches[0]++;var m=super.visitMethod(access,method,desc,signature,exceptions);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ALOAD,1);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"entitled","(Ljava/util/UUID;)Z",false);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(2,3);m.visitEnd();return null;
                }
                if(name.endsWith("/PlayerRules")&&method.equals("entitled")&&desc.equals("(Ljava/util/UUID;)Z")){
                    matches[0]++;var m=super.visitMethod(access,method,desc,signature,exceptions);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,1);m.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/AccountGuard","entitled","(Ljava/util/UUID;)Z",false);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,2);m.visitEnd();return null;
                }
                var original=super.visitMethod(access,method,desc,signature,exceptions);
                if(((name.equals("szserver/client/NativeSceneIncident")||name.equals("szserver/client/NativeSceneEncaged"))&&method.equals("<init>"))||(name.equals("szserver/client/NativeSceneDefaults")&&method.equals("resourceRoot"))||(name.equals("szserver/client/ClientSceneCodec")&&method.equals("prepareRepairs")))return new MethodVisitor(Opcodes.ASM8,original){@Override public void visitMethodInsn(int opcode,String owner,String m,String d,boolean itf){super.visitMethodInsn(opcode,owner,m,d,itf);if(owner.equals("java/nio/file/Path")&&m.equals("of")||owner.equals("szserver/client/RuntimeFiles")&&m.equals("reference")&&d.equals("(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/nio/file/Path;")){matches[0]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","bundlePath","(Ljava/nio/file/Path;)Ljava/nio/file/Path;",false);}}};
                if(name.endsWith("/LocalAccounts")&&method.equals("gateway")&&desc.equals("(Ljava/util/UUID;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lszserver/net/LocalAccounts$Grant;")){matches[0]++;return new MethodVisitor(Opcodes.ASM8,original){@Override public void visitInsn(int opcode){if(opcode==Opcodes.ARETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/AccountGuard","grant","(Ljava/lang/Object;Ljava/lang/String;)V",false);}super.visitInsn(opcode);}};}
                if(name.equals("szserver/ServerMain")&&method.equals("main"))return new MethodVisitor(Opcodes.ASM8,original){@Override public void visitMethodInsn(int opcode,String owner,String m,String d,boolean itf){super.visitMethodInsn(opcode,owner,m,d,itf);if(owner.equals("java/nio/file/Path")&&m.equals("getParent")&&d.equals("()Ljava/nio/file/Path;")){matches[0]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","bundleRoot","(Ljava/nio/file/Path;)Ljava/nio/file/Path;",false);}}};
                if(name.endsWith("/WorldLoop")&&method.equals("runTicks")&&desc.equals("()V"))return new MethodVisitor(Opcodes.ASM8,original){@Override public void visitFieldInsn(int opcode,String owner,String field,String d){super.visitFieldInsn(opcode,owner,field,d);if(opcode==Opcodes.PUTFIELD&&owner.equals(name)&&field.equals("snapshot")){matches[0]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,"bscp/next/Bridge","tick","()V",false);}}};
                if(name.endsWith("/CheckpointSources")&&method.equals("map")&&desc.equals("(Ljava/security/MessageDigest;Ljava/nio/file/Path;Ljava/lang/String;Z)V"))return new MethodVisitor(Opcodes.ASM8,original){@Override public void visitMethodInsn(int opcode,String owner,String m,String d,boolean itf){if(owner.equals("java/nio/file/Path")&&m.equals("toString")&&d.equals("()Ljava/lang/String;")){matches[0]++;super.visitInsn(Opcodes.POP);super.visitLdcInsn("managed-map-content-v2");}else super.visitMethodInsn(opcode,owner,m,d,itf);}};
                return original;
            }
        },0);
        int expected=name.endsWith("/LocalAccounts")||name.endsWith("/PlayerRules")?2:1;
        if(matches[0]!=expected)throw new IllegalStateException("patch shape mismatch "+name+": "+matches[0]);
        if(live){complete.add(name);Bridge.event("server-patch",name);if(complete.containsAll(targets))Files.writeString(Bridge.output.resolve("server-patches.ok"),String.join("\n",complete));}return writer.toByteArray();
    }
}
