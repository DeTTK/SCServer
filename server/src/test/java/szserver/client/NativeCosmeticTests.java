package szserver.client;
import com.google.gson.*;import java.nio.file.*;import java.util.*;import szserver.inventory.*;
import szserver.util.GameResources;

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
        }
        System.out.println("NativeCosmeticTests: "+checks+" checks passed");
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
