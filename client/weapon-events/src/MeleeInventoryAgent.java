import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Repairs the native cancellation and missing localization seams for melee. */
public final class MeleeInventoryAgent implements Opcodes {
    private static final String STRIKE = "1761650403";
    private static final String LOCALE = "com/squarespace/cldr/CLDR$Locale";
    private static final String LANGUAGE_TABLE = "1675930023";
    private static final String MELEE_PREFIX = "weapon.tooltip.melee_weapon.info.damage.";

    static void install(Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] input) {
                if (!STRIKE.equals(name) && !LANGUAGE_TABLE.equals(name)) return null;
                try {
                    return patch(name, input);
                } catch (RuntimeException failure) {
                    System.err.println("[WeaponEvents] Melee inventory compatibility: " + failure);
                    return null;
                }
            }
        }, true);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (STRIKE.equals(type.getName()) || LANGUAGE_TABLE.equals(type.getName())) {
                instrumentation.retransformClasses(type);
            }
        }
    }

    static byte[] patch(String target, byte[] input) {
        int[] edits = {0};
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(ASM8, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                MethodVisitor out = super.visitMethod(access, name, desc, signature, exceptions);
                if (target.equals(STRIKE) && name.equals("package") && desc.equals("()V")) {
                    edits[0]++;
                    return new MethodVisitor(ASM8, out) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            completeCancelledStrike(this);
                        }
                    };
                }
                if (target.equals(LANGUAGE_TABLE) && name.equals("return")
                        && desc.equals("(L1689789497;L" + LOCALE + ";Ljava/lang/String;)Ljava/lang/String;")) {
                    return new MethodVisitor(ASM8, out) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            super.visitTypeInsn(opcode, type);
                            if (opcode != CHECKCAST || !type.equals("java/lang/String")) return;
                            edits[0]++;
                            super.visitVarInsn(ALOAD, 3);
                            super.visitVarInsn(ALOAD, 2);
                            super.visitMethodInsn(INVOKEINTERFACE, LOCALE, "language",
                                    "()Ljava/lang/String;", true);
                            super.visitVarInsn(ALOAD, 1);
                            super.visitMethodInsn(INVOKESTATIC, "MeleeInventoryAgent", "damageLabel",
                                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Enum;)Ljava/lang/String;", false);
                        }
                    };
                }
                return out;
            }
        }, 0);
        if (edits[0] != 1) throw new IllegalStateException("Unexpected native melee seam in " + target);
        return writer.toByteArray();
    }

    private static void completeCancelledStrike(MethodVisitor out) {
        Label done = new Label();
        // The native reset discards a pending timer. A cancelled heavy strike has
        // no hit callback, so release its local first-person animation as a miss.
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, STRIKE, "catch", "I");
        out.visitJumpInsn(IFLT, done);
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, STRIKE, "int", "I");
        out.visitFieldInsn(GETSTATIC, "1476229801", "STRONG", "L1476229801;");
        out.visitMethodInsn(INVOKEVIRTUAL, "1476229801", "ordinal", "()I", false);
        out.visitJumpInsn(IF_ICMPNE, done);
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, STRIKE, "strictfp", "L351062380;");
        out.visitTypeInsn(INSTANCEOF, "200241649");
        out.visitJumpInsn(IFEQ, done);
        out.visitTypeInsn(NEW, "1946377043");
        out.visitInsn(DUP);
        out.visitFieldInsn(GETSTATIC, "1966163455", "MISS", "L1966163455;");
        out.visitInsn(FCONST_1);
        out.visitInsn(ACONST_NULL);
        out.visitMethodInsn(INVOKESPECIAL, "1946377043", "<init>", "(L1966163455;FL1987590071;)V", false);
        out.visitVarInsn(ALOAD, 0);
        out.visitFieldInsn(GETFIELD, STRIKE, "strictfp", "L351062380;");
        out.visitTypeInsn(CHECKCAST, "1217489680");
        out.visitMethodInsn(INVOKEVIRTUAL, "1946377043", "szFp_byte", "(L1217489680;)V", false);
        out.visitLabel(done);
        out.visitFrame(F_SAME, 0, null, 0, null);
    }

    public static String damageLabel(String existing, String key, String language, Enum<?> jurisdiction) {
        if (existing != null || !jurisdiction.name().equals("DEFAULT") || !key.startsWith(MELEE_PREFIX)) {
            return existing;
        }
        int row = switch (key.substring(MELEE_PREFIX.length())) {
            case "min.common" -> 0;
            case "max.common" -> 1;
            case "min.strong" -> 2;
            case "max.strong" -> 3;
            default -> -1;
        };
        if (row < 0) return null;
        return switch (language) {
            case "ru" -> RUSSIAN[row];
            case "es" -> SPANISH[row];
            case "fr" -> FRENCH[row];
            case "ko" -> KOREAN[row];
            default -> ENGLISH[row];
        };
    }

    private static final String[] RUSSIAN = {
        "Мин. урон (обычный удар)", "Макс. урон (обычный удар)",
        "Мин. урон (сильный удар)", "Макс. урон (сильный удар)"
    };
    private static final String[] ENGLISH = {
        "Min. damage (normal attack)", "Max. damage (normal attack)",
        "Min. damage (heavy attack)", "Max. damage (heavy attack)"
    };
    private static final String[] SPANISH = {
        "Daño mín. (ataque normal)", "Daño máx. (ataque normal)",
        "Daño mín. (ataque fuerte)", "Daño máx. (ataque fuerte)"
    };
    private static final String[] FRENCH = {
        "Dégâts min. (attaque normale)", "Dégâts max. (attaque normale)",
        "Dégâts min. (attaque puissante)", "Dégâts max. (attaque puissante)"
    };
    private static final String[] KOREAN = {
        "최소 피해 (일반 공격)", "최대 피해 (일반 공격)",
        "최소 피해 (강공격)", "최대 피해 (강공격)"
    };
}
