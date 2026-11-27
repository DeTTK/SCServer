package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Decode damageable scenes off-thread and acquire their visual variants on the renderer. */
public final class SceneDamageModelTransformer implements ClassFileTransformer {
    private static final String OWNER="1562480994", PENDING="bscpDamageModelsPending";
    private static final String LOAD="(L1422486348;L1886271463;)V";

    @Override
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] original) {
        if(!OWNER.equals(name))return null;
        var node=new ClassNode();
        new ClassReader(original).accept(node,0);
        int edits=0;
        for(var method:node.methods){
            if(method.name.equals("extends")&&method.desc.equals(LOAD)){
                var ready=new LabelNode();
                var guard=new InsnList();
                resourceContext(guard,ready,Opcodes.IFNONNULL);
                guard.add(new VarInsnNode(Opcodes.ALOAD,0));
                guard.add(new InsnNode(Opcodes.ICONST_1));
                guard.add(new FieldInsnNode(Opcodes.PUTFIELD,OWNER,PENDING,"Z"));
                guard.add(new InsnNode(Opcodes.RETURN));
                continuation(guard,ready);
                guard.add(new VarInsnNode(Opcodes.ALOAD,0));
                guard.add(new InsnNode(Opcodes.ICONST_0));
                guard.add(new FieldInsnNode(Opcodes.PUTFIELD,OWNER,PENDING,"Z"));
                method.instructions.insert(guard);
                edits++;
            }
            if(method.name.equals("short")&&method.desc.equals("()L1304833431;")){
                var unchanged=new LabelNode();
                var load=new InsnList();
                load.add(new VarInsnNode(Opcodes.ALOAD,0));
                load.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,PENDING,"Z"));
                load.add(new JumpInsnNode(Opcodes.IFEQ,unchanged));
                resourceContext(load,unchanged,Opcodes.IFNULL);
                load.add(new VarInsnNode(Opcodes.ALOAD,0));
                load.add(new VarInsnNode(Opcodes.ALOAD,0));
                load.add(new FieldInsnNode(Opcodes.GETFIELD,"1079929180","for","L1886271463;"));
                load.add(new FieldInsnNode(Opcodes.GETFIELD,"1886271463","class","L1814125595;"));
                load.add(new FieldInsnNode(Opcodes.GETFIELD,"1814125595","void","L1422486348;"));
                load.add(new VarInsnNode(Opcodes.ALOAD,0));
                load.add(new FieldInsnNode(Opcodes.GETFIELD,"1079929180","for","L1886271463;"));
                load.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,OWNER,"extends",LOAD,false));
                continuation(load,unchanged);
                method.instructions.insert(load);
                edits++;
            }
        }
        if(edits!=2){
            System.err.println("[Scene Editor] Native damage-model seams unavailable");
            return null;
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_VOLATILE,PENDING,"Z",null,null));
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void resourceContext(InsnList code,LabelNode jump,int when){
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"1674906321","return","()L1674906321;",false));
        code.add(new JumpInsnNode(when,jump));
    }

    private static void continuation(InsnList code,LabelNode label){
        code.add(label);
        code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
    }
}
