package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Read the replicated slot for display and the staged stack for a picker click. */
final class ArtifactSlotTransformer implements ClassFileTransformer, Opcodes {
    private static final String SLOT = "1419288974";
    private static final String BASE_SLOT = "1827602420";
    private static final String CHOICE = "786030667";
    private static final String CHOSEN = "(L" + BASE_SLOT + ";)L211097523;";
    private static final String PARENT = "761662502";
    private static final String FACTORY = "1441854005";
    private static final String STACK = "L211097523;";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] bytes) {
        if (CHOICE.equals(name)) return transformChoice(bytes);
        if (!SLOT.equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getSuperName().equals(BASE_SLOT)) return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(writer, 0);

        // Only the native container factory interprets the slot index and saved state.
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

    private static byte[] transformChoice(byte[] bytes) {
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] edited = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("boolean") || !descriptor.equals("(L1500420601;I)V")) return original;
                return new MethodVisitor(ASM9, original) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String type, boolean isInterface) {
                        if (opcode == INVOKEVIRTUAL && owner.equals(BASE_SLOT)
                                && method.equals("catch") && type.equals("()" + STACK)) {
                            super.visitMethodInsn(INVOKESTATIC, CHOICE, "bscpSelectedStack", CHOSEN, false);
                            edited[0]++;
                        } else {
                            super.visitMethodInsn(opcode, owner, method, type, isInterface);
                        }
                    }
                };
            }
        }, 0);
        if (edited[0] != 1) {
            System.err.println("[Artifact slots] Unexpected picker shape: " + edited[0]);
            return null;
        }

        // The picker has already staged its choice through the native predicate.
        // Route an artifact choice, including a null removal, to the original action.
        MethodVisitor out = writer.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            "bscpSelectedStack", CHOSEN, null, null);
        out.visitCode();
        out.visitVarInsn(ALOAD, 0);
        out.visitTypeInsn(INSTANCEOF, SLOT);
        Label stock = new Label();
        out.visitJumpInsn(IFEQ, stock);
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, BASE_SLOT, "double", STACK);
        out.visitInsn(ARETURN);
        out.visitLabel(stock);
        out.visitFrame(F_SAME, 0, null, 0, null);
        out.visitVarInsn(ALOAD, 0);
        out.visitMethodInsn(INVOKEVIRTUAL, BASE_SLOT, "catch", "()" + STACK, false);
        out.visitInsn(ARETURN);
        out.visitMaxs(0, 0);
        out.visitEnd();
        return writer.toByteArray();
    }
}
