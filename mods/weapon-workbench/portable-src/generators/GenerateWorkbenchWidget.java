import java.nio.file.*;
import org.objectweb.asm.*;
/** Only selection is deferred; drawing, dropdowns, icons and item filtering stay native. */
public final class GenerateWorkbenchWidget implements Opcodes {
 public static void main(String[] args)throws Exception {
  String n="workbench/NativeAttachmentButton",base="803562553";
  ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
  w.visit(V1_8,ACC_PUBLIC|ACC_FINAL,n,null,base,null);
  MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/Predicate;)V",null,null);
  m.visitCode();m.visitVarInsn(ALOAD,0);
  for(int i=1;i<=3;i++){m.visitVarInsn(ALOAD,i);m.visitTypeInsn(CHECKCAST,new String[]{"1552855737","1536296909","211097523"}[i-1]);}
  m.visitVarInsn(ALOAD,4);m.visitMethodInsn(INVOKESPECIAL,base,"<init>","(L1552855737;L1536296909;L211097523;Ljava/util/function/Predicate;)V",false);
  m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
  m=w.visitMethod(ACC_PUBLIC,"instanceof","(L211097523;)V",null,null);m.visitCode();
  m.visitLdcInsn("weapon.workbench-select.v1");m.visitInsn(ICONST_2);m.visitTypeInsn(ANEWARRAY,"java/lang/Object");
  m.visitInsn(DUP);m.visitInsn(ICONST_0);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,base,"final","L1536296909;");m.visitInsn(AASTORE);
  m.visitInsn(DUP);m.visitInsn(ICONST_1);m.visitVarInsn(ALOAD,1);m.visitInsn(AASTORE);
  m.visitMethodInsn(INVOKESTATIC,"workbench/WorkbenchRuntime","hook","(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false);m.visitInsn(POP);
  m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKEVIRTUAL,base,"strictfp","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
  w.visitEnd();Path p=Path.of(args[0]).resolve(n+".class");Files.createDirectories(p.getParent());Files.write(p,w.toByteArray());
 }
}
