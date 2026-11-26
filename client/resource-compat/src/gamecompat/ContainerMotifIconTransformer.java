package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class ContainerMotifIconTransformer implements ClassFileTransformer, Opcodes {
    private static final String THUMBNAIL_FACTORY = "1321302639";
    private static final String CREATE_THUMBNAIL = "(L211097523;L1366800141;Z)L1539956877;";
    private static final String CONTAINER_MOTIF_ICONS = "stalker:backpacks/motives/";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] bytes) {
        if (!THUMBNAIL_FACTORY.equals(name)) return null;
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] transformed = {false};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                var original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("do") || !descriptor.equals(CREATE_THUMBNAIL)) return original;
                transformed[0] = true;
                return new MethodVisitor(ASM9, original) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // The generated container thumbnail can be blank even when its
                        // configured motif sprite is valid. Decline 3D generation so
                        // the native icon cache uses its regular sprite fallback.
                        Label noSprite = new Label();
                        Label useThumbnail = new Label();
                        visitVarInsn(ALOAD, 1);
                        visitMethodInsn(INVOKEVIRTUAL, "211097523", "finally", "()L1596173232;", false);
                        visitInsn(DUP);
                        visitJumpInsn(IFNULL, noSprite);
                        visitMethodInsn(INVOKEINTERFACE, "1596173232", "new", "()Ljava/lang/String;", true);
                        visitLdcInsn(CONTAINER_MOTIF_ICONS);
                        visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
                        visitJumpInsn(IFEQ, useThumbnail);
                        visitInsn(ACONST_NULL);
                        visitInsn(ARETURN);
                        visitLabel(noSprite);
                        visitFrame(F_SAME1, 0, null, 1, new Object[]{"1596173232"});
                        visitInsn(POP);
                        visitLabel(useThumbnail);
                        visitFrame(F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);
        return transformed[0] ? writer.toByteArray() : null;
    }
}
