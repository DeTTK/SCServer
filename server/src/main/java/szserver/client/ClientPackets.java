package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import szserver.net.Wire;

/** Canonical native input/replication codecs, isolated from graphics/bootstrap. */
public final class ClientPackets implements AutoCloseable {
    public record Button(int device,int code,boolean pressed,List<Integer> bindings){}
    public record Input(int tick,double[] position,Float yaw,Float pitch,boolean ground,int mode,int locomotion,int previous,double speed,List<Button> buttons,Float stamina,NativeBlockPackets.Batch blockBatch,double[] velocity,PlayerAnimationPackets.Motion motion,boolean controlsPresent,int controlFlags,List<String> locomotionActions){
        public Input {locomotionActions=List.copyOf(locomotionActions);}
        public boolean flag(int index){return (controlFlags&(1<<index))!=0;}
    }
    public record BlockDelta(int x,int y,int z,int id,int metadata){}
    public record TerrainChunk(int x,int z,int primaryMask,int addMask,UUID revision,int arraysSize,int compressedSize){}
    public record CachedTerrainChunk(int x,int z,boolean hasTerrain,UUID revision,boolean arraysAbsent){}
    private final URLClassLoader loader;
    private record Codec(Constructor<?> constructor,Method read,Method write){}
    private final Map<String,Codec> codecs=new HashMap<>();
    public ClientPackets(Path runtime)throws Exception{List<URL> urls=new ArrayList<>();urls.add(runtime.resolve("classes").toUri().toURL());urls.add(runtime.resolve("bootstrap").toUri().toURL());urls.add(runtime.resolve("libs").toUri().toURL());try(var files=Files.list(runtime.resolve("dependencies"))){for(Path p:files.filter(p->p.toString().endsWith(".jar")).sorted().toList())urls.add(p.toUri().toURL());}loader=new ClientSceneCodec.Loader(urls.toArray(URL[]::new));locomotionRegistry();}
    /** Register only native movement-event codecs, without starting the graphical bootstrap. */
    private void locomotionRegistry()throws Exception{
        Class<?> registry=Class.forName("510237502",true,loader),annotation=Class.forName("1928695070",true,loader);
        Object receiver=registry.getConstructor().newInstance();Map<?,?> registered=(Map<?,?>)registry.getField("switch").get(null);
        String[][] events={{"magnet-stop","206040613"},{"spawn-barrier-stop","1669636679"},{"sit_destruct","327722217"},{"zipline","296780051"},{"zipline_destruct","228081277"},{"magnet-start","445475437"}};
        for(String[] event:events){
            Class<?> type=Class.forName(event[1],true,loader);Object tag=type.getAnnotation(annotation.asSubclass(java.lang.annotation.Annotation.class));
            if(tag==null||!event[0].equals(annotation.getMethod("value").invoke(tag)))throw new IOException("native_locomotion_registry_mismatch");
            if(!registered.containsKey(event[0]))registry.getMethod("double",String.class,Class.class).invoke(receiver,event[0],type);
            else if(registered.get(event[0])!=type)throw new IOException("native_locomotion_registry_conflict");
        }
    }
    public Object decode(String name,byte[] body)throws Exception{Codec c=codecs.get(name);if(c==null){Class<?> type=Class.forName(name,true,loader);c=new Codec(type.getConstructor(),type.getMethod("extends",DataInput.class),type.getMethod("switch",DataOutput.class));codecs.put(name,c);}Object packet=c.constructor.newInstance();var input=new DataInputStream(new ByteArrayInputStream(body));c.read.invoke(packet,input);if(input.available()!=0)throw new IOException("native_packet_trailing_bytes");return packet;}
    public void statelessItem(int id)throws Exception{Class<?> factory=Class.forName("1366800141",true,loader);Object[] registry=(Object[])factory.getField("import").get(null);if(registry[id]==null)factory.getConstructor(int.class).newInstance(id);}
    public szserver.net.StaminaRules staminaRules()throws Exception{Class<?> settings=Class.forName("1957590987",true,loader);return new szserver.net.StaminaRules(settings.getField("while").getFloat(null),settings.getField("static").getFloat(null),settings.getField("char").getFloat(null),settings.getField("extends").getFloat(null),settings.getField("int").getFloat(null));}
    public byte[] roundTrip(String name,byte[] body)throws Exception{Object object=decode(name,body);return Wire.bytes(o->codecs.get(name).write.invoke(object,o));}
    /** Genuine native outer effect packet reader; the gameplay callback is not invoked. */
    public byte[] effectRecords(byte[] body)throws Exception{return ((byte[])field(decode("456314467",body),"static")).clone();}
    public boolean explosiveFire(byte[] body)throws Exception{return (Boolean)field(decode("1176115659",body),"void");}
    public boolean underbarrelReload(byte[] body)throws Exception{return (Boolean)field(decode("1072250159",body),"if");}
    /** Actual native inventory-menu writer, including nullable address before owner/sequence. */
    public byte[] launcherUnload(int aggregate,int container,int slot,int owner,int sequence)throws Exception{
        Class<?> address=Class.forName("949164803",true,loader),packet=Class.forName("568948555",true,loader);
        Object value=packet.getConstructor(address).newInstance(address.getConstructor(int.class,int.class,int.class).newInstance(aggregate,container,slot));
        Class<?> parent=Class.forName("1799496656",true,loader);parent.getField("volatile").setInt(value,owner);parent.getField("this").setInt(value,sequence);
        return Wire.bytes(out->packet.getMethod("switch",DataOutput.class).invoke(value,out));
    }
    public record FlashPresentation(int item,String kind,boolean effectsEnabled,int blurTicks,int whiteoutTicks,boolean nativeFactoryResolved,boolean blurExpires,boolean whiteoutExpires,boolean refreshResets){}
    /** Packet/factory/effect model oracle. Does not invoke graphical world callback or claim rendered QA. */
    public FlashPresentation flashPresentation(byte[] body,com.google.gson.JsonObject definition)throws Exception{
        Object packet=decode("1155277618",body);int id=(Integer)field(packet,"finally");Object factory=grenadeFactory(id,definition),properties=field(packet,"extends"),position=field(packet,"native");
        String kind=((Enum<?>)field(properties,"catch")).name();if(!kind.equals("FLASH"))throw new IOException("not_flash_presentation");
        Class<?> blast=Class.forName("314850978",true,loader),vec=Class.forName("153304200",true,loader),receiver=Class.forName("142838726",true,loader);
        Object nativeBlast=blast.getConstructor(Class.forName("1500514786",true,loader),properties.getClass(),vec).newInstance(null,properties,position);blast.getMethod("throws",int.class).invoke(nativeBlast,id);
        int blurTicks=(Integer)field(factory,"assert"),whiteoutTicks=(Integer)field(factory,"switch");
        Class<?> blur=Class.forName("1093146959",true,loader),white=Class.forName("1015050801",true,loader);
        Object b=blur.getConstructor(vec,double.class,float.class,double.class,double.class).newInstance(position,5d,1f,(double)blurTicks,1d),w=white.getConstructor(int.class,float.class,boolean.class).newInstance(whiteoutTicks,1f,false);
        boolean initiallyLive=(Boolean)blur.getMethod("class",receiver).invoke(b,new Object[]{null})==(blurTicks<=0)&&(Boolean)white.getMethod("class",receiver).invoke(w,new Object[]{null})==(whiteoutTicks<=0);
        blur.getField("int").setInt(b,0);white.getField("interface").setInt(w,whiteoutTicks);
        boolean blurExpires=(Boolean)blur.getMethod("class",receiver).invoke(b,new Object[]{null}),whiteExpires=(Boolean)white.getMethod("class",receiver).invoke(w,new Object[]{null});
        blur.getMethod("if",vec,double.class,float.class,double.class,double.class).invoke(b,position,5d,1f,(double)blurTicks,.5d);white.getMethod("return",int.class,float.class).invoke(w,whiteoutTicks,.75f);
        boolean resets=initiallyLive&&(Integer)field(b,"int")==blurTicks&&(Integer)field(w,"interface")==0&&(Boolean)white.getMethod("class",receiver).invoke(w,new Object[]{null})==(whiteoutTicks<=0);
        return new FlashPresentation(id,kind,!(Boolean)field(packet,"do"),blurTicks,whiteoutTicks,blast.getMethod("else").invoke(nativeBlast)==factory,blurExpires,whiteExpires,resets);
    }
    /** Canonical projectile property parser and serializer, startup worker only. */
    public szserver.net.BlastRules blastRules(com.google.gson.JsonObject definition)throws Exception {
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_blast_rules_on_owner");
        Object json=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,definition.toString());Object value=json.getClass().getMethod("getAsJsonObject").invoke(json);
        Class<?> type=Class.forName("843651061",true,loader);Object p=type.getMethod("for",Class.forName("com.google.gson.JsonObject",true,loader)).invoke(null,value);
        return new szserver.net.BlastRules(((Enum<?>)field(p,"catch")).name(),((Enum<?>)field(p,"void")).name(),((Enum<?>)field(p,"final")).name(),((Number)field(p,"throw")).floatValue(),((Number)field(p,"public")).floatValue(),(Boolean)field(p,"boolean"));
    }
    public GrenadePackets.Profile grenadeProfile(com.google.gson.JsonObject definition)throws Exception {
        Object json=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,definition.toString());Object object=json.getClass().getMethod("getAsJsonObject").invoke(json);
        Class<?> type=Class.forName("824243370",true,loader);Object p=type.getMethod("interface",Class.forName("com.google.gson.JsonObject",true,loader),float.class,float.class).invoke(null,object,.1f,.8f);
        float speed=((Number)field(p,"static")).floatValue();
        // max_start_speed is the modern resource spelling used by the installed parser.
        int lifetime=definition.has("lifetime")?definition.get("lifetime").getAsInt():80,charge=definition.has("throw_period")?definition.get("throw_period").getAsInt():20;
        return new GrenadePackets.Profile(lifetime,charge,speed,((Number)field(p,"native")).floatValue(),((Number)field(p,"const")).floatValue(),(Boolean)field(p,"private"),((Number)field(p,"throws")).floatValue(),((Number)field(p,"strictfp")).floatValue(),((Number)field(p,"throw")).floatValue(),((Number)field(p,"abstract")).floatValue(),Wire.bytes(out->type.getMethod("switch",DataOutput.class).invoke(p,out)));
    }
    /** Actual grenade factory supplies prepare/throw/cook clocks and hand-use policy at startup. */
    private final Map<Integer,Object> grenadeFactories=new HashMap<>();
    private final Map<Integer,String> grenadeFactoryDefinitions=new HashMap<>();
    private Object grenadeFactory(int id,com.google.gson.JsonObject definition)throws Exception {
        String encoded=definition.toString();Object cached=grenadeFactories.get(id);if(cached!=null){if(!encoded.equals(grenadeFactoryDefinitions.get(id)))throw new IOException("grenade_factory_definition_changed");return cached;}
        Class<?> json=Class.forName("com.google.gson.JsonObject",true,loader),resource=Class.forName("1422486348",true,loader),grenade=Class.forName("850786143",true,loader),base=Class.forName("207310501",true,loader);
        // The exported hunt descriptor contains the presentation color Infinity, which the native
        // factory parses as hexadecimal. Ignore only that known invalid HUD field on a private copy;
        // every hand clock, physical property and effect still comes from the actual factory/parser.
        var factoryDefinition=definition.deepCopy();if(id==12402&&factoryDefinition.has("hud_warning_color")&&factoryDefinition.get("hud_warning_color").getAsString().equals("Infinity"))factoryDefinition.remove("hud_warning_color");
        Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,factoryDefinition.toString());Object value=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        Object descriptor=resource.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("server_grenade_"+id,value,id,null,null,null),factory=grenade.getConstructor(resource).newInstance(descriptor);
        grenadeFactories.put(id,factory);grenadeFactoryDefinitions.put(id,encoded);return factory;
    }
    public szserver.net.GrenadeUse.Rules grenadeUse(int id,com.google.gson.JsonObject definition)throws Exception {
        Object factory=grenadeFactory(id,definition);Class<?> grenade=Class.forName("850786143",true,loader),base=Class.forName("207310501",true,loader);
        return new szserver.net.GrenadeUse.Rules((Integer)NativeMethods.instance(base,"char",int.class).invoke(factory),(Integer)NativeMethods.instance(base,"new",int.class).invoke(factory),grenade.getField("void").getInt(factory),grenade.getField("double").getInt(factory),grenade.getField("throws").getBoolean(factory),(Boolean)NativeMethods.instance(grenade,"int",boolean.class).invoke(factory));
    }
    /** Real 1208863283 parser, with the same branch precedence as the installed 850786143 constructor.
     * Independent of unrelated presentation fields which may make that complete factory unavailable. */
    public List<szserver.inventory.GrenadeAreaProfile> grenadeAreas(int id,com.google.gson.JsonObject definition)throws Exception {
        List<com.google.gson.JsonObject> definitions=new ArrayList<>();
        if(definition.has("effects_aoe")){if(definition.getAsJsonArray("effects_aoe").size()>16)throw new IllegalArgumentException("grenade_area_count_budget");for(var row:definition.getAsJsonArray("effects_aoe"))definitions.add(row.getAsJsonObject());}
        else if(definition.has("effect_aoe"))definitions.add(definition.getAsJsonObject("effect_aoe"));
        else if(definition.has("has_effect")&&definition.get("has_effect").getAsBoolean())definitions.add(definition);
        Class<?> parser=Class.forName("1208863283",true,loader),json=Class.forName("com.google.gson.JsonObject",true,loader);List<Object> nativeAreas=new ArrayList<>();
        for(var row:definitions){Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,row.toString());Object nativeJson=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);nativeAreas.add(parser.getMethod("this",json).invoke(null,nativeJson));}
        List<szserver.inventory.GrenadeAreaProfile> result=new ArrayList<>();
        for(Object area:nativeAreas){Object bank=field(area,"super");Method export=Arrays.stream(bank.getClass().getMethods()).filter(m->m.getName().equals("float")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();Map<?,?> nativeStats=(Map<?,?>)export.invoke(bank);Map<String,Float> stats=new TreeMap<>();for(var e:nativeStats.entrySet()){float value=((Number)e.getValue()).floatValue();if(value!=0)stats.put((String)e.getKey().getClass().getField("for").get(e.getKey()),value);}
            result.add(new szserver.inventory.GrenadeAreaProfile(((Enum<?>)field(area,"long")).name(),((Number)field(area,"this")).doubleValue(),(Boolean)field(area,"default"),(String)field(area,"continue"),((Number)field(area,"extends")).doubleValue(),((Number)field(area,"if")).floatValue(),((Number)field(area,"protected")).floatValue(),((Number)field(area,"else")).floatValue(),(Boolean)field(area,"const"),(String)field(area,"instanceof"),((Number)field(area,"void")).byteValue(),(String)field(area,"return"),(String)field(area,"strictfp"),stats));
        }return List.copyOf(result);
    }
    /** QA oracle: real 850786143.return(), with only the elapsed-clock accessor supplied. */
    public float nativeGrenadeCooking(szserver.net.GrenadeUse.Rules rules,int elapsedTicks)throws Exception{
        var nativeLoader=(ClientSceneCodec.Loader)loader;String name="szserver.bridge.GrenadeCookProbe";Class<?> probe=nativeLoader.loaded(name)?Class.forName(name,true,loader):nativeLoader.define(name,GrenadeCookProbe.bytes());
        Class<?> unsafe=Class.forName("sun.misc.Unsafe"),base=Class.forName("850786143",true,loader);Field access=unsafe.getDeclaredField("theUnsafe");access.setAccessible(true);Object value=unsafe.getMethod("allocateInstance",Class.class).invoke(access.get(null),probe);
        probe.getField("ticks").setInt(value,Math.max(0,elapsedTicks-rules.prepareTicks()));
        Field ignite=base.getField("double"),delay=base.getField("void"),enabled=base.getField("throws");ignite.setAccessible(true);delay.setAccessible(true);enabled.setAccessible(true);
        ignite.setInt(value,rules.igniteTicks());delay.setInt(value,rules.igniteDelay());enabled.setBoolean(value,rules.ignites());
        return (Float)base.getMethod("return",Class.forName("1761650403",true,loader)).invoke(value,new Object[]{null});
    }
    /** QA exercises the installed grenade subtype reader without constructing a graphical world. */
    public int grenadeSubtypeItem(byte[] body)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field access=unsafe.getDeclaredField("theUnsafe");access.setAccessible(true);Object allocator=access.get(null);
        Class<?> type=Class.forName("1471738856",true,loader);Object value=unsafe.getMethod("allocateInstance",Class.class).invoke(allocator,type);var input=Wire.input(body);type.getMethod("default",DataInput.class).invoke(value,input);if(input.available()!=0)throw new IOException("grenade_subtype_trailing");return Class.forName("267674355",true,loader).getField("volatile").getInt(value);
    }
    public int grenadeSpawnItem(byte[] body,int expectedEntity)throws Exception {
        Object packet=decode(GrenadePackets.SPAWN,body);Class<?> parent=Class.forName("1570813014",true,loader);
        if(packet.getClass().getDeclaredField("boolean").getByte(packet)!=9||parent.getField("do").getInt(packet)+1630978953!=expectedEntity)throw new IOException("grenade_spawn_identity");
        return grenadeSubtypeItem((byte[])parent.getField("break").get(packet));
    }
    /** QA: installed remote animation reader/writer, without entering an entity/renderer handler. */
    public byte[] animationRoundTrip(byte[] body)throws Exception {
        if(body.length<4||body.length>4096)throw new IOException("animation_component_size");var b=ByteBuffer.wrap(body);if(b.get()!=1)throw new IOException("animation_component_selector");long mask=Wire.varint(b);if((mask&~0x3fffffffL)!=0)throw new IOException("animation_component_mask");
        Class<?> type=Class.forName("1507399958",true,loader);Object value=type.getConstructor().newInstance();var in=Wire.input(Arrays.copyOfRange(body,b.position(),body.length));type.getMethod("native",DataInput.class,long.class).invoke(value,in,mask);if(in.available()!=0)throw new IOException("animation_component_trailing");
        return Wire.bytes(out->{out.writeByte(1);Wire.varint(out,(int)mask);type.getMethod("new",DataOutput.class,long.class).invoke(value,out,mask);});
    }
    /** QA: actual native snapshot factories/packet constructor after bounded adapter validation. */
    public byte[] sceneEditorConstructor(byte[] body)throws Exception{
        List<SceneEditPackets.Edit> edits=SceneEditPackets.read("batch",body);Class<?> packet=Class.forName("1065423746",true,loader),base=Class.forName("1027565378",true,loader),vector=Class.forName("1987590071",true,loader),mode=Class.forName("1908426116",true,loader),editorMode=Class.forName("1183787214",true,loader);Object value=packet.getConstructor().newInstance();
        for(var edit:edits){var p=edit.properties();Object v1=vector.getConstructor(float.class,float.class,float.class).newInstance((float)p.vector1().x(),(float)p.vector1().y(),(float)p.vector1().z()),v2=vector.getConstructor(float.class,float.class,float.class).newInstance((float)p.vector2().x(),(float)p.vector2().y(),(float)p.vector2().z()),v3=vector.getConstructor(float.class,float.class,float.class).newInstance((float)p.vector3().x(),(float)p.vector3().y(),(float)p.vector3().z());boolean creation=edit.asset()!=null;Object[] args=creation?new Object[]{edit.identity(),edit.asset(),p.instanceName(),mode.getEnumConstants()[p.mode()],edit.transform(),p.renderFlags(),v1,v2,p.float1(),p.flag1(),v3,p.float2(),p.flag2(),p.flag3(),p.stateName(),p.flag4(),p.tag(),edit.parent(),edit.group()}:new Object[]{edit.identity(),mode.getEnumConstants()[p.mode()],edit.transform(),p.renderFlags(),v1,v2,p.float1(),p.flag1(),v3,p.float2(),p.flag2(),p.flag3(),p.editorFlags(),editorMode.getEnumConstants()[p.editorMode()],p.stateName(),p.flag4(),p.tag(),edit.parent()};
            java.lang.reflect.Method factory=Arrays.stream(packet.getMethods()).filter(method->method.getName().equals(creation?"const":"super")&&method.getParameterCount()==args.length).findFirst().orElseThrow();Object snapshot=factory.invoke(null,args);packet.getMethod("while",base).invoke(value,snapshot);
        }return Wire.bytes(out->packet.getMethod("switch",DataOutput.class).invoke(value,out));
    }
    /** QA uses the canonical numeric RPC registries, matching the production game protocol. */
    public byte[] sceneConfigurationRoundTrip(boolean response,byte[] body)throws Exception{Class.forName("1046176253",true,loader).getField("final").setBoolean(null,true);return roundTrip(response?"977073877":"1048773550",body);}
    /** Native save reader restores buffer capacity but leaves its write cursor at zero.
     * Verify the decoded bytes/UUID, then exercise the native writer with a freshly populated buffer. */
    public byte[] sceneConfigurationSaveCodec(byte[] body)throws Exception{
        SceneConfigurationPackets.Save checked=SceneConfigurationPackets.save(body);Object packet=decode("441478008",body),buffer=field(packet,"class");byte[] decoded=(byte[])buffer.getClass().getMethod("const").invoke(buffer);
        if(!checked.identity().equals(field(packet,"package"))||!Arrays.equals(decoded,checked.payload()))throw new IOException("native_scene_save_mismatch");Object written=buffer.getClass().getConstructor(int.class).newInstance(decoded.length);buffer.getClass().getMethod("write",byte[].class).invoke(written,(Object)decoded);packet.getClass().getField("class").set(packet,written);return Wire.bytes(out->codecs.get("441478008").write.invoke(packet,out));
    }
    public int worldReadyTick(byte[] body)throws Exception{if(body.length<45||body.length>20000)throw new IOException("world_ready_bounds");int size=ByteBuffer.wrap(body).getInt(8);if(size<162||size>16384||size+45>body.length)throw new IOException("world_ready_snapshot_bounds");return ((Number)field(decode("899750365",body),"catch")).intValue();}
    /** QA inspects the actual native player snapshot, not only the packet byte offsets. */
    public float[] worldReadyView(byte[] body)throws Exception{worldReadyTick(body);int size=ByteBuffer.wrap(body).getInt(8);Object state=decode("1943200621",Arrays.copyOfRange(body,12,12+size));return new float[]{((Number)field(state,"af")).floatValue(),((Number)field(state,"ae")).floatValue()};}
    private static Object field(Object object,String name)throws Exception{return object.getClass().getField(name).get(object);}
    /** Native record reader only: the game's terrain writer intentionally throws. No handlers are invoked. */
    public CachedTerrainChunk cachedTerrainChunk(byte[] body)throws Exception {
        if(body.length<17||body.length>240000||ByteBuffer.wrap(body).getInt()!=1||body[12]!=0)throw new IOException("cached_terrain_packet_layout");
        szserver.world.ScenePayload.parse(Arrays.copyOfRange(body,4,body.length));
        Object packet=decode("91232058",body);Object chunk=((List<?>)field(packet,"switch")).get(0);
        return new CachedTerrainChunk(((Number)field(chunk,"goto")).intValue(),((Number)field(chunk,"long")).intValue(),(Boolean)field(chunk,"static"),(UUID)field(chunk,"protected"),field(chunk,"extends")==null);
    }
    /** Canonical terrain packet is read-only: its native writer deliberately throws. */
    public TerrainChunk terrainChunk(byte[] body)throws Exception{
        if(body.length<58||body.length>1024*1024||ByteBuffer.wrap(body).getInt()!=1)throw new IOException("terrain_packet_limit");
        ByteBuffer wire=ByteBuffer.wrap(body);int mask=wire.getInt(13),add=wire.getInt(17),arrays=wire.getInt(37),compressed=wire.getInt(41);
        if(body[12]!=1||(mask&~65535)!=0||(add&~mask)!=0||arrays!=Integer.bitCount(mask)*12288+Integer.bitCount(add)*2048+256||compressed<4||(long)compressed+45>body.length)throw new IOException("terrain_geometry_layout");
        szserver.world.ScenePayload.parse(Arrays.copyOfRange(body,4,body.length));
        szserver.world.TilePayload.fromRecord(Arrays.copyOfRange(body,4,body.length));
        Object packet=decode("91232058",body);List<?> records=(List<?>)field(packet,"switch");Object chunk=records.get(0);
        return new TerrainChunk(((Number)field(chunk,"goto")).intValue(),((Number)field(chunk,"long")).intValue(),((Number)field(chunk,"private")).intValue(),((Number)field(chunk,"final")).intValue(),(UUID)field(chunk,"protected"),((Number)field(chunk,"void")).intValue(),((byte[])field(chunk,"extends")).length);
    }
    /** Native tile reader after bounded structural validation by TilePayload. */
    public List<byte[]> nativeTileBodies(byte[] record)throws Exception{
        List<szserver.world.TilePayload.Tile> checked=szserver.world.TilePayload.fromRecord(record);byte[] tail=szserver.world.TilePayload.encode(checked);
        Class<?> registry=Class.forName("29302651",true,loader);if(((Map<?,?>)registry.getField("implements").get(null)).isEmpty())registry.getMethod("assert").invoke(null);
        Class<?> chunk=Class.forName("336545457",true,loader);DataInputStream input=new DataInputStream(new ByteArrayInputStream(tail));List<?> tiles=(List<?>)chunk.getMethod("instanceof",DataInput.class).invoke(null,input);
        if(input.available()!=0||tiles.size()!=checked.size())throw new IOException("native_tile_tail_mismatch");List<byte[]> bodies=new ArrayList<>();
        for(Object tile:tiles)bodies.add(Wire.bytes(out->tile.getClass().getMethod("short",DataOutput.class).invoke(tile,out)));return List.copyOf(bodies);
    }
    public int nativeTiles(byte[] record)throws Exception{return nativeTileBodies(record).size();}
    /** Native complete packet reader/writer; compare compounds because native writing reorders tags. */
    public byte[] completeTileNbtRoundTrip(byte[] body)throws Exception{
        if(body.length<19||body.length>CompleteTileState.MAX_STATE+15)throw new IOException("complete_tile_packet_size");ByteBuffer input=ByteBuffer.wrap(body);int x=input.getInt(),y=input.getShort(),z=input.getInt(),action=input.get()&255,size=input.getInt();if(action!=127||size!=input.remaining())throw new IOException("complete_tile_packet_layout");byte[] state=new byte[size];input.get(state);var tile=CompleteTileState.decode(state);if(tile.x()!=x||tile.y()!=y||tile.z()!=z)throw new IOException("complete_tile_packet_position");
        Object packet=decode("237043901",body);if(((Number)field(packet,"native")).intValue()!=x||((Number)field(packet,"instanceof")).intValue()!=y||((Number)field(packet,"break")).intValue()!=z||((Number)field(packet,"char")).intValue()!=127)throw new IOException("complete_tile_native_mismatch");
        byte[] written=Wire.bytes(out->codecs.get("237043901").write.invoke(packet,out));Object reread=decode("237043901",written);Class<?> codec=Class.forName("1464336358",true,loader),compound=Class.forName("98008865",true,loader);byte[] nbt=Wire.bytes(out->codec.getMethod("char",compound,DataOutput.class).invoke(null,field(reread,"short"),out));if(!SceneNbt.readTile(nbt).values().equals(SceneNbt.readTile(CompleteTileState.raw(state)).values()))throw new IOException("complete_tile_native_compound_mismatch");return nbt;
    }
    public BlockDelta blockDelta(byte[] body)throws Exception{
        if(body.length!=12)throw new IOException("block_delta_length");ByteBuffer b=ByteBuffer.wrap(body);int x=b.getInt(),y=b.get()&255,z=b.getInt(),id=b.getShort()&65535,metadata=b.get()&255;
        if(Math.abs((long)x)>szserver.world.MapBounds.HORIZONTAL||Math.abs((long)z)>szserver.world.MapBounds.HORIZONTAL||id>4095||metadata>15)throw new IOException("block_delta_bounds");Object packet=decode("1429233302",body);
        return new BlockDelta(((Number)field(packet,"void")).intValue(),((Number)field(packet,"if")).intValue(),((Number)field(packet,"strictfp")).intValue(),((Number)field(packet,"do")).intValue(),((Number)field(packet,"else")).intValue());
    }
    public Input input(byte[] body,boolean extended)throws Exception{
        if(body.length>NativeBlockPackets.MAX_INPUT||body.length<18)throw new IOException("input_limit");byte[] ordinary=body;double[] pos=null;Float yaw=null,pitch=null;boolean ground=true;int mode=-1,loco=0,previous=0;double speed=0;double[] velocity=null;
        if(extended){var raw=ByteBuffer.wrap(body);int size=raw.getInt();if((size!=0&&size<162)||size<0||size>16384||size+22>body.length)throw new IOException("snapshot_limit:"+size+":"+body.length);if(size>0){pos=new double[]{raw.getDouble(4),raw.getDouble(12),raw.getDouble(20)};yaw=raw.getFloat(108);pitch=raw.getFloat(112);ground=body[4+136]!=0;loco=raw.getInt(158);previous=raw.getInt(162);if(loco<0||loco>12||previous<0||previous>12)throw new IOException("locomotion_ordinal");if(size>=190)mode=raw.getInt(186);velocity=new double[]{raw.getDouble(116),raw.getDouble(124),raw.getDouble(132)};for(double component:velocity)if(!Double.isFinite(component)||Math.abs(component)>Float.MAX_VALUE)throw new IOException("velocity_nonfinite");speed=Math.hypot(velocity[0],velocity[2]);if(!Double.isFinite(speed))throw new IOException("velocity_nonfinite");}ordinary=Arrays.copyOfRange(body,size+4,body.length);}
        // Native reader allocates an action array. Check that its encoded length fits
        // the physical packet before invoking it, including the variable climb data.
        var b=ByteBuffer.wrap(ordinary);int presence=b.get()&255;if((presence&~7)!=0)throw new IOException("input_presence");List<String> locomotionActions=new ArrayList<>();int controlFlags=0;if((presence&1)!=0)for(int i=0;i<8;i++)if(ordinary[9+i]!=0)controlFlags|=1<<i;int actions=0;if((presence&1)!=0){b.position(17);actions=b.getInt();}if(actions<0||actions>64)throw new IOException("locomotion_limit");
        for(int i=0;i<actions;i++){int size=b.getShort()&65535;if(size>64||size>b.remaining())throw new IOException("locomotion_name");byte[] name=new byte[size];b.get(name);String kind=new String(name,java.nio.charset.StandardCharsets.UTF_8);locomotionActions.add(kind);int payload=switch(kind){case "magnet-stop"->0;case "spawn-barrier-stop"->4;case "sit_destruct","zipline","zipline_destruct"->16;case "magnet-start"->40;default->throw new IOException("locomotion_kind:"+kind);};b.position(b.position()+payload);}
        if((presence&2)!=0)b.position(b.position()+24);if((presence&4)!=0)b.position(b.position()+8);b.position(b.position()+16);int actionLengthAt=b.position();int len=Wire.varint(b);if(len<0||len!=b.remaining())throw new IOException("input_action_size");
        byte[] rawActions=Arrays.copyOfRange(ordinary,b.position(),ordinary.length);NativeBlockPackets.Batch blockBatch=NativeBlockPackets.parse(rawActions);if(blockBatch.legacyLength()>65536)throw new IOException("legacy_input_action_limit");if(!blockBatch.deltas().isEmpty()){byte[] prefix=Arrays.copyOf(ordinary,actionLengthAt);ordinary=Wire.concat(prefix,Wire.bytes(out->{Wire.varint(out,blockBatch.legacyLength());out.write(rawActions,0,blockBatch.legacyLength());}));}
        Object p=decode("905354477",ordinary);Object vector=field(p,"synchronized"),view=field(p,"assert");if(vector!=null)pos=new double[]{((Number)field(vector,"case")).doubleValue(),((Number)field(vector,"void")).doubleValue(),((Number)field(vector,"import")).doubleValue()};if(view!=null){yaw=((Number)field(view,"import")).floatValue();pitch=((Number)field(view,"try")).floatValue();}
        if(pos!=null)for(double v:pos)if(!Double.isFinite(v)||Math.abs(v)>30_000_001)throw new IOException("input_position");if(yaw!=null&&(!Float.isFinite(yaw)||!Float.isFinite(pitch)))throw new IOException("input_view");
        if(mode<0){Object controls=field(p,"const");mode=controls==null?0:((Enum<?>)field(controls,"case")).ordinal();}if(mode>5)throw new IOException("movement_mode");
        Float stamina=null;if(extended&&ByteBuffer.wrap(body).getInt()>=207){stamina=ByteBuffer.wrap(body).getFloat(207);if(!Float.isFinite(stamina)||stamina<0||stamina>100)throw new IOException("input_stamina_invalid");}
        float heading=(ordinary[0]&1)!=0?ByteBuffer.wrap(ordinary).getFloat(1):0;if(!Float.isFinite(heading))throw new IOException("input_heading_nonfinite");
        byte[] snapshot=extended?Arrays.copyOfRange(body,4,4+ByteBuffer.wrap(body).getInt()):new byte[0];
        return new Input(((Number)field(p,"private")).intValue(),pos,yaw,pitch,ground,mode,loco,previous,Math.min(12,speed),buttons((byte[])field(p,"public")),stamina,blockBatch,velocity,PlayerAnimationPackets.motion(snapshot,heading),(presence&1)!=0,controlFlags,locomotionActions);
    }
    private static List<Button> buttons(byte[] raw)throws IOException{
        ByteBuffer input=ByteBuffer.wrap(raw);List<Button> buttons=new ArrayList<>();try{
            while(input.hasRemaining()){
                if(input.remaining()>=4&&input.getInt(input.position())==0x535a4244)break;
                int opcode=input.get()&255;if((opcode&128)!=0)continue;if((opcode&96)==64||(opcode&96)==96){input.get();continue;}
                switch(opcode){
                    case 0,1,2,3 -> {input.get();Wire.varint(input);Wire.varint(input);}
                    case 5 -> input.get();
                    case 6 -> {input.get();int size=input.getShort()&65535;input.position(input.position()+size);}
                    case 7,31 -> {}
                    case 8 -> input.position(input.position()+5);
                    case 9,10,12 -> input.position(input.position()+8);
                    case 11 -> input.position(input.position()+4);
                    case 13 -> {int flags=input.get()&255,code=Wire.varint(input),count=flags>>>4,device=flags&3,action=(flags>>>2)&3;if(device>1||action>1||buttons.size()>=256)throw new IOException("input_button_limit");List<Integer> bindings=new ArrayList<>();for(int n=0;n<count;n++)bindings.add(input.get()&255);buttons.add(new Button(device,code,action==0,List.copyOf(bindings)));}
                    default -> throw new IOException("input_action_opcode:"+opcode);
                }
            }
        }catch(java.nio.BufferUnderflowException|IllegalArgumentException error){throw new IOException("input_action_truncated",error);}return List.copyOf(buttons);
    }
    @Override public void close()throws Exception{loader.close();}
}
