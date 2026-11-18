package npcdirector;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.*;
public final class NpcAgent implements Opcodes {
    public static synchronized void premain(String args,Instrumentation instrumentation){if(Boolean.getBoolean("sz.npc.agent.installed"))return;System.setProperty("sz.npc.agent.installed","true");instrumentation.addTransformer(new ClassFileTransformer(){
        public byte[] transform(ClassLoader loader,String name,Class<?> c,ProtectionDomain d,byte[] bytes){
            if(!java.util.Set.of("szmods/runtime/FpvPackets","499058674","519663047","1452958654","417850766","976516049").contains(name))return null;
            ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(ASM8,writer){public MethodVisitor visitMethod(int a,String n,String desc,String s,String[] ex){
                var next=super.visitMethod(a,n,desc,s,ex);
                if(name.equals("976516049"))return new MethodVisitor(ASM8,next){
                    public void visitInsn(int opcode){if(n.equals("assert")&&desc.equals("(L779455578;)[B")&&opcode==ARETURN){super.visitInsn(DUP);super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","retainFontBytes","([B)V",false);}super.visitInsn(opcode);}
                    public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){if(opcode==INVOKEVIRTUAL&&owner.equals("imgui/ImFontConfig")&&method.equals("destroy")&&descriptor.equals("()V")){super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","retainFontConfig","(Ljava/lang/Object;)V",false);return;}super.visitMethodInsn(opcode,owner,method,descriptor,itf);if(opcode==INVOKESTATIC&&owner.equals("imgui/ImGui")&&method.equals("destroyContext")&&descriptor.equals("()V"))super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","releaseFontInputs","()V",false);}
                };
                boolean raw=name.equals("szmods/runtime/FpvPackets")&&n.equals("rawCodec")&&desc.equals("(Ljava/lang/Object;)Z"),send=name.equals("499058674")&&n.equals("boolean")&&desc.equals("()V"),permission=name.equals("519663047")&&n.equals("transient")&&desc.equals("()Z"),window=(name.equals("1452958654")||name.equals("417850766"))&&n.equals("private")&&desc.equals("()V");
                if(!raw&&!send&&!permission&&!window)return next;
                return new MethodVisitor(ASM8,next){
                    public void visitCode(){super.visitCode();if(window){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","nativeFrame","(Ljava/lang/Object;)V",false);return;}if(!permission)super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge",permission?"nativePermissions":send?"nativeSend":"packet",permission?"()Z":"(Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(IFEQ,original);if(!send)super.visitInsn(ICONST_1);super.visitInsn(send?RETURN:IRETURN);super.visitLabel(original);super.visitFrame(F_SAME,0,null,0,null);}
                    public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){if(window&&opcode==INVOKESTATIC&&owner.equals("imgui/ImGui")&&method.equals("begin"))super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","nativePlacement","()V",false);if(window&&opcode==INVOKESTATIC&&owner.equals("imgui/ImGui")&&method.equals("end")&&descriptor.equals("()V"))super.visitMethodInsn(INVOKESTATIC,"npcdirector/NpcBridge","nativeStatus","()V",false);super.visitMethodInsn(opcode,owner,method,descriptor,itf);}
                };
            }},0);System.setProperty("sz.npc.transform."+name.replace('/','.'),"true");return writer.toByteArray();
        }
    });}
}
