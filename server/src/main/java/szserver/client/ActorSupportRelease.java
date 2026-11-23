package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Installed support-query dependencies, in addition to the controller/solver guards. */
final class ActorSupportRelease {
    private static final Map<String,String> HASHES=Map.of(
        "433657566","bee95cb0d9069516daa45d5f7e1e5e74a55cc6bcf07111f6eebaf1d8cb839c02",
        "906913290","5b4a30dfb7bd57e085f1280dcac3a02830097aa7154a07b054b38fce581b9b5b",
        "1745633795","3f8025dadb52697c9fec3cb6ee7620fa074dd54001e69e1f5948e725996bcb47",
        "523148205","37551cf87351a555c3758e855b26a5234bff0c25520537dcd2bd67da9fe537ea");
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_support_release_mismatch:"+e.getKey());}
}
