import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Launch-scoped guard for the legacy splash assets. The native startup latch,
 * GL context/window and full game initialization remain intact; installed files stay untouched. */
public final class StartupTextureAgent {
    private static volatile boolean itemAliasRegistered;
    private static volatile boolean instrumentationActive;
    /** JDK 17 can report causes absent from the legacy GC metric label registry. */
    public static void registerGcCause(Object observer,com.sun.management.GarbageCollectionNotificationInfo info) {
        try {
            ClassLoader loader=observer.getClass().getClassLoader();
            Class<?> metrics=Class.forName("1730921769",true,loader);
            java.lang.reflect.Method accessor=java.util.Arrays.stream(metrics.getDeclaredMethods())
                .filter(m->m.getName().equals("public")&&m.getParameterCount()==0&&m.getReturnType().getName().equals("329609426"))
                .findFirst().orElseThrow();accessor.setAccessible(true);
            Object metric=accessor.invoke(null);
            java.lang.reflect.Method register=Class.forName("969752710",false,loader).getDeclaredMethod("int",String.class);register.setAccessible(true);
            synchronized(metric){register.invoke(metric,info.getGcCause());}
        } catch(ReflectiveOperationException error) {
            throw new IllegalStateException("native GC cause label compatibility",error);
        }
    }
    /** The installed resources use a second label for the same native item kind. */
    public static void registered(String kind,java.util.function.Function<?,?> factory) {
        if (!kind.equals("backpack_style") || itemAliasRegistered) return;
        try {
            Class.forName("1497795389",true,factory.getClass().getClassLoader())
                .getMethod("private",String.class,java.util.function.Function.class)
                .invoke(null,"backpack_motif",factory);
            itemAliasRegistered=true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native backpack resource alias",error);
        }
    }
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentationActive=true;
        System.err.println("[Java play launch] Startup texture guard loaded");
        instrumentation.addTransformer(transformer());
    }
    /** The portable RAM loader applies the same fixes without requiring new launcher flags. */
    public static byte[] transformForLoader(String name, byte[] bytes) {
        if(instrumentationActive || !(name.equals("1497795389") || name.equals("464670247") || name.equals("1788203808"))) return bytes;
        try {
            byte[] transformed=transformer().transform(null,name,null,null,bytes);
            return transformed==null?bytes:transformed;
        } catch(IllegalClassFormatException error) {
            throw new IllegalStateException("Portable startup compatibility: "+name,error);
        }
    }
    private static ClassFileTransformer transformer() {
        return new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if ("464670247".equals(name)) {
                    ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                            MethodVisitor out=super.visitMethod(access,method,descriptor,signature,exceptions);
                            if(!method.equals("implements")||!descriptor.equals("(ZLcom/sun/management/GarbageCollectionNotificationInfo;J)V"))return out;
                            return new MethodVisitor(Opcodes.ASM9,out) {
                                @Override public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"StartupTextureAgent","registerGcCause","(Ljava/lang/Object;Lcom/sun/management/GarbageCollectionNotificationInfo;)V",false);}
                            };
                        }
                    },0);
                    return writer.toByteArray();
                }
                if ("1497795389".equals(name)) {
                    ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                            MethodVisitor out=super.visitMethod(access,method,descriptor,signature,exceptions);
                            if (!method.equals("private") || !descriptor.equals("(Ljava/lang/String;Ljava/util/function/Function;)V")) return out;
                            return new MethodVisitor(Opcodes.ASM9,out) {
                                @Override public void visitInsn(int opcode) {
                                    if (opcode==Opcodes.RETURN) {
                                        super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,"StartupTextureAgent","registered",descriptor,false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                    },0);
                    return writer.toByteArray();
                }
                if (!"1788203808".equals(name)) return null;
                try {
                ClassReader reader=new ClassReader(bytes);
                ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    @Override public MethodVisitor visitMethod(int access,String method,String descriptor,
                                                                String signature,String[] exceptions) {
                        MethodVisitor original=super.visitMethod(access,method,descriptor,signature,exceptions);
                        if (!method.equals("volatile") || !descriptor.equals("()Lkotlin/Pair;")) return original;
                        return new MethodVisitor(Opcodes.ASM9,original) {
                            @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean isInterface) {
                                if(owner.equals("1413333341") && method.equals("enum") && descriptor.equals("(L263463091;)V")) {
                                    super.visitInsn(Opcodes.POP);
                                    // Native texture capabilities query GL in <clinit>; preload on
                                    // this current context before asynchronous fonts can touch it.
                                    super.visitLdcInsn("398782077");
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
                                    super.visitInsn(Opcodes.POP);
                                    return;
                                }
                                if(owner.equals("1413333341") && method.equals("strictfp") && descriptor.equals("(L263463091;F)V")) { super.visitInsn(Opcodes.POP2); return; }
                                if(owner.equals("263463091") && method.equals("byte") && descriptor.equals("(II)V")) { super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP); return; }
                                super.visitMethodInsn(opcode,owner,method,descriptor,isInterface);
                            }
                        };
                    }
                },0);
                System.err.println("[Java play launch] Legacy splash skipped; native world renderer and startup lifecycle retained");
                return writer.toByteArray();
                } catch(Throwable error) {
                    System.err.println("[Java play launch] Startup guard transform failed: "+error);
                    error.printStackTrace();
                    return null;
                }
            }
        };
    }
}
