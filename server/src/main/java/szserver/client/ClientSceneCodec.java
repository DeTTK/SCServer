package szserver.client;

import szserver.world.*;
import szserver.net.Wire;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Reviewed native default-object reader/writer. No launcher, rendering setup or desktop input. */
public final class ClientSceneCodec implements AutoCloseable {
    public record Decoded(UUID identity,byte[] body) {public Decoded{body=body.clone();}@Override public byte[] body(){return body.clone();}}
    /** Actual bundle:alliances parser and pure directed native510 lookups, no faction guesses. */
    public szserver.net.AreaRelations areaRelations()throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_area_relations_on_owner");
        Class<?> manager=Class.forName("1521591007",true,loader);Object instance=manager.getMethod("goto").invoke(null);manager.getMethod("abstract").invoke(instance);
        Map<?,?> definitions=(Map<?,?>)manager.getField("abstract").get(instance);List<szserver.net.AreaRelations.Alliance> result=new ArrayList<>();
        for(var entry:definitions.entrySet()){Object definition=entry.getValue();Class<?> type=definition.getClass();String id=entry.getKey().toString(),origin=type.getField("public").get(definition).toString();Set<String> friendly=new TreeSet<>();for(var other:definitions.keySet())if(((Enum<?>)type.getMethod("return",String.class).invoke(definition,other.toString())).name().equals("FRIENDLY"))friendly.add(other.toString());result.add(new szserver.net.AreaRelations.Alliance(id,origin,friendly));}
        return new szserver.net.AreaRelations(result);
    }
    /** Genuine pure native party comparison, including the -1 sentinel. */
    public boolean partyAlliedOracle(Long left,Long right)throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_area_relations_on_owner");
        return (boolean)Class.forName("1900336751",true,loader).getMethod("new",Long.class,Long.class).invoke(null,left,right);
    }
    /** Actual native resistance math on an ordinary typed source, no world or entity handler. */
    public float blastFactorOracle(String kind,Map<String,Float> values)throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_damage_on_owner");
        Class<?> bank=Class.forName("964863275",true,loader),stat=Class.forName("1924679062",true,loader),type=Class.forName("1160872970",true,loader);Object stats=bank.getConstructor().newInstance();
        for(var e:values.entrySet()){Object key=stat.getMethod("abstract",String.class).invoke(null,e.getKey());if(key==null)throw new IllegalArgumentException("native_stat_unknown:"+e.getKey());bank.getMethod("double",stat,float.class).invoke(stats,key,e.getValue());}
        Object source=Class.forName("415006645",true,loader).getConstructor(String.class,type).newInstance("grenade_server_check",type.getField(kind).get(null));
        return 1-((Number)bank.getMethod("short",Class.forName("49549491",true,loader)).invoke(stats,source)).floatValue();
    }
    public int grenadeEffectKind(boolean oneShot)throws Exception{if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_effect_kind_on_owner");return ((Enum<?>)Class.forName("1585835196",true,loader).getField(oneShot?"GRENADE_EFFECT_INSTANT":"GRENADE_EFFECT_CONTINUOUS").get(null)).ordinal();}
    public record NativeEffect(int id,int item,String group,int priority,String kind,long until,Map<String,Float> stats){}
    public record NativeEffects(Map<Integer,NativeEffect> effects,Map<String,Float> bank){}
    private Object effectOracle;
    /** Actual GameplayEffects SELF incremental reader, with a null entity and no graphical callback. */
    public NativeEffects effectOracle(byte[] payload)throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_effect_on_owner");
        Class<?> component=Class.forName("1967986234",true,loader),visibility=Class.forName("1120690568",true,loader),effectType=Class.forName("837608172",true,loader),attribute=Class.forName("461891820",true,loader),attributes=Class.forName("626983914",true,loader);if(effectOracle==null)effectOracle=component.getConstructor(boolean.class,boolean.class).newInstance(false,false);
        var in=new DataInputStream(new ByteArrayInputStream(payload));component.getMethod("strictfp",DataInput.class,visibility).invoke(effectOracle,in,visibility.getField("SELF").get(null));if(in.available()!=0)throw new IOException("native_effect_trailing_bytes");
        Object collection=component.getField("else").get(effectOracle);Map<Integer,NativeEffect> result=new TreeMap<>();for(Object effect:(Collection<?>)collection.getClass().getMethod("native").invoke(collection)){
            Object item=effectType.getMethod("instanceof",attribute).invoke(effect,attributes.getField("assert").get(null)),group=effectType.getMethod("instanceof",attribute).invoke(effect,attributes.getField("import").get(null)),priority=effectType.getMethod("instanceof",attribute).invoke(effect,attributes.getField("boolean").get(null)),time=effectType.getMethod("instanceof",attribute).invoke(effect,attributes.getField("try").get(null));long until=time==null?0:((java.time.Instant)time.getClass().getMethod("case").invoke(time)).toEpochMilli();int id=effectType.getField("double").getInt(effect);result.put(id,new NativeEffect(id,item==null?-1:Short.toUnsignedInt(((Number)item).shortValue()),group==null?null:group.toString(),priority==null?0:((Number)priority).intValue(),((Enum<?>)effectType.getField("case").get(effect)).name(),until,nativeBank(effectType.getField("try").get(effect))));
        }return new NativeEffects(Map.copyOf(result),nativeBank(component.getField("break").get(effectOracle)));
    }
    private Map<String,Float> nativeBank(Object bank)throws Exception{Map<String,Float> result=new TreeMap<>();Method export=Arrays.stream(bank.getClass().getMethods()).filter(m->m.getName().equals("float")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();for(var e:((Map<?,?>)export.invoke(bank)).entrySet())result.put(e.getKey().getClass().getField("for").get(e.getKey()).toString(),((Number)e.getValue()).floatValue());return Map.copyOf(result);}
    /** Cold extraction from the actual enum/resource parser, including native filtered level banks. */
    public szserver.net.SicknessRules sicknessRules()throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_sickness_on_owner");
        Class<?> bank=Class.forName("964863275",true,loader),type=Class.forName("136359471",true,loader);Method values=Arrays.stream(bank.getMethods()).filter(m->m.getName().equals("float")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        List<szserver.net.SicknessRules.Profile> result=new ArrayList<>();for(Object value:type.getEnumConstants()){
            Object definition=type.getField("continue").get(value);Class<?> d=definition.getClass();List<Float> bounds=new ArrayList<>(),damage=new ArrayList<>();for(float n:(float[])d.getField("void").get(definition))bounds.add(n);for(float n:(float[])d.getField("native").get(definition))damage.add(n);
            List<Map<String,Float>> properties=new ArrayList<>();for(Object b:(Object[])d.getField("new").get(definition)){Map<String,Float> p=new TreeMap<>();if(b!=null)for(var e:((Map<?,?>)values.invoke(b)).entrySet()){String name=e.getKey().getClass().getField("for").get(e.getKey()).toString();p.put(name,((Number)e.getValue()).floatValue());}properties.add(Map.copyOf(p));}
            String acc=type.getField("break").get(value).getClass().getField("for").get(type.getField("break").get(value)).toString(),protection=type.getField("static").get(value).getClass().getField("for").get(type.getField("static").get(value)).toString(),damageProtection=type.getField("char").get(value).getClass().getField("for").get(type.getField("char").get(value)).toString();
            result.add(new szserver.net.SicknessRules.Profile(((Enum<?>)value).name(),d.getField("for").get(definition).toString(),((Enum<?>)value).ordinal(),acc,protection,damageProtection,bounds,damage,d.getField("synchronized").getFloat(definition),d.getField("int").getFloat(definition),d.getField("protected").getFloat(definition),d.getField("enum").getInt(definition),d.getField("public").getInt(definition),d.getField("byte").getFloat(definition),properties));
        }return new szserver.net.SicknessRules(result);
    }
    public record SicknessOracle(int level,float damage,float dose){}
    /** Actual pure component methods; entity-dependent handlers and stripped native tick are not invoked. */
    public SicknessOracle sicknessOracle(String kind,float power,float dose,float resistance,int penetration)throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_sickness_on_owner");
        Class<?> type=Class.forName("136359471",true,loader),bank=Class.forName("964863275",true,loader),component=Class.forName("1953060942",true,loader),stat=Class.forName("1924679062",true,loader);Object value=type.getField(kind).get(null),object=component.getConstructor(type,Class.forName("200241649",false,loader)).newInstance(value,null);component.getField("private").setFloat(object,power);Object stats=bank.getConstructor().newInstance();bank.getMethod("double",stat,float.class).invoke(stats,type.getField("static").get(value),resistance);Object companion=Class.forName("1201171520",true,loader).getConstructor().newInstance();float protectedDose=((Number)companion.getClass().getMethod("native",bank,type,float.class,int.class).invoke(companion,stats,value,dose,penetration)).floatValue();
        return new SicknessOracle((int)component.getMethod("double").invoke(object),((Number)component.getMethod("float").invoke(object)).floatValue(),protectedDose);
    }
    static final class Loader extends URLClassLoader {
        private Path dll;
        Loader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
        boolean loaded(String name){return findLoadedClass(name)!=null;}
        @Override protected String findLibrary(String name){
            if(!name.equals("zstd-jni-1.5.0-5"))return null;
            // Native NBT's reviewed initializer calls loadLibrary directly. Resolve the same
            // bundled JNI for this isolated classloader, without changing the installed runtime.
            try{if(dll==null)dll=NativePlatform.extract(this,"sz-scene-zstd-");return dll.toAbsolutePath().toString();}catch(IOException error){throw new IllegalStateException(error);}
        }
    }
    private final Loader loader;private final Class<?> factoryType,worldType,json,descriptor;private final Method read,write;private final Object context;
    private final Map<String,Object> factories=new HashMap<>();
    private final Map<String,Object> defaultFactories=new LinkedHashMap<>(128,.75f,true);
    private boolean defaultsPrepared;
    private NativeSceneClock clock;
    private NativeSceneIncident incident;
    private NativeSceneEncaged encaged;
    private Map<String,Integer> repairNames=Map.of();
    private final Path runtimeRoot;
    private Path supplyContext;private boolean supplyPrepared;private Map<String,Integer> supplyNames=Map.of();
    /** Initialize real native behavior factories on a startup/editor worker. No renderer or launcher. */
    public void prepareDefaults(Path bindings)throws Exception{
        if(defaultsPrepared)throw new IllegalStateException("scene_defaults_already_prepared");
        if(RuntimeFiles.size(bindings)>1024*1024)throw new IOException("scene_default_bindings_limit");var manifest=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(bindings)).getAsJsonObject();if(manifest.get("version").getAsInt()!=1)throw new IOException("scene_default_bindings_version");
        for(var entry:manifest.getAsJsonObject("classesSha256").entrySet()){String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("scene_default_class_name");String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtimeRoot.resolve("classes").resolve(name+".class"))));if(!actual.equals(entry.getValue().getAsString()))throw new IOException("scene_default_native_release_changed:"+name);}
        clock=new NativeSceneClock(loader,runtimeRoot,bindings.resolveSibling("native-scene-clock-context.json"));
        incident=new NativeSceneIncident(loader,runtimeRoot,bindings.resolveSibling("native-scene-incident-context.json"));
        encaged=new NativeSceneEncaged(loader,runtimeRoot,bindings.resolveSibling("native-scene-encaged-context.json"));
        Class<?> language=Class.forName("600030218",true,loader),realm=Class.forName("1974661934",true,loader);language.getField("volatile").set(null,realm.getField("RU").get(null));Class.forName("1993742576",true,loader).getMethod("interface").invoke(null);
        for(var value:manifest.getAsJsonArray("bindings")){var row=value.getAsJsonObject();String type=row.get("type").getAsString();if(!manifest.getAsJsonObject("classesSha256").has(type))throw new IOException("scene_default_unpinned_binding");if(row.has("functionClass")){String constructor=row.get("functionClass").getAsString();if(!manifest.getAsJsonObject("classesSha256").has(constructor))throw new IOException("scene_default_unpinned_constructor");Object function=Class.forName(constructor,true,loader).getField(row.get("functionField").getAsString()).get(null);factoryType.getMethod("volatile",String.class,Class.class,java.util.function.Function.class).invoke(null,row.get("behavior").getAsString(),Class.forName(type,false,loader),function);}else factoryType.getMethod("protected",String.class,Class.class).invoke(null,row.get("behavior").getAsString(),type.equals("913250395")?incident.type():Class.forName(type,false,loader));}
        prepareItemNames(bindings.resolveSibling("native-scene-item-names.json"));prepareRepairs(bindings.resolveSibling("native-scene-clock-context.json"));supplyContext=bindings.resolveSibling("native-scene-supply-context.json");defaultsPrepared=true;
    }
    /** Resolve the actual stock stateless repair item, with no side or full mode/item configuration load. */
    private void prepareRepairs(Path path)throws Exception{
        var manifest=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(path)).getAsJsonObject();var item=manifest.getAsJsonObject("repairItemSource");Path source=RuntimeFiles.reference(path,item.get("path").getAsString());
        if(RuntimeFiles.size(source)>4*1024*1024||!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(source))).equals(item.get("sha256").getAsString()))throw new IOException("scene_repair_source_changed");
        int id=item.get("id").getAsInt();String name=item.get("name").getAsString();if(id!=31041||!name.equals("brt.les.mob"))throw new IOException("scene_repair_binding_unreviewed");repairNames=Map.of(name,id);
    }
    /** Immutable reviewed stock name bindings only; no side selection, mode loader, renderer or item grants. */
    private void prepareItemNames(Path path)throws Exception{
        if(RuntimeFiles.size(path)>1024*1024)throw new IOException("scene_item_names_limit");var manifest=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(path)).getAsJsonObject();if(manifest.get("version").getAsInt()!=1)throw new IOException("scene_item_names_version");
        for(var entry:manifest.getAsJsonObject("classesSha256").entrySet()){if(!entry.getKey().matches("[0-9]+"))throw new IOException("scene_item_names_class");String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtimeRoot.resolve("classes").resolve(entry.getKey()+".class"))));if(!actual.equals(entry.getValue().getAsString()))throw new IOException("scene_item_names_release_changed:"+entry.getKey());}
        Map<String,Integer> names=new TreeMap<>();if(manifest.getAsJsonObject("names").size()>65535)throw new IOException("scene_item_names_count");for(var entry:manifest.getAsJsonObject("names").entrySet()){int id=entry.getValue().getAsInt();if(entry.getKey().isBlank()||entry.getKey().length()>256||id<1||id>65535)throw new IOException("scene_item_names_entry");names.put(entry.getKey(),id);}ClientStateAdapter.warmItemNames(loader,names);
    }
    /** Lazy cold-worker dependencies for the selected resource only. Keep native side/mode unset. */
    private void prepareSupply()throws Exception{
        if(supplyPrepared)return;if(RuntimeFiles.size(supplyContext)>1024*1024)throw new IOException("scene_supply_context_limit");var manifest=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(supplyContext)).getAsJsonObject();if(manifest.get("version").getAsInt()!=1)throw new IOException("scene_supply_context_version");
        for(var entry:manifest.getAsJsonObject("runtimeSha256").entrySet()){String name=entry.getKey();if(!name.matches("classes/[0-9]+\\.class|dependencies/kotlin-stdlib-[0-9.]+\\.jar"))throw new IOException("scene_supply_runtime_path");String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtimeRoot.resolve(name))));if(!digest.equals(entry.getValue().getAsString()))throw new IOException("scene_supply_release_changed:"+name);}
        Map<String,Integer> names=new TreeMap<>();for(var entry:manifest.getAsJsonObject("names").entrySet()){int id=entry.getValue().getAsInt();if(id<1||id>65535||entry.getKey().isBlank()||entry.getKey().length()>256)throw new IOException("scene_supply_name");names.put(entry.getKey(),id);}if(names.size()>128)throw new IOException("scene_supply_names_limit");ClientStateAdapter.warmItemNames(loader,names);
        Class.forName("964863275",true,loader);Class.forName("1924679062",true,loader);Class<?> factory=Class.forName("1366800141",true,loader),base=Class.forName("154675812",true,loader);Object[] registry=(Object[])factory.getField("import").get(null);Map<String,Constructor<?>> constructors=new HashMap<>();if(manifest.getAsJsonObject("states").size()>128)throw new IOException("scene_supply_factory_limit");
        for(var entry:manifest.getAsJsonObject("states").entrySet()){int id=Integer.parseInt(entry.getKey());String kind=entry.getValue().getAsString();if(id<1||id>=registry.length||!kind.matches("[0-9]+")||!manifest.getAsJsonObject("runtimeSha256").has("classes/"+kind+".class")||!base.isAssignableFrom(Class.forName(kind,true,loader)))throw new IOException("scene_supply_factory");if(registry[id]!=null){Object state=itemState(registry[id]);if(state==null||!state.getClass().getName().equals(kind))throw new IOException("scene_supply_factory_conflict:"+id);continue;}Constructor<?> constructor=constructors.get(kind);if(constructor==null){String name="szserver.bridge.SceneSupplyFactory_"+kind;constructor=loader.define(name,ClientStateAdapter.factoryBytecode(name.replace('.','/'),kind,true)).getConstructor(int.class);constructors.put(kind,constructor);}constructor.newInstance(id);}
        supplyNames=Map.copyOf(names);supplyPrepared=true;
    }
    private static Object itemState(Object factory)throws Exception{return Arrays.stream(factory.getClass().getMethods()).filter(method->method.getName().equals("default")&&method.getParameterCount()==0&&method.getReturnType().getName().equals("154675812")).findFirst().orElseThrow().invoke(factory);}
    private String properties(SceneRegistry.Definition definition)throws Exception{
        if(definition.behaviour().equals("incident/spider"))incident.prepare();
        if(definition.behaviour().equals("encaged_crate"))encaged.prepare();
        var properties=definition.definition();if(definition.behaviour().equals("radar_spotter")){
            properties=properties.deepCopy();var repair=properties.get("repair_item");if(repair==null||repair.isJsonNull())throw new IOException("scene_repair_item_missing");Integer id=repairNames.get(repair.getAsString());if(id==null)throw new IOException("scene_repair_item_unreviewed:"+repair.getAsString());
            Class<?> factory=Class.forName("1366800141",true,loader);Object[] registered=(Object[])factory.getField("import").get(null);if(registered[id]==null)factory.getConstructor(int.class).newInstance(id);else if(!registered[id].getClass().equals(factory))throw new IOException("scene_repair_factory_conflict");properties.addProperty("repair_item",Integer.toString(id));return properties.toString();
        }
        if(!definition.behaviour().equals("special_weapon_supply"))return properties.toString();prepareSupply();properties=properties.deepCopy();var weapon=properties.getAsJsonObject("givenWeapon");if(weapon==null||!weapon.has("id"))throw new IOException("scene_supply_weapon_missing");var value=weapon.get("id");int id;if(value.getAsJsonPrimitive().isNumber())id=value.getAsInt();else{Integer resolved=supplyNames.get(value.getAsString());if(resolved==null)throw new IOException("scene_supply_weapon_name:"+value.getAsString());id=resolved;}
        Object[] registry=(Object[])Class.forName("1366800141",true,loader).getField("import").get(null);if(id<1||id>=registry.length||registry[id]==null)throw new IOException("scene_supply_weapon_factory:"+id);Object state=itemState(registry[id]);
        if(weapon.has("tag")&&weapon.getAsJsonObject("tag").has("attach")){var attached=weapon.getAsJsonObject("tag").getAsJsonObject("attach").getAsJsonObject("value");Method resolver=Arrays.stream(state.getClass().getMethods()).filter(method->method.getName().equals("catch")&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class})&&method.getReturnType().getName().equals("1173269411")).findFirst().orElseThrow(()->new IOException("scene_supply_slot_resolver:"+state.getClass().getName()));for(String slot:attached.keySet())if(resolver.invoke(state,slot)==null)throw new IOException("scene_supply_unknown_slot:"+slot);}
        weapon.addProperty("id",id);return properties.toString();
    }
    /** Seed generic fields from a validated ordinary envelope; real game factories own behavior defaults. */
    public synchronized SceneObject defaultObject(SceneRegistry.Definition definition,SceneObject envelope)throws Exception{
        if(!defaultsPrepared)throw new IllegalStateException("scene_defaults_not_prepared");clock.enter();try{return defaultObjectNative(definition,envelope);}finally{clock.exit();}
    }
    private SceneObject defaultObjectNative(SceneRegistry.Definition definition,SceneObject envelope)throws Exception{
        if(!defaultsPrepared)throw new IllegalStateException("scene_defaults_not_prepared");byte[] record=envelope.record();SceneObject.Prefix prefix=SceneObject.prefix(record);if(prefix.nativeName().length>2||(prefix.packed()&1)!=0||(prefix.flags()&(0x400|0x100|0x80|3))!=0)throw new IOException("scene_default_envelope_unverified");envelope.configuration();
        Object factory=defaultFactories.get(definition.name());if(factory==null){Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,properties(definition));Object properties=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);Object resource=descriptor.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance(definition.name(),properties,definition.id(),null,null,null);factory=factoryType.getConstructor(String.class,Class.forName("1669874697",true,loader),boolean.class).newInstance(definition.name(),null,false);factoryType.getField("void").set(factory,resource);defaultFactories.put(definition.name(),factory);while(defaultFactories.size()>128)defaultFactories.remove(defaultFactories.keySet().iterator().next());}
        factoryType.getField("volatile").set(factory,"");Object object=worldType.getConstructor(UUID.class,factoryType).newInstance(envelope.identity(),factory);DataInputStream input=new DataInputStream(new ByteArrayInputStream(record,2,record.length-2));read.invoke(object,input);if(input.available()!=0)throw new IOException("scene_default_envelope_trailing");factoryType.getField("volatile").set(factory,definition.behaviour());worldType.getMethod("float",String.class).invoke(object,new Object[]{null});Object behaviour=worldType.getField("byte").get(object);Class<?> compound=Class.forName("98008865",true,loader);behaviour.getClass().getMethod("char",compound).invoke(behaviour,compound.getConstructor().newInstance());
        byte[] body=Wire.bytes(out->write.invoke(object,context,out));if(body.length+2>ScenePayload.MAX_OBJECT)throw new IOException("scene_default_object_limit");return new SceneObject(definition.name(),Wire.concat(java.nio.ByteBuffer.allocate(2).putShort((short)definition.id()).array(),body));
    }
    /** Actual FULL_SAVE in one clock scope; historical milliseconds remain exact across native double conversion. */
    public synchronized byte[] fullSave(SceneRegistry.Definition definition,byte[] record)throws Exception{
        if(!defaultsPrepared)throw new IllegalStateException("scene_defaults_not_prepared");SceneAttributes.validateCurrent(record,definition.behaviour());if((java.nio.ByteBuffer.wrap(record).getShort()&65535)!=definition.id())throw new IOException("scene_full_save_asset_id");
        clock.enter();try{
            Object factory=defaultFactories.get(definition.name());if(factory==null){defaultObjectNative(definition,NativeSceneDefaults.envelope(definition));factory=defaultFactories.get(definition.name());}factoryType.getField("volatile").set(factory,definition.behaviour());
            Object object=worldType.getConstructor(UUID.class,factoryType).newInstance(SceneObject.prefix(record).identity(),factory);DataInputStream input=new DataInputStream(new ByteArrayInputStream(record,2,record.length-2));read.invoke(object,input);if(input.available()!=0)throw new IOException("scene_full_save_trailing");Class<?> sync=Class.forName("1886848284",true,loader),packet=Class.forName("441478008",true,loader);Object saved=packet.getConstructor(worldType,sync).newInstance(object,sync.getField("FULL_SAVE").get(null));return SceneConfigurationPackets.save(Wire.bytes(out->packet.getMethod("switch",DataOutput.class).invoke(saved,out))).payload();
        }finally{clock.exit();}
    }
    public byte[] behaviourNbt(byte[] record)throws Exception{SceneObject.Prefix prefix=SceneObject.prefix(record);java.nio.ByteBuffer checked=java.nio.ByteBuffer.wrap(record);checked.position(prefix.behaviourAt());int end=ScenePayload.namedNbt(checked);DataInputStream input=new DataInputStream(new ByteArrayInputStream(record,prefix.behaviourAt(),end-prefix.behaviourAt()));Class<?> codec=Class.forName("1464336358",true,loader);Object value=codec.getMethod("int",DataInput.class).invoke(null,input);if(input.available()!=0)throw new IOException("scene_behaviour_trailing");return Wire.bytes(out->codec.getMethod("char",Class.forName("98008865",true,loader),DataOutput.class).invoke(null,value,out));}
    /** Worker-only native AOE object: genuine behavior defaults, owner/target/FX serialization.
     * Geometry is supplied by the caller; this codec does not invent radius, membership or expiry. */
    public synchronized SceneObject grenadeAreaObject(SceneRegistry.Definition definition,SceneObject envelope,int owner,int target,String fx)throws Exception{
        if(Set.of("world-tick","native-game-owner").contains(Thread.currentThread().getName()))throw new IllegalStateException("grenade_area_native_on_owner");
        if(!defaultsPrepared||!Set.of("grenade_aoe_object","grenade_shield_object").contains(definition.behaviour())||envelope.networkId()!=definition.id()||owner< -1||target<0||target>3||fx!=null&&fx.length()>1024)throw new IOException("grenade_area_scene_policy");
        clock.enter();try{
            Object factory=defaultFactories.get(definition.name());if(factory==null){defaultObjectNative(definition,NativeSceneDefaults.envelope(definition));factory=defaultFactories.get(definition.name());}
            factoryType.getField("volatile").set(factory,definition.behaviour());Object object=worldType.getConstructor(UUID.class,factoryType).newInstance(envelope.identity(),factory);byte[] record=envelope.record();DataInputStream input=new DataInputStream(new ByteArrayInputStream(record,2,record.length-2));read.invoke(object,input);if(input.available()!=0)throw new IOException("grenade_area_scene_trailing");
            Object behavior=worldType.getField("byte").get(object);Class<?> area=Class.forName("1520486269",true,loader);if(!area.isInstance(behavior))throw new IOException("grenade_area_native_behavior");if(definition.behaviour().equals("grenade_shield_object"))behavior.getClass().getField("public").setFloat(behavior,1);area.getField("transient").setInt(behavior,owner);area.getField("long").setByte(behavior,(byte)target);area.getField("if").set(behavior,fx);
            byte[] body=Wire.bytes(out->write.invoke(object,context,out));SceneObject result=new SceneObject(definition.name(),Wire.concat(java.nio.ByteBuffer.allocate(2).putShort((short)definition.id()).array(),body));SceneAttributes.validateCurrent(result.record(),definition.behaviour());return result;
        }finally{clock.exit();}
    }
    /** Actual packet reader and its retained native object body. The graphical handler is not invoked. */
    public synchronized byte[] grenadeAreaSpawn(SceneRegistry.Definition definition,SceneObject object,int world)throws Exception{
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("grenade_area_native_on_owner");
        if(!defaultsPrepared||!Set.of("grenade_aoe_object","grenade_shield_object").contains(definition.behaviour())||object.networkId()!=definition.id())throw new IOException("grenade_area_spawn_definition");
        SceneAttributes.validateCurrent(object.record(),definition.behaviour());clock.enter();try{
            if(!defaultFactories.containsKey(definition.name()))defaultObjectNative(definition,NativeSceneDefaults.envelope(definition));Object factory=defaultFactories.get(definition.name());
            byte[] body=GrenadeAreaPackets.spawnBody(world,List.of(object));Class<?> packetType=Class.forName(GrenadeAreaPackets.SPAWN,true,loader);Object packet=packetType.getConstructor().newInstance();DataInputStream input=new DataInputStream(new ByteArrayInputStream(body));packetType.getMethod("extends",DataInput.class).invoke(packet,input);if(input.available()!=0)throw new IOException("grenade_area_spawn_trailing");
            Object container=packetType.getField("native").get(packet);List<?> buffers=(List<?>)container.getClass().getField("this").get(container);if(buffers.size()!=1)throw new IOException("grenade_area_spawn_nested_count");Object buffer=buffers.get(0);buffer.getClass().getMethod("char").invoke(buffer);byte[] retained=new byte[object.recordSize()];buffer.getClass().getMethod("readFully",byte[].class).invoke(buffer,(Object)retained);if(!Arrays.equals(retained,object.record())||(int)buffer.getClass().getMethod("goto").invoke(buffer)!=0)throw new IOException("grenade_area_spawn_nested_body");
            // Read the nested AOE through the actual prepared factory and object reader. Full
            // 1886271463.new additionally initializes a model; that is outside this headless codec.
            Object nativeObject=worldType.getConstructor(UUID.class,factoryType).newInstance(object.identity(),factory);DataInputStream nested=new DataInputStream(new ByteArrayInputStream(retained,2,retained.length-2));read.invoke(nativeObject,nested);if(nested.available()!=0||!worldType.getField("byte").get(nativeObject).getClass().getName().equals(definition.behaviour().equals("grenade_shield_object")?"25911724":"1520486269"))throw new IOException("grenade_area_spawn_nested_reader");
            if(definition.behaviour().equals("grenade_shield_object")){float fraction=worldType.getField("byte").get(nativeObject).getClass().getField("public").getFloat(worldType.getField("byte").get(nativeObject));var saved=SceneSettings.compound(retained,szserver.world.SceneObject.prefix(retained).behaviourAt());if(!Float.isFinite(fraction)||fraction<=0||fraction>1||saved==null||fraction!=saved.get("durability").getAsFloat())throw new IOException("grenade_shield_native_fraction");}
            byte[] rewritten=Wire.bytes(out->packetType.getMethod("switch",DataOutput.class).invoke(packet,out));if(!Arrays.equals(body,rewritten))throw new IOException("grenade_area_spawn_native_roundtrip");return body;
        }finally{clock.exit();}
    }
    public ClientSceneCodec(Path runtime)throws Exception{this(runtime,null);}
    /** Optional captured resource root for native default factories; no rendering or launcher initialization. */
    public ClientSceneCodec(Path runtime,Path resources)throws Exception{
        this(runtime,resources,new Path[0]);
    }
    public ClientSceneCodec(Path runtime,Path resources,Path... fallbacks)throws Exception{
        runtimeRoot=runtime.toRealPath();
        List<URL> urls=new ArrayList<>();for(String folder:List.of("classes","bootstrap"))urls.add(RuntimeFiles.location(runtime.resolve(folder)).toUri().toURL());try(var files=Files.list(runtime.resolve("dependencies"))){for(Path p:files.filter(p->p.toString().endsWith(".jar")).sorted().toList())urls.add(p.toUri().toURL());}urls.add(RuntimeFiles.location(runtime.resolve("libs")).toUri().toURL());if(resources!=null)urls.add(szserver.util.GameResources.url(resources));
        for(Path fallback:fallbacks)if(szserver.util.GameResources.exists(fallback))urls.add(szserver.util.GameResources.url(fallback));
        loader=new Loader(urls.toArray(URL[]::new));
        factoryType=Class.forName("1814125595",true,loader);worldType=Class.forName("1886271463",true,loader);json=Class.forName("com.google.gson.JsonObject",true,loader);descriptor=Class.forName("1422486348",true,loader);Class<?> sync=Class.forName("1886848284",true,loader);context=sync.getField("PLAYER_SYNC_PROD").get(null);read=worldType.getMethod("char",DataInput.class);write=worldType.getMethod("try",sync,DataOutput.class);
    }
    public Decoded readDefault(SceneRegistry.Definition definition,byte[] record)throws Exception{
        SceneObject.Prefix prefix=SceneObject.prefix(record);if(!definition.behaviour().isEmpty()||prefix.nativeName().length>2||(prefix.packed()&1)!=0||(prefix.flags()&(0x400|3))!=0)throw new IOException("scene_native_reader_family_unverified");
        // Bound its NBT before entering the native recursive parser. Default records have
        // self-delimiting named compounds; other behaviour families need separate adapters.
        java.nio.ByteBuffer checked=java.nio.ByteBuffer.wrap(record);checked.position(prefix.behaviourAt());ScenePayload.namedNbt(checked);
        Object factory=factories.get(definition.name());if(factory==null){if(factories.size()>=8192)throw new IOException("scene_native_factory_limit");factory=factoryType.getConstructor(String.class,Class.forName("1669874697",true,loader),boolean.class).newInstance(definition.name(),null,false);
            // Native wire reading needs only the resource property descriptor. Geometry/model
            // construction belongs to the graphical client, which keeps its actual resources.
            Object properties=json.getConstructor().newInstance();Object resource=descriptor.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance(definition.name(),properties,definition.id(),null,null,null);factoryType.getField("void").set(factory,resource);factoryType.getField("volatile").set(factory,"");factories.put(definition.name(),factory);
        }
        Object object=worldType.getConstructor(UUID.class,factoryType).newInstance(UUID.randomUUID(),factory);DataInputStream input=new DataInputStream(new ByteArrayInputStream(record,2,record.length-2));read.invoke(object,input);if(input.available()!=0)throw new IOException("scene_native_object_trailing");byte[] body=Wire.bytes(out->write.invoke(object,context,out));return new Decoded((UUID)worldType.getField("n").get(object),body);
    }
    /** Exercise the actual game's FULL_SAVE constructor, including its replicated-attribute suffix. */
    public byte[] saveDefault(SceneRegistry.Definition definition,byte[] record)throws Exception{
        readDefault(definition,record);Object object=worldType.getConstructor(UUID.class,factoryType).newInstance(SceneObject.prefix(record).identity(),factories.get(definition.name()));read.invoke(object,new DataInputStream(new ByteArrayInputStream(record,2,record.length-2)));Class<?> sync=Class.forName("1886848284",true,loader),packet=Class.forName("441478008",true,loader);Object value=packet.getConstructor(worldType,sync).newInstance(object,sync.getField("FULL_SAVE").get(null));return Wire.bytes(out->packet.getMethod("switch",DataOutput.class).invoke(value,out));
    }
    public byte[] configurationPacket(UUID identity,byte[] nbt)throws Exception{
        szserver.client.SceneConfigurationPackets.validate(nbt);Class<?> codec=Class.forName("1464336358",true,loader),compound=Class.forName("98008865",true,loader),packet=Class.forName("1169723444",true,loader);Object value=codec.getMethod("int",DataInput.class).invoke(null,new DataInputStream(new ByteArrayInputStream(nbt)));Object save=packet.getConstructor(UUID.class,compound).newInstance(identity,value);return Wire.bytes(out->packet.getMethod("switch",DataOutput.class).invoke(save,out));
    }
    public byte[] readConfigurationPacket(byte[] body)throws Exception{
        SceneConfigurationPackets.Save checked=SceneConfigurationPackets.settings(body);SceneConfigurationPackets.compressedSaveNbt(checked.payload());Class<?> packet=Class.forName("1169723444",true,loader);Object value=packet.getConstructor().newInstance();DataInputStream input=new DataInputStream(new ByteArrayInputStream(body));packet.getMethod("extends",DataInput.class).invoke(value,input);if(input.available()!=0||!checked.identity().equals(packet.getField("int").get(value)))throw new IOException("native_configuration_packet_mismatch");Object compound=packet.getField("implements").get(value);Class<?> codec=Class.forName("1464336358",true,loader);return Wire.bytes(out->codec.getMethod("char",Class.forName("98008865",true,loader),DataOutput.class).invoke(null,compound,out));
    }
    @Override public void close()throws IOException{loader.close();}
}
