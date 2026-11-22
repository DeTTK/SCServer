package szserver.client;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Host JNI selection for the pinned dependency; extraction is committed only after verification. */
final class NativePlatform {
    private NativePlatform(){}
    static String directory(){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);
        if(!arch.equals("amd64")&&!arch.equals("x86_64"))throw new IllegalStateException("unsupported_zstd_architecture:"+arch);
        if(os.startsWith("windows"))return "win";
        if(os.startsWith("linux"))return "linux";
        throw new IllegalStateException("unsupported_zstd_platform:"+os);
    }
    static String extension(){return directory().equals("win")?".dll":".so";}
    static String resource(){return directory()+"/amd64/libzstd-jni-1.5.0-5"+extension();}
    static String sha256(){return directory().equals("win")?"cd5142c5d80c839fb54e778d73178424e7900ec189eeb4fe7ef49573555050c9":"f9c701c1c53dc1616ab361c97d01d59fe93b6c13cc6d6dc3871658de14b550aa";}
    static Path extract(ClassLoader loader,String prefix)throws IOException{
        String resource=resource(),extension=extension(),expected=sha256();Path path=Files.createTempFile(prefix,extension);boolean verified=false;
        try{
            try(InputStream in=loader.getResourceAsStream(resource)){if(in==null)throw new IOException("bundled_zstd_jni_missing:"+resource);Files.copy(in,path,StandardCopyOption.REPLACE_EXISTING);}
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            if(!actual.equals(expected))throw new IOException("bundled_zstd_jni_mismatch");
            path.toFile().deleteOnExit();verified=true;return path;
        }catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}finally{if(!verified)Files.deleteIfExists(path);}
    }
}
