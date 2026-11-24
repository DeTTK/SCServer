package szserver.client;

import com.google.gson.*;
import szserver.inventory.ItemCatalog;
import szserver.inventory.ItemStack;
import szserver.util.GameResources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class NativeArtifactResourceChecks {
    private static final int CRYSTAL=11000, MULTISET=11438, KZS1=12000, NEW_ARTIFACT=31994, NEW_CONTAINER=31995;
    private static final String SETTINGS="assets/stalker/artefacts.json";
    private static final String BLOCKLIST="assets/bundle/artefact_properties_blocklist.json";
    private static final String STAMINA="stamina_regeneration_bonus";

    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),runtime=Path.of(args[1]),resources=root.resolve("resources");
        Path configs=resources.resolve("configs"),archive=resources.resolve("configs.xeon");
        Path overrides=resources.resolve("overrides/configs");
        GameResources.mount(configs,archive,overrides);
        GameResources.mount(resources.resolve("boxes"),resources.resolve("boxes.xeon"),resources.resolve("overrides/boxes"));
        Path definitions=root.resolve("fixtures/item-definitions.json");
        Path settings=root.resolve("fixtures/artifact-settings.json");
        Path registry=root.resolve("registries/client-current-item-states.json");
        float stockSetBonus;
        float sourceFactor=0;
        try(var items=new GameItems(runtime,registry)){
            items.warmGameplay(definitions,settings);
            var ordinary=items.createStack(CRYSTAL,1,0,new JsonObject());
            var empty=items.createStack(KZS1,1,0,new JsonObject());
            denied(()->items.socket(empty,0,ordinary),"artifact_not_researched");
            var crystal=researched(items,CRYSTAL);
            denied(()->items.socket(empty,1,crystal),"artifact_socket_capacity");

            var nativeStats=items.artifactStats(crystal);
            check(items.equipmentSummary(List.of(crystal)).artifacts().equals(nativeStats),"directly equipped artifact bank");
            var inserted=items.socket(empty,0,crystal);
            var effective=items.equipmentSummary(List.of(inserted.weapon())).artifacts();
            close(effective.get(STAMINA),nativeStats.get(STAMINA)*1.5f,"native effectiveness");
            close(effective.get("psycho_accumulation"),nativeStats.get("psycho_accumulation"),"native blocklist");

            var detached=items.socket(inserted.weapon(),0,null);
            check(detached.removed().id()==crystal.id()&&Arrays.equals(detached.removed().state(),crystal.state()),
                    "socket round trip preserves artifact state");
            check(items.equipmentSummary(List.of(detached.weapon())).artifacts().isEmpty(),"socket removal clears stats");
            var multiset=researched(items,MULTISET,"HEALTH_BONUS");
            stockSetBonus=items.artifactStats(multiset).get("health_bonus");
            check(items.socket(items.socket(empty,0,multiset).weapon(),0,null).removed().id()==MULTISET,
                    "event artifact uses the same native state and socket");
        }

        Path temp=Files.createTempDirectory("artifact-resource-check");
        try{
            if(Files.isDirectory(overrides)){
                try(var paths=Files.walk(overrides)){
                    for(Path source:paths.filter(Files::isRegularFile).toList()){
                        Path target=temp.resolve(overrides.relativize(source));
                        Files.createDirectories(target.getParent());
                        Files.copy(source,target);
                    }
                }
            }
            var artifact=derived(NEW_ARTIFACT,CRYSTAL,"artefakt","qa.archive_artifact");
            artifact.addProperty(STAMINA,6);
            var container=derived(NEW_CONTAINER,KZS1,"backpack","qa.archive_container");
            container.addProperty("artefakt_slots",2);
            container.addProperty("effectiveness",.75);
            container.addProperty("slot_size","3x2");
            write(temp,"assets/stalker/items/qa_archive_artifact.json",artifact);
            write(temp,"assets/stalker/items/qa_archive_container.json",container);
            var global=JsonParser.parseString(GameResources.readString(configs.resolve(SETTINGS))).getAsJsonObject();
            for(var group:global.getAsJsonArray("groups")){
                var row=group.getAsJsonObject();
                if(row.get("group").getAsString().equals("ravent_multiset")){
                    var factors=row.getAsJsonObject("factors");
                    sourceFactor=factors.get("health_bonus").getAsFloat();
                    factors.addProperty("health_bonus",sourceFactor*2);
                }
            }
            check(sourceFactor>0,"archive artifact factor");
            write(temp,SETTINGS,global);
            var blocklist=JsonParser.parseString(GameResources.readString(configs.resolve(BLOCKLIST))).getAsJsonObject();
            blocklist.getAsJsonArray("efficiency_bonus_blocklist").add(STAMINA);
            write(temp,BLOCKLIST,blocklist);
            GameResources.mount(configs,archive,temp);
            try(var items=new GameItems(runtime,registry)){
                items.warmGameplay(definitions,settings);
                var crystal=researched(items,CRYSTAL);
                var multiset=researched(items,MULTISET,"HEALTH_BONUS");
                close(items.artifactStats(multiset).get("health_bonus"),stockSetBonus*2,"native global group override");
                var added=researched(items,NEW_ARTIFACT);
                var addedStats=items.artifactStats(added);
                close(addedStats.get(STAMINA),items.artifactStats(crystal).get(STAMINA)*6/1.7f,"inherited custom artifact");
                var bag=items.createStack(NEW_CONTAINER,1,0,new JsonObject());
                var bagRow=new ItemCatalog(definitions).get(NEW_CONTAINER);
                check(bagRow.width()==3&&bagRow.height()==2&&bagRow.stackLimit()==1,"new container geometry");
                var installed=items.socket(bag,1,added).weapon();
                var combined=items.equipmentSummary(List.of(installed)).artifacts();
                close(combined.get(STAMINA),addedStats.get(STAMINA),"archive blocklist override");
                close(combined.get("psycho_protection"),addedStats.get("psycho_protection")*.75f,"archive effectiveness override");
                check(Arrays.equals(items.socket(installed,1,null).removed().state(),added.state()),"custom artifact survives socket codec");
            }
        }finally{
            GameResources.mount(configs,archive,overrides);
            try(var files=Files.walk(temp)){
                for(Path entry:files.sorted(Comparator.reverseOrder()).toList())Files.delete(entry);
            }
        }
        System.out.println("PASS native artifact configuration, socket restrictions and round trips, event artifact, custom artifact and container");
    }

    private static JsonObject derived(int id,int parent,String kind,String name){
        var row=new JsonObject();
        row.addProperty("type",kind);
        row.addProperty("item_id",id);
        row.addProperty("extends_item",parent);
        row.addProperty("unlocalized_name",name);
        return row;
    }

    private static ItemStack researched(GameItems items,int id,String... bonuses)throws Exception{
        var value=new JsonObject();
        value.addProperty("type","float");
        value.addProperty("value",.5f);
        var tags=new JsonObject();
        tags.add(items.artifactResearchTag(),value);
        if(bonuses.length>0){
            var selected=new JsonArray();
            for(String bonus:bonuses){
                var tag=new JsonObject();
                tag.addProperty("type","string");
                tag.addProperty("value",bonus);
                selected.add(tag);
            }
            var list=new JsonObject();
            list.addProperty("type","list");
            list.add("value",selected);
            tags.add("bonus_properties",list);
        }
        return items.createStack(id,1,0,tags);
    }

    private static void write(Path root,String name,JsonObject row)throws IOException{
        Path target=root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target,row.toString());
    }

    private interface Attempt{void run()throws Exception;}
    private static void denied(Attempt attempt,String reason)throws Exception{
        try{
            attempt.run();
            throw new AssertionError("expected "+reason);
        }catch(IOException failure){
            if(!reason.equals(failure.getMessage()))throw failure;
        }
    }
    private static void close(float value,float expected,String reason){
        check(Math.abs(value-expected)<.001f,reason+": "+value+" expected "+expected);
    }
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }
}
