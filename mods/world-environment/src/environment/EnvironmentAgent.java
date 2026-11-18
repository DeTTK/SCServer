package environment;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.*;

/** Scoped hooks retain the installed native class base and the original editor implementation. */
public final class EnvironmentAgent implements Opcodes {
    public static synchronized void premain(String args,Instrumentation instrumentation) {
        if(Boolean.getBoolean("sz.environment.agent.installed"))return;
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> redefined,ProtectionDomain domain,byte[] bytes){
                if(name==null||!java.util.Set.of("StandardJvmBootstrap","1049587979","1461113355","1442200882","1747837509","1730472284","szmods/runtime/FpvPackets").contains(name))return null;
                try {
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);boolean[] changed={false};
                    reader.accept(new ClassVisitor(ASM8,writer){
                        public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                            MethodVisitor next=super.visitMethod(access,method,descriptor,signature,exceptions);
                            String early=null;boolean argument=true;
                            if(name.equals("StandardJvmBootstrap")&&descriptor.equals("(Ljava/lang/Object;)V"))early=switch(method){case "synchronizeVisualWorldClock"->"clock";case "synchronizeVisualWeather"->"weather";case "prepareEnvironmentClockPacket"->"clockPacket";default->null;};
                            if(name.equals("1461113355")&&method.equals("native")&&descriptor.equals("()V"))early="nativeDayTick";
                            if(name.equals("1747837509")&&method.equals("transient")&&descriptor.equals("()V"))early="saveEditor";
                            final String guard=early;
                            final boolean codec=name.equals("szmods/runtime/FpvPackets")&&method.equals("rawCodec")&&descriptor.equals("(Ljava/lang/Object;)Z");
                            final boolean weights=name.equals("1442200882")&&method.equals("short")&&descriptor.equals("(DF)Ljava/util/Map;");
                            final boolean opened=name.equals("1747837509")&&method.equals("void")&&descriptor.equals("()V");
                            final boolean controls=name.equals("1730472284")&&method.equals("goto")&&descriptor.equals("()V");
                            final boolean cloud=name.equals("1730472284")&&method.equals("private")&&descriptor.equals("()V");
                            final boolean fallback=name.equals("1049587979")&&method.equals("byte")&&descriptor.equals("(L1987590071;L929635122;)L648055939;");
                            if(guard==null&&!codec&&!weights&&!opened&&!controls&&!cloud&&!fallback)return next;
                            changed[0]=true;
                            return new MethodVisitor(ASM8,next){
                                public void visitCode(){super.visitCode();
                                    if(guard!=null||codec){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge",codec?"packet":guard,"(Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(IFEQ,original);if(codec)super.visitInsn(ICONST_1);super.visitInsn(codec?IRETURN:RETURN);super.visitLabel(original);super.visitFrame(F_SAME,0,null,0,null);}
                                }
                                public void visitInsn(int op){
                                    if(weights&&op==ARETURN){super.visitVarInsn(FLOAD,3);super.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge","weights","(Ljava/util/Map;F)Ljava/util/Map;",false);}
                                    if((opened||controls)&&op==RETURN){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge",opened?"editorOpened":"controls","(Ljava/lang/Object;)V",false);}
                                    super.visitInsn(op);
                                }
                                public void visitMethodInsn(int opcode,String owner,String called,String desc,boolean itf){
                                    if(cloud&&opcode==INVOKESTATIC&&owner.equals("220710426")&&called.equals("final")&&desc.equals("()Z"))super.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge","cloudBakeAllowed","()Z",false);
                                    else super.visitMethodInsn(opcode,owner,called,desc,itf);
                                }
                                public void visitFieldInsn(int opcode,String owner,String field,String desc){
                                    super.visitFieldInsn(opcode,owner,field,desc);
                                    // This branch is used only when no native weather region covers the camera.
                                    if(fallback&&opcode==GETSTATIC&&owner.equals("1049587979")&&field.equals("void")&&desc.equals("L1275877375;")){
                                        super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"environment/EnvironmentBridge","defaultProfile","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(CHECKCAST,"1275877375");
                                    }
                                }
                            };
                        }
                    },0);
                    if(changed[0]){System.setProperty("sz.environment.transform."+name.replace('/','.'),"true");return writer.toByteArray();}return null;
                }catch(Throwable error){System.setProperty("sz.environment.transform.error",name+": "+error);error.printStackTrace();return null;}
            }
        });
        System.setProperty("sz.environment.agent.installed","true");
    }
}
