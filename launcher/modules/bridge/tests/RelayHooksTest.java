package bscp.next;
import java.nio.file.*;
import java.util.Base64;
import jdk.internal.org.objectweb.asm.*;
public final class RelayHooksTest {
    static final class Loader extends ClassLoader {Class<?> define(byte[] bytes){return defineClass("RelayAddresses",bytes,0,bytes.length);}}

    static void verifyHandshakeRetry() throws Exception {
        String owner = "798464807";
        ClassWriter fixture = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fixture.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        fixture.visitField(Opcodes.ACC_PUBLIC, "strictfp", "I", null, null).visitEnd();
        MethodVisitor constructor = fixture.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor hello = fixture.visitMethod(Opcodes.ACC_PUBLIC, "transient", "(I)V", null, null);
        hello.visitCode();
        hello.visitInsn(Opcodes.RETURN);
        hello.visitMaxs(0, 0);
        hello.visitEnd();
        fixture.visitEnd();
        byte[] patched = new ClientHooks().transform(null, owner, null, null, fixture.toByteArray());
        if (patched == null) throw new AssertionError("handshake hook missing");
        Class<?> type = new ClassLoader() {
            Class<?> loadPatched() { return defineClass(owner, patched, 0, patched.length); }
        }.loadPatched();
        Object connection = type.getConstructor().newInstance();
        var id = type.getField("strictfp");
        var sendHello = type.getMethod("transient", int.class);
        int initialId = 1979561914;
        sendHello.invoke(connection, initialId);
        int retryId = id.getInt(connection);
        if (retryId != initialId) throw new AssertionError("lost first hello would change the connection ID");
        sendHello.invoke(connection, retryId);
        if (id.getInt(connection) != initialId) throw new AssertionError("repeated hello changed the connection ID");
    }
    public static void main(String[] args)throws Exception{
        Bridge.output=Path.of(args[0]);Bridge.config.setProperty("relayRemoteBase","29545");Bridge.config.setProperty("relayGamePort","41001");Bridge.config.setProperty("relayWorldPort","41002");
        byte[] original=Files.readAllBytes(Path.of(args[0],"RelayAddresses.class"));byte[] patched=new ClientHooks().transform(null,"RelayAddresses",null,null,original);if(patched==null)throw new AssertionError("relay hooks missing");
        Class<?> type=new Loader().define(patched);if(!type.getMethod("game").invoke(null).equals(41001)||!type.getMethod("world").invoke(null).equals(41002)||!type.getMethod("unchanged").invoke(null).equals(443))throw new AssertionError("incorrect mapping");
        Path file=Path.of(args[0],"deferred-test-"+java.util.UUID.randomUUID()+".json");Bridge.config.setProperty("deferredSessionFile",file.toString());Bridge.config.setProperty("nativeSession","pending-fixture");
        Thread writer=new Thread(()->{try{Thread.sleep(250);Files.writeString(file,"\""+Base64.getEncoder().encodeToString("real-fixture-session".getBytes())+"\"");}catch(Exception e){throw new RuntimeException(e);}});writer.start();
        String[] actual=Bridge.clientArguments(new String[]{"--session","launcher-managed"});writer.join();if(!actual[1].equals("real-fixture-session"))throw new AssertionError("deferred session did not resolve");
        Bridge.config.setProperty("deferredSessionAlias","true");Bridge.config.setProperty("deferredSessionFile",Path.of(args[0],"not-created-session.json").toString());
        if(!Bridge.clientArguments(new String[]{"--session","launcher-managed"})[1].equals("pending-fixture"))throw new AssertionError("startup alias waited for a backend session");Bridge.config.remove("deferredSessionAlias");
        Bridge.config.remove("deferredSessionFile");Bridge.config.remove("relayRemoteBase");if(new ClientHooks().transform(null,"RelayAddresses",null,null,original)!=null)throw new AssertionError("relay hook leaked into direct mode");
        verifyHandshakeRetry();
        System.out.println("PASS relay port mapping, deferred session and stable UDP handshake retries");
    }
}
