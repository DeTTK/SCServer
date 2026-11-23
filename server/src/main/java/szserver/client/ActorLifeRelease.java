package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Pinned original living-phase, health attributes and damage admission dependencies. */
final class ActorLifeRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("351062380","7e38cf7a0ba1a8201c47309a6f6aec066ca82a03e09182f0664272345b486e2a"),Map.entry("820497227","ab3121671c5f80103b11f9488e94dc4069f85bdad343047f4b9e50d2e322d701"),Map.entry("200241649","7c53ae54224f4a62c4d37366ec273d7d83c15aa35eb7d9949266ca9fa232864b"),Map.entry("1023573494","12a406b3fff8fc95f8f5755ae33a07b115ad8a866fa6e2474bbe429b340c614d"),Map.entry("49549491","d439298522d171a45d491c3d1b125f6cb2eb5b097a4b9d5cf406e099230e264d"),Map.entry("258939","673c9c0b9b37cc17b54dae7501e3675d7452f7d6c1ffc11e620b7bf98a561b9c"),Map.entry("284743","d452b4ca880b87c5a067a08fa1e68ed9b2846e84cf13b3c382e5472e5956c848"),Map.entry("1993742576","d9d7d21661d08ce17903f8bd047fbfc036b936b9070c853e9feca7ff37698f2b"),Map.entry("1798539107","f6600d5d1e29f4d96acf525c1c4e348f7de5b1f867f0dcd3cf5b7fbfe3c7f3e5"),Map.entry("1409908300","ea60aa66e15ee2b79e6ec6c2dca3497d2c9819585682f1a50085e6096ca3e98b"),Map.entry("1500514786","476d7c2863e32381f3e1cb63d130266fee8f27ee1f944a6c615c5cfee7b95fbd"));
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_living_release_mismatch:"+e.getKey());}
}
