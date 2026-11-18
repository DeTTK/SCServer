package szserver.net;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Workers;
import szserver.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Launcher credentials and local notification outbox. One storage owner; no gameplay locks.
 * Only SHA256 credentials are persisted. Audits remain in the streaming CRC journal;
 * pending message bodies are read by reference, rather than retained during recovery.
 * This service never sends notifications to an external system.
 */
public final class LauncherAccess implements AutoCloseable {
    public record Identity(String id,String login){}
    public record Issued(String id,String token){}
    private record Credential(String id,String digest,String login,long issued,Long expires,Long revoked,Long lastSeen){}
    private record AuditRow(Journal.Reference reference,long id){}
    private final Journal journal;
    private final ThreadPoolExecutor owner=Workers.pool("launcher-access",1,128);
    // Two dedicated release HTTP workers reserve audit admission independently of admin traffic.
    private final Semaphore ordinaryRequests=new Semaphore(126);
    private final Map<String,Credential> credentials=new HashMap<>(),identities=new HashMap<>();
    private final NavigableMap<Long,Journal.Reference> outbox=new TreeMap<>();
    private final Map<String,Long> notificationLimits=new HashMap<>();
    private final Map<String,String> metadata=new HashMap<>();
    private final Deque<AuditRow> recentAudit=new ArrayDeque<>();
    private final LongSupplier seconds;
    private long sequence,auditSequence,offset,audits,removedNotifications;
    private volatile Throwable failure;
    private volatile Map<String,Object> view=Map.of();
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final DateTimeFormatter UTC=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    public LauncherAccess(Path directory)throws Exception{this(directory,()->System.currentTimeMillis()/1000);}
    public LauncherAccess(Path directory,LongSupplier seconds)throws Exception{
        this.seconds=Objects.requireNonNull(seconds);journal=Journal.streaming(directory.resolve("launcher-access.sjournal"));journal.pinOffsets();
        try{verifyImport(directory);journal.replayLocated(this::apply).get();List<Long> retired=new ArrayList<>();for(var entry:outbox.entrySet()){String text=journal.readRecord(entry.getValue()).get("text").getAsString();if(text.matches("(?s)....-..-.. ..:..:.. UTC \\| .* \\| .* \\| .* \\| token-id=.*")||text.equals("SZ Launcher: управление токенами подключено. /help"))retired.add(entry.getKey());}for(int at=0;at<retired.size();at+=1000){JsonObject e=event("delivered");e.add("ids",new Gson().toJsonTree(retired.subList(at,Math.min(at+1000,retired.size()))));commit(e);}removedNotifications=retired.size();if(!"launch-only-v1".equals(metadata.get("telegram-audit-mode"))){JsonObject e=event("metadata");e.addProperty("key","telegram-audit-mode");e.addProperty("value","launch-only-v1");commit(e);}if(!notificationLimits.isEmpty())pruneLimits(seconds.getAsLong());publish();}
        catch(Throwable error){owner.shutdownNow();journal.close();throw error;}
    }
    private void verifyImport(Path directory)throws Exception{
        Path marker=directory.resolve("launcher-import.json");if(!Files.exists(marker)){if(Files.exists(directory.resolve("launcher-import-progress.json")))throw new IllegalArgumentException("launcher_import_incomplete");return;}
        if(Files.isSymbolicLink(marker)||Files.size(marker)>65536)throw new IllegalArgumentException("launcher_import_marker_invalid");JsonObject receipt=Json.parse(Files.readAllBytes(marker));
        if(!"PASS".equals(receipt.get("status").getAsString())||!"sz-launcher-import-v1".equals(receipt.get("format").getAsString()))throw new IllegalArgumentException("launcher_import_incomplete");
        JsonObject prefix=Json.object(receipt,"journalPrefix");Journal.Prefix cut=new Journal.Prefix(prefix.get("end").getAsBigDecimal().longValueExact(),prefix.get("records").getAsBigDecimal().longValueExact(),Json.text(prefix,"sha256",64));if(!journal.matches(cut))throw new IllegalArgumentException("launcher_import_prefix_mismatch");
        // The archived snapshot is provenance, not an instruction source. No SQL is executed here.
        if(Files.isSymbolicLink(directory.resolve("legacy-source")))throw new IllegalArgumentException("launcher_import_source_mismatch");var source=new szserver.migration.LegacySnapshot(directory.resolve("legacy-source"));if(!source.manifestSha256().equals(Json.text(receipt,"sourceManifestSha256",64)))throw new IllegalArgumentException("launcher_import_source_mismatch");
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String bearer(String header){return header!=null&&header.startsWith("Bearer ")&&header.length()<=256?header.substring(7):"";}
    private static String bounded(String text,int points){if(text==null)return "";return text.codePointCount(0,text.length())<=points?text:text.substring(0,text.offsetByCodePoints(0,points));}
    private static String login(String value){if(value==null||value.isEmpty()||value.getBytes(StandardCharsets.UTF_8).length>128||value.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("invalid_login");return value;}
    private static Long nullable(JsonObject e,String key){return !e.has(key)||e.get(key).isJsonNull()?null:e.get(key).getAsLong();}
    private JsonObject event(String type){JsonObject e=new JsonObject();e.addProperty("type",type);return e;}
    private void apply(Journal.Reference reference,JsonObject e){
        switch(e.get("type").getAsString()){
            case "credential":{
                Credential row=new Credential(e.get("id").getAsString(),e.get("digest").getAsString(),login(e.get("login").getAsString()),e.get("issued").getAsLong(),nullable(e,"expires"),nullable(e,"revoked"),nullable(e,"last_seen"));
                if(!row.id.matches("[0-9a-f]{12}")||!row.digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_launcher_credential");
                Credential previous=identities.get(row.id);if(previous!=null&&!previous.digest.equals(row.digest))throw new IllegalArgumentException("launcher_identity_changed");Credential byDigest=credentials.get(row.digest);if(byDigest!=null&&!byDigest.id.equals(row.id))throw new IllegalArgumentException("launcher_digest_collision");credentials.put(row.digest,row);identities.put(row.id,row);break;
            }
            case "audit":{audits++;long id=e.has("legacy_id")?e.get("legacy_id").getAsLong():e.has("audit_id")?e.get("audit_id").getAsLong():auditSequence+1;auditSequence=Math.max(auditSequence,id);recentAudit.addLast(new AuditRow(reference,id));while(recentAudit.size()>100)recentAudit.removeFirst();if(e.has("messageId")){long message=e.get("messageId").getAsLong();sequence=Math.max(sequence,message);outbox.put(message,reference);}if(e.has("limitKey"))notificationLimits.put(e.get("limitKey").getAsString(),e.get("timestamp").getAsLong());pruneLimits(e.get("timestamp").getAsLong());break;}
            case "enqueue":{long id=e.get("messageId").getAsLong();sequence=Math.max(sequence,id);outbox.put(id,reference);break;}
            case "delivered":{for(JsonElement id:e.getAsJsonArray("ids"))outbox.remove(id.getAsLong());break;}
            case "offset":offset=e.get("offset").getAsLong();metadata.put("telegram-offset",Long.toString(offset));break;
            case "metadata":{String key=e.get("key").getAsString(),value=e.get("value").getAsString();metadata.put(key,value);if(key.equals("telegram-offset")){offset=Long.parseLong(AccountIdentity.strip(value));if(offset<0)throw new IllegalArgumentException("invalid_launcher_offset");}break;}
            case "notification_limit":notificationLimits.put(e.get("key").getAsString(),e.get("sent").getAsLong());break;
            case "legacy_sequence":sequence=Math.max(sequence,e.get("outbox").getAsLong());auditSequence=Math.max(auditSequence,e.get("audit").getAsLong());break;
            default:throw new IllegalArgumentException("unknown_launcher_record");
        }
    }
    private void pruneLimits(long now){notificationLimits.values().removeIf(sent->sent<now-3600);}
    private void commit(JsonObject event)throws Exception{Journal.Reference ref=journal.appendLocated(event).get();try{apply(ref,event);}catch(Throwable error){failure=error;throw error;}publish();}
    private void credential(Credential row)throws Exception{JsonObject e=event("credential");e.addProperty("id",row.id);e.addProperty("digest",row.digest);e.addProperty("login",row.login);e.addProperty("issued",row.issued);e.addProperty("expires",row.expires);e.addProperty("revoked",row.revoked);e.addProperty("last_seen",row.lastSeen);commit(e);}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>CompletableFuture<T> work(Work<T> task){return work(task,false);}
    private <T>CompletableFuture<T> work(Work<T> task,boolean audit){CompletableFuture<T> future=new CompletableFuture<>();if(!audit&&!ordinaryRequests.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("launcher_overloaded"));try{owner.execute(()->{try{if(failure!=null||!journal.healthy())throw new IllegalStateException("launcher_storage_failed");future.complete(task.run());}catch(Throwable error){if(!(error instanceof IllegalArgumentException)&&!(error instanceof RejectedExecutionException)){failure=error;publish();}future.completeExceptionally(error);}finally{if(!audit)ordinaryRequests.release();}});}catch(RejectedExecutionException full){if(!audit)ordinaryRequests.release();future.completeExceptionally(full);}return future.copy();}
    public CompletableFuture<Issued> issue(String login,int days){return work(()->{login(login);if(days<0||days>3650)throw new IllegalArgumentException("invalid_expiry");byte[] raw=new byte[32],id=new byte[6];String token,digest,identifier;do{RANDOM.nextBytes(raw);token="sz_"+Base64.getUrlEncoder().withoutPadding().encodeToString(raw);digest=hash(token);}while(credentials.containsKey(digest));do{RANDOM.nextBytes(id);identifier=HexFormat.of().formatHex(id);}while(identities.containsKey(identifier));long now=seconds.getAsLong();credential(new Credential(identifier,digest,login,now,days==0?null:now+days*86400L,null,null));return new Issued(identifier,token);});}
    public CompletableFuture<Identity> authenticate(String header){return work(()->{long now=seconds.getAsLong();Credential row=credentials.get(hash(bearer(header)));if(row==null||row.revoked!=null||row.expires!=null&&row.expires<=now)return null;if(!Objects.equals(row.lastSeen,now))credential(new Credential(row.id,row.digest,row.login,row.issued,row.expires,row.revoked,now));return new Identity(row.id,row.login);});}
    public CompletableFuture<Boolean> revoke(String id){return work(()->{Credential row=identities.get(id);if(row==null||row.revoked!=null)return false;credential(new Credential(row.id,row.digest,row.login,row.issued,row.expires,seconds.getAsLong(),row.lastSeen));return true;});}
    public CompletableFuture<List<Map<String,Object>>> tokens(){return work(()->{Comparator<Credential> order=Comparator.comparingLong(Credential::issued).reversed().thenComparing(Credential::id);PriorityQueue<Credential> latest=new PriorityQueue<>(101,order.reversed());for(Credential row:identities.values()){latest.add(row);if(latest.size()>100)latest.remove();}return latest.stream().sorted(order).map(row->{Map<String,Object> e=new LinkedHashMap<>();e.put("id",row.id);e.put("login",row.login);e.put("issued",row.issued);e.put("expires",row.expires);e.put("revoked",row.revoked);e.put("last_seen",row.lastSeen);return e;}).toList();});}
    public CompletableFuture<Void> enqueue(String text){return work(()->{JsonObject e=event("enqueue");e.addProperty("messageId",sequence+1);e.addProperty("text",bounded(text,3000));commit(e);return null;});}
    public CompletableFuture<List<Map<String,Object>>> pending(){return work(()->{List<Map<String,Object>> rows=new ArrayList<>();for(var entry:outbox.entrySet()){rows.add(Map.of("id",entry.getKey(),"text",journal.readRecord(entry.getValue()).get("text").getAsString()));if(rows.size()==40)break;}return rows;});}
    public CompletableFuture<Void> delivered(Collection<Long> ids){List<Long> copied=List.copyOf(ids);return work(()->{if(copied.size()>1000)throw new IllegalArgumentException("launcher_delivery_batch_limit");JsonObject e=event("delivered");e.add("ids",new Gson().toJsonTree(copied));commit(e);return null;});}
    public CompletableFuture<Long> offset(){return work(()->offset);}
    /** Offline/import tooling only; this is not exposed as an unauthenticated HTTP route. */
    public CompletableFuture<Map<String,String>> metadata(){return work(()->Map.copyOf(metadata));}
    public CompletableFuture<Void> setOffset(long value){return work(()->{if(value<0)throw new IllegalArgumentException("invalid_launcher_offset");JsonObject e=event("offset");e.addProperty("offset",value);commit(e);return null;});}
    public CompletableFuture<List<JsonObject>> audits(){return work(()->{List<JsonObject> result=new ArrayList<>();for(var row:recentAudit){JsonObject e=journal.readRecord(row.reference);e.remove("limitKey");e.remove("text");e.remove("messageId");e.addProperty("id",row.id);result.add(e);}return result;});}
    public CompletableFuture<Void> audit(String method,String path,int status,String remote,String identity,String authorization){return work(()->{
        long now=seconds.getAsLong();String plain=Objects.requireNonNullElse(path,"").split("\\?",2)[0];String safe=plain.startsWith("/launcher/")||plain.startsWith("/characters/")||plain.startsWith("/class_preload/")||plain.startsWith("/security/")||Set.of("/health","/","/address_list","/check_version","/session/game").contains(plain)?bounded(plain,180):"<other-route>";safe=safe.replaceAll("sz_[A-Za-z0-9_-]{43}","<redacted>");String supplied=bearer(authorization);if(!supplied.isEmpty())safe=safe.replace(supplied,"<redacted>");
        StringBuilder cleaned=new StringBuilder();safe.codePoints().forEach(c->cleaned.appendCodePoint(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT?'?':c));
        JsonObject e=event("audit");e.addProperty("audit_id",auditSequence+1);e.addProperty("timestamp",now);e.addProperty("remote",bounded(remote,128));e.addProperty("method",bounded(method,16));e.addProperty("path",cleaned.toString());e.addProperty("status",status);e.addProperty("token_id",bounded(identity,64));
        if(method.equals("POST")&&plain.equals("/launcher/launch")||method.equals("GET")&&plain.equals("/launcher/manifest.json")&&status>=400){
            String token=bearer(authorization),fingerprint=hash(token);Credential row=token.isEmpty()?null:credentials.get(fingerprint);if(row==null)row=identities.get(identity);String owner=row!=null?row.login+" (ID "+row.id+")":token.isEmpty()?"без токена":"неизвестный токен";
            String result="Запуск разрешён";boolean notify=true;if(status!=200){pruneLimits(now);String limit=hash(remote+"\0"+fingerprint+"\0"+status);Long previous=notificationLimits.get(limit);notify=previous==null||now-previous>=30;if(notify)e.addProperty("limitKey",limit);String reason=row!=null&&row.revoked!=null?"токен отозван":row!=null&&row.expires!=null&&row.expires<=now?"токен истёк":switch(status){case 401->"неверный токен";case 403->"требуется HTTPS";case 409->"версия игры обновилась";case 400->"некорректный запрос";default->status>=500?"ошибка сервера":"доступ отклонён";};result="Попытка запуска отклонена: "+reason+" (HTTP "+status+")";}
            if(notify){e.addProperty("messageId",sequence+1);e.addProperty("text",UTC.format(Instant.ofEpochSecond(now))+" UTC | IP: "+bounded(remote,128)+" | "+owner+" | "+result);}
        }commit(e);return null;
    },true);}
    private void publish(){view=Map.of("healthy",failure==null&&journal.healthy(),"credentials",identities.size(),"pendingNotifications",outbox.size(),"auditRecords",audits,"recentAuditLimit",100,"retainedMessageBodies",0,"removedRequestNotifications",removedNotifications,"failure",failure==null?"":failure.getClass().getSimpleName(),"outboxSequence",sequence,"auditSequence",auditSequence);}
    public boolean healthy(){return failure==null&&journal.healthy();}
    public Map<String,Object> metrics(){return view;}
    @Override public void close()throws Exception{try{Workers.stop(owner);}finally{journal.close();}}
}
