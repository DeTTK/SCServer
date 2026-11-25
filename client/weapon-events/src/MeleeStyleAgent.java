import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Inherit the base knife's first-person cast when its selected appearance omits one. */
public final class MeleeStyleAgent implements Opcodes {
    private static final String CONTROLLER = "sz/animation/fp/N1706111695";
    private static final String MELEE_FACTORY = "124763289";
    private static final String CAST = "L1124991383;";

    static void install(Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] input) {
                if (!CONTROLLER.equals(name)) return null;
                try {
                    return patch(input);
                } catch (RuntimeException failure) {
                    System.err.println("[WeaponEvents] Melee style animation cast: " + failure);
                    return null;
                }
            }
        }, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (CONTROLLER.replace('/', '.').equals(type.getName())) instrumentation.retransformClasses(type);
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
                if (!name.equals("szFp_final") || !descriptor.equals("(L805719214;)L1382318679;")) {
                    return out;
                }
                return new MethodVisitor(ASM8, out) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                                                String desc, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                        if (opcode != INVOKEINTERFACE || !owner.equals("64739610")
                                || !called.equals("goto") || !desc.equals("()" + CAST)) return;
                        // The selected appearance supplies the model and clip. Fill only the missing cast.
                        super.visitVarInsn(ALOAD, 2);
                        super.visitMethodInsn(INVOKESTATIC, "MeleeStyleAgent", "inheritCast",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                        super.visitTypeInsn(CHECKCAST, "1124991383");
                        edits[0]++;
                    }
                };
            }
        }, 0);
        if (edits[0] != 1) throw new IllegalStateException("Unexpected native first-person cast lookup");
        return writer.toByteArray();
    }

    public static Object inheritCast(Object selectedCast, Object stack) {
        if (selectedCast != null || stack == null) return selectedCast;
        try {
            Object factory = stack.getClass().getMethod("interface").invoke(stack);
            if (factory == null || !MELEE_FACTORY.equals(factory.getClass().getName())) return null;
            // The native factory returns its unstyled renderer for a null stack.
            Object base = factory.getClass().getMethod("default", stack.getClass()).invoke(factory, (Object) null);
            return base == null ? null : base.getClass().getMethod("goto").invoke(base);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read native base melee animation cast", failure);
        }
    }
}
