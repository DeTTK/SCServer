import java.nio.file.*;
import org.objectweb.asm.*;
public final class GenerateLegacyAdapter {
 public static void main(String[] args)throws Exception {
 Path bootstrap=Path.of(args[0]);
        ClassWriter adapter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        adapter.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,"szmods/bridge/LegacyAdapter",null,"1057736279",null);
        MethodVisitor ctor=adapter.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(Ljava/lang/String;)V",null,null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitVarInsn(Opcodes.ALOAD,1);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"1057736279","<init>","(Ljava/lang/String;)V",false);
        ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        for(String name:new String[]{"float","package","double","public","protected","throws","implements","interface","const"}) {
            boolean context=name.equals("implements")||name.equals("interface")||name.equals("const");
            MethodVisitor m=adapter.visitMethod(Opcodes.ACC_PUBLIC,name,context?"(L177325851;)V":"()V",null,null);
            m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"1057736279","if","()Ljava/lang/String;",false);
            m.visitLdcInsn(name);
            if(context)m.visitVarInsn(Opcodes.ALOAD,1);else m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKESTATIC,"szmods/runtime/ModLoader","legacyStage","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V",false);
            m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();
        }
        adapter.visitEnd();
        Path output=bootstrap.resolve("szmods/bridge/LegacyAdapter.class");
        Files.createDirectories(output.getParent());Files.write(output,adapter.toByteArray());

 }
}
