package szserver.inventory;

import java.util.*;

/** One immutable revision. All mutation plans are validated before journal append. */
public final class Inventory {
    public record Address(int aggregate,int container,int slot) implements Comparable<Address> {
        public int compareTo(Address a){int n=Integer.compare(aggregate,a.aggregate);if(n==0)n=Integer.compare(container,a.container);return n==0?Integer.compare(slot,a.slot):n;}
    }
    public record Plan(Inventory after,Map<Address,ItemStack> changes,int moved) {}
    private final ItemCatalog catalog;
    private final NavigableMap<Address,ItemStack> items;
    private final long revision;
    private final long residentBytes;
    public Inventory(ItemCatalog catalog){this(catalog,new TreeMap<>(),0);}
    public static Inventory restore(ItemCatalog catalog,Map<Address,ItemStack> items,long revision){if(revision<1)throw new IllegalArgumentException("inventory_snapshot_revision");return new Inventory(catalog,new TreeMap<>(items),revision);}
    private Inventory(ItemCatalog catalog,NavigableMap<Address,ItemStack> items,long revision){this.catalog=catalog;this.items=Collections.unmodifiableNavigableMap(items);this.revision=revision;validate(items);residentBytes=128+items.values().stream().mapToLong(item->96+item.residentBytes()).sum();}
    public long residentBytes(){return residentBytes;}
    public long revision(){return revision;}public Map<Address,ItemStack> items(){return items;}public ItemStack at(Address a){check(a);return items.get(a);}
    public int capacity(Address a){return a.aggregate==1?StorageLayout.BAG:a.aggregate==2?StorageLayout.capacity(a.container):1;}
    private void check(Address a){if(!StorageLayout.validAddress(a))throw new IllegalArgumentException("invalid_inventory_address");}
    private static void deposit(Address a){if(a.aggregate==2&&!StorageLayout.canDeposit(a.container))throw new IllegalArgumentException("storage_purchases_deposit_denied");}
    private BitSet cells(Address a,ItemStack item){int capacity=capacity(a),width=catalog.width(item),height=catalog.height(item),x=a.slot%8;
        if(x+width>8||a.slot/8+height>(capacity+7)/8)throw new IllegalArgumentException("item_out_of_grid");
        BitSet cells=new BitSet(capacity);for(int y=0;y<height;y++)for(int dx=0;dx<width;dx++){int slot=a.slot+y*8+dx;if(slot>=capacity)throw new IllegalArgumentException("item_out_of_grid");cells.set(slot);}return cells;
    }
    private int limit(Address a,ItemStack item){int normal=catalog.get(item.id()).stackLimit();return a.aggregate==2&&normal>1?100000:normal;}
    private void validate(Map<Address,ItemStack> state){Map<String,BitSet> occupied=new HashMap<>();
        for(var e:state.entrySet()){Address a=e.getKey();ItemStack item=e.getValue();check(a);if(item==null)throw new IllegalArgumentException("null_inventory_value");
            if(item.count()>limit(a,item))throw new IllegalArgumentException("stack_limit");
            if(item.stateful()&&catalog.get(item.id()).stackLimit()==1&&item.count()!=1)throw new IllegalArgumentException("stateful_stack_count");
            // Equipment has one root cell, not a universal one-item stack limit.
            // Native grenade stacks retain the same resource-defined cap here.
            // Native client owns equipment compatibility. Keep structural and quantity validation here.
            if(a.aggregate!=0){BitSet used=occupied.computeIfAbsent(a.aggregate+":"+a.container,k->new BitSet());BitSet footprint=cells(a,item);if(used.intersects(footprint))throw new IllegalArgumentException("occupied_inventory_cells");used.or(footprint);}
            // Do not manufacture another identity by splitting a stateful item.
        }
    }
    public Plan replace(Map<Address,ItemStack> changes,int moved){TreeMap<Address,ItemStack> copy=new TreeMap<>(items);for(var e:changes.entrySet()){check(e.getKey());if(e.getValue()==null)copy.remove(e.getKey());else copy.put(e.getKey(),e.getValue());}return new Plan(new Inventory(catalog,copy,revision+1),Collections.unmodifiableMap(new LinkedHashMap<>(changes)),moved);}
    private Map<Address,ItemStack> change(Address a,ItemStack x,Address b,ItemStack y){Map<Address,ItemStack> out=new LinkedHashMap<>();out.put(a,x);if(b!=null)out.put(b,y);return out;}
    /** Removes an actual general inventory root; covered cells never select another item. */
    public Plan remove(int slot,Integer requested){Address address=new Address(1,0,slot);ItemStack source=at(address);if(source==null)throw new IllegalArgumentException("inventory_slot_empty");int amount=requested==null?source.count():requested;if(amount<1||amount>source.count())throw new IllegalArgumentException("invalid_inventory_remove_count");Map<Address,ItemStack> changes=new LinkedHashMap<>();changes.put(address,source.count(source.count()-amount));return replace(changes,amount);}
    public Plan move(Address source,Address destination,int requested,boolean split){
        check(source);check(destination);ItemStack from=at(source),to=at(destination);if(from==null)throw new IllegalArgumentException("inventory_source_empty");if(source.equals(destination))return replace(Map.of(),0);
        int count=requested==0?from.count():requested;if(count<1||count>from.count())throw new IllegalArgumentException("move_count");
        if(count<from.count()&&from.stateful()&&catalog.get(from.id()).stackLimit()==1)throw new IllegalArgumentException("stateful_item_cannot_split");
        ItemStack sourceAfter,destinationAfter;int moved;
        deposit(destination);
        if(to!=null&&from.mergeable(to)){
            moved=Math.min(count,limit(destination,from)-to.count());if(moved<=0)throw new IllegalArgumentException("stack_full");
            sourceAfter=from.count(from.count()-moved);destinationAfter=to.count(to.count()+moved);
        }else if(count<from.count()){
            if(to!=null)throw new IllegalArgumentException("split_destination_occupied");moved=count;sourceAfter=from.count(from.count()-count);destinationAfter=from.count(count);
        }else{moved=count;sourceAfter=to;destinationAfter=from;}
        if(sourceAfter!=null&&to!=null&&!from.mergeable(to))deposit(source);
        return replace(change(source,sourceAfter,destination,destinationAfter),moved);
    }
    public Plan quick(Address source,List<Address> destinations,int requested){
        ItemStack from=at(source);if(from==null)throw new IllegalArgumentException("inventory_source_empty");if(destinations.isEmpty()||destinations.size()>StorageLayout.MAX_CAPACITY||new HashSet<>(destinations).size()!=destinations.size()||destinations.contains(source))throw new IllegalArgumentException("quick_destination");
        if(requested<0)throw new IllegalArgumentException("move_count");int remaining=requested==0?from.count():Math.min(requested,from.count());int initial=remaining;Map<Address,ItemStack> changes=new LinkedHashMap<>();
        for(Address a:destinations){check(a);deposit(a);ItemStack to=at(a);if(remaining==0||to!=null&&!from.mergeable(to))throw new IllegalArgumentException("quick_destination_occupied");
            int moved=Math.min(remaining,limit(a,from)-(to==null?0:to.count()));if(moved<=0)throw new IllegalArgumentException("stack_full");
            changes.put(a,from.count((to==null?0:to.count())+moved));remaining-=moved;
        }
        int moved=initial-remaining;if(from.stateful()&&catalog.get(from.id()).stackLimit()==1&&moved!=from.count())throw new IllegalArgumentException("stateful_item_cannot_split");changes.put(source,from.count(from.count()-moved));return replace(changes,moved);
    }
    /** Give is all-or-nothing, first merging compatible stacks then packing rectangles. */
    public Plan give(ItemStack stack){return pack(stack,1);}
    public Plan store(ItemStack stack){return pack(stack,2);}
    private Plan pack(ItemStack stack,int aggregate){
        int remaining=stack.count(),pages=aggregate==1?1:StorageLayout.PAGES;Map<Address,ItemStack> changes=new LinkedHashMap<>();
        BitSet[] occupied=new BitSet[pages];for(int page=0;page<pages;page++)occupied[page]=new BitSet(aggregate==1?StorageLayout.BAG:StorageLayout.capacity(page));
        for(var entry:items.entrySet())if(entry.getKey().aggregate==aggregate){
            Address address=entry.getKey();occupied[address.container].or(cells(address,entry.getValue()));
            if(remaining>0&&(aggregate==1||StorageLayout.canDeposit(address.container))&&stack.mergeable(entry.getValue())){int moved=Math.min(remaining,limit(address,stack)-entry.getValue().count());if(moved>0){changes.put(address,entry.getValue().count(entry.getValue().count()+moved));remaining-=moved;}}
        }
        for(int page=0;page<pages&&remaining>0;page++){if(aggregate==2&&!StorageLayout.canDeposit(page))continue;int capacity=aggregate==1?StorageLayout.BAG:StorageLayout.capacity(page);for(int slot=0;slot<capacity&&remaining>0;slot++){
            Address address=new Address(aggregate,page,slot);BitSet footprint;try{footprint=cells(address,stack);}catch(IllegalArgumentException outside){continue;}
            if(occupied[page].intersects(footprint))continue;int moved=Math.min(remaining,limit(address,stack));changes.put(address,stack.count(moved));occupied[page].or(footprint);remaining-=moved;
        }}
        if(remaining!=0)throw new IllegalArgumentException(aggregate==1?"inventory_full":"storage_full");return replace(changes,stack.count());
    }

    public double weight(){return items.entrySet().stream().filter(e->e.getKey().aggregate<2).mapToDouble(e->catalog.get(e.getValue().id()).weight()*e.getValue().count()).sum();}
}
