package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Reviewed native seat source/action/handler boundary, independent of durable capsule fingerprints. */
final class ActorSeatRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("24366313","4ea09dd5be807443427ed2e62f9730b2f8eb716ee5ea758d4c1c2a3a3a3fa822"),
Map.entry("1348676920","fac0abf4ddb7e99e2eb3187e5c9f102e220619f0232a0cc0a15a81ef4d6d52f6"),
Map.entry("327722217","cc972baa73256ac042747465e7891305cd3ef54b1ff886a3b9310f9dda9acd07"),
Map.entry("48811311","aea1c7c2a4cae69a84f3477e076e3014e58707ad9fea816f48c05f10d6b9b7cf"),
Map.entry("314510528","a708050194da9a7ef436ecc62a41b6719d3999b0d1b85adddba28353e6326731"),
Map.entry("1145326795","392cf70b8f8ecbfc9e48e93b909bca41a33a5ea7690dd4865572a1031d6d5e14"));
    static void verify(Path runtime)throws Exception{for(var entry:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(entry.getKey()+".class")))).equals(entry.getValue()))throw new IllegalStateException("actor_seat_release_mismatch:"+entry.getKey());}
}
