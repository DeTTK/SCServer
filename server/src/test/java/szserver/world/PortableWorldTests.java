package szserver.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import szserver.util.Json;
import szserver.persistence.*;

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
        System.out.println("PASS portable worlds, template disconnection, independent copies, region corruption, rollback, detached mixed journal");
    }
}
