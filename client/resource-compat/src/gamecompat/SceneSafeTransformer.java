package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.InvocationTargetException;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keep the stock default safe locked when its historical key has no item definition. */
public final class SceneSafeTransformer implements ClassFileTransformer {
    private static final int UNAVAILABLE_KEY=65536;

    public static int requiredKey(Object descriptor,String property,int defaultValue)throws ReflectiveOperationException {
        try {
            return (int)descriptor.getClass().getMethod("else",String.class,int.class)
                    .invoke(descriptor,property,defaultValue);
        } catch(InvocationTargetException error){
            if(property.equals("required_key_item")&&error.getCause() instanceof RuntimeException missing
                    &&"Unregistered item \"key.safe.mines_1\"".equals(missing.getMessage()))return UNAVAILABLE_KEY;
            if(error.getCause() instanceof RuntimeException failure)throw failure;
            throw error;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] original) {
        if(!"910562125".equals(name))return null;
        var node=new ClassNode();
        new ClassReader(original).accept(node,0);
        int edits=0;
        for(var method:node.methods){
            if(method.name.equals("<init>")&&method.desc.equals("(L1886271463;)V")){
                for(var instruction:method.instructions.toArray()){
                    if(instruction instanceof MethodInsnNode call&&call.owner.equals("1422486348")
                            &&call.name.equals("else")&&call.desc.equals("(Ljava/lang/String;I)I")){
                        method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/SceneSafeTransformer",
                                "requiredKey","(Ljava/lang/Object;Ljava/lang/String;I)I",false));
                        edits++;
                    }
                }
            }
            if(method.name.equals("public")&&method.desc.equals("()Ljava/lang/String;")){
                var normal=new LabelNode();
                var guard=new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD,0));
                guard.add(new FieldInsnNode(Opcodes.GETFIELD,name,"int","I"));
                guard.add(new LdcInsnNode(UNAVAILABLE_KEY));
                guard.add(new JumpInsnNode(Opcodes.IF_ICMPNE,normal));
                guard.add(new LdcInsnNode("Ключ к этому сейфу отсутствует в текущей версии игры."));
                guard.add(new InsnNode(Opcodes.ARETURN));
                guard.add(normal);
                guard.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
                method.instructions.insert(guard);
                edits++;
            }
        }
        if(edits!=2){
            System.err.println("[Scene Editor] Native safe compatibility seam unavailable");
            return null;
        }
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
