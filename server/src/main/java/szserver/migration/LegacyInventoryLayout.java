package szserver.migration;

import szserver.client.GameItems;
import szserver.inventory.*;
import java.util.*;

/** Offline, explicitly enabled repair of legacy counts/rectangular placement; never drops roots. */
public final class LegacyInventoryLayout {
    public record Root(ItemStack stack,boolean initializedMissingState){}
    public record Result(Map<Inventory.Address,ItemStack> items,int splitRoots,int movedRoots){}
    private record Pending(Inventory.Address source,ItemStack stack,boolean initialized,boolean retainedIdentity){}
    private static final UUID ZERO=new UUID(0,0);
    private final ItemCatalog catalog;private final GameItems nativeItems;
    private final Map<Integer,BitSet> occupied=new HashMap<>();
    private final TreeMap<Inventory.Address,ItemStack> result=new TreeMap<>();
    private int split,moved;
    private LegacyInventoryLayout(ItemCatalog catalog,GameItems nativeItems){this.catalog=catalog;this.nativeItems=nativeItems;}
    private int capacity(Inventory.Address a){return a.aggregate()==0?1:a.aggregate()==1?StorageLayout.BAG:StorageLayout.capacity(a.container());}
    private int limit(Inventory.Address a,ItemStack item){int n=catalog.get(item.id()).stackLimit();return a.aggregate()==2?NativeStackRules.storageLimit(n):n;}
    private BitSet cells(Inventory.Address a,ItemStack item){
        if(!StorageLayout.validAddress(a))return null;
        // Preserve client-chosen equipment during unrelated grid/count repairs.
        if(a.aggregate()==0)return BitSet.valueOf(new long[]{1});
        int width=catalog.width(item),height=catalog.height(item),cap=capacity(a),x=a.slot()%8;
        if(x+width>8||a.slot()/8+height>(cap+7)/8)return null;
        BitSet b=new BitSet(cap);
        for(int y=0;y<height;y++)for(int dx=0;dx<width;dx++){
            int at=a.slot()+y*8+dx;if(at>=cap)return null;b.set(at);
        }return b;
    }
    private BitSet used(Inventory.Address a){return occupied.computeIfAbsent(a.aggregate()*16+a.container(),k->new BitSet());}
    private boolean fits(Inventory.Address a,ItemStack item){BitSet b=cells(a,item);return b!=null&&!used(a).intersects(b);}
    private void add(Inventory.Address a,ItemStack item){
        if(result.size()>=StorageLayout.MAX_ROOTS)throw new IllegalArgumentException("legacy_repair_root_capacity");
        used(a).or(Objects.requireNonNull(cells(a,item)));if(result.put(a,item)!=null)throw new IllegalStateException("legacy_repair_duplicate_address");
    }
    private Inventory.Address free(int aggregate,int container,ItemStack item){
        var first=new Inventory.Address(aggregate,container,0);BitSet b=used(first);int cap=capacity(first);
        for(int n=b.nextClearBit(0);n<cap;n=b.nextClearBit(n+1)){
            var a=new Inventory.Address(aggregate,container,n);if(fits(a,item))return a;
        }return null;
    }
    private Inventory.Address destination(Pending p){
        Inventory.Address a;
        if(p.source.aggregate()==2){a=free(2,p.source.container(),p.stack);if(a!=null)return a;}
        else{a=free(1,0,p.stack);if(a!=null)return a;}
        for(int page=0;page<StorageLayout.PAGES;page++)if(StorageLayout.canDeposit(page)){
            a=free(2,page,p.stack);if(a!=null)return a;
        }throw new IllegalArgumentException("legacy_repair_no_inventory_space");
    }
    private ItemStack portion(Pending p,int count)throws Exception{
        ItemStack part=p.stack.count(count);
        if(p.retainedIdentity&&!nativeItems.identity(part).equals(ZERO)){
            if(!p.initialized)throw new IllegalArgumentException("legacy_repair_cannot_clone_saved_identity");
            part=nativeItems.copyForGrant(part);
        }return part;
    }
    public static Result repack(ItemCatalog catalog,GameItems nativeItems,Map<Inventory.Address,Root> input)throws Exception{
        var repair=new LegacyInventoryLayout(catalog,nativeItems);List<Pending> pending=new ArrayList<>();
        Map<Integer,Long> before=new TreeMap<>();
        for(var entry:new TreeMap<>(input).entrySet()){
            var a=entry.getKey();var root=entry.getValue();ItemStack stack=root.stack;
            before.merge(stack.id(),(long)stack.count(),Math::addExact);
            // Historical bag commands could allocate a root immediately past
            // the actual grid. Keep it pending for relocation, never discard it.
            if(!StorageLayout.validAddress(a)&&!(a.aggregate()==1&&a.container()==0&&a.slot()>=0&&a.slot()<=32767))throw new IllegalArgumentException("legacy_inventory_address");
            int take=Math.min(stack.count(),repair.limit(a,stack));
            if(repair.fits(a,stack)){
                repair.add(a,stack.count(take));
                if(take<stack.count())pending.add(new Pending(a,stack.count(stack.count()-take),root.initializedMissingState,true));
            }else pending.add(new Pending(a,stack,root.initializedMissingState,false));
        }
        for(Pending original:pending){
            Pending p=original;boolean relocated=false;
            while(p.stack.count()>0){
                var address=repair.destination(p);int take=Math.min(p.stack.count(),repair.limit(address,p.stack));
                repair.add(address,repair.portion(p,take));if(p.retainedIdentity)repair.split++;
                if(!address.equals(p.source))relocated=true;
                if(take==p.stack.count())break;
                p=new Pending(p.source,p.stack.count(p.stack.count()-take),p.initialized,true);
            }if(relocated)repair.moved++;
        }
        Map<Integer,Long> after=new TreeMap<>();repair.result.values().forEach(s->after.merge(s.id(),(long)s.count(),Math::addExact));
        if(!before.equals(after))throw new IllegalStateException("legacy_repair_quantity_changed");
        Inventory.restore(catalog,repair.result,1);
        return new Result(Collections.unmodifiableMap(repair.result),repair.split,repair.moved);
    }
}
