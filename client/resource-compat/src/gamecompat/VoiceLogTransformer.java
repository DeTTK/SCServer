package gamecompat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.regex.Pattern;

/** Keep access tokens out of client logs before diagnostics can collect them. */
public final class VoiceLogTransformer implements ClassFileTransformer {
    private static final Pattern XML = Pattern.compile("(?i)(<AccessToken>)[^<]+(</AccessToken>)");
    private static final Pattern TOKEN = Pattern.compile("\\be30\\.[A-Za-z0-9_-]{32,}\\.[A-Za-z0-9_-]{43}\\b");

    public static String redact(String message) {
        return TOKEN.matcher(XML.matcher(message).replaceAll("$1[redacted]$2")).replaceAll("[redacted]");
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                     ProtectionDomain domain, byte[] original) {
        if (!"1089621464".equals(name)) return null;
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        for (var method : node.methods) {
            if (!method.name.equals("invoke") || !method.desc.equals("(JIJJ)V")) continue;
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals("org/lwjgl/system/MemoryUtil")
                    || !call.name.equals("memUTF8") || !call.desc.equals("(J)Ljava/lang/String;")) continue;
                method.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "gamecompat/VoiceLogTransformer", "redact", "(Ljava/lang/String;)Ljava/lang/String;", false));
                var writer = new ClassWriter(0);
                node.accept(writer);
                return writer.toByteArray();
            }
        }
        return null;
    }
}
