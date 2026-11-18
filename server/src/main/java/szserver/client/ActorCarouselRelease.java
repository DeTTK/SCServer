package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Canonical capture/force/phase/damage dependencies used without rewriting their algorithms. */
final class ActorCarouselRelease {
 private static final Map<String,String> HASHES=Map.of(
  "1952056351","9bb89b88926cb1b3b66904c2f9d52aab7aada318d9403fca310e54ab2300cd0b",
  "1711115376","a43cffa1518dbf04e746d801b4cc5845ee4af5aec1619a469557bc44d8728191",
  "132688950","11ce1fe1dbb95734bc4185ff914c24fc4a5bd1ad36cf2afffbe89d1e7cd7abf7",
  "415006645","4cc6952c568a4bcdce6b505c0e29f54d9362710d4be682e94da57684dea6ebc3",
  "1770021764","9f2a3251b0cdf6e414fe61f43122142669b1fc571ac73749b3962e97652b8c60",
  "304663057","fc4084793ab607839724ea4ae6cb3367a2e1200928372c36282df0a71873c562");
 static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_carousel_release_mismatch:"+e.getKey());}
}
