package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A scene marker without visual variants still has a valid default variant index. */
public final class SceneVariantTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] original) {
        if (!"1886271463".equals(name)) return null;
        var node=new ClassNode();
        new ClassReader(original).accept(node,0);
        for(var method:node.methods){
            if (!method.name.equals("long") || !method.desc.equals("(I)I")) continue;
            for(var instruction:method.instructions.toArray()){
                if(instruction.getOpcode()!=Opcodes.IREM) continue;
                if(!(instruction.getPrevious() instanceof VarInsnNode count) || count.getOpcode()!=Opcodes.ILOAD || count.var!=0) continue;
                var guard=new InsnList();
                guard.add(new InsnNode(Opcodes.ICONST_1));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","max","(II)I",false));
                method.instructions.insertBefore(instruction,guard);
                var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                return writer.toByteArray();
            }
        }
        System.err.println("[Scene Editor] Native variant selector seam unavailable");
        return null;
    }
}
