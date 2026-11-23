package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Installed contact callback dependencies; world and solver hashes are checked separately. */
final class ActorFeedbackRelease {
    private static final Map<String,String> HASHES=Map.of(
        "958782514","32f471e31a7bb1e8d2014d5f1f50ac072f77d23f1ed3d9097762ee125d346a9e",
        "1562480994","bbeb5d7ea381c9c6ac38385d6f30113b46b512824309173881b7bbd6ef13d7f7",
        "1798539107","f6600d5d1e29f4d96acf525c1c4e348f7de5b1f867f0dcd3cf5b7fbfe3c7f3e5",
        "1079929180","f9d7b3fb10d07a50c8a58c3cb8f39fc12e2a5b6894ed7099e866b58d66412c07",
        "1886271463","fbafa562ccfc06f8586361f3746c40c75042261f52773f9cca55f7ff9df79adc",
        "612654711","3bcf5b3c4430631586d6ad9bbb085ed164f0d1eabe19fb8854f017c043e91138",
        "1143492736","e75c9e44365b404b1e85989554344cc94092a0a4b5b830061b04c586ec5408fe",
        "903809321","3ea4f98c116b5c98c9e23a0fbf2b9ec83f00686b393d711782659bb31f8660eb",
        "802519513","e711975fe03e008a0b4b4d937fc8b118d38ecdcd7fd1a0563df4c5a70101984d",
        "1583660016","96b141e39020b07af74631f384eae94c62e232d44564f0a618de9a42506fc2b8");
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_feedback_release_mismatch:"+e.getKey());}
}
