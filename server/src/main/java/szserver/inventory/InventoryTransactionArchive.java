package szserver.inventory;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Canonical immutable large transactions. A forced body precedes one journal
 * reference; a body without that reference never changes the inventory. */
final class InventoryTransactionArchive {
    static final String KEY="inventoryTransactionV1";
    // Persisted state/properties use hex: an 8KiB native stack expands to16KiB.
    static final int MAX_BYTES=384*1024*1024,MAX_ROWS=StorageLayout.MAX_ROOTS;
    private static final int JSON_NODES=500000;
    private final Path directory;
    InventoryTransactionArchive(Path journal)throws IOException{this(journal,true);}
    InventoryTransactionArchive(Path journal,boolean writable)throws IOException{
        worker();Path source=journal.toAbsolutePath().normalize();directory=source.resolveSibling(source.getFileName()+".transactions");
        if(Files.isSymbolicLink(directory))throw new IOException("inventory_archive_directory");
        if(writable){Files.createDirectories(directory);checkDirectory();}
    }
    private static void worker(){if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("inventory_archive_on_owner");}
    private void checkDirectory()throws IOException{if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("inventory_archive_directory");}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void validate(JsonObject event){
        if(!Set.of("character","revision","changes","gameplay","drop","grantedDrops","worldItemSpawn","worldItemClaim","worldItemGrants").containsAll(event.keySet())||!event.has("changes")||!event.get("changes").isJsonArray()||event.getAsJsonArray("changes").size()>MAX_ROWS)throw new IllegalArgumentException("inventory_archive_schema");
        UUID.fromString(Json.text(event,"character",36));if(event.get("revision").getAsBigDecimal().longValueExact()<1)throw new IllegalArgumentException("inventory_archive_revision");
        Set<Inventory.Address> ids=new HashSet<>();for(var value:event.getAsJsonArray("changes")){var row=value.getAsJsonObject();var address=new Inventory.Address(row.get("aggregate").getAsInt(),row.get("container").getAsInt(),row.get("slot").getAsInt());if(!ids.add(address))throw new IllegalArgumentException("inventory_archive_duplicate_address");}
        for(String field:List.of("grantedDrops","worldItemGrants"))
            if(event.has(field)&&(!event.get(field).isJsonArray()||event.getAsJsonArray(field).size()>160))
                throw new IllegalArgumentException("inventory_archive_drop_limit");
    }
    JsonObject store(JsonObject event)throws IOException{
        worker();byte[] raw=Json.bytes(event);
        if(raw.length<=65536){Json.parse(raw);return event;}
        if(raw.length>MAX_BYTES)throw new IllegalArgumentException("inventory_archive_byte_limit");
        JsonObject checked=Json.parse(raw,16384,JSON_NODES);validate(checked);checkDirectory();String sha=hash(raw);
        Path target=directory.resolve(sha+".szi"),temporary=directory.resolve("draft-"+UUID.randomUUID()+".tmp");
        try{
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(raw);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            JsonObject ref=new JsonObject();ref.addProperty("sha256",sha);ref.addProperty("bytes",raw.length);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){read(ref);Files.delete(temporary);}else try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException raced){read(ref);Files.delete(temporary);}
            JsonObject compact=new JsonObject();compact.add("character",event.get("character"));compact.add("revision",event.get("revision"));compact.add(KEY,ref);return compact;
        }finally{Files.deleteIfExists(temporary);}
    }
    JsonObject hydrate(JsonObject event)throws IOException{
        worker();if(!event.has(KEY))return event;if(!event.keySet().equals(Set.of("character","revision",KEY)))throw new IOException("inventory_archive_reference_fields");
        JsonObject result=read(event.getAsJsonObject(KEY));if(!result.get("character").equals(event.get("character"))||!result.get("revision").equals(event.get("revision")))throw new IOException("inventory_archive_reference_binding");return result;
    }
    private JsonObject read(JsonObject ref)throws IOException{
        checkDirectory();if(!ref.keySet().equals(Set.of("sha256","bytes")))throw new IOException("inventory_archive_reference_fields");String sha=Json.text(ref,"sha256",64);long size=ref.get("bytes").getAsBigDecimal().longValueExact();
        if(!sha.matches("[0-9a-f]{64}")||size<=65536||size>MAX_BYTES)throw new IOException("inventory_archive_reference_bounds");Path file=directory.resolve(sha+".szi");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=size)throw new IOException("inventory_archive_missing_or_size");
        byte[] raw=new byte[(int)size];try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){if(channel.size()!=size)throw new IOException("inventory_archive_size");ByteBuffer buffer=ByteBuffer.wrap(raw);while(buffer.hasRemaining())if(channel.read(buffer)<0)throw new EOFException("inventory_archive_truncated");if(channel.size()!=size)throw new IOException("inventory_archive_size");}if(!hash(raw).equals(sha))throw new IOException("inventory_archive_checksum");
        try{JsonObject event=Json.parse(raw,16384,JSON_NODES);validate(event);return event;}catch(RuntimeException invalid){throw new IOException("inventory_archive_schema",invalid);}
    }
}
