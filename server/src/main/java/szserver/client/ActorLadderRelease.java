package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Reviewed native ladder geometry/configuration/pose release guard before world warmup. */
final class ActorLadderRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("258185106","9925a16393765f2709f70f6871d1c626e83c13a463eed977864a76f53b535383"),Map.entry("285412222","652dc295eda28da625f921bdfd1f688fa5c2c8623982cf116aa2ab9aff229d6a"),Map.entry("1767064101","4588652205c0c5b0e27e6b229ce3a7db3856259874a47e95e4c61b0611d6e9a4"),Map.entry("224002626","2a7d494261d01139c895223e70ffd9275f53651626874bd0977b255643fd6d57"),Map.entry("1049002105","40ec2ef5bc3825a01915aa18e3b78621d85e3a46ab4c1e39010fad73b52c7da7"),Map.entry("1584067923","3fc2530e802a77b423ed18d72c3fffe784a1a791f980940e520c4d5db79f39eb"),Map.entry("198485650","3144095338073dd88f9b76af1bc5c97f6de915c1af82238c219a7931989f1f72"),Map.entry("1943200621","80a6fd6f1168a15e542ce87c64cfc410ccb5a73b2144c145720f09a94d105736"),Map.entry("1704556748","45db02249b76fc459139305fa8b06649d3be2ac3119bf9fc2ba153ba49904d58"),Map.entry("430277821","f10d63fc7159e9a2dd82d363ee14c546ca8a244828c74ccdaac58102b2ebe6ff"),Map.entry("573844998","38acbc94612e7079d3fb33f1dbb44b9ae3488d000cf9330338ccf89bd2758e2a"),Map.entry("1395149101","fcdd236935c11538329e79e61d0aa9bbc983f92ac258984734421a273375b60f"));
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_ladder_release_mismatch:"+e.getKey());}
}
