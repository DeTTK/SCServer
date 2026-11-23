package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Binding to the captured native config/controller constructors. */
final class NativeStorageLayoutRelease {
    private static final Map<String,String> CLASSES=Map.ofEntries(Map.entry("929397021","493b43baae6b18fce60c83a4b26037a7c23f1d53986472b6dcf0d522bc51ca92"),
        Map.entry("140755797","5080eef67ed71112675911f637432fb0d2f072b6a8f90e5017c1d8df07225826"),
        Map.entry("787606044","580fc36c37694bbe706b77c1ec2df80ec28bbb73df9ba80b6d7a866fcd305127"),
        Map.entry("669802847","4c9b052f7fae30fd8e3ab005d2cd4f6abc1f79f1de8cfc71b305cd346df9e1d0"),
        Map.entry("1925377032","3d86f87c35dcb0e90f8453b63ad7f2085ccc8ee247707d6f9ecdb8cd407bacfe"),
        Map.entry("1028727254","921604c8ee1f5276ccefc8f7f0ca2caf10977a01ca80d23ad2fb306af7083fed"),
        Map.entry("1968301976","bc3a61e0e8caeda3ff66b923ba89ae2cf534daaf90cf185c801d0b86d1d6b8f1"),
        Map.entry("175688206","742d30deafd4018a48dfd3f2348556f080403e872bcd3b2a7a09f5c2f1550d9e"));
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(file)));}
    static void verifyRuntime(Path runtime)throws Exception{for(var e:CLASSES.entrySet())if(!hash(runtime.resolve("classes").resolve(e.getKey()+".class")).equals(e.getValue()))throw new IllegalStateException("native_storage_runtime_changed:"+e.getKey());}
    static void verifyFile(Path file)throws Exception{if(!hash(file).equals("15d20053512147be39b42aa14a6bcbb0442483d6d323b3d2e627b528dd0eb6a1"))throw new IllegalStateException("native_storage_layout_changed");}
}
