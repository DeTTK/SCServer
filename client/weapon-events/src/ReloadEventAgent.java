import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;
import jdk.internal.org.objectweb.asm.*;

/** Scope the native first-person reload event; leave per-actor third-person events intact. */
public final class ReloadEventAgent implements Opcodes {
    private static boolean installed;
    public static final AtomicLong localReloads=new AtomicLong(),remoteReloads=new AtomicLong();
    public static volatile String error="";
    public static void premain(String args,Instrumentation instrumentation)throws Exception{install(instrumentation);}
    public static void agentmain(String args,Instrumentation instrumentation)throws Exception{install(instrumentation);}
    private static synchronized void install(Instrumentation instrumentation)throws Exception{
        if(installed)return;
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(!name.equals("1810277368"))return null;
                try{byte[] output=patch(bytes);System.setProperty("sz.weaponEvents.reloadScoped","true");System.out.println("[WeaponEvents] Native reload FP event scoped to source actor");return output;}
                catch(Throwable failure){error=failure.toString();failure.printStackTrace();throw new ClassFormatError("Reload event contract changed: "+failure);}
            }
        },true);
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("1810277368"))instrumentation.retransformClasses(type);
        MeleeBodyAgent.install(instrumentation);
        ReloadClipAgent.install(instrumentation);
        MeleeInventoryAgent.install(instrumentation);
        MeleeStyleAgent.install(instrumentation);
        StorageInventoryViewAgent.install(instrumentation);
        HeldEffectDeathAgent.install(instrumentation);
        installed=true;
        // Older launchers already start this companion. Discover the optional environment
        // companion beside it so a runtime update also upgrades those launchers correctly.
        java.nio.file.Path companion=null;
        java.net.URL origin=ReloadEventAgent.class.getProtectionDomain().getCodeSource().getLocation();
        if(origin.getProtocol().equals("file"))companion=java.nio.file.Path.of(origin.toURI()).getParent().resolve("world-environment.jar");
        else for(String argument:java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())if(argument.startsWith("-javaagent:")){
            java.nio.file.Path agent=java.nio.file.Path.of(argument.substring(11).split("=",2)[0]);
            if(agent.getFileName().toString().equals("weapon-events.jar")){companion=agent.toAbsolutePath().getParent().resolve("world-environment.jar");break;}
        }
        if(companion!=null&&java.nio.file.Files.isRegularFile(companion)){
            instrumentation.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(companion.toFile()));
            Class.forName("environment.EnvironmentAgent",true,ClassLoader.getSystemClassLoader()).getMethod("premain",String.class,Instrumentation.class).invoke(null,"",instrumentation);
        }
    }
    public static byte[] patch(byte[] input){
        int[] found={0,0};ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(input).accept(new ClassVisitor(ASM8,writer){
            public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                MethodVisitor out=super.visitMethod(access,name,desc,signature,exceptions);
                if(!name.equals("private")||!desc.equals("(FFFL211097523;ZZZILjava/lang/String;)V"))return out;
                return new MethodVisitor(ASM8,out){
                    public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean face){
                        if(owner.equals("318048108")&&name.equals("private")&&descriptor.equals("()V")){
                            if(opcode!=INVOKEVIRTUAL)throw new IllegalStateException("Reload dispatch opcode");
                            super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"1810277368","assert","L200241649;");
                            super.visitInsn(DUP);super.visitMethodInsn(INVOKESTATIC,"ReloadEventAgent","observe","(Ljava/lang/Object;)V",false);
                            super.visitMethodInsn(INVOKEVIRTUAL,owner,"szFp_byte","(L1217489680;)V",false);found[0]++;return;
                        }
                        if(owner.equals("318048108")&&name.equals("szFp_byte")&&descriptor.equals("(L1217489680;)V"))found[1]++;
                        super.visitMethodInsn(opcode,owner,name,descriptor,face);
                    }
                };
            }
        },0);
        if(found[0]+found[1]!=1)throw new IllegalStateException("Expected one FP reload producer, found "+found[0]+"/"+found[1]);
        return found[0]==0?input:writer.toByteArray();
    }
    public static void observe(Object source){
        if(!Boolean.getBoolean("sz.weaponEvents.audit"))return;
        try{
            Class<?> gameType=Class.forName("1402131154");Object game=null,local=null;
            for(var method:gameType.getMethods())if(method.getName().equals("try")&&method.getParameterCount()==0&&method.getReturnType()==gameType)game=method.invoke(null);
            for(var method:gameType.getMethods())if(method.getName().equals("throws")&&method.getParameterCount()==0&&method.getReturnType().getName().equals("961798934"))local=method.invoke(game);
            if(local==null)throw new IllegalStateException("Native local actor unavailable");
            if(source==local)localReloads.incrementAndGet();else remoteReloads.incrementAndGet();
        }catch(Exception failure){error=failure.toString();}
    }
}
