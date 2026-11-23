package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Canonical nullable dynamic-collider rules; world/solver guards are checked separately. */
final class ActorBodyRelease {
    private static final Map<String,String> HASHES=Map.of(
        "820497227","ab3121671c5f80103b11f9488e94dc4069f85bdad343047f4b9e50d2e322d701",
        "1422788721","df5aa131edf60f8d6d773cd804e9b82d8a5d012186ea6008c2b7ca6488734517",
        "1145363446","df04df13026b479c04b402c8b1d57424003a41054e559257c605c2cce4e62914");
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_body_release_mismatch:"+e.getKey());}
}
