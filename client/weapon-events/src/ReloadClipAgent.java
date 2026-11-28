import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Restore the original reload selector's clip-existence check in the newer cast. */
public final class ReloadClipAgent implements Opcodes {
    private static final String CONTROLLER = "1149187425";
    private static final String PARAMETERS = "1819324509";
    private static final String CLIP_FLAGS = "sz/animation/fp/N720462245";

    static void install(Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] input) {
                if (!CONTROLLER.equals(name)) return null;
                try {
                    return patch(input);
                } catch (RuntimeException failure) {
                    System.err.println("[WeaponEvents] Reload clip selection: " + failure);
                    return null;
                }
            }
        }, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (CONTROLLER.equals(type.getName())) instrumentation.retransformClasses(type);
        }
    }

    static byte[] patch(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] edits = {0};
        reader.accept(new ClassVisitor(ASM8, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor out = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("szFp_protected")
                        || !descriptor.equals("(L318048108;L1745367406;L211097523;L378545641;)V")) return out;
                return new MethodVisitor(ASM8, out) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                                                String desc, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                        if (opcode != INVOKEVIRTUAL || !owner.equals("sz/animation/fp/N1365572605")
                                || !called.equals("szFp_package")
                                || !desc.equals("(L1745367406;L211097523;L378545641;IFI)V")) return;
                        // The native reload handler has already resolved the worn magazine and set its flag.
                        super.visitVarInsn(ALOAD, 0);
                        super.visitMethodInsn(INVOKEVIRTUAL, CONTROLLER, "abstract", "()L230325512;", false);
                        super.visitTypeInsn(CHECKCAST, PARAMETERS);
                        super.visitVarInsn(ALOAD, 0);
                        super.visitFieldInsn(GETFIELD, CONTROLLER, "szFp_default", "L" + CLIP_FLAGS + ";");
                        super.visitFieldInsn(GETFIELD, CLIP_FLAGS, "szFp_goto", "L1942284906;");
                        Label checkClips = new Label();
                        Label done = new Label();
                        super.visitInsn(DUP);
                        super.visitJumpInsn(IFNONNULL, checkClips);
                        super.visitInsn(POP);
                        super.visitInsn(POP);
                        super.visitJumpInsn(GOTO, done);
                        super.visitLabel(checkClips);
                        super.visitFrame(F_FULL, 5,
                                new Object[]{CONTROLLER, "318048108", "1745367406", "211097523", "378545641"},
                                2, new Object[]{PARAMETERS, "1942284906"});
                        // Legacy first-person reload selection calls this same native clip lookup.
                        super.visitLdcInsn("reload_drum");
                        super.visitMethodInsn(INVOKEVIRTUAL, "1942284906", "short", "(Ljava/lang/String;)Z", false);
                        super.visitInsn(SWAP);
                        super.visitInsn(DUP_X1);
                        super.visitMethodInsn(INVOKEVIRTUAL, PARAMETERS, "szFp_byte", "()Z", false);
                        super.visitInsn(IAND);
                        super.visitMethodInsn(INVOKEVIRTUAL, PARAMETERS, "szFp_super", "(Z)V", false);
                        super.visitLabel(done);
                        super.visitFrame(F_SAME, 0, null, 0, null);
                        edits[0]++;
                    }
                };
            }
        }, 0);
        if (edits[0] != 1) throw new IllegalStateException("Unexpected native first-person reload handler");
        return writer.toByteArray();
    }
}
