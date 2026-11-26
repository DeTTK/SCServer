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
        if ("946927356".equals(name)) return inheritModel(bytes);
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

    private static byte[] inheritModel(byte[] bytes) {
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] edits = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                var original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("implements") || !descriptor.equals("(L1422486348;)V")) return original;
                return new MethodVisitor(ASM9, original) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean itf) {
                        if (opcode == INVOKESTATIC && owner.equals("1982565452") && name.equals("try")
                                && descriptor.equals("(L837932848;L1144692372;L1825675213;)L1982565452;")) {
                            // Slot 5 is the native target container selected by applicable_items.
                            super.visitVarInsn(ALOAD, 5);
                            super.visitMethodInsn(INVOKESTATIC, "gamecompat/ContainerMotifModels", "create",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(CHECKCAST, owner);
                            edits[0]++;
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                        }
                    }
                };
            }
        }, 0);
        if (edits[0] != 1) {
            System.err.println("[Container motifs] Unexpected model factory shape: " + edits[0]);
            return null;
        }
        return writer.toByteArray();
    }
}
