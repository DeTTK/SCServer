package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Avoid the AMD driver crash from the client's legacy alpha-test render state. */
public final class AmdAlphaTestTransformer implements ClassFileTransformer {
    private static final String RENDER_STATE = "126528842";
    private static final String NATIVE_HASH = "8c8fdf2fdb4b31e9ec56532265a332e35104a67ff5fa92a04051fbc5383c08e9";
    private static final ThreadLocal<Adapter> ADAPTER = new ThreadLocal<>();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private record Adapter(GLCapabilities context, boolean amd) {}

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] bytes) {
        if (!RENDER_STATE.equals(name)) return null;
        try {
            if (!NATIVE_HASH.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw new IllegalStateException("native render state version changed");
            }
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int replacements = 0;
            for (var method : node.methods) {
                if (!method.name.equals("private") || !method.desc.equals("()V")) continue;
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                            || !call.owner.equals("org/lwjgl/opengl/GL11") || !call.name.equals("glEnable")
                            || !call.desc.equals("(I)V")) continue;
                    var argument = call.getPrevious();
                    while (argument != null && (argument.getOpcode() == Opcodes.NOP || argument.getOpcode() < 0)) {
                        argument = argument.getPrevious();
                    }
                    if (!(argument instanceof IntInsnNode constant) || constant.operand != GL11.GL_ALPHA_TEST) {
                        throw new IllegalStateException("native alpha-test call changed");
                    }
                    call.owner = "gamecompat/AmdAlphaTestTransformer";
                    call.name = "enableAlphaTest";
                    replacements++;
                }
            }
            if (replacements != 1) throw new IllegalStateException("native alpha-test call count: " + replacements);
            var writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Exception | LinkageError failure) {
            System.err.println("[Graphics compatibility] AMD alpha-test patch skipped: " + failure);
            return null;
        }
    }

    public static void enableAlphaTest(int capability) {
        GLCapabilities context = GL.getCapabilities();
        Adapter adapter = ADAPTER.get();
        if (adapter == null || adapter.context() != context) {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            adapter = new Adapter(context, isAmdVendor(vendor));
            if (vendor != null && !vendor.isBlank()) ADAPTER.set(adapter);
        }
        if (adapter.amd()) {
            // Keep the renderer's logical state transitions while forcing the actual GL bit off.
            GL11.glDisable(capability);
            if (LOGGED.compareAndSet(false, true)) {
                System.err.println("[Graphics compatibility] Legacy alpha test disabled on the active AMD OpenGL adapter");
            }
        } else {
            GL11.glEnable(capability);
        }
    }

    private static boolean isAmdVendor(String vendor) {
        if (vendor == null) return false;
        String name = vendor.strip().toUpperCase(Locale.ROOT);
        return name.equals("ATI") || name.startsWith("ATI TECHNOLOGIES")
                || name.equals("AMD") || name.startsWith("AMD ") || name.startsWith("ADVANCED MICRO DEVICES");
    }
}
