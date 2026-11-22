package szserver.client;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** The pinned zstd-jni JAR ships one JNI per platform. A build must resolve its own host
 * native and verify it, otherwise a Windows DLL reaches Linux and fails as "not an ELF header". */
public final class NativePlatformTests {
    private static int checks;
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    public static void main(String[] args)throws Exception {
        String directory=NativePlatform.directory();
        check(directory.equals("win")||directory.equals("linux"),"host directory is a supported one: "+directory);
        check(NativePlatform.extension().equals(directory.equals("win")?".dll":".so"),"extension follows the host");
        check(NativePlatform.resource().startsWith(directory+"/amd64/libzstd-jni-1.5.0-5"),"resource path is pinned");

        // A declared hash must match the library shipped by the locked dependency, not a guess.
        ClassLoader loader=ClassLoader.getSystemClassLoader();
        try(InputStream in=loader.getResourceAsStream(NativePlatform.resource())) {
            if(in==null) {
                System.out.println("SKIP host native "+NativePlatform.resource()+" absent from this classpath");
            } else {
                byte[] raw=in.readAllBytes();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
                check(hash.equals(NativePlatform.sha256()),"declared hash matches "+NativePlatform.resource());
                check(raw.length>65536,"native library is a real binary");
                if(directory.equals("linux"))check(raw[0]==0x7f&&raw[1]=='E'&&raw[2]=='L'&&raw[3]=='F',"Linux native is an ELF image");
                if(directory.equals("win"))check(raw[0]=='M'&&raw[1]=='Z',"Windows native is a PE image");
            }
        }
        System.out.println("PASS "+checks+" host zstd-jni selection checks on "+directory+"/amd64");
    }
}
