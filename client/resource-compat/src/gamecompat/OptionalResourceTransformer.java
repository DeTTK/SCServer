package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Recover at optional asset consumers, never through a global uncaught-exception handler. */
public final class OptionalResourceTransformer implements ClassFileTransformer {
    private static final String HELPER="gamecompat/OptionalResources";
    private static final Properties BASES=new Properties();
    static{try(var in=OptionalResourceTransformer.class.getResourceAsStream("/gamecompat/optional-resource-hashes.properties")){BASES.load(Objects.requireNonNull(in));}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(name==null||!BASES.containsKey(name))return null;
        try {
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!digest.equals(BASES.getProperty(name)))throw new IllegalStateException("Native optional asset reader changed: "+name);
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
            for(MethodNode m:node.methods){
                if(name.equals("676254530")&&m.name.equals("new")&&m.desc.equals("(Ljava/lang/String;)V")){
                    m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();m.visibleLocalVariableAnnotations=null;m.invisibleLocalVariableAnnotations=null;
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"shaderHook","(Ljava/lang/Object;Ljava/lang/String;)V",false));m.instructions.add(new InsnNode(Opcodes.RETURN));edits++;continue;
                }
                if(name.equals("1669874697")&&m.name.equals("throws")&&m.desc.equals("(L779455578;)L1669874697;")){
                    m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();m.visibleLocalVariableAnnotations=null;m.invisibleLocalVariableAnnotations=null;
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"boxes","(Ljava/lang/Object;)Ljava/lang/Object;",false));
                    m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,"1669874697"));m.instructions.add(new InsnNode(Opcodes.ARETURN));edits++;continue;
                }
                if(name.equals("1086829620")&&m.name.equals("protected")&&m.desc.equals("(L1422486348;)V")){
                    m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"beginFactory","()V",false));edits++;
                }
                for(AbstractInsnNode n:m.instructions.toArray()){
                    if(name.equals("1086829620")&&m.name.equals("protected")&&n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals("1814125595")&&field.name.equals("throws")&&field.desc.equals("L1669874697;")){
                        m.instructions.set(field,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"assignCulling","(Ljava/lang/Object;Ljava/lang/Object;)V",false));edits++;
                    }
                    if(!(n instanceof MethodInsnNode call))continue;
                    if(name.equals("353540541")&&m.name.equals("enum")&&m.desc.equals("(Ljava/io/InputStream;)V")&&call.owner.equals("535595151")&&call.name.equals("class")&&call.desc.equals("(Ljava/lang/String;)V")){
                        m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"materialLine","(Ljava/lang/Object;Ljava/lang/String;)V",false));edits++;
                    }
                    if(name.equals("1086829620")&&m.name.equals("try")&&call.owner.equals("257692053")&&call.name.equals("synchronized")&&call.desc.equals("(Ljava/lang/String;)V")){
                        call.owner=HELPER;call.name="warning";edits++;
                    }
                    if(name.equals("535595151")&&m.name.equals("class")&&m.desc.equals("(Ljava/lang/String;)V")){
                        if(call.owner.equals("820404468")&&call.name.equals("super")&&call.desc.equals("(L567755251;Ljava/lang/String;[Ljava/lang/Object;)V")){
                            m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"materialWarning","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V",false));edits++;
                        }else if(call.owner.equals("567739024")&&call.name.equals("for")&&call.desc.equals("(Ljava/lang/String;[Ljava/lang/Object;)V")){call.owner=HELPER;call.name="formattedWarning";edits++;}
                    }
                }
            }
            int expected=name.equals("535595151")?4:name.equals("1086829620")?3:1;
            if(edits!=expected)throw new IllegalStateException("Optional resource seams: "+name+" "+edits+" != "+expected);
            ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
            System.err.println("[Optional resources] Installed native asset recovery: "+name+" ("+edits+" seams)");return out.toByteArray();
        }catch(Exception error){System.err.println("[Optional resources] Recovery disabled for unsupported reader "+name+": "+error);return null;}
    }
}
