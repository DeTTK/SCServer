package szserver.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import szserver.util.Json;
import szserver.persistence.*;
import szserver.inventory.*;

/** CLI layout and recovery guarantees, with small opaque payloads instead of game assets. */
public final class PortableWorldTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Io {void run()throws Exception;}
    private static void fails(Io action)throws Exception{try{action.run();}catch(IOException expected){return;}throw new AssertionError("expected storage failure");}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toRealPath(),map=root.resolve("template"),a=root.resolve("one"),b=root.resolve("two");
        Files.createDirectories(map.resolve("region"));Files.write(map.resolve("region/r.0.0.szr"),new byte[]{1,2,3});
        Files.writeString(map.resolve("registry.json"),"{}");Path codec=root.resolve("fallback.json");Files.writeString(codec,"{}");
        for(Path dest:List.of(a,b))PortableWorld.main(new String[]{"create",dest.toString(),map.toString(),codec.toString()});
        Files.move(map,root.resolve("disconnected"));
        var config=new Properties();config.setProperty("world.directory","one");PortableWorld.configure(config,root);
        check(config.getProperty("world.regions").equals(a.resolve("Map").toString())&&config.getProperty("world.resident.saveSeconds").equals("300"),"owned paths and interval");
        Files.writeString(b.resolve("Map/registry.json"),"changed");fails(()->PortableWorld.main(new String[]{"verify",b.toString()}));
        PortableWorld.main(new String[]{"verify",a.toString()});
        Path data=a.resolve("data"),disk=data.resolve("resident-map");String binding="fixture";
        Files.write(data.resolve("resident-map.json"),Json.bytes(Map.of("world","regions:"+config.getProperty("world.bindingNamespace"),"binding",binding)));
        var key=new ResidentChunks.Key(0,0);
        try(var store=new RegionSnapshotStore(disk,binding,k->null)){
            for(int i=1;i<=2;i++)store.save(List.of(new ResidentChunks.Write(key,i,new byte[]{(byte)i})),new ResidentChunks.StateDelta(i,List.of(new ResidentChunks.StateFile("test",new byte[]{(byte)i})),Set.of()));
        }
        Path current;
        try(var stream=Files.walk(disk)){current=stream.filter(p->p.getFileName().toString().endsWith(".szmc")).filter(p->{try{var bytes=Files.readAllBytes(p);return bytes[bytes.length-1]==2;}catch(IOException e){throw new RuntimeException(e);}}).findFirst().orElseThrow();}
        Files.write(current,new byte[]{0});fails(()->PortableWorld.main(new String[]{"verify",a.toString()}));
        PortableWorld.main(new String[]{"rollback",a.toString()});PortableWorld.main(new String[]{"verify",a.toString()});
        try(var store=new RegionSnapshotStore(disk,binding,k->null)){check(store.load(key).bytes()[0]==1&&store.loadState("test").bytes()[0]==1,"whole recovery generation");}
        Path legacy=root.resolve("legacy");Files.createDirectory(legacy);
        Journal.Prefix cut;
        try(var journal=Journal.streaming(legacy.resolve("world.sjournal"))){
            journal.append(Json.tree(Map.of("commandId","old-map-edit","type","block"))).get();
            cut=journal.durablePrefix().get();journal.append(Json.tree(Map.of("commandId","business","type","give"))).get();
            try(var active=PortableReceipts.separate(legacy,journal,List.of(cut),cut)){
                check(active.matches(cut)&&active.effectivePrefix(cut).records()==0,"translate old cut");
            }
        }
        Files.move(legacy.resolve("world.sjournal"),root.resolve("old-journal-offline"));
        try(var active=PortableReceipts.open(legacy)){
            var rows=new ArrayList<String>();active.replay(e->rows.add(e.get("type").getAsString())).get();
            check(rows.equals(List.of("give"))&&active.receipts().receipt("old-map-edit")!=null,"business recovery and lazy legacy receipt");
        }
        residentCharacters(root.resolve("character-cohort"));
        System.out.println("PASS portable worlds, template disconnection, independent copies, region corruption, rollback, detached mixed journal, resident character generations");
    }
    private static void residentCharacters(Path root)throws Exception{
        Files.createDirectories(root);
        var catalogFile=root.resolve("item-definitions.json");
        Files.writeString(catalogFile,"{\"1\":{\"type\":\"bullet\",\"stack_size\":100}}");
        var catalog=ItemCatalog.legacyGeometry(catalogFile);
        var legacy=root.resolve("characters.sjournal");
        var character=UUID.randomUUID();
        var first=new World.Vec(4,67,8);
        var current=new World.Vec(6,68,10);
        var discarded=new World.Vec(9,69,9);
        var point=new Inventory.Address(1,0,0);
        try(var old=new InventoryJournal(legacy,catalog);var locations=new CharacterLocations(root,"character-world")){
            var history=new com.google.gson.JsonObject();
            history.add(AdminGiveReceipt.PROGRESS,new com.google.gson.JsonObject());
            old.commit(character,old.load(character).give(new ItemStack(1,2,0,new byte[0])),history).get();
            old.compact().get();
            locations.teleport(character,first).get();
            locations.importLegacyPlayer(character,Json.tree(Map.of("character_id",character.toString(),"obsolete","fixture"))).get();
        }
        try(var packed=Journal.streaming(legacy)){
            packed.pinOffsets();check(packed.packHistory().get().get("status").equals("complete"),"packed migration fixture");
        }
        Path locationFile;
        try(var paths=Files.list(root)){locationFile=paths.filter(p->p.getFileName().toString().matches("locations-[0-9a-f]{64}\\.sjournal")).findFirst().orElseThrow();}
        byte[] oldInventory=Files.readAllBytes(legacy),oldLocations=Files.readAllBytes(locationFile);
        var inventoryPages=new ResidentPlayerPages("inventory");var locationPages=new ResidentPlayerPages("locations");
        var key=new ResidentChunks.Key(0,0);
        var generations=root.resolve("map");
        try(var inventory=InventoryJournal.resident(legacy,catalog,null);
            var locations=CharacterLocations.resident(root,"character-world",null);
            var disk=new RegionSnapshotStore(generations,"character-world",k->null)){
            check(inventory.load(character).at(point).count()==2&&locations.position(character).equals(first),"migrate both legacy roots");
            check(locations.legacyPlayer(character)==null,"old compatibility payload stays in its archive");
            inventory.commit(character,inventory.load(character).give(new ItemStack(1,3,0,new byte[0]))).get();
            locations.observe(character,current);
            var inv=inventory.capture().get();var loc=locations.capture().get();
            var files=new ArrayList<ResidentChunks.StateFile>();
            files.addAll(inventoryPages.encode(1,inv.rows(),Set.of()).files());
            files.addAll(locationPages.encode(1,loc.rows(),Set.of()).files());
            // These updates are live but belong to a later save.
            inventory.commit(character,inventory.load(character).give(new ItemStack(1,5,0,new byte[0]))).get();
            locations.observe(character,discarded);
            disk.save(List.of(new ResidentChunks.Write(key,1,new byte[]{7})),new ResidentChunks.StateDelta(1,files,Set.of()));

            var later=new ArrayList<ResidentChunks.StateFile>();
            later.addAll(inventoryPages.encode(2,inventory.capture().get().rows(),Set.of()).files());
            later.addAll(locationPages.encode(2,locations.capture().get().rows(),Set.of()).files());
            // Simulate a damaged current generation with a missing character page.
            later.removeIf(file->file.name().equals("players-locations-0.bin"));
            disk.save(List.of(new ResidentChunks.Write(key,2,new byte[]{8})),
                    new ResidentChunks.StateDelta(2,later,Set.of("players-locations-0.bin")));
        }
        try(var disk=new RegionSnapshotStore(generations,"character-world",k->null)){
            disk.bootstrapState(files->{
                try(var restored=InventoryJournal.resident(legacy,catalog,inventoryPages.reader(files));
                    var locations=CharacterLocations.resident(root,"character-world",locationPages.reader(files))){
                    check(restored.load(character).at(point).count()==5,"restore inventory from the map generation");
                    check(!restored.gameplay(character).has(AdminGiveReceipt.PROGRESS),"operation history excluded from the save");
                    check(locations.position(character).equals(current),"restore most recent captured position");
                }
                return null;
            });
            check(disk.load(key).bytes()[0]==7,"character recovery also selects the previous region generation");
        }
        check(Arrays.equals(oldInventory,Files.readAllBytes(legacy))&&Arrays.equals(oldLocations,Files.readAllBytes(locationFile)),
                "migration and later RAM updates leave both source journals untouched");
    }
}
