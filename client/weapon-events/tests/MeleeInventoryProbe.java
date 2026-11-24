import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Exercise the packaged classes without starting GLFW or submitting gameplay inputs. */
public final class MeleeInventoryProbe implements Opcodes {
    private static Object event;
    private static Object actor;
    private static int dispatches;

    public static void premain(String args, Instrumentation instrumentation) {
        // Observe only the local FP dispatch; run the real state reset and event constructor.
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!"430242295".equals(name)) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, 0);
                reader.accept(new ClassVisitor(ASM8, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String desc,
                                                     String signature, String[] exceptions) {
                        MethodVisitor out = super.visitMethod(access, method, desc, signature, exceptions);
                        if (!method.equals("szFp_byte") || !desc.equals("(L1217489680;)V")) return out;
                        out.visitCode();
                        out.visitVarInsn(ALOAD, 0);
                        out.visitVarInsn(ALOAD, 1);
                        out.visitMethodInsn(INVOKESTATIC, "MeleeInventoryProbe", "record",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        out.visitInsn(RETURN);
                        out.visitMaxs(2, 2);
                        out.visitEnd();
                        return null;
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }

    public static void record(Object result, Object source) {
        event = result;
        actor = source;
        dispatches++;
    }

    public static void main(String[] args) throws Exception {
        verifyCancelledStrike();
        verifyDamageLabels();
        System.out.println("Melee cancellation and localized item stats: PASS");
    }

    private static void verifyCancelledStrike() throws Exception {
        Class<?> strike = Class.forName("1761650403");
        Class<?> attackType = Class.forName("1476229801");
        int strong = ((Enum<?>) attackType.getField("STRONG").get(null)).ordinal();
        int common = ((Enum<?>) attackType.getField("COMMON").get(null)).ordinal();
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        var allocate = unsafeType.getMethod("allocateInstance", Class.class);
        Object unsafe = singleton.get(null);
        Object owner = allocate.invoke(unsafe, Class.forName("961798934"));
        Object action = allocate.invoke(unsafe, strike);
        var source = strike.getField("strictfp");
        source.setAccessible(true);
        source.set(action, owner);
        var timer = strike.getField("catch");
        var attack = strike.getField("int");
        var cancel = strike.getMethod("package");

        attack.setInt(action, strong);
        timer.setInt(action, -1);
        cancel.invoke(action);
        check(dispatches == 0, "idle reset emitted a strike result");
        attack.setInt(action, common);
        timer.setInt(action, 3);
        cancel.invoke(action);
        check(dispatches == 0, "ordinary attack emitted a heavy-strike result");
        attack.setInt(action, strong);
        timer.setInt(action, 3);
        cancel.invoke(action);
        check(dispatches == 1 && actor == owner && timer.getInt(action) == -1,
                "pending cancellation must resolve once for its original actor");
        check(((Enum<?>) event.getClass().getMethod("enum").invoke(event)).name().equals("MISS"),
                "the cancellation must take the miss path");
        cancel.invoke(action);
        check(dispatches == 1, "repeated reset emitted another strike result");

        Class<?> graphState = Class.forName("1479284708");
        Object state = graphState.getConstructor().newInstance();
        graphState.getMethod("while", boolean.class).invoke(state, true);
        Class<?> graph = Class.forName("1780244917");
        Object handler = graph.getConstructor(graphState).newInstance(state);
        graph.getMethod("boolean", Class.forName("430242295"), Class.forName("211097523"),
                Class.forName("378545641")).invoke(handler, event, null, null);
        check(graphState.getField("szStrikeResultReady").getBoolean(state),
                "the first-person graph is still waiting for a result");
        check(!(Boolean) graphState.getMethod("new").invoke(state), "a previous hit survived cancellation");
    }

    @SuppressWarnings("unchecked")
    private static void verifyDamageLabels() throws Exception {
        Class<?> table = Class.forName("1675930023");
        Class<?> jurisdiction = Class.forName("1689789497");
        Class<?> locale = Class.forName("com.squarespace.cldr.CLDR$Locale");
        Object base = jurisdiction.getField("DEFAULT").get(null);
        Object dictionary = table.getField("strictfp").get(null);
        Object ru = locale.getField("ru_RU").get(null);
        Object en = locale.getField("en_US").get(null);
        var lookup = table.getMethod("return", jurisdiction, locale, String.class);
        var locales = (Map<Object, Map<String, Map<String, String>>>) table.getField("volatile").get(null);
        var russian = new HashMap<String, String>();
        locales.put(base, new HashMap<>(Map.of("ru", russian, "en", new HashMap<>())));
        String prefix = "weapon.tooltip.melee_weapon.info.damage.";
        for (String suffix : new String[]{"min.common", "max.common", "min.strong", "max.strong"}) {
            String key = prefix + suffix;
            Object ruText = lookup.invoke(dictionary, base, ru, key);
            Object enText = lookup.invoke(dictionary, base, en, key);
            check(ruText instanceof String label && label.contains("урон"), "missing Russian label for " + key);
            check(enText instanceof String label && label.contains("damage"), "missing English label for " + key);
        }
        String key = prefix + "min.strong";
        russian.put(key, "Native localized value");
        check(lookup.invoke(dictionary, base, ru, key).equals("Native localized value"),
                "existing native translation was overwritten");
        check(lookup.invoke(dictionary, base, ru, prefix + "irrelevant") == null,
                "unrelated missing translations were modified");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
