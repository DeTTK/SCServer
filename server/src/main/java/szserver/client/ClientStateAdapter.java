package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Small compatibility boundary around the installed, unmodified client classes.
 * Not a sandbox: unknown client code must be reviewed before adding adapters.
 * Server calls are confined to one command worker; concurrent native use is unverified.
 */
public final class ClientStateAdapter implements AutoCloseable {
    public record DropPolicy(boolean allowed,String pickupOwner){}
    /** Read the native binding rules on the factory worker, without a client-side replacement. */
    public DropPolicy dropPolicy(byte[] raw)throws Exception{
        Class<?> type=Class.forName("211097523",true,loader),bindings=Class.forName("1391289645",true,loader);
        Object stack=type.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));
        String owner=(String)bindings.getMethod("implements",type).invoke(null,stack);
        // The active server implements physical drops. The last native rule is
        // the original realm-wide "drops disabled" switch, not an item's binding.
        // Keep the native per-item binding rules and their priority unchanged.
        Object binding=null;Class<?> rule=Class.forName("298994518",true,loader);
        Method resolve=rule.getMethod("goto",type,Class.forName("200241649",true,loader),Class.forName("949164803",true,loader),boolean.class);
        for(Object candidate:(List<?>)bindings.getField("finally").get(null)){if(candidate.getClass().getName().equals("555438324"))continue;binding=resolve.invoke(candidate,stack,null,null,false);if(binding!=null)break;}
        String kind=binding==null?"NONE":((Enum<?>)binding).name();boolean allowed=owner==null&&!Set.of("NON_DROP","PERSONAL_UNTIL").contains(kind);
        return new DropPolicy(allowed,owner);
    }
    private record Codec(Constructor<?> constructor, Method read, Method write,
                         Method exportNbt, Method importNbt) {}
    private static final class NativeLoader extends URLClassLoader {
        NativeLoader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        // JNI compression belongs to the host: two isolated copies cannot own the same native library.
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(name.startsWith("com.github.luben.zstd."))return Class.forName(name,false,ClientStateAdapter.class.getClassLoader());
            return super.loadClass(name,resolve);
        }
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
        void resources(Path directory)throws IOException{addURL(szserver.util.GameResources.url(directory));}
        @Override public void close()throws IOException {
            // Native logging owns a JVM shutdown hook. Stop its worker while this classpath is open.
            try {
                Class<?> registry=findLoadedClass("org.tinylog.provider.ProviderRegistry");
                if(registry!=null){Object provider=registry.getMethod("getLoggingProvider").invoke(null);Class.forName("org.tinylog.provider.LoggingProvider",false,this).getMethod("shutdown").invoke(provider);}
            }catch(ReflectiveOperationException failure){if(failure.getCause() instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("native_logging_shutdown",failure);}
            finally{super.close();}
        }
    }
    private final NativeLoader loader;
    private final Map<String,Constructor<?>> factories=new HashMap<>();
    private final Class<?> compound;
    private final Map<String, Codec> codecs = new ConcurrentHashMap<>();
    public void resources(Path root)throws IOException{loader.resources(root);}

    public ClientStateAdapter(Path runtime) throws Exception {
        runtime = runtime.toRealPath();NativeItemRulesRelease.verify(runtime);
        if (!Files.exists(RuntimeFiles.location(runtime.resolve("classes"))))
            throw new IOException("Missing canonical runtime classes: " + runtime);
        // Array call-site guards are tied to reviewed bytecode. Refuse a changed
        // release before using offsets; never weaken a guard on unknown classes.
        for(var entry:Map.of("58006653","fd64415a9bbb69216ee7c3619ccf2968807d55f9bdf8f7d50161fca475b3b600","1046176253","7f85ef4c47654d79b7821d428542f3f745fa67884e90e10a580d1a1c3ebe09cb").entrySet()){
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(entry.getKey()+".class"))));
            if(!digest.equals(entry.getValue()))throw new IOException("native_input_guard_release_changed:"+entry.getKey());
        }
        List<URL> urls = new ArrayList<>();
        urls.add(RuntimeFiles.location(runtime.resolve("classes")).toUri().toURL());
        // Promoted native serialization references the installed FPV packet helper.
        // Loading this classpath does not invoke the client launcher.
        urls.add(RuntimeFiles.location(runtime.resolve("bootstrap")).toUri().toURL());
        urls.add(RuntimeFiles.location(runtime.resolve("libs")).toUri().toURL());
        Path dependencies = runtime.resolve("dependencies");
        if (Files.isDirectory(dependencies)) {
            try (var paths = Files.list(dependencies)) {
                for (Path jar : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList())
                    urls.add(jar.toUri().toURL());
            }
        }
        loader = new NativeLoader(urls.toArray(URL[]::new));
        compound = Class.forName("98008865", true, loader);
        // The effect enum constructs descriptors from this class while initializing.
        // Initialize the descriptor owner first, as the native client does; entering
        // through the enum instead leaves its KEYS array null in the cyclic initializer.
        Class.forName("964863275", true, loader);
        Class.forName("1924679062", true, loader);
    }

    private Codec codec(String name) throws Exception {
        Codec cached = codecs.get(name);
        if (cached != null) return cached;
        Class<?> type = Class.forName(name, true, loader);
        Codec created = new Codec(type.getConstructor(),
            type.getMethod("extends", DataInput.class),
            type.getMethod("switch", DataOutput.class),
            type.getMethod("import", compound), type.getMethod("enum", compound));
        Codec existing = codecs.putIfAbsent(name, created);
        return existing == null ? created : existing;
    }

    private Object decode(Codec codec, byte[] input) throws Exception {
        Object state = codec.constructor().newInstance();
        BoundedNativeInput guarded=new BoundedNativeInput(input);
        codec.read().invoke(state,guarded);
        if (guarded.available() != 0) throw new IOException("Unconsumed state bytes: " + guarded.available());
        return state;
    }

    private byte[] encode(Codec codec, Object state) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        codec.write().invoke(state, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    public byte[] roundTrip(String stateClass, byte[] input) throws Exception {
        Codec codec = codec(stateClass);
        return encode(codec, decode(codec, input));
    }

    public record Stack(int id,int count,int metadata,String kind,byte[] state,byte[] properties,int equipmentMask) {}
    private final Map<Integer,Object> artifactFactories=new HashMap<>();
    private final Map<Integer,Object> armorFactories=new HashMap<>();
    /** Names are resolved before any factory construction, as in native resource loading. */
    @SuppressWarnings("unchecked")
    public void warmItemNames(Map<String,Integer> definitions)throws Exception{
        warmItemNames(loader,definitions);
    }
    /** Shared genuine name-manager initialization for independent scene factory loaders. */
    @SuppressWarnings("unchecked")
    static void warmItemNames(ClassLoader loader,Map<String,Integer> definitions)throws Exception{
        Class<?> client=Class.forName("498192453",true,loader),base=Class.forName("985191831",true,loader);
        Object manager=client.getField("class").get(client.getField("long").get(null));
        Map<String,Integer> names=(Map<String,Integer>)base.getField("finally").get(manager);
        Map<Integer,String> ids=(Map<Integer,String>)base.getField("package").get(manager);
        for(var entry:definitions.entrySet()){
            Integer existing=names.putIfAbsent(entry.getKey(),entry.getValue());
            if(existing!=null&&!existing.equals(entry.getValue()))throw new IOException("duplicate_native_item_name:"+entry.getKey());
            ids.put(entry.getValue(),entry.getKey());
        }
    }
    /** Reviewed armor stats use the real factory. Headless descriptors omit only trace_model:
     * that renderer collider starts native async asset loading; gameplay collision is separate. */
    public void warmArmor(Map<Integer,String> definitions)throws Exception{
        Class<?> json=Class.forName("com.google.gson.JsonObject",true,loader),resource=Class.forName("1422486348",true,loader),factory=Class.forName("1598274006",true,loader);
        Object[] registry=(Object[])Class.forName("1366800141",true,loader).getField("import").get(null);
        for(var entry:definitions.entrySet()){
            int id=entry.getKey();Object parsed=nativeJson(entry.getValue());json.getMethod("addProperty",String.class,String.class).invoke(parsed,"trace_model","");
            // Default cosmetic IDs are handled by the independently verified appearance
            // adapter. Some historical catalog entries contain unresolved <default> tokens.
            json.getMethod("remove",String.class).invoke(parsed,"default_visuals_id");
            Object previous=registry[id];registry[id]=null;
            try{
                Object descriptor=resource.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("server_armor_"+id,parsed,id,null,null,null);
                armorFactories.put(id,factory.getConstructor(resource).newInstance(descriptor));registry[id]=previous;
            }catch(Exception | LinkageError error){registry[id]=previous;throw new IOException("native_armor_warm_failed:"+id,error);}
        }
    }
    /** Native upgrade configuration and native bonus objects, prepared offline.
     * The normal client's builder enqueues completion onto its renderer thread;
     * here the command owner publishes the same fully prepared map synchronously. */
    public void warmArmorUpgrades(Path resources,Map<Integer,String> definitions)throws Exception{
        loader.resources(resources);Class<?> settings=Class.forName("1238110879",true,loader);Object global=settings.getField("throw").get(null);settings.getMethod("continue").invoke(global);
        Object[] levels=(Object[])settings.getField("double").get(global);Class<?> bonus=Class.forName("1915920510",true,loader),table=Class.forName("418193668",true,loader),effect=Class.forName("1924679062",true,loader);
        Map<Integer,Object> configured=new HashMap<>();
        for(var entry:armorFactories.entrySet()){
            Object factory=entry.getValue(),json=nativeJson(definitions.get(entry.getKey()));Map<Integer,Object> rows=new HashMap<>();Object[] keys=(Object[])factory.getClass().getMethod("final").invoke(factory);
            for(int level=0;level<levels.length;level++){
                float fraction=levels[level].getClass().getField("break").getFloat(levels[level]);Map<String,Float> values=new LinkedHashMap<>();
                for(Object key:keys){String name=(String)factory.getClass().getMethod("default",Enum.class).invoke(factory,key),property="upgraded_"+name.toLowerCase(Locale.ROOT);
                    Object value=json.getClass().getMethod("get",String.class).invoke(json,property);if(value==null)continue;
                    String target=(String)factory.getClass().getMethod("if",Enum.class).invoke(factory,key);float amount=(Float)value.getClass().getMethod("getAsFloat").invoke(value);values.put(target,amount*fraction/100f);
                }
                rows.put(level,bonus.getConstructor(int.class,Map.class).newInstance(level,values));
            }
            configured.put(entry.getKey(),table.getConstructor(Map.class).newInstance(rows));
        }
        settings.getField("final").set(global,configured);
    }
    public Map<String,Float> armorStats(byte[] raw)throws Exception{
        Class<?> stackType=Class.forName("211097523",true,loader),stats=Class.forName("964863275",true,loader);
        Object stack=stackType.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));
        Method id=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==int.class).findFirst().orElseThrow();
        Object factory=armorFactories.get((Integer)id.invoke(stack));if(factory==null)throw new IOException("armor_factory_not_warmed");
        Method calculation=Arrays.stream(factory.getClass().getMethods()).filter(m->m.getName().equals("for")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{stackType})&&m.getReturnType()==stats).findFirst().orElseThrow();
        Object[] registry=(Object[])Class.forName("1366800141",true,loader).getField("import").get(null);int itemId=(Integer)id.invoke(stack);Object previous=registry[itemId];
        // The command worker is the sole owner. Limit factory substitution to the
        // calculation; the verified state/appearance serializers retain their registry.
        registry[itemId]=factory;try{return bank(calculation.invoke(factory,stack));}finally{registry[itemId]=previous;}
    }
    public String upgradeTag()throws Exception{return (String)Class.forName("959598215",true,loader).getField("do").get(null);}
    public szserver.inventory.ExplosiveProfile explosive(int item,String definition)throws Exception{
        Class<?> type=Class.forName("843651061",true,loader),json=Class.forName("com.google.gson.JsonObject",true,loader);Object properties=type.getMethod("for",json).invoke(null,nativeJson(definition));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();type.getMethod("catch",DataOutput.class).invoke(properties,new DataOutputStream(bytes));
        return new szserver.inventory.ExplosiveProfile(item,((Enum<?>)type.getField("catch").get(properties)).name(),type.getField("float").getFloat(properties),type.getField("interface").getFloat(properties),type.getField("if").getFloat(properties),type.getField("strictfp").getFloat(properties),type.getField("short").getBoolean(properties),type.getField("do").getInt(properties),type.getField("this").getBoolean(properties),bytes.toByteArray(),type.getField("boolean").getBoolean(properties));
    }
    private Object nativeJson(String text)throws Exception{
        Object json=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,text);
        return json.getClass().getMethod("getAsJsonObject").invoke(json);
    }
    /** Only reviewed, headless artifact factories; call before accepting connections. */
    public void warmArtifacts(String settings,Map<Integer,String> definitions,Path resources)throws Exception{
        loader.resources(resources);
        Class<?> json=Class.forName("com.google.gson.JsonObject",true,loader),config=Class.forName("651451239",true,loader);
        Object parsed=nativeJson(settings),global=config.getField("instanceof").get(null);
        // Native initialization's quality/group readers, with the three scalars used
        // by its stat formula. No resource manager, renderer or client singleton.
        for(String method:List.of("long","try","short"))config.getMethod(method,json).invoke(global,parsed);
        String[] names=(String[])Class.forName("1281655698",true,loader).getField("if").get(null);
        for(Object[] field:List.of(new Object[]{"implements",26660},new Object[]{"void",48184})){
            Object value=json.getMethod("get",String.class).invoke(parsed,names[(int)field[1]]);
            config.getField((String)field[0]).setFloat(global,(Float)value.getClass().getMethod("getAsFloat").invoke(value));
        }
        Class<?> resource=Class.forName("1422486348",true,loader),factory=Class.forName("702433834",true,loader);
        Object[] registry=(Object[])Class.forName("1366800141",true,loader).getField("import").get(null);
        for(var entry:definitions.entrySet()){
            int id=entry.getKey();Object previous=registry[id];registry[id]=null;
            try{
                Object descriptor=resource.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("server_artifact_"+id,nativeJson(entry.getValue()),0,null,null,null);
                artifactFactories.put(id,factory.getConstructor(resource).newInstance(descriptor));
            }catch(Exception | LinkageError error){registry[id]=previous;throw error;}
        }
    }
    private Map<String,Float> bank(Object stats)throws Exception{
        Class<?> type=Class.forName("964863275",true,loader),effect=Class.forName("1924679062",true,loader);
        Method export=Arrays.stream(type.getMethods()).filter(m->m.getName().equals("float")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        Map<String,Float> result=new TreeMap<>();for(var entry:((Map<?,?>)export.invoke(stats)).entrySet()){
            String key=(String)effect.getField("for").get(entry.getKey());
            float value=szserver.inventory.StatValues.fromNative(key,((Number)entry.getValue()).floatValue());
            result.put(key,value);
        }return Map.copyOf(result);
    }
    public Map<String,Float> artifactStats(byte[] raw)throws Exception{
        Class<?> stackType=Class.forName("211097523",true,loader);Object stack=stackType.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));
        Method id=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==int.class).findFirst().orElseThrow();
        Object factory=artifactFactories.get((Integer)id.invoke(stack));if(factory==null)throw new IOException("artifact_factory_not_warmed");
        // Server has no current client player; regional eligibility is evaluated
        // separately, so this invokes the native context-free calculation.
        return bank(factory.getClass().getMethod("strictfp",stackType,boolean.class,boolean.class).invoke(factory,stack,false,false));
    }
    public Map<String,Float> numericStats(String definition)throws Exception{
        Class<?> stats=Class.forName("964863275",true,loader),json=Class.forName("com.google.gson.JsonObject",true,loader);
        return bank(stats.getMethod("long",json).invoke(null,nativeJson(definition)));
    }
    public Set<String> containerExemptStats()throws Exception{
        Class<?> type=Class.forName("1049584909",true,loader),effect=Class.forName("1924679062",true,loader);
        Object configuration=type.getField("class").get(null);Set<String> keys=new HashSet<>();
        for(Object value:(Set<?>)type.getMethod("continue").invoke(configuration))keys.add((String)effect.getField("for").get(value));return Set.copyOf(keys);
    }
    public Map<String,Integer> statOrdinals()throws Exception{
        Class<?> type=Class.forName("1924679062",true,loader);Map<String,Integer> result=new TreeMap<>();
        for(Object value:type.getEnumConstants())result.put((String)type.getField("for").get(value),((Enum<?>)value).ordinal());return Map.copyOf(result);
    }
    public List<byte[]> artifacts(String kind,byte[] state,int slots)throws Exception{
        if(!Set.of("1176431411","1782792945").contains(kind))return List.of();
        List<byte[]> result=new ArrayList<>();Object decoded=decode(codec(kind),state);Map<?,?> map=(Map<?,?>)decoded.getClass().getField("instanceof").get(decoded);
        Class<?> keyType=Class.forName("830745898",true,loader);Method key=keyType.getMethod("private",int.class);
        for(int index=0;index<slots;index++){Object item=map.get(key.invoke(null,index));if(item==null)continue;ByteArrayOutputStream bytes=new ByteArrayOutputStream();item.getClass().getMethod("switch",DataOutput.class).invoke(item,new DataOutputStream(bytes));result.add(bytes.toByteArray());}return List.copyOf(result);
    }
    public boolean hasAppearanceCodec(String factory)throws Exception {
        return Class.forName("989274007",false,loader).isAssignableFrom(Class.forName(factory,false,loader));
    }
    /** Headless boundary for 1745367406.package: native state, slot and property codecs. */
    public byte[] firearmAppearance(byte[] raw)throws Exception {
        Class<?> stackType=Class.forName("211097523",true,loader);
        Object stack=stackType.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));
        Object state=null;Class<?> base=Class.forName("154675812",true,loader);
        for(Method m:stackType.getMethods())if(m.getName().equals("finally")&&m.getParameterCount()==0&&m.getReturnType()==base){state=m.invoke(stack);break;}
        if(state==null||!Class.forName("1535556086",true,loader).isInstance(state))throw new IOException("appearance_not_firearm");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        Class.forName("1160390453",true,loader).getMethod("long",stackType,DataOutput.class).invoke(null,stack,out);
        String name=(String)state.getClass().getMethod("transient").invoke(state);
        if(name==null)out.writeByte(-1);else{byte[] encoded=name.getBytes(java.nio.charset.Charset.defaultCharset());if(name.length()>127||encoded.length!=name.length())throw new IOException("appearance_name_encoding");out.writeByte(encoded.length);out.write(encoded);}
        Map<?,?> modules=(Map<?,?>)state.getClass().getMethod("for").invoke(state);if(modules.size()>127)throw new IOException("appearance_module_count");out.writeByte(modules.size());
        Class<?> slotType=Class.forName("1173269411",true,loader);Method slotWriter=Class.forName("1046176253",true,loader).getMethod("while",slotType,DataOutput.class);
        Method itemId=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==int.class).findFirst().orElseThrow();
        for(var entry:modules.entrySet()){slotWriter.invoke(null,entry.getKey(),out);out.writeShort((Integer)itemId.invoke(entry.getValue()));}
        Class<?> properties=Class.forName("241793746",true,loader),key=Class.forName("520353426",true,loader);
        Method get=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("do")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{key})&&m.getReturnType()==int.class).findFirst().orElseThrow();out.writeShort(((Number)get.invoke(stack,properties.getField("enum").get(null))).intValue());out.writeShort(((Number)get.invoke(stack,properties.getField("strictfp").get(null))).intValue());
        return bytes.toByteArray();
    }
    /** Native armor/backpack/melee appearance contracts; artifacts stay private. */
    public byte[] cosmeticAppearance(String kind,byte[] raw)throws Exception{
        String reader=switch(kind){case "1865555906"->"double";case "1176431411","1782792945","810393443"->"strictfp";case "1490492938"->"throw";default->throw new IOException("cosmetic_appearance_state");};
        Class<?> stackType=Class.forName("211097523",true,loader),base=Class.forName("154675812",true,loader);Object stack=stackType.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));
        Method stateGetter=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("finally")&&m.getParameterCount()==0&&m.getReturnType()==base).findFirst().orElseThrow();Object state=stateGetter.invoke(stack);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);Class.forName("1160390453",true,loader).getMethod("long",stackType,DataOutput.class).invoke(null,stack,out);
        Class<?> visual=Class.forName("865153858",true,loader);for(String getter:List.of("transient","static")){String name=(String)visual.getMethod(getter).invoke(state);if(name==null)out.writeByte(-1);else{byte[] encoded=name.getBytes(java.nio.charset.Charset.defaultCharset());if(name.length()>127||encoded.length!=name.length())throw new IOException("appearance_name_encoding");out.writeByte(encoded.length);out.write(encoded);}}
        if(kind.equals("1865555906")){Class<?> key=Class.forName("520353426",true,loader);Method get=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("do")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{key})&&m.getReturnType()==int.class).findFirst().orElseThrow();out.writeShort((Integer)get.invoke(stack,Class.forName("241793746",true,loader).getField("enum").get(null)));}
        Map<?,?> attachments=(Map<?,?>)state.getClass().getMethod("for").invoke(state);Method id=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==int.class).findFirst().orElseThrow();
        for(int slot=0;slot<2;slot++){Object attached=attachments.get(cosmeticKey(kind,slot));out.writeShort(attached==null?0:(Integer)id.invoke(attached));}
        if(kind.equals("1865555906"))out.writeByte(state.getClass().getField("final").getByte(state));return bytes.toByteArray();
    }
    private NativeItemNbt itemNbt;
    private NativeItemNbt itemNbt()throws Exception{if(itemNbt==null)itemNbt=new NativeItemNbt(loader,cosmetics);return itemNbt;}
    public Stack applyTags(byte[] raw,byte[] tags)throws Exception{return readStack(itemNbt().apply(raw,tags));}
    public byte[] stackConfig(byte[] raw)throws Exception{return itemNbt().config(raw);}
    public Stack readConfig(byte[] raw)throws Exception{return readStack(itemNbt().configImport(raw));}
    /** Editor streams may have a different attachment order; semantic stability is mandatory. */
    public Stack readEditedStack(byte[] raw)throws Exception{return readStack(itemNbt().stream(raw));}
    /** Decode a complete native stack on the native worker, preserving its property bank. */
    public int stackPrefixLength(byte[] raw)throws Exception{
        if(raw.length<10||raw.length>8192)throw new IOException("native_stack_size");BoundedNativeInput input=new BoundedNativeInput(raw);Object stack=Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(input);int length=raw.length-input.available();byte[] canonical=szserver.net.Wire.bytes(out->stack.getClass().getMethod("switch",DataOutput.class).invoke(stack,out));if(!Arrays.equals(canonical,Arrays.copyOf(raw,length)))throw new IOException("native_stack_roundtrip");return length;
    }
    public Stack readStack(byte[] raw)throws Exception {
        if(raw.length<10||raw.length>8192)throw new IOException("native_stack_size");
        BoundedNativeInput input=new BoundedNativeInput(raw);
        Object stack=Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(input);
        if(input.available()!=0)throw new IOException("native_stack_trailing");
        ByteArrayOutputStream output=new ByteArrayOutputStream();stack.getClass().getMethod("switch",DataOutput.class).invoke(stack,new DataOutputStream(output));byte[] canonical=output.toByteArray();
        if(!Arrays.equals(raw,canonical))throw new IOException("native_stack_roundtrip");
        Object state=null;Class<?> base=Class.forName("154675812",true,loader);
        for(Method method:stack.getClass().getMethods())if(method.getName().equals("finally")&&method.getParameterCount()==0&&method.getReturnType()==base){state=method.invoke(stack);break;}
        String kind=state==null?null:state.getClass().getName();byte[] encoded=state==null?new byte[0]:encode(codec(kind),state);
        DataInputStream header=new DataInputStream(new ByteArrayInputStream(canonical));int id=header.readUnsignedShort(),count=header.readInt(),metadata=header.readUnsignedShort();boolean present=header.readBoolean();
        if(present!=(state!=null)||9+encoded.length>=canonical.length||!Arrays.equals(encoded,Arrays.copyOfRange(canonical,9,9+encoded.length)))throw new IOException("native_stack_state_layout");
        Object root=compound.getConstructor().newInstance();stack.getClass().getMethod("char",compound).invoke(stack,root);Object tag=((Map<?,?>)compound.getField("transient").get(root)).get("tag");int equipmentMask=0;
        if(tag!=null){Map<?,?> fields=(Map<?,?>)compound.getField("transient").get(tag);String[] names={"held_in_special_slot","held_in_special_primary_slot","held_in_override_slot"};int[] slots={8,9,11};for(int n=0;n<names.length;n++){Object flag=fields.get(names[n]);if(flag!=null){ByteArrayOutputStream value=new ByteArrayOutputStream();flag.getClass().getMethod("this",DataOutput.class).invoke(flag,new DataOutputStream(value));byte[] bytes=value.toByteArray();if(bytes.length==1&&bytes[0]!=0)equipmentMask|=1<<slots[n];}}}
        return new Stack(id,count,metadata,kind,encoded,Arrays.copyOfRange(canonical,9+encoded.length,canonical.length),equipmentMask);
    }

    /** Offline legacy import only. The native reader can normalize historical
     * attachment maps and flags; accept its output only after a stable second
     * read with identical complete native NBT (including UUIDs and properties).
     * Network-facing readStack remains byte-strict. Original SQL bodies are archived. */
    public Stack migrateLegacyStack(byte[] raw)throws Exception{
        if(raw.length<10||raw.length>8192)throw new IOException("native_stack_size");
        Class<?> type=Class.forName("211097523",true,loader);
        BoundedNativeInput input=new BoundedNativeInput(raw);Object first=type.getConstructor(DataInput.class).newInstance(input);
        if(input.available()!=0)throw new IOException("native_stack_trailing");
        Object before=compound.getConstructor().newInstance();type.getMethod("char",compound).invoke(first,before);
        byte[] canonical=szserver.net.Wire.bytes(out->type.getMethod("switch",DataOutput.class).invoke(first,out));
        if(!Arrays.equals(Arrays.copyOf(raw,8),Arrays.copyOf(canonical,8)))throw new IOException("legacy_stack_header_changed");
        Stack checked=readStack(canonical);
        Object second=type.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(canonical));
        Object after=compound.getConstructor().newInstance();type.getMethod("char",compound).invoke(second,after);
        if(!before.equals(after))throw new IOException("legacy_stack_semantics_changed");
        return checked;
    }

    /** QA for server-produced snapshots, in the same loader as the real item factories.
     * This is not an exposed decoder for arbitrary incoming compressed inventory. */
    public void verifyInventoryPoint(byte[] body)throws Exception {
        if(body.length<9||body.length>16384)throw new IOException("native_inventory_point_bounds");
        Class<?> type=Class.forName("1090368269",true,loader);Object packet=type.getConstructor().newInstance();
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(body));type.getMethod("extends",DataInput.class).invoke(packet,input);
        if(input.available()!=0)throw new IOException("native_inventory_point_trailing");
    }
    public void verifyInventorySnapshot(byte[] body)throws Exception {
        if(body.length<12||body.length>65536)throw new IOException("native_inventory_fixture_bounds");
        int compressed=java.nio.ByteBuffer.wrap(body).getInt(4);
        if(compressed!=body.length-8)throw new IOException("native_inventory_fixture_size");
        byte[] payload=Arrays.copyOfRange(body,8,body.length);long size=com.github.luben.zstd.Zstd.decompressedSize(payload);
        if(size<3||size>2*1024*1024)throw new IOException("native_inventory_fixture_expansion");
        Class<?> type=Class.forName("998343761",true,loader);Object packet=type.getConstructor().newInstance();
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(body));type.getMethod("extends",DataInput.class).invoke(packet,input);
        if(input.available()!=0)throw new IOException("native_inventory_fixture_trailing");
    }

    public record CorpseView(int source,boolean ragdoll,double[] position,float yaw,float scale,double[] velocity,double[] impulse,double[] wholeImpulse,double[] impact,byte[] armor){}
    private static double[] corpseVector(Object corpse,String field)throws Exception{Object value=corpse.getClass().getField(field).get(corpse);return new double[]{value.getClass().getField("case").getDouble(value),value.getClass().getField("void").getDouble(value),value.getClass().getField("import").getDouble(value)};}
    /** QA decodes native corpse payload fields. No world constructor, renderer, or ragdoll step runs. */
    /** QA uses the same warmed native item registry for hand/equipment replication. */
    public void verifyPlayerFrame(String kind,byte[] body)throws Exception{
        if(body.length<1||body.length>131072)throw new IOException("native_player_frame_bounds");
        String name=switch(kind){case "spawn"->"348227069";case "delta"->"456314467";default->throw new IllegalArgumentException("native_player_frame_kind");};
        Class<?> type=Class.forName(name,true,loader);Object packet=type.getConstructor().newInstance();BoundedNativeInput input=new BoundedNativeInput(body);
        type.getMethod("extends",DataInput.class).invoke(packet,input);if(input.available()!=0)throw new IOException("native_player_frame_trailing");
    }
    public CorpseView verifyCorpseSnapshot(byte[] body)throws Exception{
        if(body.length<22||body.length>9000||body[0]!=30)throw new IOException("native_corpse_fixture_bounds");Class<?> packetType=Class.forName("1510484702",true,loader);Object packet=packetType.getConstructor().newInstance();BoundedNativeInput envelope=new BoundedNativeInput(body);packetType.getMethod("extends",DataInput.class).invoke(packet,envelope);if(envelope.available()!=0)throw new IOException("native_corpse_fixture_trailing");ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        // Native read keeps encoded coordinates; write encodes logical coordinates again.
        // It is intentionally not a byte-preserving read/write codec.
        Class<?> base=packetType.getSuperclass();java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(body);header.position(1);if(base.getField("continue").getByte(packet)!=header.get()||base.getField("final").getInt(packet)!=header.getInt()||base.getField("double").getInt(packet)!=header.getInt()||base.getField("case").getInt(packet)!=header.getInt()||base.getField("do").getInt(packet)!=header.getInt()||base.getField("void").getByte(packet)!=header.get())throw new IOException("native_corpse_header_changed");
        java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(body);input.position(19);int size=szserver.net.Wire.varint(input);if(size<1||size!=input.remaining()-2)throw new IOException("native_corpse_payload_size");byte[] payload=new byte[size];input.get(payload);if(!Arrays.equals(payload,(byte[])base.getField("break").get(packet)))throw new IOException("native_corpse_payload_changed");if(input.get()!=0||input.get()!=0)throw new IOException("native_corpse_auxiliary");
        // Allocate only the receiver for a reviewed, pure DataInput method. Constructors
        // install the physics world; QA deliberately does not create such a world.
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);Class<?> corpseType=Class.forName("904579751",true,loader),vector=Class.forName("153304200",true,loader);Object corpse=unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),corpseType);
        for(Field field:corpseType.getFields())if(field.getType()==vector&&!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(corpse,vector.getConstructor().newInstance());}
        BoundedNativeInput nativeInput=new BoundedNativeInput(payload);corpseType.getMethod("const",DataInput.class).invoke(corpse,nativeInput);if(nativeInput.available()!=0)throw new IOException("native_corpse_payload_trailing");Object armor=corpseType.getField("goto").get(corpse);byte[] armorWire=null;if(armor!=null){encoded.reset();encoded.write(1);armor.getClass().getMethod("switch",DataOutput.class).invoke(armor,new DataOutputStream(encoded));armorWire=encoded.toByteArray();readStack(Arrays.copyOfRange(armorWire,1,armorWire.length));}
        return new CorpseView(corpseType.getField("case").getInt(corpse),Class.forName("544082630",true,loader).getField("transient").getBoolean(corpse),corpseVector(corpse,"implements"),corpseType.getField("continue").getFloat(corpse),corpseType.getField("volatile").getFloat(corpse),corpseVector(corpse,"while"),corpseVector(corpse,"synchronized"),corpseVector(corpse,"else"),corpseVector(corpse,"void"),armorWire);
    }

    public byte[] magazine(byte[] input,int ammo,int rounds)throws Exception{
        return magazine("1535556086",input,ammo,rounds);
    }
    public byte[] magazine(String kind,byte[] input,int ammo,int rounds)throws Exception{
        if(!Set.of("1535556086","259689215").contains(kind))throw new IOException("magazine_state_kind");
        Codec c=codec(kind);Object state=decode(c,input);
        Object stack=null;
        if(rounds>0){
            // Stateless ammunition needs a native factory entry even in headless mode.
            Class<?> factory=Class.forName("1366800141",true,loader);
            Object[] registry=(Object[])factory.getField("import").get(null);
            if(ammo<0||ammo>=registry.length)throw new IllegalArgumentException("ammo_factory_bounds");
            if(registry[ammo]==null)factory.getConstructor(int.class).newInstance(ammo);
            Class<?> type=Class.forName("211097523",true,loader);stack=type.getConstructor(int.class,int.class,int.class).newInstance(ammo,rounds,0);
        }
        state.getClass().getField("void").set(state,stack);
        return encode(c,state);
    }

    public record Magazine(UUID identity,int ammo,int rounds,int magazineFactory) {}
    public boolean jammed(String kind,byte[] raw)throws Exception{
        if(!Set.of("1535556086","259689215").contains(kind))throw new IOException("jamming_state_kind");
        return Class.forName("1535556086",true,loader).getField("instanceof").getBoolean(decode(codec(kind),raw));
    }
    /** Pure installed state field; preserve every other byte and all nested paid stacks. */
    public byte[] jamming(String kind,byte[] raw,boolean value)throws Exception{
        if(!Set.of("1535556086","259689215").contains(kind))throw new IOException("jamming_state_kind");
        Object state=decode(codec(kind),raw);Class.forName("1535556086",true,loader).getField("instanceof").setBoolean(state,value);return encode(codec(kind),state);
    }
    public double unjammingDuration(int factory)throws Exception{return Class.forName("1745367406",true,loader).getField("dc").getFloat(moduleFactory(factory));}
    public record Launcher(int factory,UUID identity,int ammo,int rounds,int remembered) {}
    private Object launcher(String kind,byte[] raw)throws Exception{
        if(!Set.of("1535556086","259689215").contains(kind))throw new IOException("launcher_state_kind");
        Object gun=decode(codec(kind),raw);Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);
        Object attached=((Map<?,?>)gun.getClass().getField("abstract").get(gun)).get(slots[1]);if(attached==null)return null;
        Object state=attached.getClass().getMethod("for",Class.class).invoke(attached,Class.forName("342965638",true,loader));return state==null?null:attached;
    }
    public Launcher inspectLauncher(String kind,byte[] raw)throws Exception{
        Object attached=launcher(kind,raw);if(attached==null)return null;
        Object state=attached.getClass().getMethod("for",Class.class).invoke(attached,Class.forName("342965638",true,loader));
        byte[] header=szserver.net.Wire.bytes(out->attached.getClass().getMethod("switch",DataOutput.class).invoke(attached,out));
        UUID identity=(UUID)Class.forName("154675812",true,loader).getDeclaredField("package").get(state);
        Object charge=state.getClass().getField("finally").get(state);int ammo=0,rounds=0;
        if(charge!=null){byte[] bytes=szserver.net.Wire.bytes(out->charge.getClass().getMethod("switch",DataOutput.class).invoke(charge,out));var in=new DataInputStream(new ByteArrayInputStream(bytes));ammo=in.readUnsignedShort();rounds=in.readInt();}
        return new Launcher(new DataInputStream(new ByteArrayInputStream(header)).readUnsignedShort(),identity,ammo,rounds,state.getClass().getField("for").getInt(state));
    }
    public byte[] launcherCharge(String kind,byte[] raw)throws Exception{
        Object attached=launcher(kind,raw);if(attached==null)throw new IOException("launcher_missing");
        Object state=attached.getClass().getMethod("for",Class.class).invoke(attached,Class.forName("342965638",true,loader));
        Object charge=state.getClass().getField("finally").get(state);if(charge==null)throw new IOException("launcher_charge_empty");
        return szserver.net.Wire.bytes(out->charge.getClass().getMethod("switch",DataOutput.class).invoke(charge,out));
    }
    public byte[] launcherMagazine(String kind,byte[] raw,int ammunition,boolean loaded)throws Exception{
        byte[] charge=loaded?szserver.net.Wire.bytes(out->Class.forName("211097523",true,loader).getMethod("switch",DataOutput.class).invoke(Class.forName("211097523",true,loader).getConstructor(int.class,int.class,int.class).newInstance(ammunition,1,0),out)):null;
        return launcherMagazine(kind,raw,charge);
    }
    public byte[] launcherMagazine(String kind,byte[] raw,byte[] chargeBytes)throws Exception{
        Object gun=decode(codec(kind),raw);Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);
        Object attached=((Map<?,?>)gun.getClass().getField("abstract").get(gun)).get(slots[1]);if(attached==null)throw new IOException("launcher_missing");
        Object state=attached.getClass().getMethod("for",Class.class).invoke(attached,Class.forName("342965638",true,loader));if(state==null)throw new IOException("launcher_missing");
        Object charge=chargeBytes==null?null:Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(new BoundedNativeInput(chargeBytes));
        state.getClass().getField("finally").set(state,charge);if(charge!=null)state.getClass().getField("for").setInt(state,new DataInputStream(new ByteArrayInputStream(chargeBytes)).readUnsignedShort());
        return encode(codec(kind),gun);
    }
    public record Attachment(byte[] state,byte[] previous,boolean builtin) {}
    public String artifactResearchTag()throws Exception{return (String)Class.forName("1609149377",true,loader).getField("synchronized").get(null);}
    private NativeBoltFactory bolts;
    public Map<Integer,szserver.net.GrenadeUse.Rules> warmBolts()throws Exception{
        bolts=new NativeBoltFactory(cosmetics,loader::define);
        return bolts.rules();
    }
    public BoltProjectile boltProjectile(byte[] raw,double vx,double vz,float yaw,float pitch)throws Exception{
        return bolts.launch(raw,vx,vz,yaw,pitch);
    }
    private NativeQuickSlots quickSlots;private NativeCosmetics cosmetics;private NativeItemSlots itemSlots;private NativeItemSlotPackets itemSlotPackets;
    private NativeMedicines nativeMedicines;private NativeMedicineEffects medicineEffects;private NativeMedicinePackets medicinePackets;
    public Map<Integer,GameItems.Medicine> warmMedicines()throws Exception{nativeMedicines=new NativeMedicines(cosmetics,this::bank);medicineEffects=new NativeMedicineEffects(cosmetics,this::bank);medicinePackets=new NativeMedicinePackets(cosmetics);return nativeMedicines.profiles();}
    public boolean medicineAllowed(int id,String mode,String location)throws Exception{return nativeMedicines.allowed(id,mode,location);}
    public record MedicineRequest(int owner,int sequence,szserver.inventory.Inventory.Address address){}
    public MedicineRequest medicineRequest(byte[] raw)throws Exception{return medicinePackets.read(raw);}
    public Map<String,Float> medicineStats(Collection<Map<String,Float>> banks)throws Exception{return medicineEffects.aggregate(banks);}
    public byte[] medicineEffects(List<Integer> removed,Collection<GameItems.MedicineEffect> added,Set<Integer> suppressed,long now)throws Exception{return medicineEffects.delta(removed,added,suppressed,now);}
    public record FactoryMetadataExport(Map<Integer,Map<String,Integer>> rows,Map<Integer,String> errors){}
    public FactoryMetadataExport exportFactoryMetadata()throws Exception{
        Map<Integer,Map<String,Integer>> rows=new TreeMap<>();Map<Integer,String> errors=new TreeMap<>();Class<?> shape=Class.forName("61438557",true,loader);Method geometry=NativeMethods.staticMethod(Class.forName("1824362651",true,loader),"while",shape,cosmetics.stack),limit=NativeMethods.instance(cosmetics.stack,"finally",int.class);
        for(int id:new TreeSet<>(cosmetics.classes.keySet()))try{cosmetics.factory(id);rows.put(id,cosmetics.bound(()->{Object item=cosmetics.stack.getConstructor(int.class,int.class,int.class).newInstance(id,1,0),size=geometry.invoke(null,item);return Map.of("limit",(Integer)limit.invoke(item),"width",shape.getField("try").getInt(size),"height",shape.getField("enum").getInt(size));}));}catch(Exception|LinkageError e){Throwable cause=e;while(cause instanceof InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();errors.put(id,cause.getClass().getSimpleName());}
        return new FactoryMetadataExport(Map.copyOf(rows),Map.copyOf(errors));
    }
    public Set<Integer> firearmFactories()throws Exception{Set<Integer> ids=new HashSet<>();Class<?> gun=Class.forName("1745367406",true,loader);for(var entry:moduleFactoryClasses.entrySet())if(gun.isAssignableFrom(Class.forName(entry.getValue(),true,loader)))ids.add(entry.getKey());return Set.copyOf(ids);}
    public void warmCosmetics(Map<Integer,String> classes,Map<Integer,String> definitions,
                              Map<Integer,String> resourceDeclarations)throws Exception{
        cosmetics=new NativeCosmetics(loader,definitions,classes,resourceDeclarations);itemNbt=null;itemSlots=new NativeItemSlots(cosmetics,this::bank);itemSlotPackets=new NativeItemSlotPackets(cosmetics);quickSlots=new NativeQuickSlots(cosmetics);itemNbt();}
    public record QuickBindRequest(int owner,int index,szserver.inventory.Inventory.Address source,String label){}
    public QuickBindRequest quickBindRequest(byte[] raw)throws Exception{return quickSlots.bindRequest(raw);}
    public int quickUseRequest(byte[] raw)throws Exception{return quickSlots.useRequest(raw);}
    public GameItems.QuickSlots quickSlots(byte[] raw)throws Exception{return quickSlots.read(raw);}
    public GameItems.QuickSlots quickBind(byte[] raw,int slot,byte[] source,String label)throws Exception{return quickSlots.bind(raw,slot,source,label);}
    public szserver.inventory.Inventory.Address quickSelect(byte[] raw,int slot,Map<szserver.inventory.Inventory.Address,szserver.inventory.ItemStack> candidates)throws Exception{return quickSlots.select(raw,slot,candidates);}
    public record ItemSlotRequest(int owner,int sequence,szserver.inventory.Inventory.Address target,szserver.inventory.Inventory.Address source,int slot){}
    public ItemSlotRequest itemSlotRequest(boolean bolt,byte[] raw)throws Exception{return itemSlotPackets.read(bolt,raw);}
    public record CosmeticSkin(byte[] state,int previous,boolean unchanged){}
    public CosmeticSkin nativeSkin(String kind,byte[] raw,int item,int selected)throws Exception{var change=cosmetics.skin(item,decode(codec(kind),raw),selected);return new CosmeticSkin(encode(codec(kind),change.state()),change.previous(),change.unchanged());}
    public Attachment nativePaint(String kind,byte[] raw,int slot,byte[] attached,boolean virtual)throws Exception{Stack original=readStack(raw);var change=cosmetics.paint(original.id(),decode(codec(kind),original.state()),kind,slot,raw,attached,virtual);return new Attachment(encode(codec(kind),change.state()),change.previous(),change.builtin());}
    public record Visual(byte[] state,String previous) {}
    public Visual skin(String kind,byte[] raw,String name)throws Exception{
        Object state=decode(codec(kind),raw);Class<?> visual=Class.forName("865153858",true,loader);
        if(!visual.isInstance(state))throw new IOException("item_has_no_visual_name");
        if((Boolean)visual.getMethod("abstract").invoke(state))throw new IOException("visual_name_locked");
        String previous=(String)visual.getMethod("transient").invoke(state);visual.getMethod("double",String.class).invoke(state,name);
        return new Visual(encode(codec(kind),state),previous);
    }
    private Object cosmeticKey(String kind,int slot)throws Exception{
        String[] descriptor=switch(kind){case "1535556086","259689215"->new String[]{"catch","1173269411"};case "1865555906"->new String[]{"double","1082342295"};case "1176431411","1782792945","810393443"->new String[]{"strictfp","1143465724"};case "1490492938"->new String[]{"throw","531128727"};default->throw new IOException("paint_target_state");};
        Class<?> result=Class.forName(descriptor[1],true,loader);Method read=Arrays.stream(Class.forName("1046176253",true,loader).getMethods()).filter(m->m.getName().equals(descriptor[0])&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{DataInput.class})&&m.getReturnType()==result).findFirst().orElseThrow();int code=kind.equals("1490492938")?slot:-slot-1;
        return read.invoke(null,new DataInputStream(new ByteArrayInputStream(new byte[]{(byte)code})));
    }
    public Attachment nativeSlot(String kind,byte[] parent,byte[] attached,int slot,boolean bolt)throws Exception{var edit=itemSlots.edit(parent,attached,slot,bolt);Stack changed=readStack(edit.stack());return new Attachment(changed.state(),edit.previous(),false);}
    public GameItems.EquipmentStats containerStats(byte[] raw)throws Exception{return itemSlots.container(raw);}
    public byte[] builtinModule(String kind,byte[] raw)throws Exception{Object state=decode(codec(kind),raw);state.getClass().getField("instanceof").setBoolean(state,true);return encode(codec(kind),state);}
    public Attachment attachment(String kind,byte[] raw,int slot,byte[] attached)throws Exception{
        if(slot<0||slot>=18)throw new IOException("module_slot");Object state=decode(codec(kind),raw);Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);Map<Object,Object> modules=(Map<Object,Object>)state.getClass().getField("abstract").get(state);Object key=slots[slot],old=modules.get(key);boolean builtin=false;
        if(old!=null){Class<?> base=Class.forName("154675812",true,loader);Object module=null;for(Method method:old.getClass().getMethods())if(method.getName().equals("finally")&&method.getParameterCount()==0&&method.getReturnType()==base){module=method.invoke(old);break;}if(module!=null){try{if(module.getClass().getField("throw").getBoolean(module))throw new IOException("module_locked");builtin=module.getClass().getField("instanceof").getBoolean(module);}catch(NoSuchFieldException ignored){}}}
        Object replacement=attached==null?null:Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(new BoundedNativeInput(attached));if(replacement==null)modules.remove(key);else modules.put(key,replacement);
        byte[] previous=null;if(old!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();old.getClass().getMethod("switch",DataOutput.class).invoke(old,new DataOutputStream(bytes));previous=bytes.toByteArray();}
        return new Attachment(encode(codec(kind),state),previous,builtin);
    }
    private Map<Integer,String> moduleFactoryClasses=Map.of(),moduleDefinitions=Map.of();
    private final Map<Integer,Object> nativeModuleFactories=new HashMap<>();
    public void moduleDefinitions(Map<Integer,String> classes,Map<Integer,String> definitions){moduleFactoryClasses=Map.copyOf(classes);moduleDefinitions=Map.copyOf(definitions);}
    /** Genuine factories are loaded lazily only on the inventory worker, never a network/world owner. */
    private Object moduleFactory(int id)throws Exception{
        Object cached=nativeModuleFactories.get(id);if(cached!=null)return cached;
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IOException("native_module_factory_on_owner");
        String name=moduleFactoryClasses.get(id),definition=moduleDefinitions.get(id);if(name==null||definition==null)throw new IOException("module_factory_definition_missing:"+id);
        Class<?> type=Class.forName(name,true,loader),gun=Class.forName("1745367406",true,loader),module=Class.forName("62393481",true,loader);
        if(!gun.isAssignableFrom(type)&&!module.isAssignableFrom(type))throw new IOException("module_factory_kind:"+id);
        Object value=cosmetics.factory(id);nativeModuleFactories.put(id,value);return value;
    }
    Object childFactory(int id)throws Exception{String name=moduleFactoryClasses.get(id);if(name==null)throw new IOException("module_factory_definition_missing:"+id);Class<?> type=Class.forName(name,true,loader);if(Class.forName("871813794",true,loader).isAssignableFrom(type)){Object value=cosmetics.factory(id);nativeModuleFactories.put(id,value);return value;}return moduleFactory(id);}
    /** Original global item registry is always restored, including failed native predicates. */
    private <T> T withModuleFactories(java.util.concurrent.Callable<T> operation)throws Exception{return cosmetics.bound(nativeModuleFactories.keySet(),operation);}
    private Object moduleStack(byte[] raw)throws Exception{return Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));}
    private int moduleId(Object stack)throws Exception{return (Integer)Arrays.stream(stack.getClass().getMethods()).filter(method->method.getName().equals("continue")&&method.getParameterCount()==0&&method.getReturnType()==int.class).findFirst().orElseThrow(()->new IOException("native_module_item_id_missing")).invoke(stack);}
    private byte[] moduleWire(Object stack)throws Exception{return szserver.net.Wire.bytes(out->stack.getClass().getMethod("switch",DataOutput.class).invoke(stack,out));}
    private Map<Object,Object> moduleMap(Object stack)throws Exception{
        Object state=stack.getClass().getMethod("for",Class.class).invoke(stack,Class.forName("822754569",true,loader));
        if(state==null)throw new IOException("module_weapon_state");return (Map<Object,Object>)state.getClass().getMethod("for").invoke(state);
    }
    private Object prepareModuleGun(byte[] raw)throws Exception{
        int id=new DataInputStream(new ByteArrayInputStream(raw)).readUnsignedShort();Object factory=moduleFactory(id),stack=moduleStack(raw);
        for(Object child:moduleMap(stack).values())ModuleCosmeticCompatibility.childFactory(this,moduleId(child));
        return stack;
    }
    /** Original inventory footprint, including the currently installed modules. Worker only. */
    public int[] weaponGeometry(byte[] raw)throws Exception{
        Object stack=prepareModuleGun(raw);
        return withModuleFactories(()->{Object size=Class.forName("1824362651",true,loader).getMethod("while",stack.getClass()).invoke(null,stack);return new int[]{size.getClass().getField("try").getInt(size),size.getClass().getField("enum").getInt(size)};});
    }
    /** Prepared on the inventory worker using the actual equipped state and its module factories. */
    public szserver.inventory.WeaponProfile weaponProfile(byte[] raw)throws Exception{
        int id=new DataInputStream(new ByteArrayInputStream(raw)).readUnsignedShort();String name=moduleFactoryClasses.get(id);Class<?> gun=Class.forName("1745367406",true,loader);if(name==null||!gun.isAssignableFrom(Class.forName(name,true,loader)))return null;
        Object value=prepareModuleGun(raw),factory=moduleFactory(id);Class<?> stack=value.getClass(),timing=Class.forName("1042667109",true,loader);Object complete=timing.getField("RELOAD_COMPLETE_DURATION").get(null);
        return withModuleFactories(()->{int capacity=(Integer)NativeMethods.instance(gun,"default",int.class,stack).invoke(factory,value);int[] ammunition=(int[])NativeMethods.instance(gun,"protected",int[].class,stack).invoke(factory,value);if(ammunition.length==0)return null;
            double reload=(Float)NativeMethods.instance(gun,"synchronized",float.class,stack,timing).invoke(factory,value,complete),tactical=(Float)NativeMethods.instance(gun,"switch",float.class,stack,timing).invoke(factory,value,complete);
            double modifier=(Float)NativeMethods.instance(gun,"continue",float.class,stack).invoke(factory,value);if(!Double.isFinite(modifier)||modifier<=0)throw new IOException("weapon_reload_modifier");
            List<Double> reloadByRounds=new ArrayList<>();if(gun.getField("cs").getBoolean(factory)){Class<?> player=Class.forName("200241649",true,loader);Method duration=NativeMethods.instance(gun,"void",float.class,stack,int.class,int.class,timing,player);for(int rounds=0;rounds<=capacity;rounds++)reloadByRounds.add(rounds==capacity?0.0:((Float)duration.invoke(factory,value,rounds,capacity-rounds,complete,null)).doubleValue()*modifier);reload=reloadByRounds.get(0)/modifier;tactical=reloadByRounds.get(Math.min(1,capacity))/modifier;}

            return new szserver.inventory.WeaponProfile(capacity,Arrays.stream(ammunition).boxed().toList(),gun.getField("synchronized").getFloat(factory),gun.getField("throw").getFloat(factory),(Float)NativeMethods.instance(gun,"import",float.class,stack).invoke(factory,value),(Float)NativeMethods.instance(gun,"float",float.class,stack).invoke(factory,value),(Float)NativeMethods.instance(gun,"throws",float.class,stack).invoke(factory,value),(Float)NativeMethods.instance(gun,"private",float.class,stack).invoke(factory,value),reload*modifier,tactical*modifier,gun.getField("i").getFloat(factory),reloadByRounds);
        });
    }
    /** Actual native gun initializer populates resource inbuilt_* slots; caller gives this only to a new item. */
    public byte[] initialModules(int id,String kind,byte[] state)throws Exception{
        Object factory=moduleFactory(id);Class<?> gun=Class.forName("1745367406",true,loader);
        Map<?,?> builtins=(Map<?,?>)gun.getField("n").get(factory);for(Object value:builtins.values())moduleFactory((Integer)value);
        byte[] raw=szserver.net.Wire.bytes(out->{out.writeShort(id);out.writeInt(1);out.writeShort(0);out.writeBoolean(true);out.write(state);out.writeByte(0);});
        Object stack=prepareModuleGun(raw);Object initialized=withModuleFactories(()->{
            Map<Object,Object> existing=new LinkedHashMap<>(moduleMap(stack));Arrays.stream(gun.getMethods()).filter(method->method.getName().equals("try")&&method.getReturnType()==void.class&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{stack.getClass()})).findFirst().orElseThrow(()->new IOException("native_builtin_initializer_missing")).invoke(factory,stack);
            Map<Object,Object> current=moduleMap(stack);current.putAll(existing);for(var entry:current.entrySet())if(!existing.containsKey(entry.getKey())){
                Object nested=entry.getValue().getClass().getMethod("for",Class.class).invoke(entry.getValue(),Class.forName("1806321611",true,loader));freshItemState(nested,0,Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            return stack.getClass().getMethod("for",Class.class).invoke(stack,Class.forName(kind,true,loader));
        });return encode(codec(kind),initialized);
    }
    public record ModuleChange(byte[] state,List<byte[]> removed){}
    private record NativeModuleChange(Object state,List<Object> removed){}
    /** Native factory/parent-slot/bitset/category rule; all mutations are still tentative until journal publication. */
    public ModuleChange nativeModule(byte[] weapon,String kind,int slot,byte[] attached)throws Exception{
        Object stack=prepareModuleGun(weapon),factory=moduleFactory(moduleId(stack));Class<?> gun=Class.forName("1745367406",true,loader),base=Class.forName("1366800141",true,loader),keyType=Class.forName("1173269411",true,loader),stateType=Class.forName("1806321611",true,loader),module=Class.forName("62393481",true,loader);
        Object replacement=null,candidate=null;if(attached!=null){int id=new DataInputStream(new ByteArrayInputStream(attached)).readUnsignedShort();candidate=moduleFactory(id);replacement=moduleStack(attached);}
        Object next=replacement,definition=candidate;NativeModuleChange nativeChange=withModuleFactories(()->{
            Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);if(slot<0||slot>=slots.length)throw new IOException("module_slot");Object key=slots[slot];Map<Object,Object> map=moduleMap(stack);Object old=map.get(key);
            if(old!=null){Object mode=module.getMethod("extends",stack.getClass()).invoke(null,old);if(!(Boolean)mode.getClass().getMethod("super").invoke(mode))throw new IOException("module_locked");}
            if(next!=null&&!(Boolean)gun.getMethod("interface",stack.getClass(),base,keyType).invoke(factory,stack,definition,key))throw new IOException("module_mount_incompatible");
            List<Object> removed=new ArrayList<>();if(old!=null)addRemovedModule(old,stateType,removed);Method setter=NativeMethods.instance(Class.forName("1579742958",true,loader),"public",void.class,stack.getClass(),stack.getClass(),Object.class);setter.invoke(factory,stack,next,key);map=moduleMap(stack);Object installed=map.get(key);if((next==null)!=(installed==null)||next!=null&&!Arrays.equals(moduleWire(next),moduleWire(installed)))throw new IOException("module_native_set_rejected");
            for(int pass=0;pass<slots.length;pass++){boolean changed=false;for(Object childKey:slots){Object child=map.get(childKey);if(child==null||childKey==key)continue;Object childFactory=moduleFactory(moduleId(child));
                if(!(Boolean)gun.getMethod("interface",stack.getClass(),base,keyType).invoke(factory,stack,childFactory,childKey)){
                    Object mode=module.getMethod("extends",stack.getClass()).invoke(null,child);if(!(Boolean)mode.getClass().getMethod("super").invoke(mode))throw new IOException("module_locked_dependency");
                    setter.invoke(factory,stack,null,childKey);map=moduleMap(stack);if(map.containsKey(childKey))throw new IOException("module_native_remove_rejected");addRemovedModule(child,stateType,removed);changed=true;
                }}if(!changed)break;}
            Object state=stack.getClass().getMethod("for",Class.class).invoke(stack,Class.forName(kind,true,loader));return new NativeModuleChange(state,List.copyOf(removed));
        });List<byte[]> removed=new ArrayList<>();for(Object value:nativeChange.removed())removed.add(moduleWire(value));return new ModuleChange(encode(codec(kind),nativeChange.state()),List.copyOf(removed));
    }
    private void addRemovedModule(Object stack,Class<?> stateType,List<Object> removed)throws Exception{
        Object state=stack.getClass().getMethod("for",Class.class).invoke(stack,stateType);if(state==null||!stateType.getField("instanceof").getBoolean(state))removed.add(stack);
    }
    public record InventoryPoint(int owner,int sequence,int aggregate,int container,int slot,byte[] stack){}
    /** Genuine native inventory point reader/writer. No client gameplay callback is run. */
    public InventoryPoint inventoryPoint(byte[] body)throws Exception{
        if(body.length<9||body.length>9000)throw new IOException("inventory_point_bounds");Class<?> type=Class.forName("1090368269",true,loader);Object packet=type.getConstructor().newInstance();BoundedNativeInput input=new BoundedNativeInput(body);type.getMethod("extends",DataInput.class).invoke(packet,input);if(input.available()!=0)throw new IOException("inventory_point_trailing");
        if(!Arrays.equals(body,szserver.net.Wire.bytes(out->type.getMethod("switch",DataOutput.class).invoke(packet,out))))throw new IOException("inventory_point_not_canonical");
        Object address=type.getField("static").get(packet),stack=type.getField("break").get(packet);Class<?> parent=Class.forName("1799496656",true,loader);
        return new InventoryPoint(parent.getField("volatile").getInt(packet),parent.getField("this").getInt(packet),address.getClass().getField("short").getInt(address),address.getClass().getField("extends").getInt(address),address.getClass().getField("switch").getInt(address),stack==null?null:moduleWire(stack));
    }
    public byte[] moduleCharge(String kind,byte[] raw)throws Exception{if(!kind.equals("342965638"))throw new IOException("module_charge_kind");Object state=decode(codec(kind),raw),charge=state.getClass().getField("finally").get(state);return charge==null?null:moduleWire(charge);}
    public record ModuleInfo(int slot,int item,boolean builtin,boolean locked){}
    public List<ModuleInfo> modules(String kind,byte[] raw)throws Exception{
        Object state=decode(codec(kind),raw);Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);Map<?,?> map=(Map<?,?>)state.getClass().getField("abstract").get(state);List<ModuleInfo> result=new ArrayList<>();Class<?> moduleState=Class.forName("1806321611",true,loader);
        for(int i=0;i<slots.length;i++){Object stack=map.get(slots[i]);if(stack==null)continue;Object child=stack.getClass().getMethod("for",Class.class).invoke(stack,moduleState);result.add(new ModuleInfo(i,moduleId(stack),child!=null&&moduleState.getField("instanceof").getBoolean(child),child!=null&&moduleState.getField("throw").getBoolean(child)));}return List.copyOf(result);
    }
    public void registerStateless(int id)throws Exception {Class<?> factory=Class.forName("1366800141",true,loader);Object[] registry=(Object[])factory.getField("import").get(null);if(id<1||id>=registry.length)throw new IllegalArgumentException("factory_id");if(registry[id]==null)factory.getConstructor(int.class).newInstance(id);}
    /** Native palette initializer, before synthetic headless state registrations.
     * The same canonical constructors are used by the client block bootstrap. */
    public void warmBlockItems()throws Exception{
        Class.forName("1959919792",true,loader);Class.forName("683052146",true,loader);
        Class.forName("1648977992",true,loader).getMethod("throws").invoke(null);
        Class<?> palette=Class.forName("1872807016",true,loader);palette.getMethod("class").invoke(null);palette.getMethod("continue").invoke(null);
    }
    /** Reviewed fixed-ID/native-int constructors; no invented JSON resource contexts. */
    public void warmDeveloperItems()throws Exception{
        Class<?> base=Class.forName("1366800141",true,loader);Object[] table=(Object[])base.getField("import").get(null);
        for(var row:NativeInventoryCatalogRelease.BLOCK_FACTORIES.entrySet()){
            int id=row.getKey();String name=row.getValue();
            if(table[id]==null)Class.forName(name,true,loader).getConstructor(int.class).newInstance(id);
            if(!table[id].getClass().getName().equals(name))throw new IllegalStateException("native_legacy_block_factory_conflict:"+id);
        }
        for(var row:NativeDeveloperItemsRelease.FACTORIES.entrySet()){
            int id=row.getKey();String name=row.getValue();
            if(table[id]==null){Class<?> type=Class.forName(name,true,loader);if(id==15789||id==16504)type.getConstructor().newInstance();else type.getConstructor(int.class).newInstance(id);}
            if(table[id]==null||!table[id].getClass().getName().equals(name))throw new IllegalStateException("native_developer_factory_conflict:"+id);
        }
    }
    /** Lightweight headless factory: the real native state constructor, with no rendering/config load. */
    public void registerStateful(int id,String kind)throws Exception {
        if(!kind.matches("[0-9]+"))throw new IllegalArgumentException("state_class_name");Class<?> base=Class.forName("154675812",true,loader);if(!base.isAssignableFrom(Class.forName(kind,true,loader)))throw new IllegalArgumentException("state_base_class");
        Class<?> nativeFactory=Class.forName("1366800141",true,loader);Object[] registry=(Object[])nativeFactory.getField("import").get(null);if(id<1||id>=registry.length)throw new IOException("native_item_registry_id_out_of_range:"+id);
        if(registry[id]!=null){Method stateFactory=Arrays.stream(nativeFactory.getMethods()).filter(m->m.getName().equals("default")&&m.getParameterCount()==0&&m.getReturnType()==base).findFirst().orElseThrow();Object existing=stateFactory.invoke(registry[id]);if(existing!=null&&existing.getClass().getName().equals(kind)||kind.equals("154675812"))return;throw new IllegalStateException("headless_factory_state_conflict:"+id);}
        Constructor<?> factory=factories.get(kind);if(factory==null){String name="szserver.bridge.StateFactory_"+kind;factory=loader.define(name,factoryBytecode(name.replace('.','/'),kind)).getConstructor(int.class);factories.put(kind,factory);}factory.newInstance(id);
    }
    static byte[] factoryBytecode(String name,String kind)throws IOException {
        return factoryBytecode(name,kind,false);
    }
    /** Scene resource factories use the genuine CLIENT neutral identity, without selecting a mode
     * or importing an invented empty tag that would reset other constructor defaults. */
    static byte[] factoryBytecode(String name,String kind,boolean neutral)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(52);out.writeShort(neutral?26:17);
        utf(out,name);clazz(out,1);utf(out,"1366800141");clazz(out,3);utf(out,"<init>");utf(out,"(I)V");pair(out,12,5,6);pair(out,10,4,7);utf(out,"Code");utf(out,"default");utf(out,"()L154675812;");utf(out,kind);clazz(out,12);utf(out,"()V");pair(out,12,5,14);pair(out,10,13,15);
        if(neutral){utf(out,"154675812");clazz(out,17);utf(out,"package");utf(out,"Ljava/util/UUID;");pair(out,12,19,20);pair(out,9,18,21);utf(out,"switch");pair(out,12,23,20);pair(out,9,18,24);}
        out.writeShort(0x21);out.writeShort(2);out.writeShort(4);out.writeShort(0);out.writeShort(0);out.writeShort(2);
        method(out,5,6,2,new byte[]{0x2a,0x1b,(byte)0xb7,0,8,(byte)0xb1});method(out,10,11,1,neutral?new byte[]{(byte)0xbb,0,13,0x59,(byte)0xb7,0,16,0x59,(byte)0xb2,0,25,(byte)0xb5,0,22,(byte)0xb0}:new byte[]{(byte)0xbb,0,13,0x59,(byte)0xb7,0,16,(byte)0xb0},neutral?3:2);out.writeShort(0);return bytes.toByteArray();
    }
    private static void utf(DataOutputStream out,String value)throws IOException{out.writeByte(1);out.writeUTF(value);}
    private static void clazz(DataOutputStream out,int name)throws IOException{out.writeByte(7);out.writeShort(name);}
    private static void pair(DataOutputStream out,int type,int first,int second)throws IOException{out.writeByte(type);out.writeShort(first);out.writeShort(second);}
    private static void method(DataOutputStream out,int name,int descriptor,int locals,byte[] code)throws IOException{method(out,name,descriptor,locals,code,2);}
    private static void method(DataOutputStream out,int name,int descriptor,int locals,byte[] code,int stack)throws IOException{out.writeShort(1);out.writeShort(name);out.writeShort(descriptor);out.writeShort(1);out.writeShort(9);out.writeInt(12+code.length);out.writeShort(stack);out.writeShort(locals);out.writeInt(code.length);out.write(code);out.writeShort(0);out.writeShort(0);}
    public Magazine inspectMagazine(byte[] input)throws Exception {
        return inspectMagazine("1535556086",input);
    }
    public Magazine inspectMagazine(String kind,byte[] input)throws Exception {
        if(!Set.of("1535556086","259689215").contains(kind))throw new IOException("magazine_state_kind");
        Object state=decode(codec(kind),input);UUID identity=(UUID)Class.forName("154675812",true,loader).getDeclaredField("package").get(state);
        Object stack=state.getClass().getField("void").get(state);int ammo=0,rounds=0;
        if(stack!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();stack.getClass().getMethod("switch",DataOutput.class).invoke(stack,new DataOutputStream(bytes));DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));ammo=in.readUnsignedShort();rounds=in.readInt();}
        int magazineFactory=0;Object[] slots=(Object[])Class.forName("1536296909",true,loader).getField("slots").get(null);Object attachment=((Map<?,?>)state.getClass().getField("abstract").get(state)).get(slots[13]);if(attachment!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();attachment.getClass().getMethod("switch",DataOutput.class).invoke(attachment,new DataOutputStream(bytes));magazineFactory=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUnsignedShort();}
        return new Magazine(identity,ammo,rounds,magazineFactory);
    }

    public byte[] freshIdentity(String stateClass, byte[] input) throws Exception {
        Codec codec = codec(stateClass);
        Object state = decode(codec, input);
        Class<?> base = Class.forName("154675812", true, loader);
        if (!base.isInstance(state)) throw new IllegalArgumentException("unsupported_state_identity");
        base.getDeclaredField("package").set(state, UUID.randomUUID());
        return encode(codec, state);
    }
    /** Traverse only native item containment, never arbitrary game/world object graphs. */
    public byte[] freshTreeIdentity(String kind,byte[] input)throws Exception{
        Codec codec=codec(kind);Object state=decode(codec,input);
        freshItemState(state,0,Collections.newSetFromMap(new IdentityHashMap<>()));return encode(codec,state);
    }
    private void freshItemState(Object state,int depth,Set<Object> visited)throws Exception{
        if(state==null||!visited.add(state))return;if(depth>8||visited.size()>128)throw new IOException("grant_nested_item_limit");
        Class<?> base=Class.forName("154675812",true,loader),stack=Class.forName("211097523",true,loader);
        if(!base.isInstance(state))throw new IOException("grant_state_identity");base.getField("package").set(state,UUID.randomUUID());
        for(Field field:state.getClass().getFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())){
            Object value=field.get(state);Collection<?> children=value instanceof Map<?,?> map?map.values():value instanceof Collection<?> list?list:value instanceof Object[] array?Arrays.asList(array):Collections.singleton(value);
            for(Object child:children)if(child!=null&&stack.isInstance(child)){
                Object nested=null;for(Method method:stack.getMethods())if(method.getName().equals("finally")&&method.getParameterCount()==0&&method.getReturnType()==base){nested=method.invoke(child);break;}freshItemState(nested,depth+1,visited);
            }
        }
    }
    public UUID identity(String kind,byte[] input)throws Exception{
        Object state=decode(codec(kind),input);Object id=Class.forName("154675812",true,loader).getDeclaredField("package").get(state);return id instanceof UUID value?value:new UUID(0,0);
    }
    public byte[] withIdentity(String kind,byte[] input,UUID identity)throws Exception{
        Codec codec=codec(kind);Object state=decode(codec,input);Class.forName("154675812",true,loader).getDeclaredField("package").set(state,identity);return encode(codec,state);
    }

    public byte[] nbtRoundTrip(String stateClass, byte[] input) throws Exception {
        Codec codec = codec(stateClass);
        Object state = decode(codec, input);
        Object nbt = compound.getConstructor().newInstance();
        codec.exportNbt().invoke(state, nbt);
        codec.importNbt().invoke(state, nbt);
        return encode(codec, state);
    }

    public byte[] setInteger(String stateClass, byte[] input, String key, int value) throws Exception {
        Codec codec = codec(stateClass);
        Object state = decode(codec, input);
        Object nbt = compound.getConstructor().newInstance();
        codec.exportNbt().invoke(state, nbt);
        compound.getMethod("while", String.class, int.class).invoke(nbt, key, value);
        codec.importNbt().invoke(state, nbt);
        return encode(codec, state);
    }

    public int readInteger(String stateClass, byte[] input, String key) throws Exception {
        Codec codec = codec(stateClass);
        Object nbt = compound.getConstructor().newInstance();
        codec.exportNbt().invoke(decode(codec, input), nbt);
        // JVM bytecode permits methods distinguished only by return type.
        // These obfuscated classes use that feature; getMethod is ambiguous.
        Method getter = Arrays.stream(compound.getMethods())
            .filter(m -> m.getName().equals("strictfp") && m.getReturnType() == int.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class}))
            .findFirst().orElseThrow(() -> new NoSuchMethodException("NBT integer getter"));
        return (Integer) getter.invoke(nbt, key);
    }

    @Override public void close() throws IOException { loader.close(); }
}
