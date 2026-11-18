package replay;

import java.io.*;
import java.util.*;
import com.google.gson.*;

/** Audited source layouts. Extra source fields stay attached to the decoded object. */
final class SourceAdapters implements ReplayDecode.Hooks {
    final Map<Object,Map<String,Object>> extras=Collections.synchronizedMap(new WeakHashMap<>());
    final Set<String> verified=new HashSet<>();final DemoFile demo;
    QuestHints activeQuestHints;
    final ThreadLocal<Boolean> nativeGunDetails=ThreadLocal.withInitial(()->false);
    long appliedGunDetails,sourceMotifProgress;
    final Map<Integer,Map<Integer,Integer>> motifProgressSnapshots=new TreeMap<>();
    Map<Long,Object> prefabKeys;long prefabsDecoded;
    final JsonArray prefabFailures=new JsonArray();
    volatile Throwable tailFailure;long appliedTailBytes,discardedTailBytes;int appliedTailComponents;
    final Map<String,Integer> sourceOnlyOccurrences=new TreeMap<>();
    final Map<String,Object> sourceOnlyLastValues=new TreeMap<>();
    long staleLocalReloads;
    SourceAdapters(DemoFile demo){this.demo=demo;}
    public boolean componentDetails(Object component,DataInput input)throws Exception {
        if(!component.getClass().getName().equals("123380613"))return false;
        verify("1079929180");verify("123380613");verify("1052471793");verify("1987590071");verify("1953837776");
        SourceLight.read(input).apply(Native.field(component,"instanceof"));return true;
    }
    public Object gameObject(DataInput input)throws Exception {
        verify("1814125595");verify("1886271463");
        if(prefabKeys==null){
            Map<Long,Object> keys=new HashMap<>();for(Object definition:((Map<?,?>)Native.field(Native.type("1814125595"),"char")).values()){
                String name=(String)Native.field(definition,"while");Object previous=keys.put(SourceGameObjects.key(name),definition);if(previous!=null)throw new IOException("Colliding source prefab key "+name);
            }prefabKeys=Map.copyOf(keys);
        }
        long key=input.readLong();Object definition=prefabKeys.get(key);if(definition==null)throw new IOException("Unknown source prefab key "+Long.toUnsignedString(key));
        if(!input.getClass().getName().equals("1754157392"))throw new IOException("Source prefab requires a bounded native buffer");
        byte[] backing=(byte[])Native.field(input,"catch");int position=((Number)Native.field(input,"private")).intValue();byte[] source=Arrays.copyOfRange(backing,position,backing.length);
        // This constructor's generated instance counter is also the source
        // default when its optional counter was omitted from a preset snapshot.
        Object object=Native.type("1886271463").getConstructor(UUID.class,Native.type("1814125595")).newInstance(UUID.nameUUIDFromBytes(source),definition);
        int defaultInstance=((Number)Native.field(object,"float")).intValue();byte[] translated=SourceGameObjects.payload(source,defaultInstance);
        Object buffer=Native.type("1754157392").getConstructor(byte[].class).newInstance((Object)translated);
        try{Native.call(object,"char","void",buffer);}catch(Exception|LinkageError failure){
            if(prefabFailures.size()<12){JsonObject entry=new JsonObject();entry.addProperty("prefab",(String)Native.field(definition,"while"));entry.addProperty("native_consumed",((Number)Native.field(buffer,"private")).intValue());entry.addProperty("source_bytes",source.length);entry.addProperty("translated_prefix_hex",HexFormat.of().formatHex(Arrays.copyOf(translated,Math.min(256,translated.length))));prefabFailures.add(entry);}
            throw failure;
        }
        if(((Number)Native.field(buffer,"private")).intValue()!=translated.length)throw new IOException("Unread source prefab payload "+Native.field(definition,"while"));
        put(input,"private",backing.length);prefabsDecoded++;return Native.call(Native.type("1886271463"),"if","1886271463",Native.field(definition,"while"),object);
    }
    public boolean itemDetails(Object definition,Object item,DataInput input)throws Exception {
        if(nativeGunDetails.get())return false;
        for(String name:List.of("1745367406","1536296909","1160390453","211097523","1535556086","822754569","1695012753","553616757","359912486","1366800141"))verify(name);
        SourceGun.State state=SourceGun.read(input);
        Object[] slots=(Object[])Native.field(Native.type("1536296909"),"slots");
        if(slots.length!=SourceGun.SLOTS.size())throw new IOException("Native attachment slots changed");
        for(int n=0;n<slots.length;n++)if(!((Enum<?>)slots[n]).name().equals(SourceGun.SLOTS.get(n)))throw new IOException("Native attachment slot identity changed");
        extras.computeIfAbsent(item,key->new LinkedHashMap<>()).put("source_weapon_display",state);
        for(SourceGun.Attachment part:state.attachments())if(part.motifProgress()!=null){sourceMotifProgress++;motifProgressSnapshots.computeIfAbsent(part.item(),key->new TreeMap<>()).merge(Short.toUnsignedInt(part.motifProgress()),1,Integer::sum);}
        // This is the complete authoritative display snapshot. Source writes
        // a HashMap, so parents (mounts/barrel) can follow their children. The
        // older native reader validates each attachment against a partial gun
        // and discards children whose parent has not been read yet.
        Map<Object,Object> attachments=new LinkedHashMap<>();Object[] items=(Object[])Native.field(Native.type("1366800141"),"import");
        for(SourceGun.Attachment part:state.attachments()){
            Object slot=part.slot()>=0?slots[part.slot()]:Native.field(Native.type(switch(part.slot()){case -1->"1695012753";case -2->"553616757";case -3->"359912486";default->throw new IOException("Invalid special attachment slot");}),switch(part.slot()){case -1->"abstract";case -2->"enum";default->"package";});
            if(part.item()>=items.length||items[part.item()]==null)throw new IOException("Unknown recorded attachment item "+part.item());
            Object attachment=Native.type("211097523").getConstructor(Native.type("1366800141")).newInstance(items[part.item()]);
            if(part.motifProgress()!=null){verify("390888342");Object progress=Native.call(attachment,"static","java.lang.Object",Native.type("390888342"));if(progress==null)throw new IOException("Recorded motif lacks its native visual component: "+part.item());put(progress,"szreplay$progress",part.motifProgress());}
            if(attachments.put(slot,attachment)!=null)throw new IOException("Duplicate recorded attachment slot "+part.slot());
        }
        byte[] nativeBytes=new SourceGun.State(state.skin(),List.of(),state.ammunition(),state.counter()).nativeBytes();nativeGunDetails.set(true);
        try(DataInputStream translated=new DataInputStream(new ByteArrayInputStream(nativeBytes))){
            Native.call(definition,"strictfp","void",item,translated);if(translated.available()!=0)throw new IOException("Unread native weapon display bytes: "+translated.available());
        }finally{nativeGunDetails.remove();}
        Object container=Native.call(item,"static","java.lang.Object",Native.type("822754569"));
        Native.call(container,"throw","void",attachments);
        Map<?,?> installed=(Map<?,?>)Native.call(container,"for","java.util.Map");
        if(!installed.equals(attachments))throw new IOException("Native attachment snapshot rejected");
        appliedGunDetails++;return true;
    }
    void verify(String name)throws Exception {if(!verified.contains(name)){demo.verifyClass(name);verified.add(name);}}
    void verifyDependency(String name,String pinnedHash)throws Exception {demo.verifyDependency(name,pinnedHash);}
    static void put(Object object,String field,Object value)throws Exception {ReplayClock.field(object,field).set(object,value);}
    static int length(int n)throws IOException {if(n<0||n>32*1024*1024)throw new IOException("Invalid source allocation: "+n);return n;}
    static long var(DataInput in,int limit)throws IOException {
        long value=0;for(int n=0;n<limit;n++){int b=in.readUnsignedByte();value|=(long)(b&127)<<(7*n);if((b&128)==0)return value;}throw new IOException("Invalid source varint");
    }
    static byte[] bytes(DataInput in,int n)throws IOException {byte[] value=new byte[length(n)];in.readFully(value);return value;}
    public boolean replace(Object object,DataInput in)throws Exception {
        if(Native.type("1570813014").isInstance(object)){verify("1570813014");SourceSpawn.State state=SourceSpawn.read(in);SourceSpawn.nativeFields(object,state);extras.put(object,Map.of("source_spawn",state));return true;}
        if(object.getClass().getName().equals("928936779")){verify("928936779");extras.put(object,Map.of("source_primary_quest",in.readBoolean()));return true;}
        if(object.getClass().getName().equals("1072250159")){
            verify("1072250159");verify("211097523");put(object,"static",in.readInt());
            extras.put(object,Map.of("source_equipment_instance",new UUID(in.readLong(),in.readLong())));
            put(object,"enum",in.readFloat());put(object,"public",in.readFloat());put(object,"class",in.readFloat());put(object,"case",Native.call(Native.type("1046176253"),"new","211097523",in));
            put(object,"import",in.readBoolean());put(object,"package",in.readBoolean());put(object,"if",in.readBoolean());put(object,"transient",in.readInt());put(object,"new",in.readUTF());return true;
        }
        if(object.getClass().getName().equals("1536302723")){
            verify("1536302723");int count=(int)var(in,5);if(count<0||count>65535)throw new IOException("Invalid attribute count");
            Map<String,Object> extra=extras.computeIfAbsent(object,k->new LinkedHashMap<>());
            for(int i=0;i<count;i++){
                int source=in.readUnsignedShort();
                // These source-only primitive definitions are audited in the
                // source registry and binding constructors. Keep their values
                // attached to this entity's container until their native UI or
                // component behavior is ported; never alias an unrelated ID.
                JsonObject definitions=demo.manifest.getAsJsonObject("source_only_attribute_codecs");
                JsonObject definition=definitions==null?null:definitions.getAsJsonObject(Integer.toString(source));
                if(definition!=null){
                    String name=definition.get("name").getAsString();
                    // Existing demos used the enum's value codec but omitted
                    // the stamped-property envelope. The source binding is
                    // fixed across this audited version, including old demos.
                    Object value=null;if(name.equals("GOResourceExtractor_state"))value=SourceExtractorState.read(in,ReplayClock.seconds,ReplayClock.wallMillis());
                    else if(!in.readBoolean())value=switch(definition.get("codec").getAsString()){
                        case "nullable_int32"->in.readInt();case "nullable_boolean"->in.readBoolean();case "nullable_float32"->in.readFloat();case "nullable_duration_ms"->java.time.Duration.ofMillis(in.readLong());
                        case "nullable_music_v1"->SourceMusic.read(in);
                        case "nullable_enum16"->{int ordinal=in.readShort();JsonArray values=definition.getAsJsonArray("values");if(ordinal<0||ordinal>=values.size())throw new IOException("Invalid source enum ordinal "+source+":"+ordinal);yield Map.of("ordinal",ordinal,"name",values.get(ordinal).getAsString());}
                        default->throw new IOException("Unsupported source-only attribute codec "+source);
                    };extra.put(name,value);sourceOnlyOccurrences.merge(name,1,Integer::sum);sourceOnlyLastValues.put(name,value);continue;
                }
                int id=ReplayDecode.attribute(source);Object property=Native.call(Native.field(object,"this"),"case","java.lang.Object",(short)id);
                if(property==null)throw new IOException("Unbound native entity attribute source="+source+" target="+id);
                Object previous=Native.field(property,"do");try{Native.call(property,"volatile","void",in);Native.call(object,"instanceof","void",property,previous);}
                catch(Exception|LinkageError failure){throw new IOException("Source entity attribute "+source+" -> native "+id+" failed",failure);}
            }return true;
        }
        if(object.getClass().getName().equals("866048497")){
            verify("866048497");verify("1727310608");Object position=Native.type("1727310608").getDeclaredConstructor().newInstance();Native.call(position,"int","void",in);put(object,"long",position);
            Map<String,Object> extra=new LinkedHashMap<>();extra.put("source_chunk_revision",in.readLong());extras.put(object,extra);put(object,"finally",null);return true;
        }
        if(!object.getClass().getName().equals("336545457"))return false;
        verify("336545457");verify("1444774311");verify("1894149612");
        Map<String,Object> extra=new LinkedHashMap<>();
        put(object,"goto",in.readInt());put(object,"long",in.readInt());boolean blocks=in.readBoolean(),entities=in.readBoolean();put(object,"static",blocks);
        if(blocks){
            put(object,"private",in.readInt());put(object,"final",in.readInt());extra.put("source_chunk_revision",in.readInt());
            put(object,"void",in.readInt());put(object,"extends",bytes(in,in.readInt()));
            // The source protocol has no native chunk UUID. Explicit sentinel;
            // this layout adapter does not certify native world equivalence.
            put(object,"protected",new UUID(0,0));extra.put("native_chunk_uuid_unavailable",true);
        }
        if(entities){extra.put("source_entity_revision",in.readInt());put(object,"interface",bytes(in,(int)var(in,5)));}else put(object,"interface",new byte[0]);
        int count=length((int)var(in,5));if(count>1_048_576)throw new IOException("Source chunk palette too large");int[] palette=new int[count];
        if(count>0){int base=in.readInt();for(int i=0;i<count;i++)palette[i]=(int)(base+var(in,10));}extra.put("source_palette",palette);
        put(object,"package",Native.call(Native.type("1444774311"),"continue","1444774311",in));
        Object state=Native.type("1894149612").getDeclaredConstructor().newInstance();Native.call(state,"public","void",in);put(object,"strictfp",state);
        put(object,"char",Native.call(Native.type("336545457"),"protected","776578491",Native.field(object,"extends"),Native.field(object,"void"),Native.field(object,"interface"),Native.field(object,"package")));
        extra.put("compressed_contents_translated",false);extras.put(object,extra);return true;
    }
    public void after(Object object,DataInput input)throws Exception {
        if(object.getClass().getName().equals("1176115659")){extras.put(object,Map.of("source_hit_extra_flag",input.readBoolean()));return;}
        if(!object.getClass().getName().equals("859236069"))return;
        verify("859236069");verify("595713561");
        Map<String,Object> extra=new LinkedHashMap<>();extra.put("source_item_extra_int",input.readInt());
        extra.put("source_item_extra_text",input.readBoolean()?null:Native.call(Native.type("1046176253"),"goto","595713561",input));extras.put(object,extra);
    }
    public boolean apply(Object packet)throws Exception {
        String type=packet.getClass().getName();
        if(type.equals("1072250159")){
            // The newer reload handler rejects a stale update for the local
            // held weapon by instance UUID before starting its animation.
            verify("1402131154");verify("211097523");Object game=Native.call(Native.type("1402131154"),"try","1402131154");Object local=Native.field(game,"e");
            if(local!=null&&((Number)Native.call(local,"for","int")).intValue()==((Number)Native.field(packet,"static")).intValue()){
                Map<String,Object> fields=extras.get(packet);if(fields==null)throw new IOException("Missing recorded reload instance UUID");
                Object held=Native.call(local,"while","211097523");
                boolean stale=held!=null&&!fields.get("source_equipment_instance").equals(Native.call(held,"catch","java.util.UUID"));if(stale)staleLocalReloads++;return stale;
            }
            return false;
        }
        // A recorded protection exchange changes no world state. Its original
        // response stays archived, with no new machine query or outbound send.
        if(type.equals("290609376"))return true;
        if(activeQuestHints==null)return false;
        if(type.equals("447872792")){activeQuestHints.update(Native.field(packet,"finally"));return true;}
        if(type.equals("928936779")){Map<String,Object> fields=extras.get(packet);if(fields==null||!fields.containsKey("source_primary_quest"))throw new IOException("Missing source quest clear slot");activeQuestHints.clear((Boolean)fields.get("source_primary_quest"));return true;}
        return false;
    }
    public void deferredFailure(Throwable failure){tailFailure=failure;}
    public void discardedTail(int bytes){discardedTailBytes+=bytes;}
    public void tailApplied(Object actor,long order,double received,double consumed,int flags,int bytes,String trigger)throws Exception {
        if(Boolean.getBoolean("sz.replay.qa.tailtrace")&&ReplayClock.active&&ReplayRender.actorTimes!=null)
            ReplayRender.actorTimes.tailApplied(actor,order,received,consumed,flags,bytes,trigger);
    }
    public void tail(Object entity,DataInput input,int flags)throws Exception {
        int start=input instanceof DataInputStream stream?stream.available():((Number)Native.field(input,"private")).intValue();
        if((flags&32)!=0)Native.call(Native.call(entity,"float","1536302723"),"native","void",input);
        if((flags&1)!=0){
            List<?> components=(List<?>)Native.call(entity,"static","java.util.List");Object mode=Native.field(Native.type("1120690568"),(flags&16)!=0?"SELF":"OTHERS");boolean last;
            do{int selector=(int)var(input,5),index=selector>>>1;last=(selector&1)!=0;if(index>=components.size())throw new IOException("Source motion component index out of range: "+index);
                Object component=components.get(index);verify(component.getClass().getName());Native.call(component,"strictfp","void",input,mode);appliedTailComponents++;
            }while(!last);
        }
        int end=input instanceof DataInputStream stream?stream.available():((Number)Native.field(input,"private")).intValue();appliedTailBytes+=Math.abs(end-start);
        if(end!=start)ReplayRender.nativeActorStateChanged(entity);
    }
}
