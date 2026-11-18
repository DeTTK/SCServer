package szserver.migration;

import com.google.gson.*;
import szserver.net.LauncherAccess;
import szserver.net.AccountIdentity;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Offline launcher-store migration. Validate before IO, then publish a new owned directory.
 * Source SQL is archived as data and never executed by Java. No production listener is opened.
 */
public final class LegacyLauncherImport {
    private static final Map<String,Set<String>> COLUMNS=Map.of(
        "launcher_tokens",Set.of("id","digest","login","issued","expires","revoked","last_seen"),
        "launcher_audit",Set.of("id","timestamp","remote","method","path","status","token_id"),
        "telegram_outbox",Set.of("id","text"),"launcher_meta",Set.of("key","value"),
        "launcher_notification_limits",Set.of("remote","fingerprint","status","sent"));
    @FunctionalInterface private interface EventSink{void event(JsonObject event)throws Exception;}
    private record Plan(Map<String,Integer> rows,long outboxSequence,long auditSequence,long originalPending){}
    private static String text(JsonObject row,String key,int limit){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("legacy_launcher_string_required:"+key);String s=value.getAsString();if(s.length()>limit)throw new IllegalArgumentException("legacy_launcher_string_limit:"+key);for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw new IllegalArgumentException("legacy_launcher_unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("legacy_launcher_unicode");}return s;}
    private static long integer(JsonObject row,String key){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("legacy_launcher_integer_required:"+key);try{return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("legacy_launcher_integer_range:"+key,e);}}
    private static void optionalInteger(JsonObject row,String key){if(!row.get(key).isJsonNull())integer(row,key);}
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static JsonObject event(String type){JsonObject e=new JsonObject();e.addProperty("type",type);return e;}
    private static void emit(EventSink sink,JsonObject event)throws Exception{if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_launcher_event_size");sink.event(event);}
    private static Plan visit(LegacySnapshot snapshot,EventSink sink)throws Exception{
        if(!snapshot.purpose().equals("launcher-access"))throw new IllegalArgumentException("legacy_launcher_snapshot_purpose");
        for(var required:COLUMNS.entrySet())if(!snapshot.hasTable(required.getKey())||!snapshot.columns(required.getKey()).equals(required.getValue()))throw new IllegalArgumentException("legacy_launcher_schema:"+required.getKey());
        for(var entry:snapshot.counts().entrySet())if(entry.getValue()>0&&!COLUMNS.containsKey(entry.getKey()))throw new IllegalArgumentException("legacy_launcher_unknown_table:"+entry.getKey());
        Set<String> ids=new HashSet<>(),digests=new HashSet<>(),keys=new HashSet<>();long[] lastAudit={0},lastMessage={0};
        snapshot.visit("launcher_tokens",row->{String id=text(row,"id",12),digest=text(row,"digest",64),login=text(row,"login",128);if(!id.matches("[0-9a-f]{12}")||!digest.matches("[0-9a-f]{64}")||!ids.add(id)||!digests.add(digest)||login.isEmpty()||login.getBytes(StandardCharsets.UTF_8).length>128||login.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("legacy_launcher_credential");integer(row,"issued");for(String field:List.of("expires","revoked","last_seen"))optionalInteger(row,field);JsonObject e=row.deepCopy();e.addProperty("type","credential");emit(sink,e);});
        snapshot.visit("launcher_meta",row->{String key=text(row,"key",16384),value=text(row,"value",16384);if(!keys.add(key))throw new IllegalArgumentException("legacy_launcher_duplicate_meta");if(key.equals("telegram-offset")){try{if(Long.parseLong(AccountIdentity.strip(value))<0)throw new IllegalArgumentException("legacy_launcher_offset");}catch(NumberFormatException error){throw new IllegalArgumentException("legacy_launcher_offset",error);}}JsonObject e=event("metadata");e.addProperty("key",key);e.addProperty("value",value);emit(sink,e);});
        snapshot.visit("launcher_audit",row->{long id=integer(row,"id");if(id<=lastAudit[0])throw new IllegalArgumentException("legacy_launcher_audit_order");lastAudit[0]=id;integer(row,"timestamp");long status=integer(row,"status");if(status<Integer.MIN_VALUE||status>Integer.MAX_VALUE)throw new IllegalArgumentException("legacy_launcher_status");for(String field:List.of("remote","method","path","token_id"))text(row,field,16384);JsonObject e=row.deepCopy();e.remove("id");e.addProperty("legacy_id",id);e.addProperty("type","audit");emit(sink,e);});
        snapshot.visit("telegram_outbox",row->{long id=integer(row,"id");if(id<=lastMessage[0])throw new IllegalArgumentException("legacy_launcher_outbox_order");lastMessage[0]=id;JsonObject e=event("enqueue");e.addProperty("messageId",id);e.addProperty("text",text(row,"text",16384));emit(sink,e);});
        keys.clear();snapshot.visit("launcher_notification_limits",row->{String remote=text(row,"remote",16384),fingerprint=text(row,"fingerprint",64);if(!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("legacy_launcher_fingerprint");long status=integer(row,"status");if(status<Integer.MIN_VALUE||status>Integer.MAX_VALUE)throw new IllegalArgumentException("legacy_launcher_status");String key=hash(remote+"\0"+fingerprint+"\0"+status);if(!keys.add(key))throw new IllegalArgumentException("legacy_launcher_duplicate_limit");JsonObject e=event("notification_limit");e.addProperty("key",key);e.addProperty("sent",integer(row,"sent"));emit(sink,e);});
        long messageHead=snapshot.sqliteSequence("telegram_outbox"),auditHead=snapshot.sqliteSequence("launcher_audit");if(messageHead<lastMessage[0]||auditHead<lastAudit[0])throw new IllegalArgumentException("legacy_launcher_sequence_behind_rows");JsonObject sequence=event("legacy_sequence");sequence.addProperty("outbox",messageHead);sequence.addProperty("audit",auditHead);emit(sink,sequence);return new Plan(snapshot.counts(),messageHead,auditHead,snapshot.count("telegram_outbox"));
    }
    private static void forcedWrite(Path path,byte[] bytes)throws Exception{try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}}
    private static void receipt(Path work,JsonObject report)throws Exception{Path temporary=work.resolve("launcher-import-receipt-"+UUID.randomUUID()+".json");forcedWrite(temporary,Json.bytes(report));Files.move(temporary,work.resolve("launcher-import.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static byte[] releaseKeys(Path path)throws Exception{if(path==null)return null;if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>256*1024)throw new IllegalArgumentException("legacy_launcher_release_keys_file");byte[] bytes=Files.readAllBytes(path);JsonObject keys=Json.parse(bytes);for(var entry:keys.entrySet()){JsonElement value=entry.getValue();if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("legacy_launcher_release_key");try{if(Base64.getDecoder().decode(value.getAsString()).length!=32)throw new IllegalArgumentException("legacy_launcher_release_key");}catch(IllegalArgumentException error){throw new IllegalArgumentException("legacy_launcher_release_key",error);}}return bytes;}
    public static JsonObject importStore(Path root,Path source,Path destination,Path keys)throws Exception{
        root=root.toRealPath();Path boundary=root.resolve("var").toRealPath();destination=destination.toAbsolutePath().normalize();if(!destination.startsWith(boundary)||!destination.getParent().toRealPath().startsWith(boundary)||Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("legacy_launcher_destination_must_be_new_under_var");
        LegacySnapshot snapshot=new LegacySnapshot(source);Plan plan=visit(snapshot,e->{});byte[] keyBytes=releaseKeys(keys);String keysHash=keyBytes==null?null:LegacySnapshot.sha(keys);
        Path work=Files.createDirectory(destination.resolveSibling(destination.getFileName()+".import-"+UUID.randomUUID()));JsonObject progress=new JsonObject();progress.addProperty("status","PREPARING");progress.addProperty("format","sz-launcher-import-v1");forcedWrite(work.resolve("launcher-import-progress.json"),Json.bytes(progress));
        try{
            snapshot.archive(work.resolve("legacy-source"));try(var files=Files.list(work.resolve("legacy-source"))){for(Path path:files.toList())try(FileChannel file=FileChannel.open(path,StandardOpenOption.WRITE)){file.force(true);}}
            LegacySnapshot archived=new LegacySnapshot(work.resolve("legacy-source"));if(!archived.manifestSha256().equals(snapshot.manifestSha256()))throw new IllegalArgumentException("legacy_launcher_source_changed");Journal.Prefix prefix;
            try(Journal journal=Journal.streaming(work.resolve("launcher-access.sjournal"))){visit(archived,event->journal.append(event).get());prefix=journal.prefix().get();}
            if(keyBytes!=null){if(!LegacySnapshot.sha(keys).equals(keysHash))throw new IllegalArgumentException("legacy_launcher_release_keys_changed");forcedWrite(work.resolve("release-keys.json"),keyBytes);if(!LegacySnapshot.sha(work.resolve("release-keys.json")).equals(keysHash))throw new IllegalArgumentException("legacy_launcher_release_keys_copy");}
            JsonObject report=new JsonObject();report.addProperty("format","sz-launcher-import-v1");report.addProperty("status","PASS");report.addProperty("sourceManifestSha256",snapshot.manifestSha256());report.add("tableRows",new Gson().toJsonTree(plan.rows));report.add("journalPrefix",new Gson().toJsonTree(prefix));report.addProperty("outboxSequence",plan.outboxSequence);report.addProperty("auditSequence",plan.auditSequence);report.addProperty("releaseKeysCopied",keyBytes!=null);report.addProperty("releaseKeysSha256",keysHash);report.addProperty("notificationsSent",0);receipt(work,report);
            // Exercise the production reader before publishing. Legacy request-noise retirement is durable.
            try(LauncherAccess access=new LauncherAccess(work)){
                Map<String,Object> metrics=access.metrics();if(!access.healthy()||((Number)metrics.get("credentials")).longValue()!=snapshot.count("launcher_tokens")||((Number)metrics.get("auditRecords")).longValue()!=snapshot.count("launcher_audit"))throw new IllegalArgumentException("legacy_launcher_runtime_readback");
                report.addProperty("retiredRequestNotifications",((Number)metrics.get("removedRequestNotifications")).longValue());report.addProperty("activePendingNotifications",((Number)metrics.get("pendingNotifications")).longValue());report.addProperty("metadataKeys",access.metadata().get().size());
            }
            receipt(work,report);if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("legacy_launcher_destination_exists");Files.move(work,destination);return report;
        }catch(Exception|LinkageError error){JsonObject failed=new JsonObject();failed.addProperty("status","FAILED");failed.addProperty("format","sz-launcher-import-v1");failed.addProperty("errorClass",error.getClass().getSimpleName());receipt(work,failed);throw error;}
    }
    public static void main(String[] args)throws Exception{if(args.length<3||args.length>4)throw new IllegalArgumentException("usage: LegacyLauncherImport <server-root> <snapshot> <new-private-directory> [release-keys.json]");JsonObject result=importStore(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),args.length==4?Path.of(args[3]):null);System.out.println("PASS launcher import: "+result.get("tableRows")+"; private values and credentials are not printed");}
}
