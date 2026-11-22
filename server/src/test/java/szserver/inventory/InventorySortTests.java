package szserver.inventory;

import szserver.net.InventoryPackets;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Sorting is a durable server mutation driven by native packet 1506. */
public final class InventorySortTests {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static void rejected(Runnable operation){try{operation.run();throw new AssertionError("invalid sort request accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static Inventory.Address address(int aggregate,int container,int slot){return new Inventory.Address(aggregate,container,slot);}
    private static int count(Inventory inventory,int aggregate,int container,int id){return inventory.items().entrySet().stream().filter(e->e.getKey().aggregate()==aggregate&&e.getKey().container()==container&&e.getValue().id()==id).mapToInt(e->e.getValue().count()).sum();}
    private static byte[] sortBody(int identity,int aggregate,int container,int slot)throws IOException{var out=new ByteArrayOutputStream();try(var data=new DataOutputStream(out)){data.writeShort(identity);data.writeShort(-1);data.writeByte(aggregate);data.writeByte(container);data.writeShort(slot);}return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path definitions=root.resolve("item-definitions.json");
        Files.writeString(definitions,"{\"1\":{\"type\":\"weapon\",\"slot_size\":\"2x1\"},\"2\":{\"type\":\"bullet\",\"stack_size\":10},\"3\":{\"type\":\"medicine\"},\"4\":{\"type\":\"armor\",\"slot_size\":\"2x3\"},\"5\":{\"type\":\"custom\"},\"6\":{\"type\":\"backpack\",\"slot_size\":\"2x2\"}}");
        ItemCatalog catalog=new ItemCatalog(definitions);
        Map<Inventory.Address,ItemStack> scattered=new LinkedHashMap<>();
        scattered.put(address(1,0,120),new ItemStack(1,1,0,new byte[0]));
        scattered.put(address(1,0,130),new ItemStack(1,1,1,new byte[0]));
        scattered.put(address(1,0,50),new ItemStack(2,7,0,new byte[0]));
        scattered.put(address(1,0,70),new ItemStack(2,3,0,new byte[0]));
        scattered.put(address(1,0,100),new ItemStack(4,1,0,new byte[0]));
        scattered.put(address(1,0,140),new ItemStack(3,1,0,new byte[0]));
        scattered.put(address(1,0,150),new ItemStack(6,1,0,new byte[0]));
        scattered.put(address(0,6,0),new ItemStack(1,1,0,new byte[0]));
        Inventory before=new Inventory(catalog).replace(scattered,0).after();
        Inventory.Plan plan=before.sort(address(1,0,0));Inventory after=plan.after();
        check(count(after,1,0,2)==10,"identical stacks merge while sorting");
        check(count(after,1,0,1)==2&&count(after,1,0,3)==1&&count(after,1,0,4)==1,"sorting conserves every item count");
        check(after.at(address(0,6,0)).json().equals(before.at(address(0,6,0)).json()),"equipment never moves during bag sort");
        for(int slot:new int[]{50,70,100,120,130,140,150})check(after.at(address(1,0,slot))==null,"old bag cell cleared "+slot);
        List<Integer> order=after.items().entrySet().stream().filter(e->e.getKey().aggregate()==1).sorted(Map.Entry.comparingByKey()).map(e->e.getValue().id()).toList();
        check(order.equals(List.of(1,1,6,4,2,3)),"items pack as weapons, containers, armor, ammo, then medicine");
        check(plan.changes().size()>0&&after.revision()==before.revision()+1,"sort is one inventory revision");
        Inventory.Plan stable=after.sort(address(1,0,0));
        check(stable.changes().isEmpty(),"repeated sort is idempotent");
        Map<Inventory.Address,ItemStack> storage=new LinkedHashMap<>();
        storage.put(address(2,0,300),new ItemStack(3,1,0,new byte[0]));storage.put(address(2,0,20),new ItemStack(2,4,0,new byte[0]));storage.put(address(2,1,10),new ItemStack(2,5,0,new byte[0]));
        Inventory withStorage=after.replace(storage,0).after();Inventory sortedStorage=withStorage.sort(address(2,0,0)).after();
        check(sortedStorage.at(address(2,0,0)).id()==2&&sortedStorage.at(address(2,0,1)).id()==3,"storage page compacts independently");
        check(sortedStorage.at(address(2,1,10)).id()==2,"other storage pages are untouched");
        rejected(()->before.sort(address(0,0,0)));
        rejected(()->before.sort(address(1,0,1)));
        rejected(()->withStorage.sort(address(2,12,0)));
        check(InventoryPackets.sort(sortBody(0,1,0,0),4,false).equals(address(1,0,0)),"native sort request reads the bag root");
        check(InventoryPackets.sort(sortBody(0,2,3,0),4,true).equals(address(2,3,0)),"native sort request reads an explicit storage page");
        check(InventoryPackets.sort(sortBody(0,0,0,0),4,true).equals(address(2,4,0)),"storage local root resolves to the open page");
        check(InventoryPackets.sort(sortBody(0,0,7,0),4,true).equals(address(2,7,0)),"storage local page carried in the container field");
        check(InventoryPackets.sort(sortBody(5,0,0,0),4,true).equals(address(2,5,0)),"storage identity overrides the open page");
        check(InventoryPackets.sort(sortBody(0,0,255,0),4,true).equals(address(2,4,0)),"an unset local container falls back to the open page");
        check(InventoryPackets.sort(sortBody(0,1,0,0),4,true).equals(address(1,0,0)),"bag sorts while storage is also open");
        rejected(()->{try{InventoryPackets.sort(sortBody(0,1,0,5),4,false);}catch(IOException error){throw new IllegalArgumentException(error.getMessage());}});
        rejected(()->{try{InventoryPackets.sort(sortBody(0,3,0,0),4,false);}catch(IOException error){throw new IllegalArgumentException(error.getMessage());}});
        rejected(()->{try{InventoryPackets.sort(sortBody(99,0,0,0),4,true);}catch(IOException error){throw new IllegalArgumentException(error.getMessage());}});
        rejected(()->{try{InventoryPackets.sort(sortBody(0,0,0,0),4,false);}catch(IOException error){throw new IllegalArgumentException(error.getMessage());}});
        // Regression: all 160 cells fit, but family-first packing strands the 2x3 armor.
        Map<Inventory.Address,ItemStack> dense=new LinkedHashMap<>();dense.put(address(1,0,0),new ItemStack(4,1,0,new byte[]{99}));int serial=0;
        for(int y=0;y<20;y++)for(int x=0;x<8;x+=2)if(y>=3||x!=0)dense.put(address(1,0,y*8+x),new ItemStack(1,1,serial++,new byte[0]));
        Inventory full=new Inventory(catalog).replace(dense,0).after(),repacked=full.sort(address(1,0,0)).after();
        check(repacked.items().size()==78&&count(repacked,1,0,1)==77&&count(repacked,1,0,4)==1,"full valid bag sorts without overflow or loss");
        check(repacked.at(address(1,0,0)).id()==4,"dense packing retries the large footprint first");
        check(repacked.sort(address(1,0,0)).changes().isEmpty(),"dense retry is idempotent");
        check(full.items().equals(dense),"sorting never mutates the source revision");
        // Key fields and native geometry must prevent accidental state/identity merging.
        Map<Inventory.Address,ItemStack> variants=new LinkedHashMap<>();
        List<ItemStack> distinct=List.of(new ItemStack(2,2,0,new byte[]{1}),new ItemStack(2,2,1,new byte[]{1}),
            new ItemStack(2,2,0,new byte[]{2}),new ItemStack(2,2,0,new byte[]{1},new byte[]{1}),
            new ItemStack(2,2,0,new byte[]{1},new byte[]{0},1<<8),new ItemStack(2,2,0,new byte[]{1}).geometry(2,1));
        for(int i=0;i<distinct.size();i++)variants.put(address(1,0,16*i),distinct.get(i));
        Inventory keyed=new Inventory(catalog).replace(variants,0).after().sort(address(1,0,0)).after();
        check(keyed.items().size()==distinct.size()&&count(keyed,1,0,2)==12,"sort key preserves metadata, bytes, properties, mask and geometry");
        byte[] publicBytes=distinct.get(0).state();publicBytes[0]=9;check(distinct.get(0).state()[0]==1,"sort comparator keeps byte arrays private");
        Map<Inventory.Address,ItemStack> partials=new LinkedHashMap<>();
        partials.put(address(1,0,50),new ItemStack(2,7,0,new byte[0]));partials.put(address(1,0,60),new ItemStack(2,8,0,new byte[0]));partials.put(address(1,0,70),new ItemStack(2,9,0,new byte[0]));
        Inventory chained=new Inventory(catalog).replace(partials,0).after().sort(address(1,0,0)).after();
        check(chained.items().values().stream().map(ItemStack::count).toList().equals(List.of(10,10,4)),"partial index carries remainder across several stacks");
        // Dense heterogeneous layouts exercise both retries and the original-anchor fallback.
        Random random=new Random(120612);for(int attempt=0;attempt<200;attempt++){
            BitSet occupied=new BitSet(160);Map<Inventory.Address,ItemStack> generated=new LinkedHashMap<>();int identity=0;
            for(int k=0;k<600;k++){int width=1+random.nextInt(5),height=1+random.nextInt(4),slot=random.nextInt(160);if(slot%8+width>8||slot/8+height>20)continue;
                BitSet cells=new BitSet(160);for(int y=0;y<height;y++)for(int x=0;x<width;x++)cells.set(slot+y*8+x);if(occupied.intersects(cells))continue;occupied.or(cells);
                generated.put(address(1,0,slot),new ItemStack(1,1,identity++,new byte[0]).geometry(width,height));
            }
            Inventory original=new Inventory(catalog).replace(generated,0).after(),result=original.sort(address(1,0,0)).after();
            check(result.items().values().stream().map(i->i.json().toString()).sorted().toList().equals(original.items().values().stream().map(i->i.json().toString()).sorted().toList()),"heterogeneous page preserves complete state "+attempt);
            check(result.sort(address(1,0,0)).changes().isEmpty(),"heterogeneous sort is stable "+attempt);
        }
        System.out.println("Inventory sort: "+checks+" PASS");
    }
}