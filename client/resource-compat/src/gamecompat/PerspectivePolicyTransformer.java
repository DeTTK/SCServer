package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keep native F5/aim/collision behavior; only remove the developer-mode view restriction. */
public final class PerspectivePolicyTransformer implements ClassFileTransformer {
    private static final String CAMERA="1389510037",BASE="8d4247b0cfe5a241ede3793e1d855e6ceb33f775f08064d612b5758cc52d78eb";
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        if(!CAMERA.equals(name))return null;
        try {
            byte[] original=bytes;
            if(loader!=null)try(var in=loader.getResourceAsStream(CAMERA+".class")){if(in!=null)original=in.readAllBytes();}
            if(!BASE.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original))))throw new IllegalStateException("native camera version changed");
            ClassReader reader=new ClassReader(bytes);ClassNode node=new ClassNode();reader.accept(node,0);int methods=0;
            for(MethodNode method:node.methods)if(method.name.equals("default")&&method.desc.equals("()V")){
                int checks=0,resets=0,returns=0,instructions=0;
                for(var insn:method.instructions){
                    if(insn.getOpcode()>=0){instructions++;if(insn.getOpcode()==Opcodes.RETURN)returns++;}
                    if(insn instanceof MethodInsnNode call&&call.owner.equals("769798186")&&call.name.equals("import")&&call.desc.equals("()Z"))checks++;
                    if(insn instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(CAMERA)&&field.name.equals("do")&&field.desc.equals("L1308185038;"))resets++;
                }
                if(instructions==1&&returns==1)return null;
                if(checks!=1||resets!=1||returns!=1)throw new IllegalStateException("native camera policy seam changed");methods++;
            }
            if(methods!=1)throw new IllegalStateException("native camera policy method missing");
            ClassWriter writer=new ClassWriter(reader,0);
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                    MethodVisitor out=super.visitMethod(access,method,descriptor,signature,exceptions);
                    if(!method.equals("default")||!descriptor.equals("()V"))return out;
                    out.visitCode();out.visitInsn(Opcodes.RETURN);out.visitMaxs(0,1);out.visitEnd();return null;
                }
            },0);
            System.err.println("[Camera compatibility] Native F5 enabled in normal gameplay");return writer.toByteArray();
        }catch(Exception | LinkageError failure){System.err.println("[Camera compatibility] Optional F5 patch skipped: "+failure);return null;}
    }
}
