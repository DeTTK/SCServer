import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Position-only adapter. Copy canonical numeric codecs without applying look or stance. */
public final class BuildPlayerCoordinates implements Opcodes {
    public static void main(String[] args)throws Exception {
        ClassNode nativeState=new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0],"1943200621.class"))).accept(nativeState,0);
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.visit(V17,ACC_PUBLIC|ACC_FINAL,"scvehicles/NativePlayerCoordinates",null,"java/lang/Object",null);
        MethodNode read=nativeState.methods.stream().filter(m->m.name.equals("boolean")&&m.desc.equals("(L200241649;)V")).findFirst().orElseThrow();
        MethodVisitor r=out.visitMethod(ACC_PUBLIC|ACC_STATIC,"read","(Ljava/lang/Object;)[D",null,null);r.visitCode();
        r.visitVarInsn(ALOAD,0);r.visitTypeInsn(CHECKCAST,"200241649");r.visitVarInsn(ASTORE,0);
        r.visitIntInsn(BIPUSH,6);r.visitIntInsn(NEWARRAY,T_DOUBLE);r.visitVarInsn(ASTORE,1);
        int count=0;List<AbstractInsnNode> expression=new ArrayList<>();boolean capture=false;
        String[] fields={"s","t","u","o","p","q"};
        for(AbstractInsnNode n:read.instructions){
            if(n instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==1){expression.clear();capture=true;}
            if(n instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD){
                if(!f.name.equals(fields[count]))throw new IllegalStateException("Coordinate field changed: "+f.name);
                r.visitVarInsn(ALOAD,1);r.visitIntInsn(BIPUSH,count);
                for(AbstractInsnNode e:expression){
                    if(e instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==1)r.visitVarInsn(ALOAD,0);
                    else e.accept(r);
                }
                r.visitInsn(DASTORE);capture=false;if(++count==6)break;
            }else if(capture&&n.getOpcode()>=0)expression.add(n);
        }
        if(count!=6)throw new IllegalStateException("Missing actor coordinate decoders");
        r.visitVarInsn(ALOAD,1);r.visitInsn(ARETURN);r.visitMaxs(0,0);r.visitEnd();

        MethodNode write=nativeState.methods.stream().filter(m->m.name.equals("do")&&m.desc.equals("(L200241649;)V")).findFirst().orElseThrow();
        MethodVisitor w=out.visitMethod(ACC_PUBLIC|ACC_STATIC,"write","(Ljava/lang/Object;[D)V",null,null);w.visitCode();
        w.visitVarInsn(ALOAD,0);w.visitTypeInsn(CHECKCAST,"200241649");w.visitVarInsn(ASTORE,0);
        String[] getters={"strictfp","if","long","continue","protected","public"};
        count=0;capture=false;
        for(AbstractInsnNode n:write.instructions){
            if(n instanceof MethodInsnNode m&&m.owner.equals(nativeState.name)&&m.desc.equals("()D")){
                if(!m.name.equals(getters[count]))throw new IllegalStateException("Coordinate getter changed: "+m.name);
                w.visitVarInsn(ALOAD,0);w.visitVarInsn(ALOAD,1);w.visitIntInsn(BIPUSH,count);w.visitInsn(DALOAD);capture=true;continue;
            }
            if(capture&&n.getOpcode()>=0)n.accept(w);
            if(capture&&n instanceof MethodInsnNode m&&m.owner.equals("200241649")&&m.desc.equals("(D)D")){
                w.visitInsn(POP2);capture=false;if(++count==6)break;
            }
        }
        if(count!=6)throw new IllegalStateException("Missing actor coordinate encoders");
        w.visitInsn(RETURN);w.visitMaxs(0,0);w.visitEnd();out.visitEnd();
        Files.write(Path.of(args[1],"scvehicles/NativePlayerCoordinates.class"),out.toByteArray());
        System.out.println("Generated position-only actor codec from canonical runtime");
    }
}
