package szserver.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owner-reserved operation pages. Encoding/reading is worker work; durability belongs to the map cohort. */
public final class ResidentOperationLog implements ResidentChunks.State {
    public static final String ROOT="edit-history",WORLD="world",PAGE="edit-page-";
    private static final int ROOT_MAGIC=0x535a4852,PAGE_MAGIC=0x535a4850,VERSION=1,MAX_ACTORS=100_000,MAX_PAYLOAD=48*1024*1024;
    @FunctionalInterface public interface Encoder {byte[] encode()throws Exception;}
    public record Payload(long bytes,Encoder encoder){public Payload {if(bytes<1||bytes>60L*1024*1024)throw new IllegalArgumentException("resident_history_payload_bound");Objects.requireNonNull(encoder);}}
    public record Stored(long id,long revision,UUID actor,UUID operation,String kind,byte[] payload){public Stored {payload=payload.clone();}@Override public byte[] payload(){return payload.clone();}}
    public record Loaded(long revision,long lastEvent,long worldRevision,long tailPage,Map<UUID,Long> heads,byte[] world,boolean commands){
        public Loaded(long revision,long lastEvent,long worldRevision,long tailPage,Map<UUID,Long> heads,byte[] world){this(revision,lastEvent,worldRevision,tailPage,heads,world,false);}
        public Loaded {heads=Map.copyOf(heads);world=world.clone();}@Override public byte[] world(){return world.clone();}
        public static Loaded empty(){return new Loaded(0,0,0,0,Map.of(),new byte[]{0});}
    }
    private record Pending(long id,long revision,UUID actor,UUID operation,String kind,Payload payload,long charge){}
    private record Page(long first,long last,long revision,List<Pending> entries,long charge){}
    private final Thread owner=Thread.currentThread();private final long limit;
    private final ArrayDeque<Page> pages=new ArrayDeque<>();private ArrayList<Pending> active=new ArrayList<>(64);
    private final Map<UUID,Long> heads=new HashMap<>();
    private long revision,savedRevision,lastEvent,tailPage,pendingBytes,activeBytes,worldRevision;
    private Payload world;private boolean worldDirty,commands;private Reservation reserved;
    public ResidentOperationLog(Loaded loaded,long pendingLimit){
        if(pendingLimit<1024||pendingLimit>64L*1024*1024)throw new IllegalArgumentException("resident_history_pending_limit");limit=pendingLimit;revision=savedRevision=loaded.revision();lastEvent=loaded.lastEvent();tailPage=loaded.tailPage();worldRevision=loaded.worldRevision();heads.putAll(loaded.heads());
        commands=loaded.commands();if(revision==0){world=new Payload(1,()->new byte[]{0});worldDirty=true;}
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("resident_history_wrong_owner");}
    private void available(){owner();if(reserved!=null)throw new IllegalStateException("resident_history_open_reservation");}
    @Override public long revision(){owner();return revision;}
    public long latest(UUID actor){available();return heads.getOrDefault(actor,0L);}
    public long pendingBytes(){owner();return pendingBytes+(worldDirty&&world!=null?world.bytes():0);}
    /** Another state owner published a change. Actual immutable roots are bound at the save cut. */
    public void touch(){available();revision=Math.incrementExact(revision);}
    public void requireCommands(){available();if(!commands){commands=true;revision=Math.incrementExact(revision);}}
    public boolean commandsRequired(){owner();return commands;}
    /** Capture immutable world/scene metadata roots before invoking this; encoder cannot consult live fields. */
    public void world(Payload next){available();Objects.requireNonNull(next);if(pendingBytes+next.bytes()>limit)throw new IllegalArgumentException("resident_history_pending_byte_limit");long changed=Math.incrementExact(revision);world=next;worldDirty=true;worldRevision=revision=changed;}
    public Reservation prepare(UUID actor,UUID operation,String kind,Payload payload){
        return prepare(actor,operation,kind,payload,null);
    }
    /** Reserve the next immutable global metadata root in the same revision as its physical edit. */
    public Reservation prepare(UUID actor,UUID operation,String kind,Payload payload,Payload nextWorld){
        available();Objects.requireNonNull(actor);Objects.requireNonNull(operation);Objects.requireNonNull(payload);
        if(kind==null||!kind.matches("[a-z][a-z0-9_-]{0,31}")||payload.bytes()>MAX_PAYLOAD)throw new IllegalArgumentException("resident_history_record");
        long charge=payload.bytes()+256;long worldCharge=nextWorld==null?(worldDirty&&world!=null?world.bytes():0):nextWorld.bytes();if(pendingBytes+worldCharge+charge>limit)throw new IllegalArgumentException("resident_history_pending_byte_limit");if(!heads.containsKey(actor)&&heads.size()>=MAX_ACTORS)throw new IllegalArgumentException("resident_history_actor_limit");
        if(!active.isEmpty()&&(active.size()==64||activeBytes+charge>512*1024))seal();
        Pending entry=new Pending(Math.incrementExact(lastEvent),Math.incrementExact(revision),actor,operation,kind,payload,charge);Long previous=heads.put(actor,entry.id());return reserved=new Reservation(entry,previous,nextWorld);
    }
    public final class Reservation implements AutoCloseable {
        private final Pending entry;private final Long previous;private final Payload nextWorld;private boolean done;
        private Reservation(Pending entry,Long previous,Payload nextWorld){this.entry=entry;this.previous=previous;this.nextWorld=nextWorld;}
        public long id(){return entry.id();}
        /** Capacity and list allocation were performed before native mutation. No serialization or disk here. */
        public void commit(){owner();if(done||reserved!=this||entry.id()!=lastEvent+1||entry.revision()!=revision+1)throw new IllegalStateException("resident_history_reservation_stale");active.add(entry);activeBytes+=entry.charge();pendingBytes+=entry.charge();lastEvent=entry.id();revision=entry.revision();if(nextWorld!=null){world=nextWorld;worldDirty=true;worldRevision=revision;}done=true;reserved=null;}
        @Override public void close(){owner();if(done)return;if(reserved!=this)throw new IllegalStateException("resident_history_reservation_stale");if(previous==null)heads.remove(entry.actor());else heads.put(entry.actor(),previous);done=true;reserved=null;}
    }
    private void seal(){if(active.isEmpty())return;Page page=new Page(active.get(0).id(),active.get(active.size()-1).id(),active.get(active.size()-1).revision(),List.copyOf(active),activeBytes);pages.addLast(page);tailPage=page.first();active=new ArrayList<>(64);activeBytes=0;}
    private static String pageName(long first){String digits=Long.toString(first);return PAGE+"0".repeat(20-digits.length())+digits;}
    private static byte[] payload(Payload payload)throws Exception{byte[] bytes=payload.encoder().encode();if(bytes==null||bytes.length<1||bytes.length>payload.bytes())throw new IOException("resident_history_encoded_bound");return bytes;}
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static Stored stored(Pending entry)throws Exception{return new Stored(entry.id(),entry.revision(),entry.actor(),entry.operation(),entry.kind(),payload(entry.payload()));}
    private static byte[] encodePage(Page page)throws Exception{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(PAGE_MAGIC);out.writeInt(VERSION);out.writeLong(page.first());out.writeLong(page.last());out.writeInt(page.entries().size());for(var entry:page.entries()){byte[] body=payload(entry.payload());out.writeLong(entry.id());out.writeLong(entry.revision());uuid(out,entry.actor());uuid(out,entry.operation());out.writeUTF(entry.kind());out.writeInt(body.length);out.write(body);}}if(bytes.size()>64*1024*1024)throw new IOException("resident_history_page_size");return bytes.toByteArray();
    }
    private static byte[] root(long revision,long last,long worldRevision,long tail,Map<UUID,Long> heads,boolean commands)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(ROOT_MAGIC);out.writeInt(2);out.writeLong(revision);out.writeLong(last);out.writeLong(worldRevision);out.writeLong(tail);out.writeInt(heads.size());for(UUID actor:new TreeSet<>(heads.keySet())){uuid(out,actor);out.writeLong(heads.get(actor));}out.writeBoolean(commands);}return bytes.toByteArray();
    }
    @Override public ResidentChunks.FrozenState freeze(){
        available();seal();long captured=revision,last=lastEvent,worldEpoch=worldRevision,tail=tailPage;var frozenPages=List.copyOf(pages);var frozenHeads=Map.copyOf(heads);Payload frozenWorld=worldDirty?world:null;boolean frozenCommands=commands;
        return new ResidentChunks.FrozenState(captured,()->{List<ResidentChunks.StateFile> files=new ArrayList<>();for(var page:frozenPages)files.add(new ResidentChunks.StateFile(pageName(page.first()),encodePage(page)));files.add(new ResidentChunks.StateFile(ROOT,root(captured,last,worldEpoch,tail,frozenHeads,frozenCommands)));if(frozenWorld!=null)files.add(new ResidentChunks.StateFile(WORLD,payload(frozenWorld)));return new ResidentChunks.StateDelta(captured,files,Set.of());});
    }
    @Override public void persisted(long captured){available();if(captured<savedRevision||captured>revision)throw new IllegalStateException("resident_history_saved_revision");savedRevision=captured;while(!pages.isEmpty()&&pages.peekFirst().revision()<=captured)pendingBytes-=pages.removeFirst().charge();if(worldRevision<=captured){worldDirty=false;world=null;}}
    /** Owner lookup of an unsaved entry; only its immutable encoder is sent to the worker. */
    public ResidentChunks.StateRead<Stored> read(long id){
        available();if(id<1||id>lastEvent)throw new IllegalArgumentException("resident_history_event_missing");Pending found=null;for(var entry:active)if(entry.id()==id){found=entry;break;}if(found==null){var iterator=pages.descendingIterator();while(iterator.hasNext()){var page=iterator.next();if(id>=page.first()&&id<=page.last()){found=page.entries().get((int)(id-page.first()));break;}}}Pending pending=found;return pending==null?files->readSaved(files,id):files->stored(pending);
    }
    public static Stored readSaved(ResidentChunks.StateFiles files,long id)throws Exception{
        String selected=null,ceiling=pageName(id);for(String name:files.index().files().keySet())if(name.startsWith(PAGE)&&name.compareTo(ceiling)<=0&&(selected==null||name.compareTo(selected)>0))selected=name;if(selected==null)throw new IOException("resident_history_event_missing");var saved=files.load(selected);if(saved==null)throw new IOException("resident_history_page_missing");for(var entry:decodePage(saved.bytes()))if(entry.id()==id)return entry;throw new IOException("resident_history_event_missing");
    }
    private static List<Stored> decodePage(byte[] bytes)throws IOException{
        if(bytes.length>64*1024*1024)throw new IOException("resident_history_page_size");try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=PAGE_MAGIC||in.readInt()!=VERSION)throw new IOException("resident_history_page_version");long first=in.readLong(),last=in.readLong();int count=in.readInt();if(first<1||last<first||count<1||count>64||last-first!=count-1)throw new IOException("resident_history_page_range");List<Stored> entries=new ArrayList<>(count);long previousRevision=0;for(int i=0;i<count;i++){long id=in.readLong(),revision=in.readLong();UUID actor=uuid(in),operation=uuid(in);String kind=in.readUTF();int size=in.readInt();if(id!=first+i||revision<=previousRevision||!kind.matches("[a-z][a-z0-9_-]{0,31}")||size<1||size>MAX_PAYLOAD||size>in.available())throw new IOException("resident_history_page_entry");entries.add(new Stored(id,revision,actor,operation,kind,in.readNBytes(size)));previousRevision=revision;}if(in.available()!=0)throw new IOException("resident_history_page_trailing");return List.copyOf(entries);}
    }
    public static Loaded bootstrap(ResidentChunks.Storage storage)throws Exception{
        return bootstrap(storage,bytes->{});
    }
    @FunctionalInterface public interface WorldValidator {void validate(byte[] bytes)throws IOException;}
    /** Validate the actual metadata schema before storage exposes any chunk from the selected root. */
    public static Loaded bootstrap(ResidentChunks.Storage storage,WorldValidator validator)throws Exception{
        Objects.requireNonNull(validator);
        return storage.bootstrapState(files->restore(files,validator));
    }
    public static Loaded restore(ResidentChunks.StateFiles files,WorldValidator validator)throws Exception{
        Objects.requireNonNull(validator);var index=files.index();if(index.revision()==0)return Loaded.empty();var saved=files.load(ROOT);var world=files.load(WORLD);if(saved==null||world==null||saved.revision()!=index.revision())throw new IOException("resident_history_root_missing");
            try(var in=new DataInputStream(new ByteArrayInputStream(saved.bytes()))){if(in.readInt()!=ROOT_MAGIC)throw new IOException("resident_history_root_version");int version=in.readInt();if(version!=1&&version!=2)throw new IOException("resident_history_root_version");long revision=in.readLong(),last=in.readLong(),worldEpoch=in.readLong(),tail=in.readLong();int actors=in.readInt();if(revision!=index.revision()||last<0||worldEpoch<0||worldEpoch>revision||worldEpoch>world.revision()||tail<0||tail>last||last>0&&tail==0||actors<0||actors>MAX_ACTORS)throw new IOException("resident_history_root_values");Map<UUID,Long> heads=new HashMap<>();for(int i=0;i<actors;i++){UUID actor=uuid(in);long id=in.readLong();if(id<1||id>last||heads.put(actor,id)!=null)throw new IOException("resident_history_actor_head");}int commandFlag=version==2?in.readUnsignedByte():0;if(commandFlag>1)throw new IOException("resident_history_command_flag");if(in.available()!=0)throw new IOException("resident_history_root_trailing");if(last>0){var page=files.load(pageName(tail));if(page==null)throw new IOException("resident_history_tail_missing");var entries=decodePage(page.bytes());if(entries.get(0).id()!=tail||entries.get(entries.size()-1).id()!=last||entries.get(entries.size()-1).revision()>revision)throw new IOException("resident_history_tail_range");}validator.validate(world.bytes());return new Loaded(revision,last,worldEpoch,tail,heads,world.bytes(),commandFlag==1);}
    }
    public Map<String,Object> metrics(){owner();return Map.of("revision",revision,"savedRevision",savedRevision,"lastEvent",lastEvent,"pendingBytes",pendingBytes(),"pendingPages",pages.size(),"activeEntries",active.size(),"actors",heads.size(),"reserved",reserved!=null);}
}
