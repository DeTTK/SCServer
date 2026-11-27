import java.nio.file.*;
import org.objectweb.asm.*;

/** Numeric game identifiers cannot occur in Java source. Generate this tiny adapter only. */
public final class BuildVehicleCollider implements Opcodes {
    public static void main(String[] args)throws Exception {
        ClassWriter c=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        String name="scvehicles/NativeVehicleCollider";
        c.visit(V17,ACC_PUBLIC|ACC_FINAL,name,null,"334744794",null);
        c.visitField(ACC_PUBLIC,"ignored","Ljava/lang/Object;",null,null).visitEnd();
        c.visitField(ACC_PUBLIC,"suppressed","Z",null,null).visitEnd();
        MethodVisitor m=c.visitMethod(ACC_PUBLIC,"<init>","(L1886271463;Ljava/lang/String;L1994198637;)V",null,null);m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,2);m.visitVarInsn(ALOAD,3);m.visitMethodInsn(INVOKESPECIAL,"334744794","<init>","(L1886271463;Ljava/lang/String;L1994198637;)V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=c.visitMethod(ACC_PUBLIC,"strictfp","(Ljava/lang/Object;)L1384438816;",null,null);m.visitCode();Label none=new Label();
        m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"suppressed","Z");m.visitJumpInsn(IFNE,none);
        m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"ignored","Ljava/lang/Object;");m.visitJumpInsn(IF_ACMPEQ,none);
        m.visitVarInsn(ALOAD,1);m.visitTypeInsn(INSTANCEOF,"200241649");m.visitJumpInsn(IFEQ,none);
        m.visitFieldInsn(GETSTATIC,"1384438816","OBB","L1384438816;");m.visitInsn(ARETURN);
        m.visitLabel(none);m.visitFieldInsn(GETSTATIC,"1384438816","NONE","L1384438816;");m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();c.visitEnd();
        Path out=Path.of(args[0],name+".class");Files.createDirectories(out.getParent());Files.write(out,c.toByteArray());
    }
}
