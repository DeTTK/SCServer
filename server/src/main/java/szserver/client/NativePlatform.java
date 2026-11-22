package szserver.client;

/** Picks the JNI directory of the pinned zstd-jni JAR that matches the running host.
 * The server JAR ships both win/amd64 and linux/amd64 natives; loading the wrong one
 * fails with "not a valid ELF header" or a missing DLL depending on the direction. */
final class NativePlatform {
    private NativePlatform() {}
    static String directory() {
        String os=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
        String arch=System.getProperty("os.arch","").toLowerCase(java.util.Locale.ROOT);
        if(!arch.equals("amd64")&&!arch.equals("x86_64"))throw new IllegalStateException("unsupported_zstd_architecture:"+arch);
        if(os.startsWith("windows"))return "win";
        if(os.startsWith("linux"))return "linux";
        throw new IllegalStateException("unsupported_zstd_platform:"+os);
    }
    static String extension(){return directory().equals("win")?".dll":".so";}
    static String resource(){return directory()+"/amd64/libzstd-jni-1.5.0-5"+extension();}
    static String sha256(){return directory().equals("win")
        ?"cd5142c5d80c839fb54e778d73178424e7900ec189eeb4fe7ef49573555050c9"
        :"f9c701c1c53dc1616ab361c97d01d59fe93b6c13cc6d6dc3871658de14b550aa";}
}
