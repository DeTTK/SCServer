package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Treat a shared squad as friendly in direct native player relation lookups. */
public final class PlayerRelationsTransformer implements ClassFileTransformer {
    private static final String BASE="b3ba6abc47c1c013fad060c84e17abd7fca4c2677b35dee0df04e46c450fcfda";

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(!"495513349".equals(name))return null;
        try{
            if(!BASE.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalStateException("native relations base changed");
            var node=new ClassNode();
            new ClassReader(bytes).accept(node,0);
            var method=node.methods.stream().filter(m->m.name.equals("float")
                    &&m.desc.equals("(L200241649;L200241649;)L495513349;")).findFirst().orElseThrow();
            var hook=new InsnList();
            var faction=new LabelNode();
            hook.add(new VarInsnNode(Opcodes.ALOAD,0));
            hook.add(new VarInsnNode(Opcodes.ALOAD,1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"1900336751","static","(L200241649;L200241649;)Z",false));
            hook.add(new JumpInsnNode(Opcodes.IFEQ,faction));
            hook.add(new FieldInsnNode(Opcodes.GETSTATIC,name,"FRIENDLY","L495513349;"));
            hook.add(new InsnNode(Opcodes.ARETURN));
            hook.add(faction);
            hook.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
            method.instructions.insert(hook);
            var output=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(output);
            return output.toByteArray();
        }catch(Exception error){
            System.err.println("[Player relations] Unsupported native class "+name+": "+error);
            return null;
        }
    }
}
