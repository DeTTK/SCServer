package bscp.next;
import java.nio.file.*;
public final class BridgeHooksTest {
    static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass("StandardJvmBootstrap", bytes, 0, bytes.length); }
    }
    public static void main(String[] args) throws Exception {
        Bridge.output=Path.of(args[0]); Bridge.kind="server";
        Bridge.config.setProperty("serverJar",Path.of("overlay/server.jar").toAbsolutePath().toString());
        if(!Bridge.bundleRoot(Path.of("session")).equals(Path.of("overlay").toAbsolutePath()))throw new AssertionError("legacy root");
        Bridge.config.setProperty("serverRoot",Path.of("foundation/Server").toAbsolutePath().toString());
        if(!Bridge.bundleRoot(Path.of("session")).equals(Path.of("foundation/Server").toAbsolutePath()))throw new AssertionError("foundation root");
        Bridge.kind="client";
        if(!Bridge.bundleRoot(Path.of("client")).equals(Path.of("client")))throw new AssertionError("client root");
        byte[] original=Files.readAllBytes(Path.of(args[0],"StandardJvmBootstrap.class"));
        System.setProperty("bscp.qa.passiveObserver","true");
        byte[] patched=new ClientHooks().transform(null,"StandardJvmBootstrap",null,null,original);
        if(patched==null)throw new AssertionError("hook not installed");
        Object result=new Loader().define(patched).getMethod("probe",Object.class).invoke(null,new Object());
        if(!"{\"observerDisabled\":true}".equals(result))throw new AssertionError(result);
        System.clearProperty("bscp.qa.passiveObserver");
        if(new ClientHooks().transform(null,"StandardJvmBootstrap",null,null,original)!=null)throw new AssertionError("QA hook leaked into regular launch");
        System.out.println("Bridge paths and passive QA observer: PASS");
    }
}
