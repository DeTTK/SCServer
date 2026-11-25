import java.nio.file.*;
import java.lang.reflect.*;
import org.objectweb.asm.*;

/** JVM descriptors retain the native return-type overloads that Java Proxy rejects. */
public final class GenerateFpAdapter implements Opcodes {
    public static void main(String[] args)throws Exception {
        String name="vr/NativeFpAdapter",contract="sz/animation/fp/N1632869237";Class<?> api=Class.forName(contract.replace('/','.'),false,GenerateFpAdapter.class.getClassLoader());
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);out.visit(V17,ACC_PUBLIC|ACC_FINAL,name,null,"java/lang/Object",new String[]{contract});
        out.visitField(ACC_PRIVATE|ACC_FINAL,"delegate","L"+contract+";",null,null).visitEnd();out.visitField(ACC_PRIVATE|ACC_FINAL,"poses","[Ljava/lang/Object;",null,null).visitEnd();
        MethodVisitor ctor=out.visitMethod(ACC_PUBLIC,"<init>","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",null,null);ctor.visitCode();ctor.visitVarInsn(ALOAD,0);ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitVarInsn(ALOAD,0);ctor.visitVarInsn(ALOAD,1);ctor.visitTypeInsn(CHECKCAST,contract);ctor.visitFieldInsn(PUTFIELD,name,"delegate","L"+contract+";");ctor.visitVarInsn(ALOAD,0);ctor.visitInsn(ICONST_4);ctor.visitTypeInsn(ANEWARRAY,"java/lang/Object");for(int i=0;i<4;i++){ctor.visitInsn(DUP);ctor.visitInsn(ICONST_0+i);ctor.visitVarInsn(ALOAD,i+2);ctor.visitInsn(AASTORE);}ctor.visitFieldInsn(PUTFIELD,name,"poses","[Ljava/lang/Object;");ctor.visitInsn(RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        Method[] methods=api.getMethods();java.util.Arrays.sort(methods,java.util.Comparator.comparing(Method::getName).thenComparing((Method method)->org.objectweb.asm.Type.getMethodDescriptor(method)));
        for(Method method:methods){
            String desc=org.objectweb.asm.Type.getMethodDescriptor(method);MethodVisitor m=out.visitMethod(ACC_PUBLIC,method.getName(),desc,null,null);m.visitCode();int slot=desc.equals("()L1118769848;")?switch(method.getName()){case "super"->0;case "catch"->1;case "switch"->2;default->-1;}:desc.equals("()L211097523;")?3:-1;
            if(slot>=0){m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"poses","[Ljava/lang/Object;");m.visitInsn(ICONST_0+slot);m.visitInsn(AALOAD);m.visitInsn(DUP);Label fallback=new Label();m.visitJumpInsn(IFNULL,fallback);m.visitTypeInsn(CHECKCAST,slot==3?"211097523":"1118769848");m.visitInsn(ARETURN);m.visitLabel(fallback);m.visitInsn(POP);}
            // Provider and item must describe the same rifle. The delegated
            // native context may still refer to the previous equipped item
            // during a switch; mixing definitions loses attachment meshes.
            if(desc.equals("()L168071319;")){
                m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"poses","[Ljava/lang/Object;");m.visitInsn(ICONST_3);m.visitInsn(AALOAD);m.visitInsn(DUP);Label fallback=new Label();m.visitJumpInsn(IFNULL,fallback);
                m.visitTypeInsn(CHECKCAST,"211097523");m.visitMethodInsn(INVOKEVIRTUAL,"211097523","continue","()L168071319;",false);m.visitInsn(ARETURN);m.visitLabel(fallback);m.visitInsn(POP);
            }
            m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"delegate","L"+contract+";");int variable=1;for(org.objectweb.asm.Type type:org.objectweb.asm.Type.getArgumentTypes(desc)){m.visitVarInsn(type.getOpcode(ILOAD),variable);variable+=type.getSize();}m.visitMethodInsn(INVOKEINTERFACE,contract,method.getName(),desc,true);m.visitInsn(org.objectweb.asm.Type.getReturnType(desc).getOpcode(IRETURN));m.visitMaxs(0,0);m.visitEnd();
        }
        out.visitEnd();Path output=Path.of(args[0]).resolve(name+".class");Files.createDirectories(output.getParent());Files.write(output,out.toByteArray());System.out.println("Generated descriptor-exact native FP adapter: "+api.getMethods().length+" methods");
    }
}
