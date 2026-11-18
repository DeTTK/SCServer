package szserver.inventory;

import com.google.gson.JsonObject;
import szserver.persistence.CharacterLocations.PlayerState;

/** The inventory commit is the authority; locations acknowledge its revision exactly once. */
public record VitalsReceipt(long revision, PlayerState state) {
    public static final String KEY="vitalsReceipt";
    public VitalsReceipt {if(revision<=0||state==null)throw new IllegalArgumentException("invalid_vitals_receipt");}
    public JsonObject json(){JsonObject value=new JsonObject();value.addProperty("revision",revision);value.add("state",state.json());return value;}
    public static VitalsReceipt read(JsonObject progress,long inventoryRevision){
        if(!progress.has(KEY))return null;
        JsonObject value=progress.getAsJsonObject(KEY);
        VitalsReceipt receipt=new VitalsReceipt(value.get("revision").getAsBigDecimal().longValueExact(),PlayerState.read(value.getAsJsonObject("state")));
        if(receipt.revision()>inventoryRevision)throw new IllegalArgumentException("vitals_receipt_revision_ahead");return receipt;
    }
    public PlayerState recover(PlayerState latest){
        if(latest==null)return state;
        return new PlayerState(latest.yaw(),latest.pitch(),state.health(),latest.hand(),latest.holstered(),latest.damagedAt(),latest.stamina(),state.sickness());
    }
}
