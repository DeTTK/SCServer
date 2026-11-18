package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Float geometry dependencies of the installed feedback transform. */
final class ActorFeedbackGeometryRelease {
    private static final Map<String,String> HASHES=Map.of(
        "970199578","8efd26aad3491c0c81e371cf5424a2fda4b9e5cb0c8129ee13ecbb0dcc544e71",
        "447252483","4cd8d5e874e96415b6e539a383c58e92f73d532d407421b7316976315cda924b",
        "491314621","e5726a09057db8f18b923d14635fa57089ec727544cce3cdacf4703187ad7136",
        "1987590071","8f8950db515deecd434fb59981507b6ee51348ac273e0517f0e7dfee93d55dcb",
        "480350223","2e33ddc2cf7513af19b9b7970c8c9d616fb258dae59e9169c7802704a71d17ed",
        "1155182995","39825761ced9caa239813004cbfb213b6bb8a66aa8b83bc97b15ce8deaf1c9d5",
        "283571282","2862869c1653786e6aa9b9357775e8a02dd731daf88b57c14bbbdc010e20ee47");
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_feedback_geometry_release_mismatch:"+e.getKey());}
}
