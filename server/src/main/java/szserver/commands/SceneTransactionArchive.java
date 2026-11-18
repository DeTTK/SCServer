package szserver.commands;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable large scene payloads. Publication and fsync precede the small journal reference. */
final class SceneTransactionArchive {
    static final long MAX_BYTES=64L*1024*1024;
    private final Path directory;
    @FunctionalInterface interface BeforePublish{void run(Path temporary)throws IOException;}
    private final BeforePublish beforePublish;
    SceneTransactionArchive(Path journal)throws IOException{this(journal,p->{});}
    SceneTransactionArchive(Path journal,BeforePublish beforePublish)throws IOException{
        worker();this.beforePublish=beforePublish;Path source=journal.toAbsolutePath().normalize();directory=source.resolveSibling(source.getFileName()+".scene-state");
        if(Files.isSymbolicLink(directory))throw new IOException("scene_archive_directory");Files.createDirectories(directory);checkDirectory();
    }
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("scene_archive_on_owner");}
    private static boolean field(String key){return !key.equals("sceneArchive")&&(key.startsWith("scene")||key.equals("editorStateChanges")||key.equals("portalMetadata")||key.equals("actorRuntime"));}
    private static long characters(JsonElement value){if(value.isJsonNull())return 4;if(value.isJsonPrimitive())return value.getAsJsonPrimitive().isString()?value.getAsString().length()+2L:32;long count=2;if(value.isJsonArray())for(var child:value.getAsJsonArray())count+=characters(child)+1;else for(var child:value.getAsJsonObject().entrySet())count+=child.getKey().length()+4L+characters(child.getValue());return count;}
    Path directory(){return directory;}
    JsonObject store(JsonObject event)throws IOException{
        worker();if(event.has("sceneArchive"))throw new IllegalArgumentException("scene_archive_already_stored");JsonObject payload=new JsonObject();long chars=0;
        for(var row:event.entrySet())if(field(row.getKey())&&!row.getValue().isJsonPrimitive()){payload.add(row.getKey(),row.getValue());chars+=characters(row.getValue());}
        if(chars<=8192&&!event.has("actorRuntime"))return event;checkDirectory();Path temporary=directory.resolve("draft-"+UUID.randomUUID()+".tmp");
        try{MessageDigest digest=digest();
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                OutputStream limited=new FilterOutputStream(new DigestOutputStream(Channels.newOutputStream(channel),digest)){
                    long count;private void admit(int n)throws IOException{count+=n;if(count>MAX_BYTES)throw new IOException("scene_archive_byte_limit");}
                    @Override public void write(int b)throws IOException{admit(1);out.write(b);}
                    @Override public void write(byte[] b,int o,int n)throws IOException{admit(n);out.write(b,o,n);}
                };
                Writer out=new BufferedWriter(new OutputStreamWriter(limited,StandardCharsets.UTF_8),65536);new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(payload,out);out.flush();channel.force(true);
            }
            long bytes=Files.size(temporary);String hash=HexFormat.of().formatHex(digest.digest());JsonObject ref=new JsonObject();ref.addProperty("format","SZSA1");ref.addProperty("sha256",hash);ref.addProperty("bytes",bytes);
            beforePublish.run(temporary);Path target=path(hash);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){read(ref);Files.delete(temporary);}else try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException raced){read(ref);Files.delete(temporary);}
            read(ref);JsonObject compact=event.deepCopy();for(String name:payload.keySet())compact.remove(name);compact.add("sceneArchive",ref);return compact;
        }finally{Files.deleteIfExists(temporary);}
    }
    JsonObject hydrate(JsonObject event)throws IOException{
        worker();if(!event.has("sceneArchive"))return event;JsonObject payload=read(event.getAsJsonObject("sceneArchive"));JsonObject hydrated=event.deepCopy();hydrated.remove("sceneArchive");
        for(var row:payload.entrySet()){if(!field(row.getKey())||row.getValue().isJsonPrimitive()||event.has(row.getKey()))throw new IOException("scene_archive_field");hydrated.add(row.getKey(),row.getValue());}return hydrated;
    }
    private JsonObject read(JsonObject ref)throws IOException{
        checkDirectory();String hash;long bytes;
        try{if(!ref.keySet().equals(Set.of("format","sha256","bytes"))||!ref.get("format").getAsString().equals("SZSA1"))throw new IOException("scene_archive_reference");hash=ref.get("sha256").getAsString();bytes=ref.get("bytes").getAsLong();if(!hash.matches("[0-9a-f]{64}")||bytes<2||bytes>MAX_BYTES||!ref.get("bytes").toString().equals(Long.toString(bytes)))throw new IOException("scene_archive_reference");}catch(RuntimeException bad){throw new IOException("scene_archive_reference",bad);}
        Path file=path(hash);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=bytes)throw new IOException("scene_archive_missing_or_size");MessageDigest digest=digest();JsonObject payload;
        final long expectedBytes=bytes;
        try(InputStream stream=new DigestInputStream(new FilterInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            long count;private int admit(int n)throws IOException{if(n<0){if(count!=expectedBytes)throw new IOException("scene_archive_missing_or_size");return n;}count+=n;if(count>expectedBytes)throw new IOException("scene_archive_missing_or_size");return n;}
            @Override public int read()throws IOException{int value=in.read();admit(value<0?-1:1);return value;}
            @Override public int read(byte[] b,int offset,int length)throws IOException{return admit(in.read(b,offset,length));}
        },digest);Reader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8),65536)){
            try{payload=JsonParser.parseReader(reader).getAsJsonObject();}catch(RuntimeException invalid){throw new IOException("scene_archive_json",invalid);}
        }
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("scene_archive_checksum");if(payload.isEmpty()||payload.size()>128)throw new IOException("scene_archive_fields");return payload;
    }
    private void checkDirectory()throws IOException{if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("scene_archive_directory");}
    private Path path(String hash){return directory.resolve(hash+".sza");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
