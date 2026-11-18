package gamecompat;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Only the type header changes; native UUID, pose, behavior and attributes stay native. */
public final class SceneHashTransformer implements ClassFileTransformer {
    private static final Map<String,String> BASES=Map.of(
        "1886271463","fbafa562ccfc06f8586361f3746c40c75042261f52773f9cca55f7ff9df79adc",
        "1814125595","3f00da14ae31c9922f7ca0f699dede3a0d17741c0904b356ecb96ab70ff2f24c");
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(!BASES.containsKey(name==null?"":name))return null;
        try {
            try(var in=loader.getResourceAsStream(name+".class")){
                byte[] original=in==null?bytes:in.readAllBytes();
                String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
                if(!BASES.get(name).equals(sha))throw new IllegalStateException("scene_hash_native_base_changed:"+name);
            }
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
            for(MethodNode method:node.methods){
                if(name.equals("1814125595")&&method.desc.equals("()V")&&(method.name.equals("extends")||method.name.equals("int"))){
                    for(var insn:method.instructions.toArray())if(insn.getOpcode()==Opcodes.RETURN){
                        InsnList hook=new InsnList();hook.add(new LdcInsnNode(Type.getObjectType(name)));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/SceneHashWire",method.name.equals("int")?"clear":"prepare","(Ljava/lang/Class;)V",false));
                        method.instructions.insertBefore(insn,hook);edits++;
                    }
                }
                if(!name.equals("1886271463"))continue;
                for(var insn:method.instructions.toArray())if(insn instanceof MethodInsnNode call){
                    if(method.name.equals("new")&&method.desc.equals("(Ljava/io/DataInput;)L1886271463;")&&call.owner.equals("java/io/DataInput")&&call.name.equals("readShort")){
                        // Keep the original exception range, null handling, construction and body reader.
                        MethodInsnNode lookup=null;for(var n=call.getNext();n!=null;n=n.getNext())if(n instanceof MethodInsnNode c){lookup=c;break;}
                        if(lookup==null||!lookup.owner.equals("1814125595")||!lookup.desc.equals("(S)L1814125595;"))throw new IllegalStateException("scene_hash_read_seam");
                        InsnList hook=new InsnList();hook.add(new LdcInsnNode(Type.getObjectType("1814125595")));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/SceneHashWire","read","(Ljava/io/DataInput;Ljava/lang/Class;)Ljava/lang/Object;",false));hook.add(new TypeInsnNode(Opcodes.CHECKCAST,"1814125595"));
                        method.instructions.insertBefore(call,hook);method.instructions.remove(call);method.instructions.remove(lookup);edits++;
                    }
                    if(method.name.equals("class")&&method.desc.equals("(L1886848284;L1886271463;Ljava/io/DataOutput;)V")&&call.owner.equals("1814125595")&&call.desc.equals("(L1814125595;)S")){
                        MethodInsnNode output=null;for(var n=call.getNext();n!=null;n=n.getNext())if(n instanceof MethodInsnNode c){output=c;break;}
                        if(output==null||!output.owner.equals("java/io/DataOutput")||!output.name.equals("writeShort"))throw new IllegalStateException("scene_hash_write_seam");
                        method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/SceneHashWire","write","(Ljava/io/DataOutput;Ljava/lang/Object;)V",false));method.instructions.remove(output);edits++;
                    }
                }
            }
            if(edits!=2)throw new IllegalStateException("scene_hash_seam_count:"+name+":"+edits);
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
            System.err.println("[Scene hash wire] Installed native name-hash codec: "+name);return writer.toByteArray();
        }catch(Throwable error){System.err.println("[Scene hash wire] FAILED: "+error);return new byte[]{0};}
    }
}
