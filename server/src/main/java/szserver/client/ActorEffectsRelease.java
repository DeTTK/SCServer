package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Native contact/component dependencies; separate from the v1 capsule fingerprint. */
final class ActorEffectsRelease {
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("1448531254","7f732a9957ac25188a9d46b4fcfc57dfd59bd5cbd41339f25e8c0de15363b6bc"),Map.entry("1451253642","082b7cb9c2b067e1198c9ed870e53d8f377b64e7049eea0c0382837f16cc691c"),Map.entry("1470377155","f45625b7f37e921fe66914332d221c4d638fd0659f9913076469ac3fe8c61760"),Map.entry("636748791","2181230186a52df92cc61e96088baeca1a46da6ea7712d7d0e2c56ff8a5237e8"),Map.entry("776835576","f8aaa233cdf07d092643f064935ea3c563d84f769090f1e376d187b703a33459"),Map.entry("870287846","026e6b7ee2ed0f3da51c225d40cb25b72060c1710bc106acaded51bd03ebdb8c"),Map.entry("1553688601","d5725aeac8b0317935c36ca0e6d2729d3c968f82b2e68488e3b4a0d33b6519c5"),Map.entry("1850427543","ec85feee4cf32ff460b5f2ac2ece6cd1c421d69642f3e854c7faa7c2dbc18dbc"),Map.entry("1820749314","7661d3df58ac9bab7b59e7ecd9784f6bba9da09847a23539ea4da80ece701a01"),Map.entry("304132750","e9d930e6c68a4d03d563f3b2bc19d8a4920820a391f2697d3de81d19bec40b7f"));
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_effects_release_mismatch:"+e.getKey());}
}
