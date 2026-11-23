package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Original authored ladder geometry and admission dependencies; capsule fingerprint stays stable. */
final class ActorSceneLadderRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(
     Map.entry("1395149101","fcdd236935c11538329e79e61d0aa9bbc983f92ac258984734421a273375b60f"),
     Map.entry("1049002105","40ec2ef5bc3825a01915aa18e3b78621d85e3a46ab4c1e39010fad73b52c7da7"),
     Map.entry("493563411","1cf702707b7a8f0daf928fd9a72f1750a68f15cf2ecc98926814513db093bda5"),
     Map.entry("334744794","25cbc4e4ea6587ad0f54debb496317a876d1edb5fa7726a8fb3f53f8bc622cb4"),
     Map.entry("1767064101","4588652205c0c5b0e27e6b229ce3a7db3856259874a47e95e4c61b0611d6e9a4"),
     Map.entry("1886271463","fbafa562ccfc06f8586361f3746c40c75042261f52773f9cca55f7ff9df79adc"),
     Map.entry("1384438816","7bc08a9fc2f81596128570c0117d8695cec470236c54a66e0c3a0557c1acdb1f"),
     Map.entry("879126158","82e64be1a07c53e8b4c6cd45d30dc02aedabf001065ded23ec0eb601f5fda0d9"),
     Map.entry("1338767630","d741e61116a43a688aff141ea741ddedbf184e575b86d6962f80d40a422d0769"),
     Map.entry("1362694342","59c14265fe52e6fe987fb0567790df72406b497f7555899db53201e4b84f0482"),
     Map.entry("153304200","2bfb0df2aa1c7d68c3f83d78f9bb92689e8a970c15883cb07850bb83a0b7f460"),
     Map.entry("1738045740","65bc3aa0dae524a5355953dbcac185b9f9fbda5ff573485a0c7b2b4c8e6760a8"),
     Map.entry("1087990608","878690de2df6e9eece2db86550d92afb99a6e97cc9becdf6fbf14ee9d421b2d7")
 );
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_scene_ladder_release_mismatch:"+e.getKey());}
}
