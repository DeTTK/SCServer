package bscp.next;
import java.nio.file.*;
import java.util.Base64;
public final class RelayHooksTest {
    static final class Loader extends ClassLoader {Class<?> define(byte[] bytes){return defineClass("RelayAddresses",bytes,0,bytes.length);}}
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
        System.out.println("PASS relay TCP/UDP port mapping, ordinary ports preserved, deferred native session and opt-in hooks");
    }
}
