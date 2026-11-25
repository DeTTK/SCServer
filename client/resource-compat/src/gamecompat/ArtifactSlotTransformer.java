package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The native artifact widget must read the current stack, including after a server update. */
final class ArtifactSlotTransformer implements ClassFileTransformer, Opcodes {
    private static final String SLOT = "1419288974";
    private static final String PARENT = "761662502";
    private static final String FACTORY = "1441854005";
    private static final String STACK = "L211097523;";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] bytes) {
        if (!SLOT.equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getSuperName().equals("1827602420")) return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(writer, 0);

        // Override the getter used by rendering, tooltips and clicks. Only the native
        // container factory interprets the slot index and the stack's saved state.
        MethodVisitor out = writer.visitMethod(ACC_PUBLIC, "catch", "()" + STACK, null, null);
        out.visitCode();
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, SLOT, "double", "L" + PARENT + ";");
        out.visitMethodInsn(INVOKEVIRTUAL, PARENT, "else", "()" + STACK, false);
        Label occupied = new Label();
        out.visitInsn(DUP);
        out.visitJumpInsn(IFNONNULL, occupied);
        out.visitInsn(ARETURN);
        out.visitLabel(occupied);
        out.visitFrame(F_SAME1, 0, null, 1, new Object[]{"211097523"});
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, SLOT, "double", "L" + PARENT + ";");
        out.visitFieldInsn(GETFIELD, PARENT, "enum", "L" + FACTORY + ";");
        out.visitInsn(SWAP);
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, SLOT, "final", "I");
        out.visitMethodInsn(INVOKESTATIC, "830745898", "private", "(I)L830745898;", false);
        out.visitMethodInsn(INVOKEVIRTUAL, FACTORY, "continue", "(" + STACK + "Ljava/lang/Object;)" + STACK, false);
        out.visitInsn(ARETURN);
        out.visitMaxs(0, 0);
        out.visitEnd();
        return writer.toByteArray();
    }
}
