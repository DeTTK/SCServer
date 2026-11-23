package szserver.client;

import com.google.gson.*;
import szserver.util.Json;
import java.nio.file.*;
import java.util.*;

/** Live native serializers, not Python ports. All calls on the command worker. */
public final class GameItems implements AutoCloseable {
    public ClientStateAdapter.DropPolicy dropPolicy(szserver.inventory.ItemStack stack)throws Exception{
        byte[] raw=szserver.net.InventoryPackets.stack(stack);var policy=adapter.dropPolicy(Arrays.copyOfRange(raw,1,raw.length));
        JsonObject definition=definitions.getAsJsonObject(Integer.toString(stack.id()));
        if(definition.has("can_drop_manually")&&!definition.get("can_drop_manually").getAsBoolean()||definition.has("bind_state")&&Set.of("no_drop","non_drop").contains(definition.get("bind_state").getAsString().toLowerCase(Locale.ROOT)))return new ClientStateAdapter.DropPolicy(false,policy.pickupOwner());
        return policy;
    }
    private final Set<String> verified=new HashSet<>();
    private final Map<String,String> codecFailures=new TreeMap<>();
    private final ClientStateAdapter adapter;
    private final JsonObject registry;
    private final Path runtime;
    public GameItems(Path runtime,Path registryPath)throws Exception{
        this(runtime,registryPath,null);
    }
    public GameItems(Path runtime,Path registryPath,Path manifestPath)throws Exception{
        this.runtime=runtime.toRealPath();
        if(manifestPath!=null){
            JsonObject manifest=JsonParser.parseString(RuntimeFiles.readString(manifestPath)).getAsJsonObject();
            for(var entry:manifest.getAsJsonObject("classes_sha256").entrySet()){
                String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IllegalArgumentException("invalid_runtime_manifest_class");
                String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(name+".class"))));
                if(!hash.equals(entry.getValue().getAsString()))throw new IllegalStateException("client_runtime_release_changed:"+name);
            }
        }
        registry=JsonParser.parseString(RuntimeFiles.readString(registryPath)).getAsJsonObject();
        Path overrides=registryPath.resolveSibling("client-current-item-state-overrides.json");
        if(Files.isRegularFile(overrides))for(var e:JsonParser.parseString(RuntimeFiles.readString(overrides)).getAsJsonObject().entrySet())registry.add(e.getKey(),e.getValue());
        NativeBlockItemsRelease.verifyRuntime(runtime);
        NativeDeveloperItemsRelease.verifyRuntime(runtime);
        NativeStorageLayoutRelease.verifyRuntime(runtime);
        NativeInventoryCatalogRelease.verifyRuntime(runtime);
        adapter=new ClientStateAdapter(runtime);adapter.warmBlockItems();adapter.warmDeveloperItems();
        for(var entry:registry.entrySet()){JsonObject row=entry.getValue().getAsJsonObject();int id=Integer.parseInt(entry.getKey());if(row.has("state"))adapter.registerStateful(id,row.get("state").getAsString());else adapter.registerStateless(id);}
        // Verify each installed state serializer before the server listens.
        // A failed codec is explicitly unavailable, never substituted with a guessed state.
        Map<String,byte[]> examples=new TreeMap<>();for(var entry:registry.entrySet()){JsonObject row=entry.getValue().getAsJsonObject();if(row.has("state")&&row.has("default"))examples.putIfAbsent(row.get("state").getAsString(),HexFormat.of().parseHex(row.get("default").getAsString()));}
        for(var example:examples.entrySet())try{byte[] state=adapter.freshIdentity(example.getKey(),example.getValue());if(!Arrays.equals(state,adapter.roundTrip(example.getKey(),state)))throw new IllegalStateException("state_roundtrip");verified.add(example.getKey());}catch(Exception error){codecFailures.put(example.getKey(),Objects.toString(error.getMessage(),error.getClass().getSimpleName()));}
    }
    public Map<String,String> codecFailures(){return Map.copyOf(codecFailures);}
    private JsonObject definitions;
    private Set<String> containerExempt=Set.of();
    public record MedicineStages(long startMillis,long activeMillis,long endMillis,Map<String,Float> stats,String key,boolean stopOnFullHealth){
        public static final MedicineStages NONE=new MedicineStages(0,0,0,Map.of(),null,false);
        public MedicineStages{stats=Map.copyOf(stats);}
    }
    public record MedicineRules(long useMillis,float instantStamina,boolean persistent,int food,float saturation,int calories,int drunkTicks,float toxicity,boolean quickEligible,int quickRank,MedicineStages stages,boolean active){
        public MedicineRules(long useMillis,float instantStamina,boolean persistent,int food,float saturation,int calories,int drunkTicks,float toxicity,boolean quickEligible,int quickRank){this(useMillis,instantStamina,persistent,food,saturation,calories,drunkTicks,toxicity,quickEligible,quickRank,MedicineStages.NONE,false);}
    }
    public record Medicine(int id,String group,int priority,boolean fast,int fastPriority,double duration,double cooldown,float instant,Map<String,Float> stats,MedicineRules rules){
        public Medicine(int id,String group,int priority,boolean fast,int fastPriority,double duration,double cooldown,float instant,Map<String,Float> stats){this(id,group,priority,fast,fastPriority,duration,cooldown,instant,stats,new MedicineRules(0,0,false,0,0,0,0,0,fast,fastPriority));}
        public Medicine activeUse(){var stage=rules.stages();return new Medicine(id,group,priority,false,fastPriority,stage.activeMillis()/1000d,cooldown,0,stage.stats(),new MedicineRules(rules.useMillis(),0,false,0,0,0,0,0,false,rules.quickRank(),stage,true));}
        public String cooldownKey(){return group==null?"item/"+id:group;}
    }
    public record MedicineEffect(int id,long until,Medicine medicine){}
    private final Map<Integer,Medicine> medicines=new HashMap<>();private Set<Integer> firearmFactories=Set.of();
    public boolean firearm(int id){return firearmFactories.contains(id);}
    private final Map<Integer,szserver.inventory.ExplosiveProfile> explosives=new HashMap<>();
    public Map<Integer,szserver.inventory.ExplosiveProfile> explosives(){return Map.copyOf(explosives);}
    private Map<String,Integer> statOrdinals=Map.of();
    public Map<Integer,Medicine> medicines(){return Map.copyOf(medicines);}
    public boolean medicineAllowed(int id,String mode,String location)throws Exception{return adapter.medicineAllowed(id,mode,location);}
    public ClientStateAdapter.MedicineRequest medicineRequest(byte[] body)throws Exception{return adapter.medicineRequest(body);}
    public Map<String,Float> medicineStats(Collection<Map<String,Float>> banks)throws Exception{return adapter.medicineStats(banks);}
    public byte[] medicineEffects(List<Integer> removed,Collection<MedicineEffect> added,long now)throws Exception{return adapter.medicineEffects(removed,added,now);}
    public Map<String,Integer> statOrdinals(){return statOrdinals;}
    public String upgradeTag()throws Exception{return adapter.upgradeTag();}
    public void warmGameplay(Path definitionsPath,Path artifactSettings)throws Exception{
        NativeMedicineRelease.verify(runtime);
        if(definitions!=null)throw new IllegalStateException("gameplay_already_warmed");
        Path fixtures=definitionsPath.getParent().toRealPath();JsonObject manifest=JsonParser.parseString(RuntimeFiles.readString(fixtures.resolve("native-gameplay-manifest.json"))).getAsJsonObject();
        for(var entry:manifest.getAsJsonObject("classes_sha256").entrySet()){
            if(!entry.getKey().matches("[0-9]+"))throw new IllegalArgumentException("native_gameplay_manifest_class");verifyDigest(runtime.resolve("classes").resolve(entry.getKey()+".class"),entry.getValue().getAsString());
        }
        for(var entry:manifest.getAsJsonObject("files_sha256").entrySet()){
            Path file=fixtures.resolve(entry.getKey()).toRealPath();if(!file.startsWith(fixtures))throw new IllegalArgumentException("native_gameplay_manifest_path");verifyDigest(file,entry.getValue().getAsString());
        }
        NativeBlockItemsRelease.verifyDefinitions(fixtures);
        NativeDeveloperItemsRelease.verifyDefinitions(fixtures);
        NativeStorageLayoutRelease.verifyFile(fixtures.resolve("native-storage-layout.json"));
        JsonObject catalog=szserver.inventory.CatalogDefinitions.read(definitionsPath);Map<Integer,String> artifacts=new TreeMap<>(),armors=new TreeMap<>();Map<String,Integer> names=new TreeMap<>();
        JsonObject nativeNames=JsonParser.parseString(RuntimeFiles.readString(definitionsPath.resolveSibling("native-item-names.json"))).getAsJsonObject();
        for(var entry:nativeNames.entrySet()){int id=entry.getValue().getAsInt();JsonObject definition=catalog.getAsJsonObject(Integer.toString(id));if(definition==null||!definition.has("unlocalized_name")||!definition.get("unlocalized_name").getAsString().equals(entry.getKey()))throw new IllegalArgumentException("native_item_name_catalog_mismatch:"+entry.getKey());names.put(entry.getKey(),id);}
        for(var entry:catalog.entrySet()){
            JsonObject item=entry.getValue().getAsJsonObject();String type=item.get("type").getAsString();
            int id=Integer.parseInt(entry.getKey());
            if(type.equals("armor"))armors.put(id,item.toString());
            if(type.equals("artefakt")||type.equals("artifact"))artifacts.put(Integer.parseInt(entry.getKey()),item.toString());
        }
        // Real gun factories read animation metadata and collision boxes while
        // preparing pure inventory rules. These roots are shipped with the server.
        for(String directory:List.of("resources/configs","resources/boxes")){Path extra=fixtures.getParent().resolve(directory);if(Files.isDirectory(extra))adapter.resources(extra);}
        adapter.warmItemNames(names);adapter.warmArmor(armors);
        adapter.warmArmorUpgrades(definitionsPath.getParent().resolve("native-resources"),armors);
        adapter.warmArtifacts(RuntimeFiles.readString(artifactSettings),artifacts,definitionsPath.getParent().resolve("native-resources"));containerExempt=adapter.containerExemptStats();definitions=catalog;
        LauncherPackets.verifyManifest(fixtures.getParent(),runtime,"native-weapon-modules.json");LauncherPackets.verifyManifest(fixtures.getParent(),runtime,"native-weapon-jamming.json");
        Map<Integer,String> moduleClasses=new HashMap<>(),moduleDefs=new HashMap<>();for(var entry:registry.entrySet())if(entry.getValue().getAsJsonObject().has("definition")){int id=Integer.parseInt(entry.getKey());if(catalog.has(entry.getKey())){moduleClasses.put(id,entry.getValue().getAsJsonObject().get("definition").getAsString());moduleDefs.put(id,catalog.get(entry.getKey()).toString());}}adapter.moduleDefinitions(moduleClasses,moduleDefs);adapter.warmCosmetics(moduleClasses,moduleDefs);firearmFactories=adapter.firearmFactories();
        medicines.clear();medicines.putAll(adapter.warmMedicines());statOrdinals=adapter.statOrdinals();for(var entry:catalog.entrySet()){
            JsonObject item=entry.getValue().getAsJsonObject();int id=Integer.parseInt(entry.getKey());
            if(item.get("type").getAsString().equals("grenade"))explosives.put(id,adapter.explosive(id,(item.has("explosive_props")?item.getAsJsonObject("explosive_props"):item).toString()));

        }
    }
    private static float number(JsonObject item,String key,float fallback){float value=item.has(key)?item.get(key).getAsFloat():fallback;if(!Float.isFinite(value))throw new IllegalArgumentException("resource_number_nonfinite:"+key);return value;}
    private static void verifyDigest(Path file,String expected)throws Exception{String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(file)));if(!actual.equals(expected))throw new java.io.IOException("native_gameplay_release_changed:"+file.getFileName());}
    public Map<String,Float> artifactStats(szserver.inventory.ItemStack artifact)throws Exception{
        byte[] wire=szserver.net.InventoryPackets.stack(artifact);return adapter.artifactStats(Arrays.copyOfRange(wire,1,wire.length));
    }
    public record EquipmentStats(Map<String,Float> total,Map<String,Float> artifacts){}
    /** Command-worker authoring for NPCs, using the same native armor/artifact calculators as players. */
    public szserver.world.ActorEquipment actorEquipment(Path root,List<Integer> worn,Integer held,boolean encumbered)throws Exception{
        if(definitions==null)warmGameplay(root.resolve("fixtures/item-definitions.json"),root.resolve("fixtures/artifact-settings.json"));
        if(worn.size()>2)throw new IllegalArgumentException("actor_equipment_slots");List<szserver.inventory.ItemStack> equipment=new ArrayList<>();Set<String> slots=new HashSet<>();Map<Integer,szserver.inventory.ItemStack> roots=new TreeMap<>();
        for(int id:worn){JsonObject definition=definitions.getAsJsonObject(Integer.toString(id));if(definition==null)throw new IllegalArgumentException("actor_equipment_item");String type=definition.get("type").getAsString();if(!Set.of("armor","backpack","container","tradepack","extreme_tradepack").contains(type)||!slots.add(type.equals("armor")?"armor":"backpack"))throw new IllegalArgumentException("actor_equipment_slot");var stack=createStack(id,1,0,new JsonObject());equipment.add(stack);roots.put(type.equals("armor")?1:3,stack);}
        var weapon=held==null?null:createStack(held,1,0,new JsonObject());if(weapon!=null)roots.put(6,weapon);
        Map<Integer,byte[]> visual=new TreeMap<>();for(int container=0;container<12;container++){var stack=roots.get(container);visual.put(55+container,stack==null?null:appearance(stack,null));}
        visual.put(89,new byte[]{0,0});visual.put(91,new byte[]{0});visual.put(127,new byte[]{(byte)(weapon==null?0:1)});
        visual.put(124,weapon==null?null:new byte[]{0,6,0,0});
        UUID identity=weapon==null?null:identity(weapon);
        visual.put(125,identity==null?null:java.nio.ByteBuffer.allocate(16).putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).array());
        visual.put(126,weapon==null?null:szserver.net.InventoryPackets.stack(weapon));
        var profile=szserver.world.ActorEquipment.stats(equipmentSummary(equipment).total(),encumbered,weapon);
        return new szserver.world.ActorEquipment(profile.movement(),profile.energyCapacity(),weapon,szserver.world.ActorPresentation.prepared(roots,visual),profile.stats());
    }
    /** Startup worker migration: derive the missing bank from existing immutable roots, no fresh item IDs. */
    public Map<String,Float> actorStats(Path root,szserver.world.ActorPresentation presentation){
        try{if(presentation.roots().isEmpty())return Map.of();if(definitions==null)warmGameplay(root.resolve("fixtures/item-definitions.json"),root.resolve("fixtures/artifact-settings.json"));
            var worn=new ArrayList<szserver.inventory.ItemStack>();for(var e:presentation.roots().entrySet())if(e.getKey()==1||e.getKey()==3)worn.add(szserver.inventory.ItemStack.read(JsonParser.parseString(e.getValue()).getAsJsonObject()));
            return szserver.world.ActorEquipment.checkedStats(equipmentStats(worn));
        }catch(Exception failure){throw new IllegalStateException("actor_legacy_stats_prepare",failure);}
    }
    public Map<String,Float> equipmentStats(Collection<szserver.inventory.ItemStack> equipment)throws Exception{return equipmentSummary(equipment).total();}
    public EquipmentStats equipmentSummary(Collection<szserver.inventory.ItemStack> equipment)throws Exception{
        if(definitions==null)throw new IllegalStateException("gameplay_not_warmed");Map<String,Float> stats=new TreeMap<>(),artifactTotal=new TreeMap<>();
        for(var item:equipment){
            byte[] wire=szserver.net.InventoryPackets.stack(item);var nativeBanks=adapter.containerStats(Arrays.copyOfRange(wire,1,wire.length));mergeStats(stats,nativeBanks.total());mergeStats(artifactTotal,nativeBanks.artifacts());

        }return new EquipmentStats(Map.copyOf(stats),Map.copyOf(artifactTotal));
    }
    private static void mergeStats(Map<String,Float> into,Map<String,Float> from){from.forEach((key,value)->into.merge(key,value,(a,b)->szserver.inventory.StatValues.add(key,a,b)));}
    public ClientStateAdapter.FactoryMetadataExport exportFactoryMetadata()throws Exception{return adapter.exportFactoryMetadata();}
    public int verifiedCodecs(){return verified.size();}
    public int size(){return registry.size();}
    /** The native common appearance is sufficient only without a factory-specific codec. */
    public boolean hasAppearanceCodec(int id)throws Exception {
        JsonObject row=registry.getAsJsonObject(Integer.toString(id));
        if(row==null||!row.has("definition"))throw new IllegalArgumentException("appearance_factory_missing:"+id);
        return adapter.hasAppearanceCodec(row.get("definition").getAsString());
    }
    public byte[] appearance(szserver.inventory.ItemStack stack,byte[] fallback)throws Exception {
        JsonObject row=registry.getAsJsonObject(Integer.toString(stack.id()));
        byte[] result;
        if(row.has("state")&&Set.of("1535556086","259689215").contains(row.get("state").getAsString())){
            byte[] raw=szserver.net.InventoryPackets.stack(stack);result=adapter.firearmAppearance(Arrays.copyOfRange(raw,1,raw.length));
        }else if(row.has("state")&&Set.of("1865555906","1176431411","1782792945","810393443","1490492938").contains(row.get("state").getAsString())){
            byte[] raw=szserver.net.InventoryPackets.stack(stack);result=adapter.cosmeticAppearance(row.get("state").getAsString(),Arrays.copyOfRange(raw,1,raw.length));
        }else if(fallback!=null)result=fallback.clone();
        else if(!hasAppearanceCodec(stack.id()))result=java.nio.ByteBuffer.allocate(7).putInt(stack.id()).array();
        else throw new IllegalArgumentException("equipment_appearance_missing:"+stack.id());
        int mask=stack.equipmentMask();result[4]=(byte)((mask&(1<<8))!=0?1:0);result[5]=(byte)((mask&(1<<9))!=0?1:0);result[6]=(byte)((mask&(1<<11))!=0?1:0);return result;
    }
    public szserver.inventory.WeaponProfile weaponProfile(szserver.inventory.ItemStack item)throws Exception{if(item==null)return null;byte[] raw=szserver.net.InventoryPackets.stack(item);return adapter.weaponProfile(Arrays.copyOfRange(raw,1,raw.length));}
    public byte[] magazine(byte[] state,int ammo,int rounds)throws Exception{return adapter.magazine(state,ammo,rounds);}
    public ClientStateAdapter.Magazine inspectMagazine(byte[] state)throws Exception{return adapter.inspectMagazine(state);}
    private String stateKind(szserver.inventory.ItemStack item){return registry.getAsJsonObject(Integer.toString(item.id())).get("state").getAsString();}
    public UUID identity(szserver.inventory.ItemStack item)throws Exception{return item.state().length==0?new UUID(0,0):adapter.identity(stateKind(item),item.state());}
    public szserver.inventory.ItemStack preserveIdentity(szserver.inventory.ItemStack original,szserver.inventory.ItemStack edited)throws Exception{
        if(original.id()!=edited.id())throw new IllegalArgumentException("editor_item_factory_changed");
        if(original.state().length==0)return edited;
        UUID identity=identity(original);return edited.state(adapter.withIdentity(stateKind(original),edited.state(),identity));
    }
    public ClientStateAdapter.Magazine inspectMagazine(szserver.inventory.ItemStack item)throws Exception{return adapter.inspectMagazine(stateKind(item),item.state());}
    public byte[] magazine(szserver.inventory.ItemStack item,int ammo,int rounds)throws Exception{return adapter.magazine(stateKind(item),item.state(),ammo,rounds);}
    public record Jamming(boolean jammed,double duration){}
    public Jamming jamming(szserver.inventory.ItemStack item)throws Exception{return new Jamming(adapter.jammed(stateKind(item),item.state()),adapter.unjammingDuration(item.id()));}
    public szserver.inventory.ItemStack jamming(szserver.inventory.ItemStack item,boolean value)throws Exception{return item.state(adapter.jamming(stateKind(item),item.state(),value));}
    public record Launcher(int factory,UUID identity,int ammo,int rounds,int remembered,List<Integer> ammunition,double reload,double cooldown,byte[] emptyState){
        public Launcher{ammunition=List.copyOf(ammunition);emptyState=emptyState.clone();}
        @Override public byte[] emptyState(){return emptyState.clone();}
    }
    public Launcher inspectLauncher(szserver.inventory.ItemStack item)throws Exception{
        var info=adapter.inspectLauncher(stateKind(item),item.state());if(info==null)return null;
        JsonObject p=definitions.getAsJsonObject(Integer.toString(info.factory()));if(p==null||!p.get("type").getAsString().equals("launcher"))throw new IllegalArgumentException("launcher_factory_type");
        List<Integer> ammunition=new ArrayList<>();for(JsonElement id:p.getAsJsonArray("grenades_id"))ammunition.add(id.getAsInt());
        if(info.rounds()<0||info.rounds()>1||info.rounds()>0&&!ammunition.contains(info.ammo()))throw new IllegalArgumentException("launcher_ammunition_state");
        double reload=number(p,"reload",35)/20.,cooldown=number(p,"cooldown",5)/20.;if(reload<=0||reload>120||cooldown<0||cooldown>120)throw new IllegalArgumentException("launcher_timing");
        return new Launcher(info.factory(),info.identity(),info.ammo(),info.rounds(),info.remembered(),ammunition,reload,cooldown,adapter.launcherMagazine(stateKind(item),item.state(),0,false));
    }
    public byte[] launcherMagazine(szserver.inventory.ItemStack item,int ammo,boolean loaded)throws Exception{return adapter.launcherMagazine(stateKind(item),item.state(),ammo,loaded);}
    public szserver.inventory.ItemStack launcherCharge(szserver.inventory.ItemStack item)throws Exception{return readStack(adapter.launcherCharge(stateKind(item),item.state()));}
    public byte[] launcherMagazine(szserver.inventory.ItemStack item,szserver.inventory.ItemStack charge)throws Exception{
        if(charge.count()!=1)throw new IllegalArgumentException("launcher_charge_count");byte[] wire=szserver.net.InventoryPackets.stack(charge);
        return adapter.launcherMagazine(stateKind(item),item.state(),Arrays.copyOfRange(wire,1,wire.length));
    }
    public void registerStateless(int id)throws Exception{adapter.registerStateless(id);}
    public szserver.inventory.ItemStack readStack(byte[] raw)throws Exception{
        return validateStack(adapter.readStack(raw));
    }
    /** Offline migration boundary; ordinary packets still use the strict decoder. */
    public szserver.inventory.ItemStack migrateLegacyStack(byte[] raw)throws Exception{return validateStack(adapter.migrateLegacyStack(raw));}
    /** Explicit offline repair of a duplicated outer UUID; attachments and all other state stay intact. */
    public szserver.inventory.ItemStack legacyRootIdentity(szserver.inventory.ItemStack item,UUID identity)throws Exception{return item.state(adapter.withIdentity(stateKind(item),item.state(),identity));}
    /** Bound and validate one genuine native stack before the following scene attributes. Worker only. */
    public int stackPrefixLength(byte[] window)throws Exception{int length=adapter.stackPrefixLength(window);readStack(Arrays.copyOf(window,length));return length;}
    public byte[] stackConfig(byte[] raw)throws Exception{return adapter.stackConfig(raw);}
    public record ModuleEdit(szserver.inventory.ItemStack weapon,szserver.inventory.ItemStack removed) {}
    public record SkinEdit(szserver.inventory.ItemStack item,String previous) {}
    public SkinEdit skin(szserver.inventory.ItemStack item,String name)throws Exception{
        JsonObject row=registry.getAsJsonObject(Integer.toString(item.id()));if(!row.has("state"))throw new IllegalArgumentException("skin_target_state");
        var edit=adapter.skin(row.get("state").getAsString(),item.state(),name);return new SkinEdit(item.state(edit.state()),edit.previous());
    }
    public record CosmeticEdit(szserver.inventory.ItemStack item,szserver.inventory.ItemStack removed,boolean unchanged){}
    public CosmeticEdit nativeSkin(szserver.inventory.ItemStack item,szserver.inventory.ItemStack source)throws Exception{
        String kind=stateKind(item);var edit=adapter.nativeSkin(kind,item.state(),item.id(),source==null?0:source.id());return new CosmeticEdit(item.state(edit.state()),edit.previous()==0?null:createStack(edit.previous(),1,0,new JsonObject()),edit.unchanged());
    }
    public ModuleEdit paint(szserver.inventory.ItemStack item,int slot,szserver.inventory.ItemStack source,int selection)throws Exception{
        JsonObject row=registry.getAsJsonObject(Integer.toString(item.id()));if(!row.has("state"))throw new IllegalArgumentException("paint_target_state");
        szserver.inventory.ItemStack paint=source==null&&selection>0?createStack(selection,1,0,new JsonObject()):source;
        byte[] raw=paint==null?null:szserver.net.InventoryPackets.stack(paint.count(1)),target=szserver.net.InventoryPackets.stack(item);var edit=adapter.nativePaint(row.get("state").getAsString(),Arrays.copyOfRange(target,1,target.length),slot,raw==null?null:Arrays.copyOfRange(raw,1,raw.length),source==null&&selection>0);
        return new ModuleEdit(item.state(edit.state()),edit.previous()==null||edit.builtin()?null:readStack(edit.previous()));
    }
    public String artifactResearchTag()throws Exception{return adapter.artifactResearchTag();}
    public ModuleEdit socket(szserver.inventory.ItemStack parent,int slot,szserver.inventory.ItemStack artifact)throws Exception{
        if(parent.count()!=1||artifact!=null&&artifact.count()!=1)throw new IllegalArgumentException("artifact_socket_count");return nativeSlot(parent,artifact,slot,false);
    }
    public ModuleEdit bolt(szserver.inventory.ItemStack parent,szserver.inventory.ItemStack source)throws Exception{if(parent.count()!=1)throw new IllegalArgumentException("bolt_target_count");return nativeSlot(parent,source==null?null:source.count(1),0,true);}
    private ModuleEdit nativeSlot(szserver.inventory.ItemStack parent,szserver.inventory.ItemStack source,int slot,boolean bolt)throws Exception{
        byte[] p=szserver.net.InventoryPackets.stack(parent),a=source==null?null:szserver.net.InventoryPackets.stack(source);var edit=adapter.nativeSlot(stateKind(parent),Arrays.copyOfRange(p,1,p.length),a==null?null:Arrays.copyOfRange(a,1,a.length),slot,bolt);return new ModuleEdit(parent.state(edit.state()),edit.previous()==null?null:readStack(edit.previous()));
    }
    public szserver.inventory.ItemStack initializeBuiltins(szserver.inventory.ItemStack weapon)throws Exception{
        JsonObject definition=definitions.getAsJsonObject(Integer.toString(weapon.id())),row=registry.getAsJsonObject(Integer.toString(weapon.id()));if(row==null||!row.has("state"))return weapon;String kind=row.get("state").getAsString();
        if(!Set.of("1535556086","259689215").contains(kind)||definition.keySet().stream().noneMatch(key->key.startsWith("inbuilt_")))return geometry(weapon);
        byte[] state=adapter.initialModules(weapon.id(),kind,weapon.state());return geometry(Arrays.equals(state,weapon.state())?weapon:weapon.state(state));
    }
    public ClientStateAdapter.ItemSlotRequest itemSlotRequest(boolean bolt,byte[] raw)throws Exception{return adapter.itemSlotRequest(bolt,raw);}
    public ClientStateAdapter.InventoryPoint inventoryPoint(byte[] body)throws Exception{return adapter.inventoryPoint(body);}
    public szserver.inventory.ItemStack moduleCharge(szserver.inventory.ItemStack module)throws Exception{byte[] charge=adapter.moduleCharge(stateKind(module),module.state());return charge==null?null:readStack(charge);}
    public record ModuleBatch(szserver.inventory.ItemStack weapon,List<szserver.inventory.ItemStack> removed){}
    public List<ClientStateAdapter.ModuleInfo> modules(szserver.inventory.ItemStack weapon)throws Exception{return adapter.modules(stateKind(weapon),weapon.state());}
    public ModuleEdit module(szserver.inventory.ItemStack weapon,int slot,szserver.inventory.ItemStack source,int selection)throws Exception{
        var batch=moduleBatch(weapon,slot,source,selection);if(batch.removed().size()>1)throw new IllegalArgumentException("module_multiple_removals");return new ModuleEdit(batch.weapon(),batch.removed().isEmpty()?null:batch.removed().get(0));
    }
    public ModuleBatch moduleBatch(szserver.inventory.ItemStack weapon,int slot,szserver.inventory.ItemStack source,int selection)throws Exception{
        JsonObject definition=registry.getAsJsonObject(Integer.toString(weapon.id()));String kind=definition.get("state").getAsString();if(!Set.of("1535556086","259689215").contains(kind))throw new IllegalArgumentException("module_target_not_firearm");
        szserver.inventory.ItemStack attached=source;
        if(source==null&&selection>0){boolean allowed=false;if(definition.has("builtin_modules"))for(JsonElement id:definition.getAsJsonArray("builtin_modules"))if(id.getAsInt()==selection)allowed=true;if(!allowed)throw new IllegalArgumentException("module_not_builtin");attached=createStack(selection,1,0,new JsonObject());}
        if(attached!=null){JsonObject row=registry.getAsJsonObject(Integer.toString(attached.id()));if(row==null||!row.has("state")||!Set.of("1806321611","342965638","743110253").contains(row.get("state").getAsString()))throw new IllegalArgumentException("attachment_not_weapon_module");attached=attached.count(1);if(source==null)attached=attached.state(adapter.builtinModule(row.get("state").getAsString(),attached.state()));}
        byte[] gunWire=szserver.net.InventoryPackets.stack(weapon),wire=attached==null?null:szserver.net.InventoryPackets.stack(attached);
        var result=adapter.nativeModule(Arrays.copyOfRange(gunWire,1,gunWire.length),kind,slot,wire==null?null:Arrays.copyOfRange(wire,1,wire.length));
        List<szserver.inventory.ItemStack> removed=new ArrayList<>();for(byte[] raw:result.removed())removed.add(readStack(raw));return new ModuleBatch(geometry(weapon.state(result.state())),List.copyOf(removed));
    }
    public szserver.inventory.ItemStack createStack(int id,int count,int metadata,JsonObject tags)throws Exception{
        byte[] state=create(id,new JsonObject());var original=new szserver.inventory.ItemStack(id,count,metadata,state);if(tags.isEmpty())return geometry(original);
        byte[] wire=szserver.net.InventoryPackets.stack(original);return validateStack(adapter.applyTags(Arrays.copyOfRange(wire,1,wire.length),NativeTypedNbt.encode(tags)));
    }
    public szserver.inventory.ItemStack readEditedStack(byte[] raw)throws Exception{return validateStack(adapter.readEditedStack(raw));}
    public szserver.inventory.ItemStack readConfig(byte[] raw)throws Exception{return validateStack(adapter.readConfig(raw));}
    /** Granting a saved configuration creates fresh outer and attached identities. Editing does not. */
    public szserver.inventory.ItemStack copyForGrant(szserver.inventory.ItemStack template)throws Exception{
        if(!template.stateful())return template;
        return template.state(adapter.freshTreeIdentity(stateKind(template),template.state()));
    }
    private szserver.inventory.ItemStack validateStack(ClientStateAdapter.Stack decoded)throws Exception{
        JsonElement definition=registry.get(Integer.toString(decoded.id()));if(definition==null)throw new IllegalArgumentException("unknown_item_id");JsonObject row=definition.getAsJsonObject();
        String expected=row.has("state")?row.get("state").getAsString():null;
        if(!Objects.equals(expected,decoded.kind()))throw new IllegalArgumentException("item_state_factory_mismatch");
        if(expected!=null&&!verified.contains(expected))throw new IllegalArgumentException("state_codec_not_verified");
        return geometry(new szserver.inventory.ItemStack(decoded.id(),decoded.count(),decoded.metadata(),decoded.state(),decoded.properties(),decoded.equipmentMask()));
    }
    public szserver.inventory.ItemStack geometry(szserver.inventory.ItemStack item)throws Exception{
        if(definitions==null||!definitions.has(Integer.toString(item.id())))return item;
        JsonObject definition=definitions.getAsJsonObject(Integer.toString(item.id()));
        if(!definition.get("type").getAsString().equals("weapon"))return item;
        String kind=stateKind(item);if(!Set.of("1535556086","259689215").contains(kind))return item;
        byte[] wire=szserver.net.InventoryPackets.stack(item);int[] size=adapter.weaponGeometry(Arrays.copyOfRange(wire,1,wire.length));return item.geometry(size[0],size[1]);
    }
    public int stackLimit(int id){
        JsonElement row=registry.get(Integer.toString(id));if(row==null)throw new IllegalArgumentException("unknown_item_id");
        return row.getAsJsonObject().has("state")?1:64;
    }
    public byte[] create(int id,JsonObject tags)throws Exception{
        JsonElement definition=registry.get(Integer.toString(id));
        if(definition==null)throw new IllegalArgumentException("unknown_item_id");
        JsonObject row=definition.getAsJsonObject();
        if(!row.has("state")){if(tags.size()!=0)throw new IllegalArgumentException("item_has_no_extended_state");return new byte[0];}
        String kind=row.get("state").getAsString();
        if(!verified.contains(kind))throw new IllegalArgumentException("state_codec_not_verified:"+kind);
        if(!row.has("default"))throw new IllegalArgumentException("item_default_missing");
        byte[] state=HexFormat.of().parseHex(row.get("default").getAsString());
        // Never give multiple items the factory fixture's UUID.
        if(definitions!=null&&Set.of("1535556086","259689215").contains(kind)&&definitions.has(Integer.toString(id))&&definitions.getAsJsonObject(Integer.toString(id)).keySet().stream().anyMatch(key->key.startsWith("inbuilt_")))state=adapter.initialModules(id,kind,state);
        state=adapter.freshTreeIdentity(kind,state);
        if(!tags.isEmpty())return createStack(id,1,0,tags).state();
        if(!Arrays.equals(state,adapter.roundTrip(kind,state)))throw new IllegalStateException("native_codec_roundtrip_failed");
        return state;
    }
    public ClientStateAdapter.CorpseView verifyCorpseSnapshot(byte[] body)throws Exception{return adapter.verifyCorpseSnapshot(body);}
    public void verifyInventorySnapshot(byte[] body)throws Exception{adapter.verifyInventorySnapshot(body);}
    public void verifyInventoryPoint(byte[] body)throws Exception{adapter.verifyInventoryPoint(body);}
    public void verifyPlayerFrame(String kind,byte[] body)throws Exception{adapter.verifyPlayerFrame(kind,body);}
    @Override public void close()throws Exception{adapter.close();}
}
