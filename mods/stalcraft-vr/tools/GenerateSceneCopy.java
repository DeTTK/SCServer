import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;

/** Typed copying of mutable draw-command state, without boxing each native field. */
public final class GenerateSceneCopy implements Opcodes {
    public static void main(String[] args)throws Exception {
        String owner="vr/NativeSceneCopy";
        List<Class<?>> types=new ArrayList<>();List<List<Field>> fields=new ArrayList<>();
        for(String name:List.of("1216601991","1604484550","1637348622","1095892820","971330199","998644527")){
            Class<?> type=Class.forName(name,false,GenerateSceneCopy.class.getClassLoader());
            List<Field> copy=new ArrayList<>();boolean accessible=true;
            for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Field field:c.getDeclaredFields()){
                int flags=field.getModifiers();if(Modifier.isStatic(flags)||Modifier.isFinal(flags)||field.getType().getName().equals("1270595881"))continue;
                if(field.getName().equals("throws")&&field.getType().getName().equals("447252483"))continue;
                if(!Modifier.isPublic(flags)||!Modifier.isPublic(c.getModifiers()))accessible=false;
                copy.add(field);
            }
            if(!accessible){System.out.println("Scene copy uses reflective fallback for "+name);continue;}
            copy.sort(Comparator.comparing((Field f)->f.getDeclaringClass().getName()).thenComparing(Field::getName).thenComparing(f->f.getType().getName()));
            types.add(type);fields.add(copy);
        }
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        out.visit(V17,ACC_PUBLIC|ACC_FINAL,owner,null,"java/lang/Object",new String[]{"java/util/function/BiConsumer"});
        out.visitField(ACC_PRIVATE|ACC_FINAL,"plan","I",null,null).visitEnd();
        MethodVisitor ctor=out.visitMethod(ACC_PRIVATE,"<init>","(I)V",null,null);ctor.visitCode();ctor.visitVarInsn(ALOAD,0);ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitVarInsn(ALOAD,0);ctor.visitVarInsn(ILOAD,1);ctor.visitFieldInsn(PUTFIELD,owner,"plan","I");ctor.visitInsn(RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        MethodVisitor factory=out.visitMethod(ACC_PUBLIC|ACC_STATIC,"create","(Ljava/lang/Class;)Ljava/util/function/BiConsumer;",null,null);factory.visitCode();
        for(int i=0;i<types.size();i++){
            Label next=new Label();factory.visitVarInsn(ALOAD,0);factory.visitLdcInsn(org.objectweb.asm.Type.getType(types.get(i)));factory.visitJumpInsn(IF_ACMPNE,next);
            factory.visitTypeInsn(NEW,owner);factory.visitInsn(DUP);factory.visitLdcInsn(i);factory.visitMethodInsn(INVOKESPECIAL,owner,"<init>","(I)V",false);factory.visitInsn(ARETURN);factory.visitLabel(next);
        }
        factory.visitInsn(ACONST_NULL);factory.visitInsn(ARETURN);factory.visitMaxs(0,0);factory.visitEnd();
        MethodVisitor copy=out.visitMethod(ACC_PUBLIC,"accept","(Ljava/lang/Object;Ljava/lang/Object;)V",null,null);copy.visitCode();
        copy.visitVarInsn(ALOAD,0);copy.visitFieldInsn(GETFIELD,owner,"plan","I");Label fail=new Label();Label[] labels=new Label[types.size()];for(int i=0;i<labels.length;i++)labels[i]=new Label();copy.visitTableSwitchInsn(0,labels.length-1,fail,labels);
        for(int i=0;i<types.size();i++){
            copy.visitLabel(labels[i]);String nativeType=org.objectweb.asm.Type.getInternalName(types.get(i));
            copy.visitVarInsn(ALOAD,1);copy.visitTypeInsn(CHECKCAST,nativeType);copy.visitVarInsn(ASTORE,3);copy.visitVarInsn(ALOAD,2);copy.visitTypeInsn(CHECKCAST,nativeType);copy.visitVarInsn(ASTORE,4);
            for(Field f:fields.get(i)){
                String declared=org.objectweb.asm.Type.getInternalName(f.getDeclaringClass()),descriptor=org.objectweb.asm.Type.getDescriptor(f.getType());
                copy.visitVarInsn(ALOAD,4);copy.visitVarInsn(ALOAD,3);copy.visitFieldInsn(GETFIELD,declared,f.getName(),descriptor);copy.visitFieldInsn(PUTFIELD,declared,f.getName(),descriptor);
            }
            copy.visitInsn(RETURN);
        }
        copy.visitLabel(fail);copy.visitTypeInsn(NEW,"java/lang/IllegalStateException");copy.visitInsn(DUP);copy.visitLdcInsn("Unknown native scene copy plan");copy.visitMethodInsn(INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false);copy.visitInsn(ATHROW);copy.visitMaxs(0,0);copy.visitEnd();
        out.visitEnd();Path output=Path.of(args[0]).resolve(owner+".class");Files.createDirectories(output.getParent());Files.write(output,out.toByteArray());
        System.out.println("Generated native scene copy adapter: "+types.size()+" command classes");
    }
}
