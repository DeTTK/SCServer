package szserver.net;

import com.sun.net.httpserver.*;
import szserver.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import com.google.gson.*;

/** Independent development realm. No production credentials or database. */
public final class LocalAccounts implements AutoCloseable {
    public record Grant(UUID protocolId,UUID characterId,String name,String worldHost){}
    public record CharacterIdentity(UUID id,String name){}
    private static final class Account {final UUID id;final String tokenHash;final Map<String,UUID> characters=new LinkedHashMap<>();Account(UUID id,String tokenHash){this.id=id;this.tokenHash=tokenHash;}}
    private static final class Profile {final UUID accountId;final String session;String legal=null;Profile(Account account,String session){accountId=account.id;this.session=session;}}
    private Account profileAccount(Profile profile){Account row=accounts.get(profile.accountId);if(row==null)throw new IllegalArgumentException("account_not_found");return row;}
    private record Token(String login,String name,long expires,String worldHost){}
    private record Ticket(Grant grant,String session,long expires,String login,String tokenDigest){}
    private record Result(Object payload,CompletableFuture<Void> durable){}
    private static final class RouteError extends IllegalArgumentException {final int status;RouteError(int status,String message){super(message);this.status=status;}}
    private volatile boolean storageFailed;
    private volatile String storageError="";
    private volatile long atomicMoveRetries;
    private final Map<String,Profile> profiles;
    private final Map<UUID,Set<String>> accountProfiles;private final Map<String,Set<String>> sessionProfiles;
    private AccountJournal journalStore;private final boolean readOnly;private long preparedRevision;
    private final Map<UUID,Account> accounts;
    private final Map<String,UUID> accountTokens;
    private final Set<String> characterNames;
    private final Set<UUID> characterIds;
    private DiskAccountTree directory;private szserver.persistence.Journal accountLog;private Path privateIndex;private AccountCache cache;
    private final Map<String,AccountIndexMap<?,?>> workingIndexes=new LinkedHashMap<>();
    private final Map<Path,DiskAccountTree> retiredIndexes=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor maintenance=Workers.pool("account-index-maintenance",1,2);
    private long compactBaseBytes;private boolean coldNeedsVacuum;private IndexJob indexJob;
    private volatile Map<String,Object> indexStatus=Map.of("status","idle");
    private <K,V> AccountIndexMap<K,V> indexMap(String table,java.lang.reflect.Type type,java.util.function.Function<String,K> decode){var map=new AccountIndexMap<K,V>(directory,type,decode,table);workingIndexes.put(table,map);return map;}
    private Map<String,Long> indexRoots(){Map<String,Long> roots=new LinkedHashMap<>(preparedSnapshot.roots());workingIndexes.forEach((name,map)->roots.put(name,map.offset()));return Map.copyOf(roots);}
    private record ChangeKey(String table,String key){}
    private static final class IndexJob {final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();final Map<ChangeKey,DiskAccountTree.Change> changes=new LinkedHashMap<>();long bytes;boolean pressure;}
    private record IndexCut(DiskAccountTree source,Map<String,Long> roots,long revision,CompletableFuture<Void> durable,AutoCloseable lease){}
    private record Rebase(List<DiskAccountTree.Change> changes,long revision,CompletableFuture<Void> durable){}
    private record SealCut(DiskAccountTree tree,Map<String,Long> roots,long revision,JsonObject credentials,AutoCloseable lease){}
    private void indexChange(DiskAccountTree.Change change){IndexJob job=indexJob;if(job==null||job.pressure)return;ChangeKey key=new ChangeKey(change.table(),change.key());var previous=job.changes.put(key,change);job.bytes+=change.value()==null?0:change.value().length;if(previous!=null&&previous.value()!=null)job.bytes-=previous.value().length;if(job.changes.size()>512||job.bytes>16L*1024*1024){job.pressure=true;job.changes.clear();job.bytes=0;}}
    public Map<String,Object> indexMaintenanceStatus(){Map<String,Object> out=new LinkedHashMap<>(indexStatus);out.put("cache",cache==null?Map.of():cache.metrics());out.put("retainedGenerations",retiredIndexes.size());out.put("copyMiBPerSecond",DiskAccountTree.BULK_MIB_PER_SECOND);return Map.copyOf(out);}
    public CompletableFuture<Map<String,Object>> compactIndex(){return stateWork(this::startIndexMaintenance).thenCompose(v->v).copy();}
    private void maybeCompactIndex(){if(cache!=null&&!closed&&!storageFailed&&indexJob==null&&(coldNeedsVacuum||directory.end()>Math.max(32L*1024*1024,compactBaseBytes*2)))startIndexMaintenance();}
    private CompletableFuture<Map<String,Object>> startIndexMaintenance(){
        if(cache==null||closed||readOnly||storageFailed)return CompletableFuture.failedFuture(new IllegalStateException("account_index_unavailable"));if(indexJob!=null)return indexJob.result.copy();if(retiredIndexes.size()>=8)return CompletableFuture.failedFuture(new IllegalStateException("account_index_readers_busy"));
        IndexJob job=new IndexJob();indexJob=job;IndexCut cut=new IndexCut(directory,indexRoots(),preparedRevision,lastWrite,directory.pin());coldNeedsVacuum=false;indexStatus=Map.of("status","running","sourceBytes",directory.end());
        try{maintenance.execute(()->runIndexMaintenance(job,cut));}catch(RejectedExecutionException error){indexJob=null;try{cut.lease.close();}catch(Exception cleanup){error.addSuppressed(cleanup);}job.result.completeExceptionally(error);}return job.result.copy();
    }
    private JsonObject cacheCredentials(AccountStoreSnapshot snapshot){JsonObject row=new JsonObject();row.add("tokens",HTTP_JSON.toJsonTree(snapshot.tokens));row.add("tickets",HTTP_JSON.toJsonTree(snapshot.tickets));row.add("pending",HTTP_JSON.toJsonTree(snapshot.pendingDeletions));return row;}
    private void loadWarmCredentials(JsonObject row){if(!row.keySet().equals(Set.of("tokens","tickets","pending")))throw new IllegalArgumentException("account_warm_credentials");Gson gson=new Gson();for(JsonElement element:row.getAsJsonArray("tokens")){var token=gson.fromJson(element,AccountStoreSnapshot.Token.class);JsonObject value=new JsonObject();value.addProperty("token",token.token());value.addProperty("login",token.login());value.addProperty("name",token.name());value.addProperty("expires",token.expires());value.addProperty("worldHost",token.worldHost());loadToken(value,6);}for(JsonElement element:row.getAsJsonArray("tickets")){var ticket=gson.fromJson(element,AccountStoreSnapshot.Ticket.class);JsonObject value=new JsonObject();value.addProperty("protocol",ticket.grant().protocolId().toString());value.addProperty("character",ticket.grant().characterId().toString());value.addProperty("name",ticket.grant().name());value.addProperty("worldHost",ticket.grant().worldHost());value.addProperty("session",ticket.session());value.addProperty("expires",ticket.expires());if(ticket.login()!=null){value.addProperty("login",ticket.login());value.addProperty("tokenDigest",ticket.tokenDigest());}loadTicket(value,6);}loadPending(row.getAsJsonArray("pending"));}
    private void runIndexMaintenance(IndexJob job,IndexCut cut){DiskAccountTree target=null;boolean published=false;long before=cut.source.end();int rounds=0;
        try{
            cut.durable.get(30,TimeUnit.SECONDS);target=cache.create();Map<String,Long> roots=new HashMap<>();for(String table:AccountCache.TABLES){long root=cut.roots.get(table);roots.put(table,target.bulk(cut.source.size(root),cut.source.iterator(root)));}
            for(;rounds<8;rounds++){
                Rebase rebase=stateRead(()->{if(closed||storageFailed||job.pressure)throw new IllegalStateException("account_index_rebase_pressure");List<DiskAccountTree.Change> changes=List.copyOf(job.changes.values());job.changes.clear();job.bytes=0;return new Rebase(changes,preparedRevision,lastWrite);});
                for(var change:rebase.changes)roots.put(change.table(),target.put(roots.get(change.table()),change.key(),change.value()));rebase.durable.get(30,TimeUnit.SECONDS);
                DiskAccountTree candidate=target;SealCut seal=stateRead(()->{
                    if(closed||storageFailed||job.pressure)throw new IllegalStateException("account_index_rebase_pressure");if(preparedRevision!=rebase.revision||!job.changes.isEmpty()||!lastWrite.isDone())return null;
                    DiskAccountTree old=directory;var nextSnapshot=preparedSnapshot.relocate(candidate,roots);var credentials=cacheCredentials(nextSnapshot);var installs=workingIndexes.entrySet().stream().map(e->e.getValue().relocation(candidate,roots.get(e.getKey()))).toList();SealCut result=new SealCut(candidate,Map.copyOf(roots),preparedRevision,credentials,candidate.pin());candidate.observe(this::indexChange);retiredIndexes.put(old.path(),old);directory=candidate;for(Runnable install:installs)install.run();preparedSnapshot=nextSnapshot;durableSnapshot=nextSnapshot;compactBaseBytes=candidate.end();try{old.retire(()->{retiredIndexes.remove(old.path());cache.remove(old.path());});}catch(java.io.IOException error){storageError=error.getClass().getSimpleName();}return result;
                });
                if(seal!=null){published=true;sealCache(seal).get(60,TimeUnit.SECONDS);long after=target.end();int completedRounds=rounds+1;stateRead(()->{indexJob=null;indexStatus=Map.of("status","complete","sourceBytes",before,"liveBytes",after,"rebaseRounds",completedRounds);return null;});cache.cleanup(retainedPaths());job.result.complete(indexMaintenanceStatus());return;}
            }
            throw new IllegalStateException("account_index_rebase_busy");
        }catch(Exception error){System.err.println("Account index maintenance aborted: "+error);try{stateRead(()->{indexJob=null;indexStatus=Map.of("status","failed","error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));compactBaseBytes=directory.end();return null;});}catch(Exception cleanup){error.addSuppressed(cleanup);}job.result.completeExceptionally(error);
        }finally{try{cut.lease.close();}catch(Exception ignored){}if(target!=null&&!published)try{Path path=target.path();target.close();cache.remove(path);}catch(Exception ignored){}}
    }
    private Set<Path> retainedPaths(){Set<Path> paths=new HashSet<>(retiredIndexes.keySet());paths.add(directory.path());return paths;}
    private CompletableFuture<Void> sealCache(SealCut seal){CompletableFuture<Void> result=new CompletableFuture<>();try{storage.execute(()->{try{cache.seal(seal.tree,seal.roots,seal.revision,seal.credentials,journalStore.cacheBinding(seal.revision));result.complete(null);}catch(Exception error){result.completeExceptionally(error);}finally{try{seal.lease.close();}catch(Exception ignored){}}});}catch(RejectedExecutionException error){try{seal.lease.close();}catch(Exception ignored){}result.completeExceptionally(error);}return result;}

    private final ThreadPoolExecutor state=Workers.pool("local-account-state",1,128);
    private <T> CompletableFuture<T> stateWork(Callable<T> task){CompletableFuture<T> result=new CompletableFuture<>();try{state.execute(()->{try{result.complete(task.call());}catch(Throwable error){result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result.copy();}
    private <T> T stateRead(Callable<T> task){if(Thread.currentThread().getName().startsWith("local-account-state-"))try{return task.call();}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}String thread=Thread.currentThread().getName();if(thread.equals("native-game-owner")||thread.equals("world-tick"))throw new IllegalStateException("account_state_owner_wait");try{return stateWork(task).get(30,TimeUnit.SECONDS);}catch(ExecutionException error){if(error.getCause() instanceof RuntimeException cause)throw cause;throw new IllegalStateException(error.getCause());}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}catch(TimeoutException error){throw new IllegalStateException("account_state_timeout",error);}}
    private void changedProfile(String login,Profile profile){profiles.put(login,profile);dirtyProfiles.add(login);}
    private volatile AccountStoreSnapshot durableSnapshot=new AccountStoreSnapshot();
    private AccountStoreSnapshot preparedSnapshot=new AccountStoreSnapshot();
    private final Set<UUID> dirtyAccounts=new HashSet<>();private final Set<String> dirtyProfiles=new HashSet<>();
    private volatile Map<String,Object> liveCounts=Map.of();
    private volatile long snapshotWrites,snapshotBytes,snapshotRowsCopied,snapshotCommits;
    private record PendingWrite(AccountStoreSnapshot snapshot,AccountDelta delta,CompletableFuture<Void> future){}
    private final ArrayDeque<PendingWrite> pendingWrites=new ArrayDeque<>();
    private boolean writerScheduled;private volatile int pendingWriteCount;
    private CompletableFuture<Void> lastWrite=CompletableFuture.completedFuture(null);
    private final Map<String,Token> tokens=new HashMap<>();
    private final Map<String,Ticket> tickets=new HashMap<>();
    private final Map<String,CompletableFuture<Void>> pendingTickets=new HashMap<>();
    private volatile long gatewayRetries;
    private static final Gson HTTP_JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final HttpServer http;
    private volatile boolean started,closed;
    public interface DeleteLease {CompletableFuture<Void> commit();void release();}
    private static final DeleteLease EMPTY_DELETE=new DeleteLease(){public CompletableFuture<Void> commit(){return CompletableFuture.completedFuture(null);}public void release(){}};
    private final Set<UUID> pendingDeletions=new LinkedHashSet<>();
    private final Map<UUID,CompletableFuture<Void>> deletionFlights=new HashMap<>();
    private java.util.function.Function<UUID,CompletableFuture<DeleteLease>> deletionHandler=id->CompletableFuture.completedFuture(EMPTY_DELETE);
    /** Bind before exposing HTTP. Preparing a lease must fence and drain accepted gameplay writes. */
    public synchronized void bindCharacterDeletion(java.util.function.Function<UUID,CompletableFuture<DeleteLease>> handler){if(started||closed)throw new IllegalStateException("character_delete_bind_after_start");deletionHandler=Objects.requireNonNull(handler);}
    public boolean hasPendingCharacterDeletions(){return ((Number)liveCounts.getOrDefault("pendingCharacterDeletes",0)).intValue()!=0;}
    public CompletableFuture<Void> recoverCharacterDeletions(java.util.function.Function<UUID,CompletableFuture<Void>> purge){
        List<UUID> pending=stateRead(()->{if(started)throw new IllegalStateException("character_delete_recovery_after_start");return List.copyOf(pendingDeletions);});
        CompletableFuture<Void> result=CompletableFuture.completedFuture(null);for(UUID id:pending)result=result.thenCompose(ignored->purge.apply(id)).thenCompose(ignored->acknowledgeDeletion(id));return result.copy();
    }
    private CompletableFuture<Void> acknowledgeDeletion(UUID id){return stateWork(()->{if(closed)return CompletableFuture.<Void>failedFuture(new IllegalStateException("account_store_closed"));if(!pendingDeletions.remove(id))return CompletableFuture.<Void>completedFuture(null);return saveProfiles();}).thenCompose(v->v);}
    private CompletableFuture<Void> deleteCharacter(Profile profile,String name){
        UUID owner=profile.accountId,id=profileAccount(profile).characters.get(name);if(id==null)return CompletableFuture.failedFuture(new RouteError(404,"character_not_found"));
        CompletableFuture<Void> prior=deletionFlights.get(id);if(prior!=null)return prior.copy();
        if(pendingDeletions.size()+deletionFlights.size()>=64)return CompletableFuture.failedFuture(new RouteError(503,"character_delete_queue_full"));
        CompletableFuture<Void> result=new CompletableFuture<>();deletionFlights.put(id,result);publishCounts();
        CompletableFuture<DeleteLease> preparing;try{preparing=deletionHandler.apply(id);}catch(RuntimeException error){deletionFlights.remove(id);publishCounts();return CompletableFuture.failedFuture(error);}
        preparing.whenComplete((lease,error)->stateWork(()->{
            if(error!=null){deletionFlights.remove(id);publishCounts();result.completeExceptionally(error);return null;}
            Account account=accounts.get(owner);
            if(closed||storageFailed||account==null||!id.equals(account.characters.get(name))){lease.release();deletionFlights.remove(id);publishCounts();result.completeExceptionally(new IllegalStateException("character_delete_owner_changed"));return null;}
            account.characters.remove(name);accounts.put(owner,account);characterNames.remove(name);characterIds.remove(id);dirtyAccounts.add(owner);pendingDeletions.add(id);
            tokens.values().removeIf(t->t.name.equals(name)&&profiles.get(t.login)!=null&&profiles.get(t.login).accountId.equals(owner));tickets.values().removeIf(t->t.grant.characterId.equals(id));
            saveProfiles().thenCompose(ignored->lease.commit()).thenCompose(ignored->acknowledgeDeletion(id)).whenComplete((ignored,failure)->stateWork(()->{
                lease.release();deletionFlights.remove(id);publishCounts();if(failure==null)result.complete(null);else result.completeExceptionally(failure);return null;
            }).whenComplete((v,e)->{if(e!=null){lease.release();result.completeExceptionally(e);}}));return null;
        }).whenComplete((v,e)->{if(e!=null){if(lease!=null)lease.release();result.completeExceptionally(e);}}));return result.copy();
    }
    private volatile LauncherBackend launcher;
    private final ThreadPoolExecutor workers=Workers.pool("game-http",4,64);
    private final ConnectionRoutes routes;
    private final Path profileFile;
    private final java.util.function.Function<UUID,Map<String,Object>> preview;
    private final java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment;
    private final ThreadPoolExecutor storage=Workers.pool("local-account-storage",1,32);
    private static String secret(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public int port(){if(http==null)throw new IllegalStateException("account_store_offline");return http.getAddress().getPort();}
    /** Import validation opens no listener and cannot migrate/rewrite its input. */
    public static void validateStore(Path file)throws Exception{try(var ignored=new LocalAccounts(0,ConnectionRoutes.loopback(0,0),file,id->Map.of(),id->CompletableFuture.completedFuture(null),false,false,true)){} }
    /** Standalone development JSON backend; no native HTTP listener or equipment factory. */
    public static LocalAccounts openStore(Path file)throws Exception{return new LocalAccounts(0,ConnectionRoutes.loopback(0,0),file,id->Map.of(),id->CompletableFuture.completedFuture(null),false,false);}
    public boolean healthy(){return !closed&&!storageFailed;}
    /** Binding reserves the port during preparation; requests are admitted only here. */
    public synchronized void start(){if(closed||started||http==null)throw new IllegalStateException("account_listener_already_started_closed_or_offline");http.start();started=true;}
    public synchronized void attachLauncher(LauncherBackend backend){if(launcher!=null)throw new IllegalStateException("launcher_already_attached");launcher=Objects.requireNonNull(backend);}
    public synchronized void detachLauncher(LauncherBackend backend){if(launcher==backend)launcher=null;}
    /** Shared native profile/session, with the same durable account binding as /session/game. */
    public CompletableFuture<String> ensureLauncherProfile(String login){try{Result result=routed("POST","/session/game",Map.of("login",login),null);String session=(String)((Map<?,?>)result.payload).get("session");return result.durable.thenApply(ignored->session).copy();}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}}
    public Map<String,Object> metrics(){Map<String,Object> view=new LinkedHashMap<>(liveCounts);view.put("healthy",healthy());view.put("listenerStarted",started);view.put("closed",closed);view.put("gatewayRetries",gatewayRetries);view.put("atomicMoveRetries",atomicMoveRetries);view.put("storageError",storageError);view.put("storageQueued",storage.getQueue().size());view.put("snapshotWrites",snapshotWrites);view.put("snapshotBytes",snapshotBytes);view.put("snapshotRowsCopied",snapshotRowsCopied);view.put("snapshotPartitions",32);view.put("snapshotIndex","disk-persistent-avl");view.put("accountDirectory",directory==null?Map.of():directory.metrics());view.put("accountCache",indexMaintenanceStatus());view.put("stateQueued",state.getQueue().size());view.put("accountJournal",journalStore==null?Map.of():journalStore.metrics());view.put("snapshotCommits",snapshotCommits);view.put("pendingWrites",pendingWriteCount);return Collections.unmodifiableMap(view);}
    private void publishCounts(){liveCounts=Map.of("profiles",profiles.size(),"tokens",tokens.size(),"tickets",tickets.size(),"pendingTickets",pendingTickets.size(),"accounts",accounts.size(),"characters",characterIds.size(),"pendingCharacterDeletes",pendingDeletions.size(),"characterDeleteFlights",deletionFlights.size());}
    public LocalAccounts(int port,int gatewayPort,Path profileFile)throws Exception{this(port,gatewayPort,profileFile,id->Map.of());}
    public LocalAccounts(int port,int gatewayPort,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview)throws Exception{this(port,gatewayPort,profileFile,preview,id->CompletableFuture.completedFuture(null));}
    public LocalAccounts(int port,int gatewayPort,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview,java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment)throws Exception{this(port,ConnectionRoutes.loopback(gatewayPort,0),profileFile,preview,initializeEquipment);}
    public LocalAccounts(int port,ConnectionRoutes routes,Path profileFile)throws Exception{this(port,routes,profileFile,id->Map.of(),id->CompletableFuture.completedFuture(null));}
    public LocalAccounts(int port,ConnectionRoutes routes,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview,java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment)throws Exception{this(port,routes,profileFile,preview,initializeEquipment,true,true);}
    public LocalAccounts(int port,ConnectionRoutes routes,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview,java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment,boolean autoStart)throws Exception{this(port,routes,profileFile,preview,initializeEquipment,true,autoStart);}
    private LocalAccounts(int port,ConnectionRoutes routes,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview,java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment,boolean listen,boolean autoStart)throws Exception{this(port,routes,profileFile,preview,initializeEquipment,listen,autoStart,false);}
    private LocalAccounts(int port,ConnectionRoutes routes,Path profileFile,java.util.function.Function<UUID,Map<String,Object>> preview,java.util.function.Function<UUID,CompletableFuture<Void>> initializeEquipment,boolean listen,boolean autoStart,boolean readOnly)throws Exception{this.routes=Objects.requireNonNull(routes);this.profileFile=profileFile.toAbsolutePath();this.readOnly=readOnly;this.preview=Objects.requireNonNull(preview);this.initializeEquipment=Objects.requireNonNull(initializeEquipment);
        HttpServer prepared=null;try{
        Files.createDirectories(this.profileFile.getParent());
        if(!readOnly)accountLog=szserver.persistence.Journal.streaming(this.profileFile.resolveSibling(this.profileFile.getFileName()+".account-journal"));
        AccountBase.Header header=Files.exists(profileFile,LinkOption.NOFOLLOW_LINKS)?AccountBase.header(profileFile):null;AccountCache.Warm warm=null;
        if(readOnly){privateIndex=Files.createTempFile("sz-account-validation-",".index");directory=new DiskAccountTree(privateIndex);}else{cache=new AccountCache(this.profileFile);warm=cache.warm(header,accountLog);directory=warm==null?cache.create():warm.tree();}
        var strings=new com.google.gson.reflect.TypeToken<Set<String>>(){}.getType();
        accounts=indexMap("accounts",Account.class,UUID::fromString);profiles=indexMap("profiles",Profile.class,v->v);accountTokens=indexMap("hashes",UUID.class,v->v);
        accountProfiles=indexMap("accountProfiles",strings,UUID::fromString);sessionProfiles=indexMap("sessionProfiles",strings,v->v);characterNames=AccountIndexMap.set(indexMap("names",Boolean.class,v->v));characterIds=AccountIndexMap.set(indexMap("ids",Boolean.class,UUID::fromString));
        preparedSnapshot=new AccountStoreSnapshot(AccountStoreSnapshot.Index.disk(directory,AccountStoreSnapshot.Account.class,UUID::fromString,"snapshotAccounts"),AccountStoreSnapshot.Index.disk(directory,AccountStoreSnapshot.Profile.class,v->v,"snapshotProfiles"),AccountStoreSnapshot.Index.disk(directory,UUID.class,UUID::fromString,"snapshotCharacters"),List.of(),List.of());
        if(warm!=null){AccountCache.Warm restored=warm;workingIndexes.forEach((name,map)->map.relocate(directory,restored.roots().get(name)));preparedSnapshot=preparedSnapshot.relocate(directory,warm.roots());loadWarmCredentials(warm.credentials());}else if(header!=null)loadBase(header);
        durableSnapshot=captureSnapshot();publishCounts();
        if(readOnly){if(header==null||header.version()!=6)throw new IllegalArgumentException("offline_validation_requires_v6");}
        else{journalStore=new AccountJournal(this.profileFile,header,durableSnapshot,this::recoverDelta,accountLog,warm==null?0:warm.revision());preparedRevision=journalStore.revision();durableSnapshot=captureSnapshot();publishCounts();directory.observe(this::indexChange);compactBaseBytes=directory.end();coldNeedsVacuum=warm==null&&accounts.size()>4096;cache.cleanup(retainedPaths());}
        prepared=listen?HttpServer.create(new InetSocketAddress(routes.bindAddress(),port),32):null;http=prepared;if(http!=null){http.setExecutor(workers);http.createContext("/",this::handle);if(autoStart)start();}
        }catch(Exception|Error error){if(journalStore!=null)try{journalStore.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}else if(accountLog!=null)try{accountLog.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}if(directory!=null)try{directory.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}if(privateIndex!=null)try{Files.deleteIfExists(privateIndex);}catch(Throwable cleanup){error.addSuppressed(cleanup);}if(prepared!=null)prepared.stop(0);for(var pool:List.of(workers,storage,state,maintenance))try{Workers.stop(pool);}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}}
    private static UUID uuid(JsonObject row,String name){String value=szserver.util.Json.text(row,name,36);UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("invalid_saved_uuid");return id;}
    private static void validLogin(String login){if(!AccountIdentity.strip(login).equals(login)||login.isEmpty()||login.getBytes(StandardCharsets.UTF_8).length>128||login.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("invalid_saved_profile");}
    private void trackProfile(String login,Profile profile){Set<String> owner=new HashSet<>(accountProfiles.getOrDefault(profile.accountId,Set.of()));owner.add(login);accountProfiles.put(profile.accountId,owner);Set<String> session=new HashSet<>(sessionProfiles.getOrDefault(profile.session,Set.of()));session.add(login);sessionProfiles.put(profile.session,session);}
    private boolean sessionOwner(String session,Grant grant){for(String login:sessionProfiles.getOrDefault(session,Set.of())){Profile profile=profiles.get(login);if(grant.characterId.equals(profileAccount(profile).characters.get(grant.name)))return true;}return false;}
    private void addProfile(String login,Profile profile){validLogin(login);if(profile.session.isEmpty()||profile.session.length()>256||profiles.putIfAbsent(login,profile)!=null)throw new IllegalArgumentException("invalid_saved_profile");trackProfile(login,profile);changedProfile(login,profile);}
    private void loadToken(JsonObject row,int version){long expires=row.get("expires").getAsBigDecimal().longValueExact();if(expires<=System.currentTimeMillis())return;String key=szserver.util.Json.text(row,"token",256);if(tokens.size()>=256||tokens.containsKey(key))throw new IllegalArgumentException("invalid_saved_token");tokens.put(key,new Token(szserver.util.Json.text(row,"login",128),szserver.util.Json.text(row,"name",96),expires,savedHost(row,version)));}
    private void loadTicket(JsonObject row,int version){long expires=row.get("expires").getAsBigDecimal().longValueExact();if(expires<=System.currentTimeMillis())return;Grant grant=new Grant(uuid(row,"protocol"),uuid(row,"character"),szserver.util.Json.text(row,"name",96),savedHost(row,version));String session=szserver.util.Json.text(row,"session",256),login=row.has("login")?szserver.util.Json.text(row,"login",128):null,digest=row.has("tokenDigest")?szserver.util.Json.text(row,"tokenDigest",64):null;
        if((login==null)!=(digest==null)||digest!=null&&(!digest.matches("[0-9a-f]{64}")||!profiles.containsKey(login)||!profiles.get(login).session.equals(session)||!grant.characterId.equals(profileAccount(profiles.get(login)).characters.get(grant.name))))throw new IllegalArgumentException("invalid_saved_gateway_receipt");String key=grant.protocolId+":"+session;if(!sessionOwner(session,grant)||tickets.size()>=128||tickets.containsKey(key))throw new IllegalArgumentException("invalid_saved_ticket");tickets.put(key,new Ticket(grant,session,expires,login,digest));}
    private void loadPending(JsonArray rows){if(rows.size()>64)throw new IllegalArgumentException("character_delete_checkpoint_limit");pendingDeletions.clear();for(JsonElement row:rows){UUID id=UUID.fromString(row.getAsString());if(characterIds.contains(id)||!pendingDeletions.add(id))throw new IllegalArgumentException("character_delete_checkpoint_owner");}}
    private void loadBase(AccountBase.Header header)throws Exception{
        if(header.legacyArray()){AccountBase.visit(profileFile,"legacy",element->{JsonObject row=element.getAsJsonObject();String login=row.get("login").getAsString();Profile profile=profiles.get(login);if(profile==null){Account account=new Account(UUID.randomUUID(),AccountIdentity.nativeHash(login));registerAccount(account);profile=new Profile(account,row.get("session").getAsString());addProfile(login,profile);}String name=AccountIdentity.name(row.get("character").getAsString());UUID id=row.has("id")?uuid(row,"id"):UUID.randomUUID();Account account=profileAccount(profile);if(characterNames.contains(name)||characterIds.contains(id)||account.characters.putIfAbsent(name,id)!=null)throw new IllegalArgumentException("duplicate_saved_character");accounts.put(account.id,account);characterNames.add(name);characterIds.add(id);dirtyAccounts.add(account.id);captureSnapshot();});return;}
        int version=header.version();if(version>=6)AccountBase.visit(profileFile,"accounts",element->{JsonObject row=element.getAsJsonObject();Account account=new Account(uuid(row,"id"),szserver.util.Json.text(row,"tokenHash",64));account.characters.putAll(readCharacters(row));registerAccount(account);captureSnapshot();});
        AccountBase.visit(profileFile,"profiles",element->{JsonObject row=element.getAsJsonObject();String login=szserver.util.Json.text(row,"login",128);Account account;if(version>=6){account=accounts.get(uuid(row,"accountId"));if(account==null||!account.tokenHash.equals(AccountIdentity.nativeHash(login))||!account.characters.equals(readCharacters(row)))throw new IllegalArgumentException("invalid_saved_profile_account");}else{account=new Account(UUID.randomUUID(),AccountIdentity.nativeHash(login));account.characters.putAll(readCharacters(row));registerAccount(account);}Profile profile=new Profile(account,szserver.util.Json.text(row,"session",256));profile.legal=row.has("legal")&&!row.get("legal").isJsonNull()?row.get("legal").getAsString():null;addProfile(login,profile);captureSnapshot();});
        AccountBase.visit(profileFile,"tokens",element->loadToken(element.getAsJsonObject(),version));if(version>=3)AccountBase.visit(profileFile,"tickets",element->loadTicket(element.getAsJsonObject(),version));JsonArray pending=new JsonArray();AccountBase.visit(profileFile,"pendingCharacterDeletes",pending::add);loadPending(pending);
    }
    private void recoverDelta(JsonObject event){
        if(!event.keySet().equals(Set.of("type","store","revision","accounts","characters","profiles","tokens","removedTokens","tickets","removedTickets","pendingCharacterDeletes")))throw new IllegalArgumentException("account_delta_schema");
        for(JsonElement element:event.getAsJsonArray("accounts")){JsonObject row=element.getAsJsonObject();UUID id=uuid(row,"id");String hash=szserver.util.Json.text(row,"tokenHash",64);Account account=accounts.get(id);if(account==null)registerAccount(new Account(id,hash));else if(!account.tokenHash.equals(hash))throw new IllegalArgumentException("account_delta_hash_changed");}
        for(JsonElement element:event.getAsJsonArray("characters")){JsonObject row=element.getAsJsonObject();UUID id=uuid(row,"id"),owner=uuid(row,"account");String name=szserver.util.Json.text(row,"name",96);if(!name.equals(AccountIdentity.name(name)))throw new IllegalArgumentException("invalid_saved_character_name");Account account=accounts.get(owner);if(account==null)throw new IllegalArgumentException("account_delta_owner");if(row.get("removed").getAsBoolean()){if(!id.equals(account.characters.remove(name)))throw new IllegalArgumentException("account_delta_character_remove");characterNames.remove(name);characterIds.remove(id);}else{if(characterNames.contains(name)||characterIds.contains(id)||account.characters.putIfAbsent(name,id)!=null)throw new IllegalArgumentException("account_delta_character_duplicate");characterNames.add(name);characterIds.add(id);}accounts.put(owner,account);dirtyAccounts.add(owner);}
        for(JsonElement element:event.getAsJsonArray("profiles")){JsonObject row=element.getAsJsonObject();String login=szserver.util.Json.text(row,"login",128);validLogin(login);UUID id=uuid(row,"accountId");Account account=accounts.get(id);if(account==null||!account.tokenHash.equals(AccountIdentity.nativeHash(login)))throw new IllegalArgumentException("invalid_saved_profile_account");String session=szserver.util.Json.text(row,"session",256);Profile profile=profiles.get(login);if(profile==null){profile=new Profile(account,session);addProfile(login,profile);}else if(!profile.accountId.equals(account.id)||!profile.session.equals(session))throw new IllegalArgumentException("account_delta_profile_changed");profile.legal=!row.has("legal")||row.get("legal").isJsonNull()?null:row.get("legal").getAsString();changedProfile(login,profile);}
        for(JsonElement value:event.getAsJsonArray("removedTokens"))tokens.remove(value.getAsString());for(JsonElement value:event.getAsJsonArray("tokens"))loadToken(value.getAsJsonObject(),6);
        for(JsonElement value:event.getAsJsonArray("removedTickets"))tickets.remove(value.getAsString());for(JsonElement value:event.getAsJsonArray("tickets"))loadTicket(value.getAsJsonObject(),6);loadPending(event.getAsJsonArray("pendingCharacterDeletes"));captureSnapshot();
    }
    private String savedHost(JsonObject row,int version){if(version<5)return routes.primary();String host=row.get("worldHost").getAsString();if(!host.equals(ConnectionRoutes.configHost(host)))throw new IllegalArgumentException("invalid_saved_connection_host");return host;}
    private static Map<String,UUID> readCharacters(JsonObject row){Map<String,UUID> result=new LinkedHashMap<>();for(var entry:row.getAsJsonObject("characters").entrySet()){if(!entry.getKey().equals(AccountIdentity.name(entry.getKey())))throw new IllegalArgumentException("invalid_saved_character_name");result.put(entry.getKey(),UUID.fromString(entry.getValue().getAsString()));}return result;}
    private void registerAccount(Account account){
        if(!account.tokenHash.matches("[0-9a-f]{64}")||accounts.containsKey(account.id)||accountTokens.containsKey(account.tokenHash))throw new IllegalArgumentException("invalid_saved_account");
        for(var entry:account.characters.entrySet()){if(!entry.getKey().equals(AccountIdentity.name(entry.getKey()))||!characterNames.add(entry.getKey())||!characterIds.add(entry.getValue()))throw new IllegalArgumentException("invalid_saved_character_identity");}
        accounts.put(account.id,account);accountTokens.put(account.tokenHash,account.id);dirtyAccounts.add(account.id);
    }
    private Account account(String hash){UUID existing=accountTokens.get(hash);if(existing!=null)return accounts.get(existing);Account created=new Account(UUID.randomUUID(),hash);registerAccount(created);return created;}
    private static List<CharacterIdentity> identities(Account account){return account.characters.entrySet().stream().sorted((a,b)->AccountIdentity.compareNames(a.getKey(),b.getKey())).map(entry->new CharacterIdentity(entry.getValue(),entry.getKey())).toList();}

    private CharacterIdentity create(Account account,String rawName){String name=AccountIdentity.name(rawName);if(characterNames.contains(name))throw new RouteError(409,"character_name_already_exists");UUID id=UUID.randomUUID();account.characters.put(name,id);accounts.put(account.id,account);characterNames.add(name);characterIds.add(id);dirtyAccounts.add(account.id);return new CharacterIdentity(id,name);}
    /** Old Store token authentication; opaque tokens are hashed, never persisted or echoed. */
    public CompletableFuture<UUID> authenticate(String token){return stateWork(()->authenticateNow(token)).thenCompose(v->v);}
    private CompletableFuture<UUID> authenticateNow(String token){if(storageFailed||closed||readOnly)return CompletableFuture.failedFuture(new IllegalStateException("account_storage_failed"));String hash=AccountIdentity.hash(token);boolean created=!accountTokens.containsKey(hash);Account account=account(hash);CompletableFuture<Void> commit=created?saveProfiles():lastWrite;return commit.thenApply(ignored->account.id);}
    /** Durable domain create shares exactly the native menu's account and character indexes. */
    public CompletableFuture<CharacterIdentity> createCharacter(UUID accountId,String name){return stateRead(()->createCharacterNow(accountId,name)).copy();}
    private CompletableFuture<CharacterIdentity> createCharacterNow(UUID accountId,String name){if(storageFailed||closed||readOnly)return CompletableFuture.failedFuture(new IllegalStateException("account_storage_failed"));Account owner=accounts.get(accountId);if(owner==null)throw new IllegalArgumentException("account_not_found");CharacterIdentity character=create(owner,name);return saveProfiles().thenApply(ignored->character);}
    public List<CharacterIdentity> listCharacters(UUID accountId){return stateRead(()->listCharactersNow(accountId));}
    private List<CharacterIdentity> listCharactersNow(UUID accountId){if(storageFailed)throw new IllegalStateException("account_storage_failed");List<CharacterIdentity> result=durableSnapshot.identities(accountId);if(result==null)throw new IllegalArgumentException("account_not_found");return result;}
    public CompletableFuture<Boolean> prepareCharacterCheck(UUID id){return stateWork(()->{if(storageFailed||closed)throw new IllegalStateException("account_storage_failed");return durableSnapshot.characterExists(id);});}
    public boolean characterExists(UUID id){return stateRead(()->{if(storageFailed)throw new IllegalStateException("account_storage_failed");return durableSnapshot.characterExists(id);});}
    public CharacterIdentity getCharacter(UUID accountId,UUID characterId){return listCharacters(accountId).stream().filter(character->character.id.equals(characterId)).findFirst().orElseThrow(()->new IllegalArgumentException("character_does_not_belong_to_account"));}
    private Set<String> touchedProfiles(){Set<String> result=new HashSet<>(dirtyProfiles);for(UUID id:dirtyAccounts)result.addAll(accountProfiles.getOrDefault(id,Set.of()));return result;}
    /** Capture touched immutable disk roots on the account-state worker; bounded120s credentials stay resident. */
    private AccountStoreSnapshot captureSnapshot(){
        var accountRows=preparedSnapshot.accounts;var profileRows=preparedSnapshot.profiles;var characterRows=preparedSnapshot.characters;
        for(UUID id:dirtyAccounts){Account account=accounts.get(id);var previous=accountRows.get(id);var row=new AccountStoreSnapshot.Account(account.id,account.tokenHash,account.characters);accountRows=accountRows.put(id,row);
            if(previous!=null){Set<UUID> retained=new HashSet<>(row.characters().values());for(UUID character:previous.characters().values())if(!retained.contains(character))characterRows=characterRows.put(character,null);}
            for(UUID character:row.characters().values())if(!characterRows.contains(character))characterRows=characterRows.put(character,id);
        }
        for(String login:touchedProfiles()){Profile profile=profiles.get(login);var account=accountRows.get(profile.accountId);profileRows=profileRows.put(login,new AccountStoreSnapshot.Profile(login,account.id(),profile.session,profile.legal,account.characters()));snapshotRowsCopied++;}
        snapshotRowsCopied+=dirtyAccounts.size();dirtyAccounts.clear();dirtyProfiles.clear();long now=System.currentTimeMillis();
        List<AccountStoreSnapshot.Token> tokenRows=new ArrayList<>(tokens.size());for(var e:tokens.entrySet()){Token t=e.getValue();if(t.expires>now)tokenRows.add(new AccountStoreSnapshot.Token(e.getKey(),t.login,t.name,t.expires,t.worldHost));}
        List<AccountStoreSnapshot.Ticket> ticketRows=new ArrayList<>(tickets.size());for(Ticket t:tickets.values())if(t.expires>now)ticketRows.add(new AccountStoreSnapshot.Ticket(t.grant,t.session,t.expires,t.login,t.tokenDigest));
        preparedSnapshot=new AccountStoreSnapshot(accountRows,profileRows,characterRows,tokenRows,ticketRows,pendingDeletions);return preparedSnapshot;
    }
    /** Snapshot references are cheap; durable bytes and publication belong to the storage owner. */
    private CompletableFuture<Void> saveProfiles(){
        CompletableFuture<Void> future=new CompletableFuture<>();lastWrite=future;
        if(storageFailed||pendingWriteCount>=32){failWrites(new RejectedExecutionException("account_storage_backpressure"));future.completeExceptionally(new IllegalStateException("account_storage_failed"));publishCounts();return future;}
        if(closed||readOnly){future.completeExceptionally(new IllegalStateException("account_store_closed_or_read_only"));return future;}
        var before=preparedSnapshot;Set<UUID> touched=new HashSet<>(dirtyAccounts);Set<String> touchedProfiles=touchedProfiles();var snapshot=captureSnapshot();var delta=AccountDelta.capture(++preparedRevision,before,snapshot,touched,touchedProfiles);
        synchronized(pendingWrites){pendingWrites.addLast(new PendingWrite(snapshot,delta,future));pendingWriteCount=pendingWrites.size();
        if(!writerScheduled){writerScheduled=true;try{storage.execute(this::flushWrites);}catch(RejectedExecutionException error){writerScheduled=false;failWrites(error);}}
        }publishCounts();maybeCompactIndex();return future;
    }
    /** Group adjacent mutations into one latest immutable checkpoint; every receipt waits for its fsync. */
    private void flushWrites(){
        for(;;){List<PendingWrite> batch;
            synchronized(pendingWrites){if(pendingWrites.isEmpty()){writerScheduled=false;return;}batch=new ArrayList<>(pendingWrites);pendingWrites.clear();pendingWriteCount=0;}
            try{
                if(storageFailed)throw new IllegalStateException("account_storage_failed");
                AccountStoreSnapshot snapshot=batch.get(batch.size()-1).snapshot;try(AutoCloseable lease=snapshot.retain()){journalStore.persist(batch.stream().map(PendingWrite::delta).toList());
                durableSnapshot=snapshot;snapshotBytes=((Number)journalStore.metrics().get("journalBytes")).longValue();snapshotWrites++;snapshotCommits+=batch.size();
                for(PendingWrite write:batch)write.future.complete(null);
                if(journalStore.shouldCheckpoint(liveAccountCount(),liveProfileCount()))try{journalStore.checkpoint(snapshot);}catch(Exception checkpoint){if(!journalStore.healthy()){synchronized(pendingWrites){failWrites(checkpoint);}return;}}}
            }catch(Exception error){synchronized(pendingWrites){failWrites(error);writerScheduled=false;}for(PendingWrite write:batch)write.future.completeExceptionally(error);return;}
        }
    }
    private void failWrites(Exception error){
        storageError=error.getClass().getSimpleName();storageFailed=true;
        synchronized(pendingWrites){while(!pendingWrites.isEmpty())pendingWrites.removeFirst().future.completeExceptionally(error);pendingWriteCount=0;}
    }
    private long liveAccountCount(){return ((Number)liveCounts.getOrDefault("accounts",0)).longValue();}private long liveProfileCount(){return ((Number)liveCounts.getOrDefault("profiles",0)).longValue();}
    public CompletableFuture<Void> compact(){return stateWork(()->{CompletableFuture<Void> result=new CompletableFuture<>();if(closed||readOnly||storageFailed)return CompletableFuture.<Void>failedFuture(new IllegalStateException("account_storage_failed"));try{storage.execute(()->{try{AccountStoreSnapshot snapshot;AutoCloseable lease;for(;;){snapshot=durableSnapshot;try{lease=snapshot.retain();break;}catch(IllegalStateException retired){if(snapshot==durableSnapshot)throw retired;}}try(AutoCloseable held=lease){journalStore.checkpoint(snapshot);}result.complete(null);}catch(Exception error){if(!journalStore.healthy())failWrites(error);result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result.copy();}).thenCompose(v->v);}
    private CompletableFuture<Void> requestWrite;
    private Result routed(String method,String path,Map<String,String> parameters,String host){return stateRead(()->routedNow(method,path,parameters,host));}
    private Result routedNow(String method,String path,Map<String,String> parameters,String host){if(closed)throw new IllegalStateException("account_store_closed");requestWrite=path.equals("/")||path.equals("/health")?CompletableFuture.completedFuture(null):lastWrite;Object payload=route(method,path,parameters,host);return new Result(payload,requestWrite);}
    private Object route(String method,String path,Map<String,String> p,String host){
        String required=switch(path){case "/session/game","/characters/create","/characters/delete","/characters/accept_legal","/characters/take_bundle","/security/issue_auth_token"->"POST";case "/","/health","/address_list","/check_version","/class_preload/config","/characters/list"->"GET";default->throw new RouteError(404,"unknown_route");};
        if(!method.equals(required))throw new RouteError(405,"method_not_allowed");
        if(path.equals("/")||path.equals("/health"))return Map.of("ok",!storageFailed,"service","szserver-stalcraft-http");
        if(storageFailed)throw new RouteError(503,"account_storage_failed");
        String suppliedLogin=AccountIdentity.strip(p.getOrDefault("login","LocalPlayer"));String login=suppliedLogin.isEmpty()?"LocalPlayer":suppliedLogin;if(login.getBytes(StandardCharsets.UTF_8).length>128||login.codePoints().anyMatch(value->value<32))throw new IllegalArgumentException("invalid_login");
        boolean created=!profiles.containsKey(login);Profile profile=profiles.computeIfAbsent(login,k->new Profile(account(AccountIdentity.nativeHash(k)),secret()));if(created){trackProfile(login,profile);changedProfile(login,profile);requestWrite=saveProfiles();}
        if(path.equals("/session/game"))return Map.of("session",profile.session);
        if(path.equals("/address_list"))return Map.of("mode","l4","addresses",routes.addresses(host));
        if(path.equals("/check_version")||path.equals("/health"))return Map.of("ok",true);
        if(path.equals("/class_preload/config"))return Map.of("classes",List.of());
        // Native menu's HTTP session is the launcher session; the independently
        // issued game-session ticket is verified by the binary gateway below.
        if(path.equals("/characters/accept_legal")){String version=p.getOrDefault("version","");if(version.length()>8192)throw new IllegalArgumentException("legal_version_limit");profile.legal=version;changedProfile(login,profile);requestWrite=saveProfiles();return Map.of("ok",true,"version",profile.legal);}
        if(path.equals("/characters/create")){CharacterIdentity createdCharacter=create(profileAccount(profile),p.getOrDefault("character",""));requestWrite=saveProfiles();return character(createdCharacter.name,createdCharacter.id);}
        if(path.equals("/characters/delete")){requestWrite=deleteCharacter(profile,AccountIdentity.strip(p.getOrDefault("character","")));return Map.of("ok",true);}
        if(path.equals("/characters/take_bundle")){String raw=p.getOrDefault("bundle_ids","");if(raw.length()>1024)throw new IllegalArgumentException("bundle_ids_limit");return Map.of("ok",true,"bundle_ids",Arrays.stream(raw.split(",")).filter(value->!value.isEmpty()).toList());}
        if(path.equals("/characters/list")){var out=new LinkedHashMap<String,Object>();out.put("characters",identities(profileAccount(profile)).stream().map(e->character(e.name,e.id)).toList());out.put("had_characters",!profileAccount(profile).characters.isEmpty());out.put("characters_limit",10);for(String key:List.of("reserved_names","char_security_types","char_security_modes","available_platform_dlcs","available_steam_dlcs"))out.put(key,List.of());for(String key:List.of("deletion_delay","end_of_premium"))out.put(key,0);out.put("accepted_legal_version",profile.legal);out.put("overridden_display_login",login);out.put("open_browser_url",null);return out;}
        if(path.equals("/security/issue_auth_token")){String name=AccountIdentity.strip(p.getOrDefault("username",""));if(!profileAccount(profile).characters.containsKey(name))throw new RouteError(404,"character_not_found");long now=System.currentTimeMillis();tokens.values().removeIf(t->t.expires<now);if(tokens.size()>=256)throw new IllegalStateException("token_limit");String token=secret();tokens.put(token,new Token(login,name,now+120_000,host==null?routes.primary():host));requestWrite=saveProfiles();return Map.of("username",name,"token",token);}
        throw new IllegalArgumentException("unknown_route:"+path);
    }
    private Map<String,Object> character(String name,UUID id){var c=new LinkedHashMap<String,Object>();c.put("username",name);c.put("id",id.toString());c.put("locationUnlocalized","location.dev");for(String k:List.of("gearScore","money","playedTime","seasonPassLevel"))c.put(k,0);c.put("isLegacy",false);c.put("lastLogin",System.currentTimeMillis());for(String k:List.of("armor","skin","weapon","weaponSecondary","melee","backpack","clan","clanIcon","playerCard","allianceId","transferEnd","deleteAt"))c.put(k,null);Map<String,Object> equipment=preview.apply(id);for(String key:List.of("armor","skin","weapon","weaponSecondary","melee","backpack"))if(equipment.containsKey(key))c.put(key,equipment.get(key));return c;}
    /** Gateway login runs on login workers. Its durable consumption never holds the account lock. */
    private record GatewaySave(Grant grant,CompletableFuture<Void> durable,boolean retry){}
    public Grant gateway(UUID protocol,String login,String name,String session,String token){
        String key=protocol+":"+session;GatewaySave prepared=stateRead(()->{
            if(storageFailed||closed)throw new IllegalArgumentException("account_storage_failed");
            Token t=tokens.get(token);Profile profile=profiles.get(login);long now=System.currentTimeMillis();tickets.values().removeIf(v->v.expires<=now);Ticket existing=tickets.get(key);
            if(existing!=null&&existing.tokenDigest!=null){
                if(!existing.login.equals(login)||profile==null)throw new IllegalArgumentException("gateway_identity_mismatch");
                boolean sameCharacter=existing.grant.name.equals(name)&&existing.grant.characterId.equals(profileAccount(profile).characters.get(name));
                if(!profile.session.equals(session))throw new IllegalArgumentException("gateway_game_session_mismatch");
                if(sameCharacter&&java.security.MessageDigest.isEqual(HexFormat.of().parseHex(existing.tokenDigest),HexFormat.of().parseHex(tokenDigest(token))))
                    return new GatewaySave(existing.grant,pendingTickets.getOrDefault(key,CompletableFuture.completedFuture(null)),true);
                // A failed redirect can leave a receipt while the menu issues a new
                // token or selects another character. Only a fresh, independently
                // validated token for this account and selected character may replace it.
                if(t==null)throw new IllegalArgumentException("gateway_token_mismatch");
            }
            if(t==null)throw new IllegalArgumentException("gateway_token_unknown");if(t.expires<=now)throw new IllegalArgumentException("gateway_token_expired");
            if(!t.login.equals(login)||!t.name.equals(name)||profile==null||!profileAccount(profile).characters.containsKey(name))throw new IllegalArgumentException("gateway_identity_mismatch");
            if(!profile.session.equals(session))throw new IllegalArgumentException("gateway_game_session_mismatch");if(existing==null&&tickets.size()>=128)throw new IllegalStateException("ticket_limit");if(existing!=null&&existing.tokenDigest==null||existing==null&&pendingTickets.containsKey(key))throw new IllegalArgumentException("duplicate_ticket");
            Grant grant=new Grant(protocol,profileAccount(profile).characters.get(name),name,t.worldHost);tokens.remove(token);tickets.put(key,new Ticket(grant,session,now+120_000,login,tokenDigest(token)));CompletableFuture<Void> saved=saveProfiles();pendingTickets.put(key,saved);publishCounts();
            saved.whenComplete((v,e)->stateWork(()->{pendingTickets.remove(key,saved);publishCounts();return null;}));return new GatewaySave(grant,saved,false);
        });
        try{prepared.durable.get(10,TimeUnit.SECONDS);return stateRead(()->{pendingTickets.remove(key,prepared.durable);Profile profile=profiles.get(login);Ticket current=tickets.get(key);if(profile==null||!prepared.grant.characterId.equals(profileAccount(profile).characters.get(name)))throw new IllegalArgumentException("gateway_character_deleted");if(current==null||!current.grant.equals(prepared.grant)||current.expires<=System.currentTimeMillis())throw new IllegalArgumentException("gateway_ticket_unavailable");if(prepared.retry)gatewayRetries++;publishCounts();return prepared.grant;});}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalArgumentException("gateway_save_interrupted",error);}catch(ExecutionException|TimeoutException error){throw new IllegalArgumentException("gateway_save_failed",error);}
    }
    private static String tokenDigest(String token){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    /** Called on login workers: persist consumption before publishing the grant. */
    public Grant claim(UUID protocol,String session){GatewaySave prepared=stateRead(()->{if(storageFailed||closed)throw new IllegalArgumentException("account_storage_failed");String key=protocol+":"+session;if(pendingTickets.containsKey(key))throw new IllegalArgumentException("gateway_ticket_pending");Ticket ticket=tickets.remove(key);if(ticket==null||ticket.expires<=System.currentTimeMillis())throw new IllegalArgumentException("world_auth_failed");return new GatewaySave(ticket.grant,saveProfiles(),false);});
        try{prepared.durable.get(10,TimeUnit.SECONDS);return stateRead(()->{if(!sessionOwner(session,prepared.grant))throw new IllegalArgumentException("world_character_deleted");return prepared.grant;});}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalArgumentException("world_save_interrupted",error);}catch(ExecutionException|TimeoutException error){throw new IllegalArgumentException("world_save_failed",error);}
    }
    /** Payload is detached from account state. Wait and refresh only on the HTTP worker. */
    @SuppressWarnings("unchecked")
    private void prepareEquipment(Object payload)throws Exception{
        if(!(payload instanceof Map<?,?> map))return;
        List<Map<String,Object>> characters=new ArrayList<>();
        if(map.containsKey("username")&&map.containsKey("id"))characters.add((Map<String,Object>)map);
        else if(map.get("characters") instanceof List<?> rows)for(Object row:rows)characters.add((Map<String,Object>)row);
        CompletableFuture<?>[] ready=characters.stream().map(row->initializeEquipment.apply(UUID.fromString((String)row.get("id")))).toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(ready).get(10,TimeUnit.SECONDS);
        for(Map<String,Object> row:characters){Map<String,Object> equipment=preview.apply(UUID.fromString((String)row.get("id")));for(String key:List.of("armor","skin","weapon","weaponSecondary","melee","backpack"))if(equipment.containsKey(key))row.put(key,equipment.get(key));}
    }
    private void handle(HttpExchange e){try{
        if(e.getRequestURI().getPath().startsWith("/launcher/")){LauncherBackend backend=launcher;if(backend==null)LauncherBackend.send(e,503,Map.of("error","launcher_not_configured"));else backend.dispatch(e);return;}
        if(e.getRequestMethod().equals("OPTIONS")){e.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");e.getResponseHeaders().set("Access-Control-Allow-Headers","*");respond(e,204,null);return;}
        byte[] body=e.getRequestBody().readNBytes(8193);if(body.length>8192)throw new IllegalArgumentException("body_limit");
        Map<String,String> p=new HashMap<>();String raw=e.getRequestMethod().equals("POST")?new String(body,StandardCharsets.UTF_8):Objects.toString(e.getRequestURI().getRawQuery(),"");
        for(String field:raw.split("&")){String[] kv=field.split("=",2);if(kv.length==2)p.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
        String route=p.remove("__bscp_route");
        String host=ConnectionRoutes.requestHost(route==null?e.getRequestHeaders().getFirst("Host"):route);
        if(route!=null&&host==null)throw new IllegalArgumentException("invalid_connection_origin");
        if(host==null)host=ConnectionRoutes.configHost(e.getLocalAddress().getAddress().getHostAddress());
        Result result=routed(e.getRequestMethod(),e.getRequestURI().getPath(),p,host);result.durable.get(10,TimeUnit.SECONDS);prepareEquipment(result.payload);respond(e,200,result.payload);
    }catch(Exception ex){Throwable cause=ex;while((cause instanceof ExecutionException||cause instanceof CompletionException)&&cause.getCause()!=null)cause=cause.getCause();try{respond(e,cause instanceof RouteError error?error.status:storageFailed||cause instanceof TimeoutException?503:Objects.toString(cause.getMessage(),"").equals("character_has_world_drops")?409:400,Map.of("error",Objects.toString(cause.getMessage(),"request_failed")));}catch(Exception ignored){e.close();}}}
    private static void respond(HttpExchange e,int code,Object payload)throws Exception{e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("Access-Control-Allow-Origin","*");if(code==204){e.sendResponseHeaders(code,-1);e.close();return;}byte[] data=HTTP_JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(code,data.length);try(var out=e.getResponseBody()){out.write(data);}}
    @Override public void close()throws InterruptedException{
        if(closed)return;if(!stateRead(()->{if(closed)return false;closed=true;return true;}))return;if(http!=null)http.stop(0);maintenance.shutdown();while(!maintenance.awaitTermination(1,TimeUnit.SECONDS)){}workers.shutdown();while(!workers.awaitTermination(1,TimeUnit.SECONDS)){}
        storage.shutdown();while(!storage.awaitTermination(1,TimeUnit.SECONDS)){}
        state.shutdown();while(!state.awaitTermination(1,TimeUnit.SECONDS)){}
        if(journalStore!=null)try{if(!storageFailed){journalStore.checkpoint(durableSnapshot);cache.seal(directory,indexRoots(),preparedRevision,cacheCredentials(durableSnapshot),journalStore.cacheBinding(preparedRevision));cache.cleanup(retainedPaths());}}catch(Exception error){storageFailed=true;storageError=error.getClass().getSimpleName();}finally{try{journalStore.close();}catch(Exception error){storageFailed=true;storageError=error.getClass().getSimpleName();}}
        try{directory.close();if(privateIndex!=null)Files.deleteIfExists(privateIndex);}catch(Exception error){storageFailed=true;storageError=error.getClass().getSimpleName();}
    }
}
