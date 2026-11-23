package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Additional native data dependencies of the complete speed function. */
final class ActorLocomotionRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("596586228","c62223d4624bf27e993f59fc2c71a1fef9defe1a11d3bf6c1574e3e7b5a33e49"),
        Map.entry("1798539107","f6600d5d1e29f4d96acf525c1c4e348f7de5b1f867f0dcd3cf5b7fbfe3c7f3e5"),
        Map.entry("1810277368","0d21fd010ee3d30e9e3230595f5c23e0bab7c32c46ab4c201d4bd42e5cbe9bdf"),
        Map.entry("358035993","032ef46c3bd7a4d357b618cb0f42da93e2af6485f1f65a969c3590eb095c9083"),
        Map.entry("200241649","7c53ae54224f4a62c4d37366ec273d7d83c15aa35eb7d9949266ca9fa232864b"),
        Map.entry("353451775","e152ac1b0635649498ebbf6566e307535da74c0dfb4af419d2592838880bbad3"),
        Map.entry("478901502","7b0ba678e8387f5ee1335b3e4c0761df3cf4a60e05059f476cd35bb62d6cefb8"),
        Map.entry("158575574","cd971e5634e24a17863fb62ccd5a2b24956aa1c443208e99da4fe00a1571996b"),
        Map.entry("570877235","6f4d92c720ab0597a2dd08e4574b94617a61bf9295b88d9c79952b888bfd18d9"));
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_locomotion_release_mismatch:"+e.getKey());}
}
