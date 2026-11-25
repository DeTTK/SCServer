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
                verifyWeaponModule(items);
                verifyStyledReloadProfile(items);
                verifyStyledSidearms(items);
                var seedGun=personalWeapon(items,20051);
                journalSeed=new ModuleJournalSeed(seedGun,rolledModule(items,11701),items.weaponProfile(seedGun).damage());
            }
        }
        if(journalSeed!=null)verifyWeaponModuleJournal(journalSeed,Path.of(args[0]),root);
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

    private record ModuleJournalSeed(ItemStack gun,ItemStack module,double damage){}
    private static void verifyWeaponModuleJournal(ModuleJournalSeed seed,Path runtime,Path root)throws Exception{
        Path data=Files.createTempDirectory("native-weapon-module-journal");
        Path registry=root.resolve("registries/client-current-item-states.json");
        var id=UUID.randomUUID();
        var weapon=new Inventory.Address(0,6,0);
        var source=new Inventory.Address(1,0,0);
        try{
            try(var inventory=new GameInventory(root,data,runtime,registry)){
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
}
