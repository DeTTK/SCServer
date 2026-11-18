package szserver.net;

import szserver.inventory.Inventory;

/** Native screen state is committed only after its complete response is queued. */
public final class InventoryScreen {
    public static final int PAGES=15;
    public record Page(int index,boolean refreshPlayer){}
    private boolean storageOpen;
    private int page;
    public int page(){return page;}
    public boolean storageOpen(){return storageOpen;}
    public static boolean available(float health,boolean respawning){return health>0&&!respawning;}
    public Page prepare(int index,boolean refreshPlayer){
        if(index<0||index>=PAGES)throw new IllegalArgumentException("storage_page");
        return new Page(index,refreshPlayer||!storageOpen);
    }
    public void opened(Page plan){if(plan.index()<0||plan.index()>=PAGES)throw new IllegalArgumentException("storage_page");page=plan.index();storageOpen=true;}
    /** Match the old order: restore actor attributes, bag/equipment, then acknowledge the context. */
    public static byte[] player(Inventory inventory,byte[] actorAttributes){
        return Wire.concat(actorAttributes,InventoryPackets.snapshot(inventory,0,1,0),InventoryPackets.snapshot(inventory,0,0,0),InventoryPackets.open());
    }
    public static byte[] storage(Inventory inventory,Page plan,byte[] actorAttributes){
        return Wire.concat(plan.refreshPlayer()?player(inventory,actorAttributes):new byte[0],InventoryPackets.storage(plan.index()),InventoryPackets.snapshot(inventory,-1,0,plan.index()));
    }
}
