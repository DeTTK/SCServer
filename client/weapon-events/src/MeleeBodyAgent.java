import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import jdk.internal.org.objectweb.asm.*;

/** Complete the accepted native melee action with its per-actor third-person event. */
public final class MeleeBodyAgent implements Opcodes {
    private static final String PRODUCER="124763289",METHOD="(L1761650403;L351062380;I)V";
    public static final String BASE_SHA256="c6ef01a9264f2f7de4df1a08f9c569068b99ef25b64cb84363f7fd894e98676e";
    private static boolean installed;
    private static Instrumentation instrumentationOwner;
    private static ClassFileTransformer transformer;
    public static volatile String error="";
    public static void premain(String args,Instrumentation instrumentation)throws Exception{install(instrumentation);}
    public static void agentmain(String args,Instrumentation instrumentation)throws Exception{
        if("off".equals(args)){uninstall();return;}
        ReloadEventAgent.agentmain(args,instrumentation);install(instrumentation);
    }
    public static synchronized void uninstall()throws Exception{
        if(!installed)return;
        instrumentationOwner.removeTransformer(transformer);
        for(Class<?> type:instrumentationOwner.getAllLoadedClasses())if(type.getName().equals(PRODUCER))instrumentationOwner.retransformClasses(type);
        installed=false;System.setProperty("sz.weaponEvents.meleeBody","false");
    }
    public static synchronized void install(Instrumentation instrumentation)throws Exception{
        if(installed)return;
        transformer=new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(!PRODUCER.equals(name))return null;
                try{
                    // Earlier compatibility transformers may rewrite this factory, and
                    // retransformation may reorder its constant pool. Pin the installed
                    // resource; validate the action seam in the actual incoming bytes.
                    byte[] base;
                    try(var source=loader.getResourceAsStream(PRODUCER+".class")){
                        if(source==null)throw new IllegalStateException("Native melee resource unavailable");base=source.readAllBytes();
                    }
                    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(base));
                    if(!BASE_SHA256.equals(hash))throw new IllegalStateException("Native melee producer changed: "+hash);
                    byte[] output=patch(bytes);System.setProperty("sz.weaponEvents.meleeBody","true");
                    System.out.println("[WeaponEvents] Native melee FP and TP events routed to the originating actor");return output;
                }catch(Throwable failure){error=failure.toString();failure.printStackTrace();return new byte[]{0};}
            }
        };
        instrumentationOwner=instrumentation;instrumentation.addTransformer(transformer,true);
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals(PRODUCER))instrumentation.retransformClasses(type);
        installed=true;
    }
    private static boolean event(String owner){return owner.equals("1150336718")||owner.equals("876318404");}
    public static byte[] patch(byte[] input){
        int[] existing=new int[4];
        new ClassReader(input).accept(new ClassVisitor(ASM8){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!name.equals("default")||!descriptor.equals(METHOD))return null;
                existing[3]++;
                return new MethodVisitor(ASM8){@Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean face){
                    if(event(owner)&&name.equals("private")&&descriptor.equals("()V"))existing[0]++;
                    if(event(owner)&&name.equals("szFp_byte")&&descriptor.equals("(L1217489680;)V"))existing[1]++;
                    if(owner.equals("1478690128")&&name.equals("if")&&descriptor.equals("(L1217489680;L1739524891;)V"))existing[2]++;
                }};
            }
        },0);
        if(existing[3]!=1)throw new IllegalStateException("Native melee action method missing");
        if(existing[0]==0&&existing[1]==2&&existing[2]==2)return input;
        if(existing[0]!=2||existing[1]!=0||existing[2]!=0)throw new IllegalStateException("Melee producer seam changed");
        int[] edits={0};ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(input).accept(new ClassVisitor(ASM8,writer){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                MethodVisitor out=super.visitMethod(access,name,descriptor,signature,exceptions);
                if(!name.equals("default")||!descriptor.equals(METHOD))return out;
                return new MethodVisitor(ASM8,out){@Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean face){
                    if(event(owner)&&name.equals("private")&&descriptor.equals("()V")){
                        if(opcode!=INVOKEVIRTUAL)throw new IllegalStateException("Native melee FP opcode changed");
                        // Slot 4 is the native source actor, checked/cast before either attack branch.
                        super.visitVarInsn(ALOAD,4);
                        super.visitMethodInsn(INVOKEVIRTUAL,owner,"szFp_byte","(L1217489680;)V",false);
                        super.visitVarInsn(ALOAD,4);
                        super.visitMethodInsn(INVOKESTATIC,"1739524891",owner.equals("876318404")?"continue":"catch","()L1739524891;",false);
                        super.visitMethodInsn(INVOKESTATIC,"1478690128","if","(L1217489680;L1739524891;)V",false);
                        edits[0]++;return;
                    }
                    super.visitMethodInsn(opcode,owner,name,descriptor,face);
                }};
            }
        },0);
        if(edits[0]!=2)throw new IllegalStateException("Incomplete melee event patch");
        return writer.toByteArray();
    }
}
