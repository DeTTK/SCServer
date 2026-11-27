import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Stop actor-owned equipment particles immediately when their owner dies. */
final class HeldEffectDeathAgent implements Opcodes {
    private static final String RENDERER = "1043044427";
    private static final String FIRST_PERSON = "sz/animation/fp/N1699943857";
    private static final String ACTOR = "351062380";
    private static final String WEAPON_EFFECT_STATE = "314437914";
    private static final String LIVING_ENTITY = "1217489680";
    private static final String EFFECT = "1366534706";

    static void install(Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] input) {
                if (!RENDERER.equals(name) && !FIRST_PERSON.equals(name)) return null;
                try {
                    return patch(input);
                } catch (RuntimeException failure) {
                    System.err.println("[WeaponEvents] Held effect death cleanup: " + failure);
                    return null;
                }
            }
        }, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (RENDERER.equals(type.getName()) || FIRST_PERSON.replace('/', '.').equals(type.getName())) {
                instrumentation.retransformClasses(type);
            }
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
                return switch (name + descriptor) {
                    case "class(L351062380;L405296112;L1612003613;L1118769848;L779455578;L1987590071;L168071319;I)V" ->
                            guard(out, ACTOR, 0, -1, false, edits);
                    case "protected(L314437914;L39815754;)V" -> guard(out, WEAPON_EFFECT_STATE, 0, -1, false, edits);
                    case "synchronized(L351062380;L168071319;L1366534706;)Z" -> guard(out, ACTOR, 0, 2, false, edits);
                    case "this(L314437914;L1366534706;)Z" -> guard(out, WEAPON_EFFECT_STATE, 0, 1, false, edits);
                    case "szFp_class()V" -> guard(out, "961798934", -1, -1, false, edits);
                    case "szFp_try(L378545641;L779455578;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)L1366534706;" ->
                            guard(out, "378545641", 1, -1, true, edits);
                    case "szFp_try(L378545641;Lsz/animation/fp/N1699943857;L1366534706;)Z" ->
                            guard(out, "378545641", 0, 2, false, edits);
                    case "szFp_int(L961798934;Lsz/animation/fp/N1699943857;L1810277368;L1366534706;)Z",
                         "szFp_static(L961798934;Lsz/animation/fp/N1699943857;L1810277368;L1366534706;)Z" ->
                            guard(out, "961798934", 0, 3, false, edits);
                    default -> out;
                };
            }
        }, 0);
        int expected = FIRST_PERSON.equals(reader.getClassName()) ? 5 : 4;
        if (edits[0] != expected) throw new IllegalStateException("Unexpected native equipment effect methods");
        return writer.toByteArray();
    }

    private static MethodVisitor guard(MethodVisitor out, String owner, int ownerSlot, int effectSlot,
                                       boolean returnsEffect, int[] edits) {
        return new MethodVisitor(ASM8, out) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label alive = new Label();
                if (ownerSlot < 0) {
                    visitMethodInsn(INVOKESTATIC, "1402131154", "try", "()L1402131154;", false);
                    visitMethodInsn(INVOKEVIRTUAL, "1402131154", "throws", "()L961798934;", false);
                } else {
                    visitVarInsn(ALOAD, ownerSlot);
                }
                if (WEAPON_EFFECT_STATE.equals(owner)) {
                    visitFieldInsn(GETFIELD, WEAPON_EFFECT_STATE, "package", "L" + LIVING_ENTITY + ";");
                }
                visitMethodInsn(INVOKEVIRTUAL, WEAPON_EFFECT_STATE.equals(owner) ? LIVING_ENTITY : owner,
                        "super", "()Z", false);
                visitJumpInsn(IFNE, alive);
                if (effectSlot < 0) {
                    // The renderer may have queued this spawn before the death update.
                    if (returnsEffect) visitInsn(ACONST_NULL);
                    visitInsn(returnsEffect ? ARETURN : RETURN);
                } else {
                    visitVarInsn(ALOAD, effectSlot);
                    visitMethodInsn(INVOKEVIRTUAL, EFFECT, "void", "()V", false);
                    // Skip the caller's soft StopRoot path. The native layer still retires the stopped effect.
                    visitInsn(ICONST_1);
                    visitInsn(IRETURN);
                }
                visitLabel(alive);
                visitFrame(F_SAME, 0, null, 0, null);
                edits[0]++;
            }
        };
    }
}
