package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class SceneSelectionTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader,String name,Class<?> redefined,ProtectionDomain domain,byte[] original) {
        if (!"775872119".equals(name)) return null;
        try {
            var node = new ClassNode();
            new ClassReader(original).accept(node,0);
            int edits = 0;
            for (var method : node.methods) {
                if (!method.name.equals("char") || !method.desc.equals("(L1886271463;)V")) continue;
                for (var instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFNE) continue;
                    if (!(branch.getPrevious() instanceof VarInsnNode index) || index.getOpcode() != Opcodes.ILOAD || index.var != 2) continue;
                    var swaps = java.util.Arrays.stream(method.instructions.toArray()).filter(i ->
                        i instanceof MethodInsnNode call && call.owner.equals("java/util/Collections") &&
                        call.name.equals("swap") && call.desc.equals("(Ljava/util/List;II)V")).count();
                    if (swaps != 1) throw new IllegalStateException("scene_selection_swap_changed");
                    // indexOfFirst yields -1 when the object was not added to the group.
                    branch.setOpcode(Opcodes.IFGT);
                    edits++;
                }
            }
            if (edits != 1) throw new IllegalStateException("scene_selection_branch_changed");
            var writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException error) {
            System.err.println("[Scene Editor] Selection guard unavailable: " + error);
            return null;
        }
    }
}
