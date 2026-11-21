package playerscale;

import java.io.*;
import java.lang.instrument.*;
import java.security.*;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

/** Exact-version native adapters. Original class files on disk remain intact. */
public final class ScaleAgent {
    public static volatile boolean installed,healthy;
    public static final Set<String> transformed=Collections.synchronizedSet(new HashSet<>());
    public static void premain(String ignored,Instrumentation instrumentation)throws Exception {
        Module own=ScaleAgent.class.getModule();instrumentation.redefineModule(Object.class.getModule(),Set.of(),Map.of("jdk.internal.org.objectweb.asm",Set.of(own)),Map.of(),Set.of(),Map.of());
        Properties hashes=new Properties();try(InputStream in=ScaleAgent.class.getResourceAsStream("/playerscale-native.properties")){if(in==null)throw new IOException("character_scale_release_missing");hashes.load(in);}
        for(String name:hashes.stringPropertyNames())try(InputStream in=ClassLoader.getSystemResourceAsStream(name+".class")){
            if(in==null||!hashes.getProperty(name).equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())))){
                System.err.println("[CharacterScale] disabled: incompatible client class "+name);return;
            }
        }
        healthy=true;instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] raw){
                if(!hashes.containsKey(name)&&!name.equals("szmods/runtime/ModLoader"))return null;
                try{String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));if(hashes.containsKey(name)&&!digest.equals(hashes.getProperty(name)))throw new IOException("character_scale_transform_chain:"+name);
                    byte[] result=patch(name,raw);transformed.add(name);return result;
                }catch(Throwable failure){healthy=false;System.err.println("[CharacterScale] adapter disabled: "+failure);return null;}
            }
        });installed=true;
    }
    static byte[] patch(String name,byte[] raw){
        ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] changes={0};
        reader.accept(new ClassVisitor(ASM8,writer){
            public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){
                boolean render=name.equals("175605189")&&method.equals("public")&&desc.equals("(L1253930690;FFZ)V");
                if(render){changes[0]++;return super.visitMethod(access,"szScale$original$public",desc,signature,exceptions);}
                MethodVisitor original=super.visitMethod(access,method,desc,signature,exceptions);
                return new MethodVisitor(ASM8,original){
                    public void visitCode(){super.visitCode();
                        if(name.equals("szmods/runtime/ModLoader")&&method.equals("frame")&&desc.equals("(Ljava/lang/Object;)V")){visitVarInsn(ALOAD,0);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","frame","(Ljava/lang/Object;)V",false);changes[0]++;}
                        if(name.equals("1943200621")&&method.equals("long")&&desc.equals("()V")){visitVarInsn(ALOAD,0);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","body","(Ljava/lang/Object;)V",false);changes[0]++;}
                        if(name.equals("1943200621")&&method.equals("do")&&desc.equals("(L200241649;)V")){visitVarInsn(ALOAD,0);visitVarInsn(ALOAD,1);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","bind","(Ljava/lang/Object;Ljava/lang/Object;)V",false);changes[0]++;}
                    }
                    public void visitInsn(int opcode){
                        if(name.equals("1943200621")&&method.equals("import")&&desc.equals("()F")&&opcode==FRETURN){visitVarInsn(ALOAD,0);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","eye","(FLjava/lang/Object;)F",false);changes[0]++;}
                        super.visitInsn(opcode);
                    }
                    public void visitFieldInsn(int opcode,String owner,String field,String fieldDesc){
                        super.visitFieldInsn(opcode,owner,field,fieldDesc);
                        if(name.equals("1389510037")&&method.equals("szmods$flight$enum")&&opcode==GETFIELD&&owner.equals("1987590071")&&Set.of("assert","byte","catch").contains(field)&&fieldDesc.equals("F")){
                            visitVarInsn(ALOAD,0);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","camera","(FLjava/lang/Object;)F",false);changes[0]++;
                        }
                    }
                    public void visitLdcInsn(Object value){
                        super.visitLdcInsn(value);
                        if(name.equals("981501872")&&method.equals("szmods$original$synchronized")&&value instanceof Double distance&&distance==3d){
                            visitVarInsn(ALOAD,2);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","factor","(Ljava/lang/Object;)F",false);visitInsn(F2D);visitInsn(DMUL);changes[0]++;
                        }
                    }
                    public void visitMethodInsn(int opcode,String owner,String target,String targetDesc,boolean itf){
                        if(name.equals("873944585")&&method.equals("szmods$animation$transient")&&desc.equals("(L351062380;)L578131936;")&&owner.equals("578131936")&&targetDesc.equals("(F)V")&&(target.equals("finally")||target.equals("float"))){
                            visitVarInsn(ALOAD,1);super.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime",target.equals("finally")?"animationSpeed":"animationCoefficient","(FLjava/lang/Object;)F",false);changes[0]++;
                        }
                        if(name.equals("803707421")&&method.equals("assert")&&desc.equals("(L200241649;)L771745706;")&&owner.equals("1363883020")&&target.equals("super")&&targetDesc.equals("(L153304200;FF)V")){
                            visitVarInsn(ALOAD,1);super.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","hit","(Ljava/lang/Object;Ljava/lang/Object;FFLjava/lang/Object;)V",false);changes[0]++;return;
                        }
                        super.visitMethodInsn(opcode,owner,target,targetDesc,itf);
                        if(name.equals("656386660")&&method.equals("goto")&&desc.equals("(Z)Z")&&owner.equals("1597325473")&&target.equals("transient")&&targetDesc.equals("(L1217489680;F)V")){
                            // The native outer push/finally owns this matrix. Scale after
                            // world-to-foot placement, before local body offsets and pivots.
                            visitVarInsn(ALOAD,5);visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","firstPersonBody","(Ljava/lang/Object;)V",false);changes[0]++;
                        }
                    }
                };
            }
            public void visitEnd(){if(name.equals("175605189"))renderWrapper(cv);super.visitEnd();}
        },0);
        int expected=switch(name){case "175605189","803707421","981501872","656386660","szmods/runtime/ModLoader"->1;case "873944585"->2;case "1943200621","1389510037"->3;default->throw new IllegalArgumentException(name);};
        if(changes[0]!=expected)throw new IllegalStateException("character_scale_hook_count:"+name+":"+changes[0]);return writer.toByteArray();
    }
    private static void renderWrapper(ClassVisitor visitor){
        MethodVisitor m=visitor.visitMethod(ACC_PUBLIC,"public","(L1253930690;FFZ)V",null,null);m.visitCode();
        m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","renderBegin","(Ljava/lang/Object;)Z",false);m.visitVarInsn(ISTORE,5);
        Label start=new Label(),end=new Label(),handler=new Label();m.visitTryCatchBlock(start,end,handler,null);m.visitLabel(start);
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitVarInsn(FLOAD,2);m.visitVarInsn(FLOAD,3);m.visitVarInsn(ILOAD,4);m.visitMethodInsn(INVOKEVIRTUAL,"175605189","szScale$original$public","(L1253930690;FFZ)V",false);
        m.visitLabel(end);m.visitVarInsn(ILOAD,5);m.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","renderEnd","(Z)V",false);m.visitInsn(RETURN);
        m.visitLabel(handler);m.visitFrame(F_FULL,6,new Object[]{"175605189","1253930690",FLOAT,FLOAT,INTEGER,INTEGER},1,new Object[]{"java/lang/Throwable"});
        m.visitVarInsn(ASTORE,6);m.visitVarInsn(ILOAD,5);m.visitMethodInsn(INVOKESTATIC,"playerscale/ScaleRuntime","renderEnd","(Z)V",false);m.visitVarInsn(ALOAD,6);m.visitInsn(ATHROW);m.visitMaxs(0,0);m.visitEnd();
    }
}
