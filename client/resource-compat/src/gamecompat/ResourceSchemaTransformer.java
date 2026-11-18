package gamecompat;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Change format readers in the client, without intercepting resource bytes. */
public final class ResourceSchemaTransformer implements ClassFileTransformer {
    private static final java.util.Properties BASES=new java.util.Properties();
    static{try(var in=ResourceSchemaTransformer.class.getResourceAsStream("/gamecompat/schema-reader-hashes.properties")){BASES.load(java.util.Objects.requireNonNull(in));}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(name==null||!name.equals("1740810107")&&!name.equals("813156312")&&!name.equals("683052146"))return null;
        try {
            try(var input=loader.getResourceAsStream(name+".class")){
                // The encrypted launcher deliberately hides .class resources. In that case
                // verify the exact bytes supplied by its loader instead of exposing code.
                byte[] original=input==null?bytes:input.readAllBytes();
                String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
                if(!digest.equals(BASES.getProperty(name)))throw new IllegalStateException("Installed schema reader changed: "+name);
            }
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
            if(name.equals("683052146"))for(MethodNode method:node.methods)if(method.name.equals("implements")&&method.desc.equals("(L177325851;)V")){
                method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/ClientResourceSchema","registerItemTypes","()V",false));edits++;
            }
            for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions.toArray())if(insn instanceof MethodInsnNode call){
                if(name.equals("683052146")&&call.owner.equals("1786433297")&&call.name.equals("short")&&call.desc.equals("(Ljava/lang/String;Ljava/lang/Class;Z)V")){
                    AbstractInsnNode flag=call.getPrevious(),clazz=flag.getPrevious();
                    if(clazz instanceof LdcInsnNode constant&&constant.cst instanceof Type t&&t.getInternalName().equals("702433834")){
                        InsnList register=new InsnList();register.add(new LdcInsnNode("ravent/multiset_artefact"));register.add(new LdcInsnNode(Type.getObjectType("gamecompat/MultisetArtefact")));register.add(new InsnNode(Opcodes.ICONST_1));register.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"1786433297","short","(Ljava/lang/String;Ljava/lang/Class;Z)V",false));method.instructions.insert(call,register);edits++;
                    }
                }
                String replacement=null;
                if(name.equals("1740810107")&&method.name.equals("import")&&method.desc.equals("()V")){
                    if(call.owner.equals("313873871")){
                        if(call.name.equals("new")&&call.desc.equals("(Lcom/google/gson/JsonObject;Ljava/lang/String;)I"))replacement="arenaInt";
                        if(call.name.equals("final")&&call.desc.equals("(Lcom/google/gson/JsonObject;Ljava/lang/String;)F"))replacement="arenaFloat";
                        if(call.name.equals("float")&&call.desc.equals("(Lcom/google/gson/JsonObject;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)Ljava/util/List;"))replacement="arenaList";
                    }
                    if(call.owner.equals("com/google/gson/JsonObject")&&call.name.equals("getAsJsonObject")&&call.desc.equals("(Ljava/lang/String;)Lcom/google/gson/JsonObject;")){
                        replacement="arenaObject";call.desc="(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonObject;";
                    }
                }
                if(name.equals("813156312")&&call.owner.equals("313873871")&&call.name.equals("continue")&&call.desc.equals("(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))replacement="modifierIcon";
                if(replacement!=null){call.owner="gamecompat/ClientResourceSchema";call.name=replacement;call.setOpcode(Opcodes.INVOKESTATIC);call.itf=false;edits++;}
            }
            if(edits==0)throw new IllegalStateException("Resource schema seam missing: "+name);
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);System.err.println("[Client resource schema] Updated native reader "+name+" ("+edits+" calls)");return writer.toByteArray();
        }catch(Throwable error){error.printStackTrace();return new byte[]{0};}
    }
    public static byte[] multisetFactory(){
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);out.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,"gamecompat/MultisetArtefact",null,"702433834",null);
        MethodVisitor ctor=out.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(L1422486348;)V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitVarInsn(Opcodes.ALOAD,1);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"702433834","<init>","(L1422486348;)V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();out.visitEnd();return out.toByteArray();
    }
    public static void main(String[] args)throws Exception{java.nio.file.Files.write(java.nio.file.Path.of(args[0]),multisetFactory());}
}
