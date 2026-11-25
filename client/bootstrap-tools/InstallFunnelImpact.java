import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Wraps the impact decoder in a native dynamic packet with no registered ID. */
public final class InstallFunnelImpact implements Opcodes {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        writePoint(root, "FunnelImpact");
        writePoint(root, "BoltImpact");
        writePoint(root, "WeaponAction");
        writeCapability(root, "FunnelImpactReady");
        writeCapability(root, "BoltImpactReady");
        writeCapability(root, "WeaponActionReady");
    }

    private static void writePoint(Path root, String name) throws Exception {
        String packet = "szmods/bridge/" + name;
        String impact = "szmods/runtime/" + name;
        String type = "L" + impact + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, packet, null, "499058674", null);
        writer.visitField(ACC_PRIVATE, "impact", type, null, null).visitEnd();

        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "499058674", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        finish(ctor);

        MethodVisitor read = writer.visitMethod(ACC_PUBLIC, "extends", "(Ljava/io/DataInput;)V",
                null, new String[]{"java/io/IOException"});
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitVarInsn(ALOAD, 1);
        read.visitMethodInsn(INVOKESTATIC, impact, "read", "(Ljava/io/DataInput;)" + type, false);
        read.visitFieldInsn(PUTFIELD, packet, "impact", type);
        read.visitInsn(RETURN);
        finish(read);

        MethodVisitor receive = writer.visitMethod(ACC_PUBLIC, "class", "(L1219970978;Z)V", null, null);
        receive.visitCode();
        receive.visitVarInsn(ALOAD, 0);
        receive.visitFieldInsn(GETFIELD, packet, "impact", type);
        receive.visitVarInsn(ALOAD, 1);
        receive.visitMethodInsn(INVOKEVIRTUAL, impact, "play", "(Ljava/lang/Object;)V", false);
        receive.visitInsn(RETURN);
        finish(receive);
        writer.visitEnd();
        Path path = root.resolve(packet + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, writer.toByteArray());
    }

    private static void writeCapability(Path root, String name) throws Exception {
        String packet = "szmods/bridge/" + name;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, packet, null, "499058674", null);
        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "499058674", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        finish(ctor);
        MethodVisitor write = writer.visitMethod(ACC_PUBLIC, "switch", "(Ljava/io/DataOutput;)V", null, null);
        write.visitCode();
        write.visitInsn(RETURN);
        finish(write);
        MethodVisitor dynamic = writer.visitMethod(ACC_PUBLIC, "finally", "()Z", null, null);
        dynamic.visitCode();
        dynamic.visitInsn(ICONST_1);
        dynamic.visitInsn(IRETURN);
        finish(dynamic);
        writer.visitEnd();
        Files.write(root.resolve(packet + ".class"), writer.toByteArray());
    }

    private static void finish(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
