package szserver.net;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Source-bound incremental account writer. The v6 JSON is a checkpoint, not a per-request rewrite. */
final class AccountJournal implements AutoCloseable {
    private final Path base;private final Journal journal;private final UUID id;private long revision,bytes;
    private String baseDigest;private long groups,records,checkpoints,checkpointFailures,moveRetries;private BasicFileAttributes stamp;
    private volatile Map<String,Object> view=Map.of();
    private static long revision(JsonObject event){long value=event.get("revision").getAsBigDecimal().longValueExact();if(value<0)throw new IllegalArgumentException("account_journal_revision");return value;}
    private static String sha(Path file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] block=new byte[65536];for(int n;(n=in.read(block))>=0;)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static JsonObject marker(String type,UUID id,long revision,String hash){JsonObject row=new JsonObject();row.addProperty("type",type);row.addProperty("store",id.toString());row.addProperty("revision",revision);row.addProperty("baseSha256",hash);return row;}
    AccountJournal(Path base,AccountBase.Header header,AccountStoreSnapshot snapshot,java.util.function.Consumer<JsonObject> recover,Journal owned,long restoredRevision)throws Exception{
        this.base=base.toAbsolutePath();Path path=base.resolveSibling(base.getFileName()+".account-journal");journal=owned;UUID store=header==null||header.journalId()==null?UUID.randomUUID():header.journalId();id=store;
        try{if(Files.size(path)==8){if(header!=null&&header.revision()!=0)throw new IllegalArgumentException("account_journal_missing_history");if(header==null||header.journalId()==null){Path candidate=candidate();snapshot.write(candidate,id,0);replace(candidate);}journal.append(marker("account-head",id,0,sha(this.base))).get();revision=0;}
            else{if(header==null||header.journalId()==null)throw new IllegalArgumentException("account_journal_base_missing_identity");String baseHash=sha(this.base);long[] cursor={-1};boolean[] bound={false};journal.replayLocated((reference,event)->{
                if(!Json.text(event,"store",36).equals(id.toString()))throw new IllegalArgumentException("account_journal_wrong_store");String type=Json.text(event,"type",32);long next=revision(event);
                if(type.equals("account-head")){if(cursor[0]!=-1||next>header.revision())throw new IllegalArgumentException("account_journal_head");cursor[0]=next;}
                else if(type.equals("account-delta")){if(cursor[0]<0||next!=cursor[0]+1)throw new IllegalArgumentException("account_journal_order");cursor[0]=next;if(next>Math.max(header.revision(),restoredRevision))recover.accept(event);}
                else if(type.equals("account-checkpoint")){if(cursor[0]!=next)throw new IllegalArgumentException("account_journal_checkpoint_order");}
                else throw new IllegalArgumentException("account_journal_event");
                if(type.equals("account-head")||type.equals("account-checkpoint")){String hash=Json.text(event,"baseSha256",64);if(!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("account_journal_base_hash");if(next==header.revision()&&hash.equals(baseHash))bound[0]=true;}
            }).get();if(cursor[0]<Math.max(header.revision(),restoredRevision)||!bound[0])throw new IllegalArgumentException("account_journal_base_mismatch");revision=cursor[0];}
            bytes=Files.size(path);stamp=attributes();baseDigest=sha(this.base);publish();
        }catch(Exception|Error error){journal.close();throw error;}
    }
    private Path candidate(){return base.resolveSibling(base.getFileName()+".account-checkpoint");}
    private BasicFileAttributes attributes()throws IOException{if(Files.isSymbolicLink(base))throw new IOException("account_base_changed");BasicFileAttributes value=Files.readAttributes(base,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!value.isRegularFile())throw new IOException("account_base_changed");return value;}
    private void verifyBase()throws IOException{BasicFileAttributes current=attributes();if(current.size()!=stamp.size()||!current.lastModifiedTime().equals(stamp.lastModifiedTime())||!Objects.equals(current.fileKey(),stamp.fileKey()))throw new IOException("account_base_changed");}
    private void replace(Path candidate)throws Exception{for(int attempt=0;;attempt++)try{Files.move(candidate,base,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return;}catch(FileSystemException error){if(error instanceof AtomicMoveNotSupportedException||error instanceof NoSuchFileException||attempt>=7)throw error;moveRetries++;Thread.sleep((attempt+1)*10L);}}
    long revision(){return revision;}
    void persist(List<AccountDelta> changes)throws Exception{verifyBase();List<JsonObject> events=new ArrayList<>(changes.size());long next=revision;long added=0;for(var change:changes){if(change.revision()!=++next)throw new IllegalArgumentException("account_journal_order");JsonObject event=change.json(id);added+=8+Json.bytes(event).length;events.add(event);}journal.appendBatch(events,()->{}).get();revision=next;bytes+=added;groups++;records+=events.size();publish();}
    boolean shouldCheckpoint(long accountCount,long profileCount){return bytes>Math.max(4L*1024*1024,accountCount*512+profileCount*512);}
    void checkpoint(AccountStoreSnapshot snapshot)throws Exception{
        verifyBase();Path candidate=candidate();try{snapshot.write(candidate,id,revision);String hash=sha(candidate);JsonObject binding=marker("account-checkpoint",id,revision,hash);journal.append(binding).get();bytes+=8+Json.bytes(binding).length;replace(candidate);stamp=attributes();baseDigest=hash;journal.compactStreaming(sink->sink.accept(marker("account-head",id,revision,hash))).get();bytes=Files.size(journal.path());checkpoints++;publish();}
        catch(Exception error){checkpointFailures++;publish();throw error;}
    }
    JsonObject cacheBinding(long restoredRevision)throws Exception{verifyBase();if(restoredRevision>revision)throw new IOException("account_cache_not_durable");var prefix=journal.durablePrefix().get();JsonObject row=new JsonObject();row.addProperty("store",id.toString());row.addProperty("baseSha256",baseDigest);row.add("journal",Json.tree(prefix));return row;}
    boolean healthy(){return journal.healthy();}
    private void publish(){view=Map.of("revision",revision,"journalBytes",bytes,"groups",groups,"records",records,"checkpoints",checkpoints,"checkpointFailures",checkpointFailures,"atomicMoveRetries",moveRetries,"journal",journal.metrics());}
    Map<String,Object> metrics(){return view;}
    @Override public void close()throws Exception{try{journal.durablePrefix().get();}finally{journal.close();}}
}
