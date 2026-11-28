import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reuse the installed solver in the mod, changing only its vehicle voxel collector. */
public final class BuildVehicleRampSolver implements Opcodes {
    public static void main(String[] args)throws Exception{
        Path runtime=Path.of(args[0]),out=Path.of(args[1]);ClassNode c=read(runtime.resolve("251809992.class"));
        String original=c.name;c.name="SCVehicleTerrainSolver";int hooks=0;
        for(MethodNode m:c.methods)for(AbstractInsnNode n:m.instructions){
            if(n instanceof FieldInsnNode f&&f.owner.equals(original))f.owner=c.name;
            if(n instanceof MethodInsnNode call){
                if(call.owner.equals(original))call.owner=c.name;
                if(call.owner.equals("1500514786")&&call.name.equals("const")&&call.desc.equals("(L1217489680;L242639986;Ljava/util/List;)V")){
                    call.setOpcode(INVOKESTATIC);call.owner="scvehicles/VehicleRampContacts";call.name="collect";call.desc="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V";call.itf=false;hooks++;
                }
            }
        }
        if(hooks!=1)throw new IllegalStateException("Expected one voxel collector, got "+hooks);
        ClassWriter w=new ClassWriter(0);c.accept(w);Files.write(out.resolve(c.name+".class"),w.toByteArray());

        // Decode bounds using the canonical centre getters' exact arithmetic.
        // Numeric native getter values are encoded and cannot be read as plain doubles.
        ClassNode aabb=read(runtime.resolve("242639986.class"));ClassWriter b=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        b.visit(V17,ACC_PUBLIC|ACC_FINAL,"scvehicles/NativeRampBounds",null,"java/lang/Object",null);
        MethodVisitor m=b.visitMethod(ACC_PUBLIC|ACC_STATIC,"read","(Ljava/lang/Object;)[D",null,null);m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitTypeInsn(CHECKCAST,"242639986");m.visitVarInsn(ASTORE,0);m.visitIntInsn(BIPUSH,6);m.visitIntInsn(NEWARRAY,T_DOUBLE);
        String[] getters={"implements","switch","goto"};
        for(int axis=0;axis<3;axis++){
            String getter=getters[axis];MethodNode source=aabb.methods.stream().filter(v->v.name.equals(getter)&&v.desc.equals("()D")).findFirst().orElseThrow();
            List<AbstractInsnNode> expression=new ArrayList<>();int term=0;
            for(AbstractInsnNode n:source.instructions){
                if(n.getOpcode()<0)continue;
                expression.add(n);
                if(n instanceof MethodInsnNode call&&call.owner.equals("java/lang/Double")&&call.name.equals("longBitsToDouble")){
                    m.visitInsn(DUP);m.visitIntInsn(BIPUSH,term==0?axis+3:axis);
                    for(AbstractInsnNode instruction:expression)instruction.accept(m);
                    m.visitInsn(DASTORE);expression.clear();if(++term==2)break;
                }
            }
            if(term!=2)throw new IllegalStateException("Expected encoded bound pair: "+source.name);
        }
        m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();b.visitEnd();
        Files.write(out.resolve("scvehicles/NativeRampBounds.class"),b.toByteArray());
        System.out.println("Generated scoped native vehicle solver and canonical bounds decoder");
    }
    private static ClassNode read(Path p)throws Exception{ClassNode c=new ClassNode();new ClassReader(Files.readAllBytes(p)).accept(c,0);return c;}
}
