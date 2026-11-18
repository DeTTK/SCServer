package szserver.client;

import com.google.gson.JsonObject;
import szserver.inventory.ItemStack;
import szserver.net.InventoryPackets;
import java.nio.ByteBuffer;
import java.util.*;

/** Old SQLite SZIS envelope; item semantics/validation belong to the installed game serializers. */
public final class LegacyItemCodec {
    private static final byte[] MAGIC={'S','Z','I','S',1};
    public record Converted(ItemStack stack,boolean initializedMissingState){}
    private LegacyItemCodec(){}
    public static Converted convert(GameItems items,int id,int count,int metadata,int damage,byte[] saved,JsonObject tags)throws Exception{
        if(metadata<0||metadata>65535||damage<0||damage>65535||saved!=null&&(saved.length==0||saved.length>8201))throw new IllegalArgumentException("legacy_item_bounds");
        int effective=metadata!=0?metadata:damage;byte[] state=saved,properties={0};
        if(saved!=null&&saved.length>=5&&Arrays.equals(Arrays.copyOf(saved,5),MAGIC)){
            if(saved.length<10)throw new IllegalArgumentException("legacy_szis_truncated");int size=ByteBuffer.wrap(saved,5,4).getInt();
            if(size<0||size>=saved.length-9)throw new IllegalArgumentException("legacy_szis_length");state=size==0?null:Arrays.copyOfRange(saved,9,9+size);properties=Arrays.copyOfRange(saved,9+size,saved.length);
        }
        boolean initialized=false;
        if(state==null){
            // Only absent native state can acquire a new identity. Existing blobs and
            // property banks remain authoritative over potentially stale SQL tag mirrors.
            ItemStack created=items.createStack(id,count,effective,saved==null?tags:new JsonObject());state=created.state();initialized=state.length>0;if(saved==null)properties=created.properties();
        }
        ItemStack original=new ItemStack(id,count,effective,state,properties);byte[] wire=InventoryPackets.stack(original);
        ItemStack decoded=items.migrateLegacyStack(Arrays.copyOfRange(wire,1,wire.length));
        return new Converted(decoded,initialized);
    }
}
