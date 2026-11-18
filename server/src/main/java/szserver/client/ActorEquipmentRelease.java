package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Canonical held-item and movement property boundary, independent of old capsule fingerprints. */
final class ActorEquipmentRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("1745367406","d456665e126411fd18ef84489b4bc2e69bd243f6bfcb92e6e2e3f6d7eb4927ae"),Map.entry("211097523","4a9a19a8416399a0a331d3f9ac430c44c00609c9cdf1a503f0d2649c7b12194a"),Map.entry("1392406414","87d13cac961a70c7c6b6e685308cd50b70f6fe4dbe69ce728d548ed762a3e6e3"),Map.entry("62393481","1d303c830b73c78f591271ab33c4129650c28cf3874647107dde7d4a0567254f"),Map.entry("822754569","208d88226b168281e957f17cc2848b3491dd76c6538e7ee5c87a2cc7a534d6d1"),Map.entry("1798539107","f6600d5d1e29f4d96acf525c1c4e348f7de5b1f867f0dcd3cf5b7fbfe3c7f3e5"),Map.entry("632909433","93efd09ffb41825b4c200c51232aa7f10c2acb1348267b943fb68bc44f08a186"),Map.entry("154883352","d452c9944a64fdc73f971e704bc5307f7b8d70d0256befbe76988cda0c03c801"),Map.entry("1810277368","0d21fd010ee3d30e9e3230595f5c23e0bab7c32c46ab4c201d4bd42e5cbe9bdf"));
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_equipment_release_mismatch:"+e.getKey());}
}
