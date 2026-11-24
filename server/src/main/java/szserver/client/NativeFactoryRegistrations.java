package szserver.client;

import org.objectweb.asm.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Reads the promoted client's declarative item registrations without starting its modules. */
final class NativeFactoryRegistrations implements Opcodes {
    private static final String REGISTRAR = "1497795389";
    private static final String REGISTER = "(Ljava/lang/String;Ljava/util/function/Function;)V";
    private static final String RESOURCE = "(L1422486348;)V";
    private static final List<String> MODULES = List.of("266834036", "683052146", "1036856485",
            "275683976", "632123724", "64190119", "687315053");
    private record Member(String owner, String name) {}
    private final Path classes;
    private final ClassLoader loader;
    private final String[] strings;
    private final Map<String,Member> registrations = new HashMap<>();
    private final Map<String,Set<String>> constructors = new HashMap<>();

    NativeFactoryRegistrations(Path runtime, ClassLoader loader) throws Exception {
        classes = runtime.resolve("classes");
        this.loader = loader;
        strings = (String[]) Class.forName("1281655698", true, loader).getField("if").get(null);
        for (String module : MODULES) {
            read(module, new ClassVisitor(ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return name.equals("implements") && descriptor.equals("(L177325851;)V")
                            ? new RegistrationVisitor() : null;
                }
            });
        }
        if (registrations.isEmpty()) throw new IOException("native_factory_registrations_missing");
    }

    boolean matches(String type, String expected) throws IOException {
        Member function = registrations.get(type);
        if (function == null) return false;
        Set<String> targets = constructors.get(function.owner());
        if (targets == null) {
            targets = directConstructors(function.owner());
            constructors.put(function.owner(), targets);
        }
        return targets.equals(Set.of(expected));
    }

    Object create(String type, Class<?> expected, Object resource) throws Exception {
        // Legacy/debug IDs may have a specialized factory and wire codec.
        if (!matches(type, expected.getName())) return null;
        Member function = registrations.get(type);
        var field = Class.forName(function.owner(), true, loader).getDeclaredField(function.name());
        if (!field.trySetAccessible()) throw new IOException("native_factory_callback_access:" + type);
        @SuppressWarnings("unchecked")
        var factory = (Function<Object,Object>) field.get(null);
        Object value = factory.apply(resource);
        if (value == null || value.getClass() != expected)
            throw new IOException("native_factory_product_mismatch:" + type);
        return value;
    }

    private Set<String> directConstructors(String callback) throws IOException {
        Set<String> targets = new HashSet<>();
        read(callback, new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("apply")) return null;
                return new MethodVisitor(ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String desc, boolean itf) {
                        if (opcode == INVOKESPECIAL && method.equals("<init>") && desc.equals(RESOURCE))
                            targets.add(owner);
                    }
                };
            }
        });
        return Set.copyOf(targets);
    }

    private void read(String name, ClassVisitor visitor) throws IOException {
        new ClassReader(RuntimeFiles.read(classes.resolve(name + ".class"))).accept(visitor,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private final class RegistrationVisitor extends MethodVisitor {
        private final ArrayDeque<Object> recent = new ArrayDeque<>();
        RegistrationVisitor() { super(ASM9); }

        private void push(Object value) {
            if (recent.size() == 4) recent.removeFirst();
            recent.addLast(value);
        }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == GETSTATIC) push(new Member(owner, name));
            else recent.clear();
        }
        @Override public void visitLdcInsn(Object value) { push(value); }
        @Override public void visitIntInsn(int opcode, int operand) {
            if (opcode == SIPUSH || opcode == BIPUSH) push(operand);
            else recent.clear();
        }
        @Override public void visitInsn(int opcode) {
            if (opcode >= ICONST_M1 && opcode <= ICONST_5) push(opcode - ICONST_0);
            else if (opcode == AALOAD) push(Boolean.TRUE);
            else if (opcode != NOP) recent.clear();
        }
        @Override public void visitVarInsn(int opcode, int var) { recent.clear(); }
        @Override public void visitTypeInsn(int opcode, String type) { recent.clear(); }
        @Override public void visitJumpInsn(int opcode, Label label) { recent.clear(); }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                Object... arguments) { recent.clear(); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals(REGISTRAR) && name.equals("private")
                    && descriptor.equals(REGISTER) && recent.size() >= 2 && recent.peekLast() instanceof Member function) {
                Object[] sequence = recent.toArray();
                Object key = sequence[sequence.length - 2];
                if (key instanceof String literal) register(literal, function);
                else if (sequence.length == 4 && key == Boolean.TRUE
                        && sequence[0].equals(new Member("1281655698", "if"))
                        && sequence[1] instanceof Integer index)
                    register(strings[index], function);
            }
            recent.clear();
        }

        private void register(String type, Member function) {
            Member old = registrations.putIfAbsent(type, function);
            if (old != null && !old.equals(function))
                throw new IllegalStateException("native_factory_duplicate_registration:" + type);
        }
    }
}
