package szserver.net;

import szserver.inventory.ItemStack;
import java.util.*;

/** Bounded placement hints for the native, client-owned 84-slot creative model.
 * These hints never become survival items or permission to use a survival weapon. */
public final class CreativeGrantSlots {
    public static final int CAPACITY=84;
    private final BitSet occupied=new BitSet(CAPACITY);
    private final int[] factories=new int[CAPACITY];
    public CreativeGrantSlots(){Arrays.fill(factories,-1);}
    public void echo(int slot,byte[] raw){
        if(slot<0||slot>=CAPACITY)throw new IllegalArgumentException("creative_inventory_slot");
        occupied.set(slot,raw!=null);factories[slot]=raw==null?-1:((raw[1]&255)<<8)|(raw[2]&255);
    }
    public int factory(int slot){return slot<0||slot>=CAPACITY?-1:factories[slot];}
    public Map<Integer,ItemStack> place(List<ItemStack> stacks,Integer requested){
        if(stacks.isEmpty()||stacks.size()>CAPACITY)throw new IllegalArgumentException("creative_inventory_full");
        BitSet used=(BitSet)occupied.clone();Map<Integer,ItemStack> result=new LinkedHashMap<>();
        for(int i=0;i<stacks.size();i++){
            int slot=i==0&&requested!=null?requested:used.nextClearBit(0);
            if(slot<0||slot>=CAPACITY||used.get(slot))throw new IllegalArgumentException("creative_inventory_slot_occupied_or_full");
            used.set(slot);result.put(slot,Objects.requireNonNull(stacks.get(i)));
        }
        return Collections.unmodifiableMap(result);
    }
    public void granted(Map<Integer,ItemStack> stacks){for(var entry:stacks.entrySet()){occupied.set(entry.getKey());factories[entry.getKey()]=entry.getValue().id();}}
}
