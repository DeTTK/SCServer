package szserver.persistence;

import com.google.gson.JsonObject;
import szserver.util.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable compatibility rows. IO is forbidden on gameplay/world owners and never holds their getter lock. */
final class LegacyPlayerArchive {
    record Ref(String sha256,int bytes){
        Ref{if(!sha256.matches("[0-9a-f]{64}")||bytes<2||bytes>48000)throw new IllegalArgumentException("legacy_player_archive_reference");}
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("format","SZLP1");value.addProperty("sha256",sha256);value.addProperty("bytes",bytes);return value;}
        static Ref read(JsonObject value){if(!value.keySet().equals(Set.of("format","sha256","bytes"))||!Json.text(value,"format",16).equals("SZLP1"))throw new IllegalArgumentException("legacy_player_archive_reference");return new Ref(Json.text(value,"sha256",64),Json.integer(value,"bytes",2,48000));}
    }
    private final Path directory;
    LegacyPlayerArchive(Path journal)throws IOException{worker();directory=journal.toAbsolutePath().resolveSibling(journal.getFileName()+".players");checkDirectory();}
    private void checkDirectory()throws IOException{if(Files.isSymbolicLink(directory))throw new IOException("legacy_player_archive_directory");Files.createDirectories(directory);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("legacy_player_archive_directory");}
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("legacy_player_archive_on_owner");}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    Ref store(JsonObject value)throws IOException{
        worker();checkDirectory();byte[] bytes=Json.bytes(value);Json.parse(bytes);Ref ref=new Ref(sha(bytes),bytes.length);Path target=directory.resolve(ref.sha256()+".szp");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){read(ref);return ref;}
        Path temporary=directory.resolve("draft-"+UUID.randomUUID()+".tmp");
        try{try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);read(ref);return ref;}
        finally{Files.deleteIfExists(temporary);}
    }
    JsonObject read(Ref ref)throws IOException{
        worker();checkDirectory();Path target=directory.resolve(ref.sha256()+".szp");byte[] bytes;
        try(FileChannel file=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(file.size()!=ref.bytes())throw new IOException("legacy_player_archive_size");ByteBuffer buffer=ByteBuffer.allocate(ref.bytes());while(buffer.hasRemaining())if(file.read(buffer)<0)throw new EOFException("legacy_player_archive_truncated");if(file.size()!=ref.bytes())throw new IOException("legacy_player_archive_changed");bytes=buffer.array();
        }
        if(!sha(bytes).equals(ref.sha256()))throw new IOException("legacy_player_archive_checksum");return Json.parse(bytes);
    }
}
