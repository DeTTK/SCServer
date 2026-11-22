package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The protocol selector stays on UDP; unrelated enum settings retain their values. */
public final class UdpPolicyTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        if(!"812797379".equals(name))return null;
        try {
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!sha.equals("cd8b95d4eb75848408fba6831ccc816cb8141f262eda6297ffcdb862019a2700"))throw new IllegalStateException("Native protocol selector changed");
            ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,ClassReader.EXPAND_FRAMES);int edits=0;
            for(MethodNode m:c.methods) {
                boolean get=m.name.equals("goto")&&m.desc.equals("()I"),set=m.name.equals("float")&&m.desc.equals("(I)L812797379;");
                if(!get&&!set)continue;
                LabelNode ordinary=new LabelNode();InsnList hook=new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new FieldInsnNode(Opcodes.GETSTATIC,"1546247182","ar","L812797379;"));hook.add(new JumpInsnNode(Opcodes.IF_ACMPNE,ordinary));
                if(get){hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new InsnNode(Opcodes.ICONST_1));hook.add(new FieldInsnNode(Opcodes.PUTFIELD,"812797379","default","I"));hook.add(new InsnNode(Opcodes.ICONST_1));hook.add(new InsnNode(Opcodes.IRETURN));}
                else{hook.add(new InsnNode(Opcodes.ICONST_1));hook.add(new VarInsnNode(Opcodes.ISTORE,1));}
                hook.add(ordinary);hook.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));m.instructions.insert(hook);edits++;
            }
            if(edits!=2)throw new IllegalStateException("Native protocol accessors changed");
            ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);c.accept(out);System.err.println("[Network compatibility] Protocol setting locked to UDP");return out.toByteArray();
        }catch(Throwable failure){System.err.println("[Network compatibility] FAILED: "+failure);return new byte[]{0};}
    }
}
