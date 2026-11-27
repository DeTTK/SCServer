package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Supplies a missing locator before the native attachment code falls back to the stock model. */
public final class SightMountAnchorTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] original) {
        if (!"62393481".equals(name)) return null;
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        for (var method : node.methods) {
            if (!"try".equals(method.name) || !"(Ljava/lang/String;)L1612003613;".equals(method.desc)) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var != 3) continue;
                var hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "gamecompat/SightMountAnchors", "resolve",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "1612003613"));
                hook.add(new VarInsnNode(Opcodes.ASTORE, 3));
                method.instructions.insert(store, hook);
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                return writer.toByteArray();
            }
        }
        System.err.println("[Game resource compatibility] Native sight mount resolver unavailable");
        return null;
    }
}
