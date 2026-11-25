package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Bind the replicated selector after the native component has registered its attributes. */
final class EquipmentBindingsTransformer implements ClassFileTransformer, Opcodes {
    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] input) {
        if (!"276406255".equals(name)) return null;
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] bindings = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!"<init>".equals(method) || !"(L358035993;)V".equals(descriptor)) return output;
                return new MethodVisitor(ASM9, output) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == RETURN) {
                            super.visitVarInsn(ALOAD, 0);
                            super.visitMethodInsn(INVOKESTATIC, "StandardJvmBootstrap",
                                    "compatibilityBindSelectedEquipment", "(Ljava/lang/Object;)V", false);
                            bindings[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (bindings[0] != 1) throw new IllegalStateException("Native equipment constructor changed");
        return writer.toByteArray();
    }
}
