package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Narrow repairs for native text fields, item-browser input and an uninitialised map UI. */
public final class UiStabilityTransformer implements ClassFileTransformer {
    private static final Map<String,String> BASES=Map.of(
        "978664536","9db4ac95db98c97a2b0471cac8a5ecd7fc8fa531a1f15951667b4931a3f96259",
        "414345046","de30457faa50d4a90aaaf0e443a131f8891ed31f02d3ee7719ba5c694ffc8c50",
        "1883402698","502276f1455ce0373f01988fc480faf4fc5375683b7557edaf731a12674f6580");

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        if(!BASES.containsKey(name==null?"":name))return null;
        try {
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!BASES.get(name).equals(sha))throw new IllegalStateException("Native UI base changed: "+name);
            ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,ClassReader.EXPAND_FRAMES);
            int edits=0;
            for(MethodNode m:c.methods) {
                if(name.equals("978664536")&&!m.name.startsWith("<")&&(m.access&Opcodes.ACC_STATIC)==0) {
                    // Event callbacks can shorten the text before the original caret update runs.
                    // Normalise before any native substring/charAt operation, including rendering.
                    boolean readsText=false;
                    for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode call&&call.owner.equals("java/lang/String")&&(call.name.equals("substring")||call.name.equals("charAt")))readsText=true;
                    if(readsText) {m.instructions.insert(clampText());edits++;}
                }
                if(name.equals("414345046")&&m.name.equals("catch")&&m.desc.equals("(L414345046;L1286843023;)V")) {
                    for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode call&&call.owner.equals("java/lang/Integer")&&call.name.equals("parseInt")&&call.desc.equals("(Ljava/lang/String;)I")) {
                        // A partially typed number is not a new value. Keep the previous count.
                        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
                        m.instructions.insertBefore(i,start);m.instructions.insert(i,end);
                        m.instructions.add(handler);
                        m.instructions.add(new FrameNode(Opcodes.F_NEW,3,new Object[]{"414345046","1286843023","java/lang/String"},1,new Object[]{"java/lang/NumberFormatException"}));
                        m.instructions.add(new InsnNode(Opcodes.POP));m.instructions.add(new InsnNode(Opcodes.RETURN));
                        m.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,"java/lang/NumberFormatException"));edits++;
                    }
                }
                if(name.equals("1883402698")&&m.name.equals("this")&&m.desc.equals("(Ljava/lang/Boolean;)L36549396;")) {
                    for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode call&&call.owner.equals("java/util/List")&&call.name.equals("isEmpty")&&call.desc.equals("()Z")) {
                        m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/UiState","nullOrEmpty","(Ljava/util/List;)Z",false));edits++;
                    }
                }
                if(name.equals("1883402698")&&m.name.equals("instanceof")&&m.desc.equals("()L1235189211;")) {
                    LabelNode ready=new LabelNode();InsnList guard=new InsnList();
                    guard.add(new VarInsnNode(Opcodes.ALOAD,0));guard.add(new FieldInsnNode(Opcodes.GETFIELD,name,"byte","L1235189211;"));
                    guard.add(new JumpInsnNode(Opcodes.IFNONNULL,ready));guard.add(new InsnNode(Opcodes.ACONST_NULL));guard.add(new InsnNode(Opcodes.ARETURN));
                    guard.add(ready);guard.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{name},0,new Object[]{}));m.instructions.insert(guard);edits++;
                }
            }
            int expected=name.equals("978664536")?9:name.equals("1883402698")?2:1;
            if(edits!=expected)throw new IllegalStateException("Native UI seam count "+name+": "+edits+" != "+expected);
            ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);c.accept(out);
            System.err.println("[UI stability] Installed native UI repair: "+name+" ("+edits+" seams)");return out.toByteArray();
        }catch(Exception error){System.err.println("[UI stability] Unsupported native UI; original retained: "+error);return null;}
    }

    private static InsnList clampText() {
        InsnList code=new InsnList();
        for(String field:new String[]{"enum","class","char"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD,0));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,"978664536",field,"I"));
            code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,"978664536","switch","Ljava/lang/String;"));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/String","length","()I",false));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","min","(II)I",false));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","max","(II)I",false));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD,"978664536",field,"I"));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD,0));
        code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,"978664536","char","I"));
        for(String field:new String[]{"enum","class"}) {code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,"978664536",field,"I"));}
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","max","(II)I",false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","min","(II)I",false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD,"978664536","char","I"));
        return code;
    }
}
