package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Connects the native stream lifecycle to the current first-person effect rig. */
public final class FlamethrowerEffectTransformer implements ClassFileTransformer {
    private static final String OWNER="78772814";
    private static final String HOOKS="gamecompat/FlamethrowerEffectHooks";

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> redefined,ProtectionDomain domain,byte[] original){
        if(!OWNER.equals(name))return null;
        try{return patch(original);}
        catch(IllegalArgumentException changed){
            System.err.println("[Flamethrower effects] Native effect contract changed: "+changed.getMessage());
            return null;
        }
    }

    static byte[] patch(byte[] original){
        ClassNode node=new ClassNode();
        new ClassReader(original).accept(node,0);
        int spawn=0, transform=0;
        for(MethodNode method:node.methods){
            if(method.name.equals("private")&&method.desc.equals("(Z)V")){
                InsnList prepare=new InsnList();
                prepare.add(new VarInsnNode(Opcodes.ALOAD,0));
                prepare.add(new VarInsnNode(Opcodes.ILOAD,1));
                prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOKS,"prepare","(Ljava/lang/Object;Z)V",false));
                method.instructions.insert(prepare);
                for(AbstractInsnNode instruction:method.instructions.toArray()){
                    if(!(instruction instanceof MethodInsnNode call)
                            ||!call.owner.equals("432112703")||!call.name.equals("break")
                            ||!call.desc.equals("(L779455578;IFFF)L1366534706;"))continue;
                    InsnList bridge=new InsnList();
                    bridge.add(new VarInsnNode(Opcodes.ALOAD,0));
                    bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOKS,"start",
                            "(Ljava/lang/Object;Ljava/lang/Object;IFFFLjava/lang/Object;)Ljava/lang/Object;",false));
                    bridge.add(new TypeInsnNode(Opcodes.CHECKCAST,"1366534706"));
                    method.instructions.insertBefore(call,bridge);
                    method.instructions.remove(call);
                    spawn++;
                }
            }else if(method.name.equals("abstract")&&method.desc.equals("(L1366534706;)V")){
                for(AbstractInsnNode instruction:method.instructions.toArray()){
                    if(!(instruction instanceof MethodInsnNode call)
                            ||!call.owner.equals("1366534706")||!call.name.equals("else")
                            ||!call.desc.equals("(L1303032651;)V"))continue;
                    method.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));
                    method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOKS,"attach",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));
                    transform++;
                }
            }
        }
        if(spawn!=1||transform!=1)throw new IllegalArgumentException("spawn="+spawn+", transform="+transform);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
