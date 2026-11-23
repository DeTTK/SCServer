package szserver.net;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Owned disposable generations; source-bound warm roots are an optimisation, never canonical data. */
final class AccountCache {
    static final Set<String> TABLES=Set.of("accounts","profiles","hashes","accountProfiles","sessionProfiles","names","ids","snapshotAccounts","snapshotProfiles","snapshotCharacters");
    record Warm(DiskAccountTree tree,Map<String,Long> roots,long revision,JsonObject credentials){}
    private final Path base,folder;private final String source;
    private volatile String startup="cold",failure="";private volatile long cleaned,cleanedBytes,seals;private Path warmPath;
    AccountCache(Path base)throws Exception{
        this.base=base.toAbsolutePath();Path data=this.base.getParent();
        boolean portable=data.getFileName()!=null&&data.getFileName().toString().equals("data")&&Files.isRegularFile(data.resolveSibling("world.properties"),LinkOption.NOFOLLOW_LINKS);
        source=portable?"world-data/"+base.getFileName():this.base.toString();
        folder=this.base.resolveSibling(base.getFileName()+(portable?".account-cache-v2":".account-cache"));
        if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(folder)))throw new IOException("account_cache_not_directory");
        Files.createDirectories(folder);Path owner=folder.resolve("owner.json");
        if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS)){JsonObject row=checked(owner);if(!row.keySet().equals(Set.of("kind","source"))||!row.get("kind").getAsString().equals("account-cache")||!row.get("source").getAsString().equals(source))throw new IOException("account_cache_foreign_owner");}
        else{try(var entries=Files.newDirectoryStream(folder)){if(entries.iterator().hasNext())throw new IOException("account_cache_unowned_directory");}JsonObject row=new JsonObject();row.addProperty("kind","account-cache");row.addProperty("source",source);atomic(owner,row);}
    }
    static String digest(Path path,long length)throws Exception{
        if(length<0||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)<length)throw new IOException("account_cache_digest_bounds");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] block=new byte[65536];long remaining=length;while(remaining>0){int n=in.read(block,0,(int)Math.min(block.length,remaining));if(n<0)throw new EOFException();digest.update(block,0,n);remaining-=n;}}return HexFormat.of().formatHex(digest.digest());
    }
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static JsonObject checked(Path file)throws Exception{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>512*1024)throw new IOException("account_cache_metadata_bounds");
        JsonObject envelope=Json.parse(Files.readAllBytes(file));if(!envelope.keySet().equals(Set.of("payload","sha256")))throw new IOException("account_cache_metadata_schema");JsonObject payload=envelope.getAsJsonObject("payload");if(!digest(Json.bytes(payload)).equals(envelope.get("sha256").getAsString()))throw new IOException("account_cache_metadata_hash");return payload;
    }
    private static void atomic(Path file,JsonObject row)throws Exception{
        Path candidate=file.resolveSibling(file.getFileName()+".pending");if(Files.isSymbolicLink(file)||Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(candidate)||Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("account_cache_metadata_not_regular");
        JsonObject out=new JsonObject();out.add("payload",row);out.addProperty("sha256",digest(Json.bytes(row)));byte[] bytes=Json.bytes(out);try(FileChannel channel=FileChannel.open(candidate,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}Files.move(candidate,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private Path data(String name)throws Exception{if(!name.matches("g-[0-9a-f-]{36}\\.dat")||!UUID.fromString(name.substring(2,38)).toString().equals(name.substring(2,38)))throw new IOException("account_cache_generation_name");Path path=folder.resolve(name);if(!path.normalize().getParent().equals(folder))throw new IOException("account_cache_generation_path");return path;}
    private Path owner(Path data){return data.resolveSibling(data.getFileName()+".owner");}
    private boolean owned(Path data)throws Exception{JsonObject row=checked(owner(data));return row.keySet().equals(Set.of("kind","source","file"))&&row.get("kind").getAsString().equals("account-cache-generation")&&row.get("source").getAsString().equals(source)&&row.get("file").getAsString().equals(data.getFileName().toString());}
    DiskAccountTree create()throws Exception{
        Path data=data("g-"+UUID.randomUUID()+".dat");if(Files.exists(data,LinkOption.NOFOLLOW_LINKS)||Files.exists(owner(data),LinkOption.NOFOLLOW_LINKS))throw new IOException("account_cache_generation_exists");
        JsonObject row=new JsonObject();row.addProperty("kind","account-cache-generation");row.addProperty("source",source);row.addProperty("file",data.getFileName().toString());atomic(owner(data),row);return new DiskAccountTree(data);
    }
    Warm warm(AccountBase.Header header,Journal journal){
        Path manifest=folder.resolve("warm.json");if(!Files.exists(manifest,LinkOption.NOFOLLOW_LINKS))return null;
        try{
            JsonObject row=checked(manifest);if(!row.keySet().equals(Set.of("kind","source","store","revision","baseSha256","journal","file","end","sha256","roots","credentials"))||!row.get("kind").getAsString().equals("account-warm-index")||!row.get("source").getAsString().equals(source)||header==null||header.journalId()==null)throw new IOException("account_warm_schema");
            long revision=row.get("revision").getAsBigDecimal().longValueExact();if(revision<header.revision()||!row.get("store").getAsString().equals(header.journalId().toString())||!digest(base,Files.size(base)).equals(row.get("baseSha256").getAsString()))throw new IOException("account_warm_base_binding");
            JsonObject prefix=row.getAsJsonObject("journal");Journal.Prefix binding=new Journal.Prefix(prefix.get("end").getAsLong(),prefix.get("records").getAsLong(),prefix.get("sha256").getAsString());if(!journal.matches(binding))throw new IOException("account_warm_journal_binding");
            Path path=data(row.get("file").getAsString());if(!owned(path))throw new IOException("account_warm_foreign_generation");long end=row.get("end").getAsBigDecimal().longValueExact();if(end<8||!digest(path,end).equals(row.get("sha256").getAsString()))throw new IOException("account_warm_index_hash");
            JsonObject table=row.getAsJsonObject("roots");if(!table.keySet().equals(TABLES))throw new IOException("account_warm_tables");Map<String,Long> roots=new HashMap<>();for(String name:TABLES){long at=table.get(name).getAsBigDecimal().longValueExact();if(at!=0&&(at<8||at>=end))throw new IOException("account_warm_root_bounds");roots.put(name,at);}
            DiskAccountTree tree=new DiskAccountTree(path,end);try{for(long root:roots.values())tree.size(root);}catch(Throwable error){tree.close();throw error;}
            warmPath=path;startup="warm";return new Warm(tree,Map.copyOf(roots),revision,row.getAsJsonObject("credentials"));
        }catch(Exception error){startup="cold-fallback";failure=error.getClass().getSimpleName()+":"+Objects.toString(error.getMessage(),"");return null;}
    }
    void seal(DiskAccountTree tree,Map<String,Long> roots,long revision,JsonObject credentials,JsonObject binding)throws Exception{
        if(!roots.keySet().equals(TABLES))throw new IOException("account_cache_tables");long end=tree.forcePrefix();JsonObject row=new JsonObject();row.addProperty("kind","account-warm-index");row.addProperty("source",source);row.add("store",binding.get("store"));row.addProperty("revision",revision);row.add("baseSha256",binding.get("baseSha256"));row.add("journal",binding.get("journal"));row.addProperty("file",tree.path().getFileName().toString());row.addProperty("end",end);row.addProperty("sha256",digest(tree.path(),end));row.add("roots",Json.tree(roots));row.add("credentials",credentials);atomic(folder.resolve("warm.json"),row);warmPath=tree.path();seals++;
    }
    void remove(Path path){try{if(path.equals(warmPath)||!path.toAbsolutePath().normalize().getParent().equals(folder)||!owned(path)||Files.isSymbolicLink(path)||Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return;long bytes=Files.exists(path)?Files.size(path):0;Files.deleteIfExists(path);Files.deleteIfExists(owner(path));cleaned++;cleanedBytes+=bytes;}catch(Exception error){failure=error.getClass().getSimpleName();}}
    void cleanup(Set<Path> retained){long deadline=System.nanoTime()+250_000_000L;int scanned=0;try(var files=Files.newDirectoryStream(folder,"*.dat")){for(Path path:files){if(++scanned>128||System.nanoTime()>deadline)break;if(!retained.contains(path)&&!path.equals(warmPath))remove(path);}}catch(Exception error){failure=error.getClass().getSimpleName();}}
    Map<String,Object> metrics(){return Map.of("startup",startup,"failure",failure,"seals",seals,"removedGenerations",cleaned,"reclaimedBytes",cleanedBytes);}
}
