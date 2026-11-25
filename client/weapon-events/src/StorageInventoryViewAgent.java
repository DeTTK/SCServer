import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Keep the storage screen's observer inside its own inventory model. */
final class StorageInventoryViewAgent implements Opcodes {
    private static final String SCREEN = "833813734";

    static void install(Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] input) {
                if (!SCREEN.equals(name)) return null;
                try {
                    return patch(input);
                } catch (RuntimeException failure) {
                    System.err.println("[WeaponEvents] Storage inventory view: " + failure);
                    return null;
                }
            }
        }, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (SCREEN.equals(type.getName())) instrumentation.retransformClasses(type);
        }
    }

    static byte[] patch(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        reader.accept(new ClassVisitor(ASM8, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                MethodVisitor out = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("case") || !desc.equals("(L949164803;)V")) return out;
                matches[0]++;
                return new MethodVisitor(ASM8, out) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        checkedView(this);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1) throw new IllegalStateException("Storage slot callback changed");
        return writer.toByteArray();
    }

    private static void checkedView(MethodVisitor out) {
        Label hasInventory = new Label();
        Label visibleSection = new Label();
        // The native packet already updated the owner model before notifying this view.
        out.visitVarInsn(ALOAD, 0);
        out.visitMethodInsn(INVOKEVIRTUAL, SCREEN, "double", "()L105205961;", false);
        out.visitMethodInsn(INVOKEVIRTUAL, "105205961", "throws", "()Ljava/util/Map;", false);
        out.visitVarInsn(ALOAD, 1);
        out.visitMethodInsn(INVOKEVIRTUAL, "949164803", "protected", "()I", false);
        out.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        out.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        out.visitTypeInsn(CHECKCAST, "47325642");
        out.visitInsn(DUP);
        out.visitJumpInsn(IFNONNULL, hasInventory);
        out.visitInsn(POP);
        out.visitInsn(RETURN);
        out.visitLabel(hasInventory);
        out.visitFrame(F_SAME1, 0, null, 1, new Object[]{"47325642"});
        out.visitVarInsn(ALOAD, 1);
        out.visitMethodInsn(INVOKEVIRTUAL, "949164803", "break", "()I", false);
        out.visitMethodInsn(INVOKEVIRTUAL, "47325642", "final", "(I)L830050478;", false);
        out.visitJumpInsn(IFNONNULL, visibleSection);
        out.visitInsn(RETURN);
        out.visitLabel(visibleSection);
        out.visitFrame(F_SAME, 0, null, 0, null);
    }
}
