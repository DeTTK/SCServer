package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Installed local/remote traversal graph dependencies, independent of capsule schema. */
final class ActorTraversalRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("1943200621","80a6fd6f1168a15e542ce87c64cfc410ccb5a73b2144c145720f09a94d105736"),Map.entry("873944585","f4843893b0326bef628a0ec927ff7b4bfd3605c12417727f67729a7644246e1c"),Map.entry("332253308","f6e1d65e5286d79285fa4537555c2e8a0cb384e883b2628d77676b62f8026f30"),Map.entry("578131936","c58779bff7b808b82b5750ccf24131d093a47e5cc51df781cd62829f1fb64cb9"),Map.entry("140709081","f23834a0237c9dc58017a37754d71c201d72ed124eb42d7aa347f707fe6937a6"),Map.entry("703715006","0190c699bf6b59fbbcf67a84f699f0d141cffa154298a9911bede4e084e11af5"),Map.entry("1585574065","70de3ebd9171d3077b8592757942a2f566bb93196e1cf859519817191471a741"),Map.entry("1361517468","d50c82d76f1fca15766adb3ea851111c6bf3376f8f46be2b43e40959ccb9371d"));
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_traversal_release_mismatch:"+e.getKey());}
}
