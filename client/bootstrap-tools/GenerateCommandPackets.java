import java.nio.file.*;
import org.objectweb.asm.*;
public final class GenerateCommandPackets implements Opcodes {
 static final String H="szmods/runtime/CommandHooks";
 public static void main(String[] args)throws Exception {Path root=Path.of(args[0]);packet(root,"CommandRequest",true);packet(root,"CommandCatalog",false);}
 static void packet(Path root,String simple,boolean request)throws Exception {
  String n="szmods/bridge/"+simple;ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);
  w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>",request?"([B)V":"()V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);
  if(request){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
  m=w.visitMethod(ACC_PUBLIC,request?"switch":"extends",request?"(Ljava/io/DataOutput;)V":"(Ljava/io/DataInput;)V",null,null);m.visitCode();
  if(request){m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);}
  else{m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,H,"read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}
  m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
  if(!request){m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKESTATIC,H,"receive","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();}
  w.visitEnd();Files.createDirectories(root.resolve(n).getParent());Files.write(root.resolve(n+".class"),w.toByteArray());
 }
}
