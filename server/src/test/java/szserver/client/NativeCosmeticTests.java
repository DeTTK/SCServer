package szserver.client;
import com.google.gson.*;import java.nio.file.*;import java.util.*;import szserver.inventory.*;
import szserver.util.GameResources;
import szserver.net.GameInventory;
import java.util.concurrent.CompletableFuture;

/** Optional integration suite: requires the same private runtime and fixtures as the server. */
public final class NativeCosmeticTests {
    private static int checks;private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private interface Attempt{void run()throws Exception;}
    private static void denied(Attempt action,String reason)throws Exception{try{action.run();throw new AssertionError(reason);}catch(java.io.IOException|IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("runtime and server-resource-root required");Path root=Path.of(args[1]);
        Path resources=root.resolve("resources");
        for(String name:List.of("configs","boxes")){
            Path archive=resources.resolve(name+".xeon");
            if(Files.isRegularFile(archive))GameResources.mount(resources.resolve(name),archive,resources.resolve("overrides").resolve(name));
        }
        ModuleJournalSeed journalSeed=null;
        List<EditorSeed> editorSeeds=new ArrayList<>();
        try(var items=new GameItems(Path.of(args[0]),root.resolve("registries/client-current-item-states.json"))){items.warmGameplay(root.resolve("fixtures/item-definitions.json"),root.resolve("fixtures/artifact-settings.json"));
            // These are regression fixtures, never compatibility tables: every decision is made by native factories.
            for(int[] pair:new int[][]{{20866,13200},{20000,13200},{25000,13600}}){
                ItemStack original=items.createStack(pair[0],1,7,new JsonObject()),source=items.createStack(pair[1],3,0,new JsonObject());byte[] before=original.state(),token=source.state();
                var applied=items.nativeSkin(original,source);check(!applied.unchanged(),"native skin applied");check(items.identity(original).equals(items.identity(applied.item())),"root UUID preserved");check(applied.item().metadata()==7&&Arrays.equals(applied.item().properties(),original.properties()),"metadata and item properties preserved");
                check(items.nativeSkin(applied.item(),source).unchanged(),"same native color is a no-op");var removed=items.nativeSkin(applied.item(),null);check(removed.removed().id()==pair[1]&&removed.removed().count()==1,"native inverse lookup returns one color");check(Arrays.equals(before,removed.item().state()),"native color round trip bytes");check(Arrays.equals(token,source.state())&&source.count()==3,"source snapshot immutable");
            }
            for(int[] pair:new int[][]{{20296,17579,0},{25161,17100,0},{25354,19629,1},{12061,18836,0},{25138,19594,1},{25077,19063,1}}){
                ItemStack original=items.createStack(pair[0],1,7,new JsonObject()),source=items.createStack(pair[1],2,0,new JsonObject());byte[] before=original.state(),token=source.state();var applied=items.paint(original,pair[2],source,0);var removed=items.paint(applied.weapon(),pair[2],null,0);
                check(items.identity(original).equals(items.identity(applied.weapon())),"paint preserves root identity");check(applied.weapon().metadata()==7&&Arrays.equals(applied.weapon().properties(),original.properties()),"paint preserves metadata/properties");check(removed.removed().id()==pair[1]&&removed.removed().count()==1,"native slot returns original token");check(Arrays.equals(before,removed.weapon().state()),"native paint slot round trip bytes");check(source.count()==2&&Arrays.equals(token,source.state()),"paint source immutable");
            }
            ItemStack gun=items.createStack(20866,1,0,new JsonObject()),armorColor=items.createStack(13600,1,0,new JsonObject()),container=items.createStack(12061,1,0,new JsonObject()),containerMotif=items.createStack(18836,1,0,new JsonObject());
            denied(()->items.nativeSkin(gun,armorColor),"original factory rejects incompatible color");denied(()->items.paint(container,1,containerMotif,0),"original kind rejects backpack motif in wrong slot");denied(()->items.paint(gun,2,null,0),"invalid slot rejected before mutation");
            verifySightMount(items);
            if(GameResources.mounted(resources.resolve("configs"))){
                verifySelectableAttachments(items);
                verifyWeaponModule(items);
                verifyStyledReloadProfile(items);
                verifySavedRailAndSpark(items);
                verifyStyledSidearms(items);
                for(int id:new int[]{11000,25078,20175})editorSeeds.add(editorSeed(items,id));
                var seedGun=personalWeapon(items,20051);
                var firearm=items.createStack(20004,1,7,new JsonObject());
                int ammo=items.weaponMagazineRules(firearm).ammunition().get(0);
                var loaded=firearm.state(items.magazine(firearm,ammo,12));
                var unload=items.unloadWeapon(loaded);
                check(unload.ammo().id()==ammo&&unload.ammo().count()==12,"unload returns the original ammunition");
                check(items.inspectMagazine(unload.weapon()).rounds()==0,"native magazine is empty");
                check(items.identity(unload.weapon()).equals(items.identity(loaded))&&unload.weapon().metadata()==7,
                        "unloading keeps the weapon identity and metadata");
                journalSeed=new ModuleJournalSeed(seedGun,rolledModule(items,11701),items.weaponProfile(seedGun).damage(),loaded,unload.ammo());
            }
        }
        if(journalSeed!=null){
            Path runtime=Path.of(args[0]);
            verifyUnloadPacket(runtime);
            verifyWeaponModuleJournal(journalSeed,editorSeeds,runtime,root);
        }
        System.out.println("NativeCosmeticTests: "+checks+" checks passed");
    }

    static ItemStack rolledModule(GameItems items,int factory)throws Exception{
        var tags=JsonParser.parseString("""
                {"attributes":{"type":"list","value":[
                    {"type":"compound","value":{"type":{"type":"integer","value":0},
                     "definitionId":{"type":"string","value":"shooting_wear"},
                     "statsRandom":{"type":"float","value":1},
                     "quality":{"type":"integer","value":0}}}
                ]}}
                """).getAsJsonObject();
        return items.createStack(factory,1,0,tags);
    }

    static ItemStack personalWeapon(GameItems items,int factory)throws Exception{
        var tags=JsonParser.parseString("""
                {"owner":{"type":"string","value":"ModuleQA"}}
                """).getAsJsonObject();
        return items.createStack(factory,1,0,tags);
    }

    private static void verifyStyledReloadProfile(GameItems items)throws Exception{
        var gun=items.createStack(20175,1,0,new JsonObject());
        var style=items.createStack(17554,1,0,new JsonObject());
        var styled=items.paint(gun,0,style,0).weapon();
        var profile=items.weaponProfile(styled);
        check(profile!=null&&profile.capacity()==100,"styled PKP retains its native magazine");
        check(profile.reloadTiming(0,profile.capacity()).complete()>0,"styled PKP can reload headlessly");
        var raw=szserver.net.InventoryPackets.stack(styled);
        var restored=items.readStack(Arrays.copyOfRange(raw,1,raw.length));
        check(items.weaponProfile(restored).equals(profile),"rejoining with a saved styled PKP retains its native profile");
    }

    private static void verifyStyledSidearms(GameItems items)throws Exception{
        // Deagle XIX, Golden and Modified use the same native style family.
        for(int gunId:new int[]{20477,20485,20839})for(int styleId:new int[]{17518,17533,17559}){
            var gun=items.initializeBuiltins(items.createStack(gunId,1,0,new JsonObject()));
            var style=items.createStack(styleId,1,0,new JsonObject());
            var styled=items.paint(gun,0,style,0).weapon();
            String combination=gunId+"/"+styleId;
            var profile=items.weaponProfile(styled);
            check(profile.capacity()==items.weaponProfile(gun).capacity(),"styled Deagle magazine "+combination);
            var raw=szserver.net.InventoryPackets.stack(styled);
            var restored=items.readStack(Arrays.copyOfRange(raw,1,raw.length));
            check(items.weaponProfile(restored).equals(profile),"saved styled Deagle profile "+combination);
        }
    }

    private static byte[] stackBytes(ItemStack item){
        byte[] wire=szserver.net.InventoryPackets.stack(item);
        return Arrays.copyOfRange(wire,1,wire.length);
    }

    private static void verifySavedRailAndSpark(GameItems items)throws Exception{
        for(int id:new int[]{20155,20175}){
            for(boolean styled:new boolean[]{false,true}){
                if(styled&&id!=20175)continue;
                ItemStack gun=items.createStack(id,1,0,new JsonObject());
                if(styled)gun=items.paint(gun,0,items.createStack(17554,1,0,new JsonObject()),0).weapon();
                ItemStack rail=items.createStack(22903,1,0,new JsonObject());
                ItemStack attached=items.moduleBatch(gun,11,rail,0).weapon();
                ItemStack restored=items.readStack(stackBytes(attached));
                check(items.modules(restored).stream().anyMatch(m->m.slot()==11&&m.item()==rail.id()),
                        "recovered PKP retains the native rail");
                check(items.weaponProfile(restored).equals(items.weaponProfile(attached)),
                        "recovered PKP rail and style resolve the original weapon profile");
            }
        }
        ItemStack gauss=items.createStack(20580,1,0,new JsonObject());
        ItemStack spark=items.createStack(17561,1,0,new JsonObject());
        ItemStack styled=items.paint(gauss,0,spark,0).weapon();
        ItemStack restored=items.readStack(stackBytes(styled));
        check(items.weaponProfile(restored).equals(items.weaponProfile(styled)),"saved Spark resolves the equipped profile");
        check(items.paint(restored,0,null,0).removed().id()==spark.id(),"saved Gauss retains the Spark style");
    }

    private record EditorSeed(ItemStack original,ItemStack other,byte[] config,byte[] wire){}
    private static EditorSeed editorSeed(GameItems items,int id)throws Exception{
        ItemStack original=items.createStack(id,1,0,new JsonObject());
        ItemStack other=items.copyForGrant(original);
        ItemStack edited=items.preserveIdentity(original,items.createStack(id,1,7,new JsonObject()));
        byte[] wire=stackBytes(edited);
        return new EditorSeed(original,other,items.stackConfig(wire),wire);
    }

    private static void verifyMovedEditor(GameInventory inventory,EditorSeed seed)throws Exception{
        var character=UUID.randomUUID();
        var opening=new Inventory.Address(1,0,0);
        var moved=new Inventory.Address(1,0,56);
        var initial=new CompletableFuture<Inventory.Plan>();
        inventory.mutate(character,before->before.replace(Map.of(opening,seed.original(),moved,seed.other()),0),null,
                initial::complete,error->initial.completeExceptionally(new AssertionError(error)));
        await(inventory,initial);
        var move=new CompletableFuture<Inventory.Plan>();
        inventory.mutate(character,before->before.move(opening,moved,0,false),null,
                move::complete,error->move.completeExceptionally(new AssertionError(error)));
        await(inventory,move);
        for(boolean config:new boolean[]{true,false}){
            var result=new CompletableFuture<Inventory.Plan>();
            inventory.edit(character,opening,config?seed.config():seed.wire(),config,
                    result::complete,error->result.completeExceptionally(new AssertionError(error)));
            var plan=await(inventory,result);
            check(plan.changes().keySet().equals(Set.of(moved)),"editor follows the moved item for "+seed.original().id());
            check(plan.after().at(moved).metadata()==7,"edited state reaches the moved item");
            check(Arrays.equals(stackBytes(plan.after().at(opening)),stackBytes(seed.other())),
                    "editor leaves the same-type stack in the old cell alone");
        }
    }

    private static void verifyWeaponModule(GameItems items)throws Exception{
        var gun=personalWeapon(items,20051);
        var modifier=rolledModule(items,11701);
        denied(()->items.weaponModule(items.createStack(20051,1,0,new JsonObject()),modifier),
                "unpersonalized weapon has a locked module slot");
        var stock=items.weaponProfile(gun);
        var equipped=items.weaponModule(gun,modifier).weapon();
        var changed=items.weaponProfile(equipped);
        check(changed.damage()>stock.damage()&&changed.rpm()<stock.rpm(),"native module damage and fire rate");
        check(items.identity(gun).equals(items.identity(equipped)),"special module preserves gun identity");
        var raw=szserver.net.InventoryPackets.stack(equipped);
        var restored=items.readStack(Arrays.copyOfRange(raw,1,raw.length));
        check(items.weaponProfile(restored).equals(changed),"special module persists through genuine weapon codec");
        var replacement=rolledModule(items,11701);
        var swap=items.weaponModule(restored,replacement);
        check(swap.removed().size()==1&&items.identity(swap.removed().get(0)).equals(items.identity(modifier)),
                "replacing a module returns the previous item");
        check(!items.identity(replacement).equals(items.identity(modifier)),"separate module identities");

        var withScope=items.moduleBatch(equipped,11,items.createStack(22919,1,0,new JsonObject()),0).weapon();
        check(items.weaponProfile(withScope).damage()==changed.damage(),"ordinary attachment preserves special module");
        var removed=items.weaponModule(withScope,null);
        check(removed.removed().size()==1&&Arrays.equals(removed.removed().get(0).state(),modifier.state()),"same module returned");
        check(items.weaponProfile(removed.weapon()).damage()==stock.damage(),"removal restores stock damage");
        var incompatible=items.createStack(21603,1,0,new JsonObject());
        denied(()->items.weaponModule(personalWeapon(items,20000),incompatible),"native category rejects SMG");
        denied(()->items.weaponModule(personalWeapon(items,20898),modifier),"disabled slot rejected");
    }

    private static void verifyUnloadPacket(Path runtime)throws Exception{
        var packets=new ClientPackets(runtime);
        byte[] raw={-1,-1,0,24,1,0,0,0,10};
        check(Arrays.equals(packets.roundTrip(szserver.net.InventoryPackets.WEAPON_UNLOAD_REQUEST,raw),raw),
                "unload framing matches the native context-menu packet");
        var request=szserver.net.InventoryPackets.weaponUnload(raw,3);
        check(request.owner()==-1&&request.sequence()==24&&request.address().equals(new Inventory.Address(2,3,10)),
                "unload resolves the currently visible storage page");
    }

    private record ModuleJournalSeed(ItemStack gun,ItemStack module,double damage,ItemStack loaded,ItemStack ammo){}
    private static void verifyUnloadJournal(GameInventory inventory,ModuleJournalSeed seed)throws Exception{
        for(var address:List.of(new Inventory.Address(0,6,0),new Inventory.Address(1,0,8),new Inventory.Address(2,1,0))){
            var character=UUID.randomUUID();
            var initial=new CompletableFuture<Inventory.Plan>();
            inventory.mutate(character,before->before.replace(Map.of(address,seed.loaded()),0),null,
                    initial::complete,error->initial.completeExceptionally(new AssertionError(error)));
            var before=await(inventory,initial).after();
            var result=new CompletableFuture<Inventory.Plan>();
            inventory.unloadWeapon(character,address,result::complete,error->result.completeExceptionally(new AssertionError(error)));
            var plan=await(inventory,result);
            check(plan.after().revision()==before.revision()+1&&plan.moved()==seed.ammo().count(),"one durable unload commit");
            check(plan.after().at(address).id()==seed.loaded().id(),"weapon remains in its original cell");
            check(plan.after().items().entrySet().stream().anyMatch(e->e.getKey().aggregate()==1&&
                    e.getValue().id()==seed.ammo().id()&&e.getValue().count()==seed.ammo().count()),"ammo goes into the backpack");
            var empty=new CompletableFuture<String>();
            inventory.unloadWeapon(character,address,ignored->empty.completeExceptionally(new AssertionError("already unloaded")),
                    empty::complete);
            check(await(inventory,empty).contains("нет патронов"),"repeated unload cannot return more ammo");
        }
        var character=UUID.randomUUID();
        var weapon=new Inventory.Address(0,6,0);
        var contents=new LinkedHashMap<Inventory.Address,ItemStack>();
        contents.put(weapon,seed.loaded());
        var fullStack=seed.ammo().count(inventory.catalog().limit(seed.ammo()));
        for(int i=0;i<StorageLayout.BAG;i++)contents.put(new Inventory.Address(1,0,i),fullStack);
        var initial=new CompletableFuture<Inventory.Plan>();
        inventory.mutate(character,before->before.replace(contents,0),null,
                initial::complete,error->initial.completeExceptionally(new AssertionError(error)));
        var before=await(inventory,initial).after();
        var full=new CompletableFuture<String>();
        inventory.unloadWeapon(character,weapon,ignored->full.completeExceptionally(new AssertionError("bag full")),full::complete);
        check(await(inventory,full).contains("нет места")&&inventory.inventory(character)==before,
                "full backpack leaves the loaded weapon and all ammunition intact");
    }
    private static void verifyWeaponModuleJournal(ModuleJournalSeed seed,List<EditorSeed> editorSeeds,Path runtime,Path root)throws Exception{
        Path data=Files.createTempDirectory("native-weapon-module-journal");
        Path registry=root.resolve("registries/client-current-item-states.json");
        var id=UUID.randomUUID();
        var weapon=new Inventory.Address(0,6,0);
        var source=new Inventory.Address(1,0,0);
        try{
            try(var inventory=new GameInventory(root,data,runtime,registry)){
                for(EditorSeed editorSeed:editorSeeds)verifyMovedEditor(inventory,editorSeed);
                verifyUnloadJournal(inventory,seed);
                var initial=new CompletableFuture<Inventory.Plan>();
                inventory.mutate(id,before->before.replace(Map.of(weapon,seed.gun(),source,seed.module()),0),null,
                        initial::complete,error->initial.completeExceptionally(new AssertionError(error)));
                await(inventory,initial);
                var install=new CompletableFuture<Inventory.Plan>();
                inventory.weaponModule(id,weapon,source,install::complete,
                        error->install.completeExceptionally(new AssertionError(error)));
                check(await(inventory,install).after().at(source)==null,"module is debited in the weapon update");
            }
            try(var recovered=new GameInventory(root,data,runtime,registry)){
                var profile=new CompletableFuture<GameInventory.Weapon>();
                recovered.weapon(id,6,profile::complete,error->profile.completeExceptionally(new AssertionError(error)));
                check(await(recovered,profile).profile().damage()>seed.damage(),
                        "owner weapon refresh uses persisted bonus after restart");
                var remove=new CompletableFuture<Inventory.Plan>();
                recovered.weaponModule(id,weapon,null,remove::complete,error->remove.completeExceptionally(new AssertionError(error)));
                check(Arrays.equals(await(recovered,remove).after().at(source).state(),seed.module().state()),
                        "durable removal returns the original module");
            }
        }finally{
            try(var paths=Files.walk(data)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }

    private static <T> T await(GameInventory inventory,CompletableFuture<T> result)throws Exception{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!result.isDone()){
            inventory.drain();
            if(System.nanoTime()>deadline)throw new AssertionError("weapon module journal timeout");
            Thread.sleep(2);
        }
        return result.get();
    }

    private static void verifySightMount(GameItems items)throws Exception{
        int sightSlot=11,sightOnMountSlot=12,magazineSlot=13;
        var gun=items.initializeBuiltins(items.createStack(20051,1,0,new JsonObject()));
        var riser=items.createStack(22919,1,0,new JsonObject());
        var scope=items.createStack(22206,1,0,new JsonObject());
        var magazine=items.createStack(22700,1,0,new JsonObject());
        var color=items.createStack(13200,1,0,new JsonObject());
        denied(()->items.moduleBatch(gun,sightOnMountSlot,scope,0),"dependent sight requires its mount");
        var raised=items.moduleBatch(gun,sightSlot,riser,0).weapon();
        var equipped=items.moduleBatch(raised,sightOnMountSlot,scope,0).weapon();
        var modules=items.modules(equipped);
        check(modules.stream().anyMatch(m->m.slot()==sightSlot&&m.item()==riser.id())
                &&modules.stream().anyMatch(m->m.slot()==sightOnMountSlot&&m.item()==scope.id()),"both native slots retained");
        var appearance=java.nio.ByteBuffer.wrap(items.appearance(equipped,null));
        appearance.position(7);
        int styleBytes=appearance.get();
        if(styleBytes>=0)appearance.position(appearance.position()+styleBytes);
        int count=Byte.toUnsignedInt(appearance.get()),mountIndex=-1,scopeIndex=-1;
        for(int index=0;index<count;index++){
            int slot=appearance.get(),item=Short.toUnsignedInt(appearance.getShort());
            if(slot==sightSlot&&item==riser.id())mountIndex=index;
            if(slot==sightOnMountSlot&&item==scope.id())scopeIndex=index;
        }
        check(mountIndex>=0&&scopeIndex>mountIndex,"appearance installs parent rail before its optic");
        var detached=items.moduleBatch(equipped,sightSlot,null,0);
        check(detached.removed().size()==2&&detached.removed().stream().map(ItemStack::id)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(riser.id(),scope.id())),"mount removal returns dependent sight");
        var loaded=items.moduleBatch(equipped,magazineSlot,magazine,0).weapon();
        var stockProfile=items.weaponProfile(equipped);
        var changedProfile=items.weaponProfile(loaded);
        check(stockProfile.capacity()==30&&changedProfile.capacity()==20,"native magazine capacity");
        check(stockProfile.reload()!=changedProfile.reload(),"native magazine reload duration");
        var painted=items.nativeSkin(loaded,color);
        check(!painted.unchanged()&&items.modules(painted.item()).equals(items.modules(loaded)),"color keeps attached modules");
    }

    private static void verifySelectableAttachments(GameItems items)throws Exception{
        int sight=11,magazine=13;
        var stock=items.createStack(9575,1,0,new JsonObject());
        var mounted=items.moduleBatch(stock,sight,null,22201);
        check(mounted.removed().isEmpty(),"free preset consumes no bag item");
        check(items.modules(mounted.weapon()).stream().anyMatch(m->m.slot()==sight&&m.item()==22201&&!m.builtin()&&!m.locked()),
                "selectable optic is serialized as a removable default");
        var wire=szserver.net.InventoryPackets.stack(mounted.weapon());
        var restored=items.initializeBuiltins(items.readStack(Arrays.copyOfRange(wire,1,wire.length)));
        var alternate=items.moduleBatch(restored,sight,null,22308);
        check(alternate.removed().isEmpty(),"switching presets grants no free attachment");
        check(items.modules(alternate.weapon()).stream().anyMatch(m->m.slot()==sight&&m.item()==22308),
                "saved selectable optic can be switched");
        var detached=items.moduleBatch(alternate.weapon(),sight,null,0);
        check(detached.removed().isEmpty()&&items.modules(detached.weapon()).stream().noneMatch(m->m.slot()==sight),
                "removing a selectable default never returns it to inventory");
        denied(()->items.moduleBatch(detached.weapon(),magazine,null,0),"permanent stock magazine remains fixed");
        var purchased=items.createStack(22200,1,0,new JsonObject());
        var swapped=items.moduleBatch(mounted.weapon(),sight,purchased,0);
        check(swapped.removed().isEmpty(),"purchased optic replaces preset without credit");
        var restoredPreset=items.moduleBatch(swapped.weapon(),sight,null,22201);
        check(restoredPreset.removed().size()==1&&restoredPreset.removed().get(0).id()==22200,
                "replacing a purchased optic with a preset returns the real optic");
    }
}
