package szserver.inventory;

import java.util.*;
import com.google.gson.*;

/** Immutable persisted native state, including its original identity and tags. */
public final class ItemStack {
    private final int id,count,metadata,equipmentMask,gridWidth,gridHeight,nativeStackLimit;
    private final byte[] state,properties;
    public ItemStack(int id,int count,int metadata,byte[] state){
        this(id,count,metadata,state,new byte[]{0});
    }
    public ItemStack(int id,int count,int metadata,byte[] state,byte[] properties){
        this(id,count,metadata,state,properties,0);
    }
    public ItemStack(int id,int count,int metadata,byte[] state,byte[] properties,int equipmentMask){
        this(id,count,metadata,state,properties,equipmentMask,0,0,0);
    }
    private ItemStack(int id,int count,int metadata,byte[] state,byte[] properties,int equipmentMask,int gridWidth,int gridHeight,int nativeStackLimit){
        if(id<0||id>65535||count<1||metadata<0||metadata>65535||state.length+properties.length>8192||properties.length<1)throw new IllegalArgumentException("invalid_stack");
        if((equipmentMask&~((1<<8)|(1<<9)|(1<<11)))!=0)throw new IllegalArgumentException("invalid_equipment_mask");
        if(gridWidth<0||gridHeight<0||gridWidth>256||gridHeight>256||(gridWidth==0)!=(gridHeight==0))throw new IllegalArgumentException("invalid_native_item_geometry");
        if(nativeStackLimit<0)throw new IllegalArgumentException("invalid_native_stack_limit");
        this.nativeStackLimit=nativeStackLimit;
        this.id=id;this.count=count;this.metadata=metadata;this.state=state.clone();this.properties=properties.clone();this.equipmentMask=equipmentMask;this.gridWidth=gridWidth;this.gridHeight=gridHeight;
    }
    public int id(){return id;} public int count(){return count;} public int metadata(){return metadata;}
    public byte[] state(){return state.clone();} public boolean stateful(){return state.length!=0;}
    public byte[] properties(){return properties.clone();}
    public int equipmentMask(){return equipmentMask;}
    public int gridWidth(){return gridWidth;}public int gridHeight(){return gridHeight;}
    public int nativeStackLimit(){return nativeStackLimit;}
    public ItemStack geometry(int width,int height){return inventoryFacts(width,height,nativeStackLimit);}
    public ItemStack inventoryFacts(int width,int height,int limit){
        return width==gridWidth&&height==gridHeight&&limit==nativeStackLimit?this:
                new ItemStack(id,count,metadata,state,properties,equipmentMask,width,height,limit);
    }
    public long residentBytes(){return 64L+state.length+properties.length;}
    public ItemStack count(int n){return n==0?null:new ItemStack(id,n,metadata,state,properties,equipmentMask,gridWidth,gridHeight,nativeStackLimit);}
    public ItemStack state(byte[] bytes){return new ItemStack(id,count,metadata,bytes,properties,equipmentMask,gridWidth,gridHeight,nativeStackLimit);}
    public boolean mergeable(ItemStack other){return other!=null&&id==other.id&&metadata==other.metadata&&equipmentMask==other.equipmentMask&&Arrays.equals(state,other.state)&&Arrays.equals(properties,other.properties);}
    /** Stable stack/geometry key without exposing or cloning immutable native bytes. Counts are excluded. */
    int compareSortKey(ItemStack other){
        int n=Integer.compare(id,other.id);if(n==0)n=Integer.compare(metadata,other.metadata);
        if(n==0)n=Arrays.compareUnsigned(state,other.state);if(n==0)n=Arrays.compareUnsigned(properties,other.properties);
        if(n==0)n=Integer.compare(equipmentMask,other.equipmentMask);if(n==0)n=Integer.compare(gridWidth,other.gridWidth);
        if(n==0)n=Integer.compare(gridHeight,other.gridHeight);
        return n==0?Integer.compare(nativeStackLimit,other.nativeStackLimit):n;
    }
    public JsonObject json(){JsonObject p=new JsonObject();p.addProperty("id",id);p.addProperty("count",count);p.addProperty("metadata",metadata);p.addProperty("state",HexFormat.of().formatHex(state));p.addProperty("properties",HexFormat.of().formatHex(properties));p.addProperty("equipmentMask",equipmentMask);if(gridWidth>0){p.addProperty("nativeGridWidth",gridWidth);p.addProperty("nativeGridHeight",gridHeight);}if(nativeStackLimit>0)p.addProperty("nativeStackLimit",nativeStackLimit);return p;}
    public static ItemStack read(JsonObject p){return new ItemStack(p.get("id").getAsInt(),p.get("count").getAsInt(),p.get("metadata").getAsInt(),HexFormat.of().parseHex(p.get("state").getAsString()),p.has("properties")?HexFormat.of().parseHex(p.get("properties").getAsString()):new byte[]{0},p.has("equipmentMask")?p.get("equipmentMask").getAsInt():0,p.has("nativeGridWidth")?p.get("nativeGridWidth").getAsInt():0,p.has("nativeGridHeight")?p.get("nativeGridHeight").getAsInt():0,p.has("nativeStackLimit")?p.get("nativeStackLimit").getAsInt():0);}
}
