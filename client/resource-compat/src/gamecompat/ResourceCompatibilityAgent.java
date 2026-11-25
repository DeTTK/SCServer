package gamecompat;
import java.lang.instrument.*;import java.security.ProtectionDomain;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
/** One construction seam; installed game classes and their original files stay unchanged. */
public final class ResourceCompatibilityAgent {
 public static synchronized void premain(String options,Instrumentation agent){
  if(Boolean.getBoolean("sz.client.resourceCompat.installed"))return;
  System.setProperty("sz.client.resourceCompat.installed","true");
  System.setProperty("sz.client.resourceSchema","20261030");
  if(AttributeWireIds.baseSize()!=469)throw new IllegalStateException("wire table unavailable");
  agent.addTransformer(new ResourceSchemaTransformer());
  agent.addTransformer(new OptionalResourceTransformer());
  agent.addTransformer(new UiStabilityTransformer());
  agent.addTransformer(new UdpPolicyTransformer());
  agent.addTransformer(new PerspectivePolicyTransformer());
  agent.addTransformer(new EquipmentBindingsTransformer());
  agent.addTransformer(new SceneHashTransformer());
  agent.addTransformer(new ClassFileTransformer(){public byte[] transform(ClassLoader loader,String name,Class<?> redefined,ProtectionDomain domain,byte[] original){
   if(!"1993742576".equals(name))return null;
   try{String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));if(!sha.equals(AttributeWireIds.expectedBase()))throw new IllegalStateException("native attribute base changed");var node=new ClassNode();new ClassReader(original).accept(node,0);int edits=0;
    for(var method:node.methods)if(method.name.equals("synchronized")&&method.desc.equals("(Ljava/lang/String;)L1961828072;")){
     for(var insn:method.instructions.toArray())if(insn instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESPECIAL&&call.owner.equals("1961828072")&&call.name.equals("<init>")){
      // Native constructor operands are: short id, name, nullable marker.
      AbstractInsnNode marker=insn.getPrevious(),text=marker.getPrevious(),id=text.getPrevious();
      if(marker.getOpcode()!=Opcodes.ACONST_NULL||!(text instanceof VarInsnNode v)||v.getOpcode()!=Opcodes.ALOAD||v.var!=1||!(id instanceof VarInsnNode n)||n.getOpcode()!=Opcodes.ILOAD)throw new IllegalStateException("native attribute constructor seam changed");
      var hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/AttributeWireIds","resolve","(ILjava/lang/String;)I",false));hook.add(new InsnNode(Opcodes.I2S));method.instructions.insert(id,hook);edits++;
     }
    }
    if(edits!=1)throw new IllegalStateException("native attribute factory changed: "+edits);var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);System.err.println("[Game resource compatibility] Native wire attributes retain the verified server epoch");return writer.toByteArray();
   }catch(Throwable error){System.err.println("[Game resource compatibility] FAILED: "+error);return new byte[]{0};}
  }});
 }
}
