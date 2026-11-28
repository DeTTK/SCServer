package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Keep long clipboard commands intact in the native chat box. */
public final class ChatInputTransformer implements ClassFileTransformer {
    private static final int CHAT_COMMAND_LIMIT = 16 * 1024;

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                            ProtectionDomain domain, byte[] original) {
        if (!"922139741".equals(name)) return null;
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        for (var method : node.methods) {
            if (!method.name.equals("synchronized") || !method.desc.equals("(L1500420601;L61438557;)L978664536;")) continue;
            IntInsnNode regularLimit = null;
            IntInsnNode adminLimit = null;
            int setters = 0;
            for (var insn : method.instructions) {
                if (insn instanceof IntInsnNode limit && limit.getOpcode() == Opcodes.SIPUSH) {
                    if (limit.operand == 140) regularLimit = limit;
                    if (limit.operand == 10_000) adminLimit = limit;
                }
                if (insn instanceof MethodInsnNode call && call.owner.equals("978664536")
                        && call.name.equals("new") && call.desc.equals("(I)L978664536;")) setters++;
            }
            if (regularLimit == null || adminLimit == null || setters != 1) break;
            regularLimit.operand = CHAT_COMMAND_LIMIT;
            adminLimit.operand = CHAT_COMMAND_LIMIT;
            var writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        }
        System.err.println("[Game resource compatibility] Native chat input limit unavailable");
        return null;
    }
}
