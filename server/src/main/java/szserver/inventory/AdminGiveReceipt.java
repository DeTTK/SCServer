package szserver.inventory;

import com.google.gson.*;
import szserver.util.Json;
import java.util.*;

/** Compact last-give marker saved atomically with the native inventory, never full stack copies. */
public record AdminGiveReceipt(String commandId,String fingerprint,UUID character,int item,int count,int metadata,int slot,long before,long after) {
    public static final String PROGRESS="adminLastGiveV1";
    public AdminGiveReceipt {
        if(commandId==null||commandId.isBlank()||commandId.length()>64||commandId.indexOf('\0')>=0||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||character==null||item<0||item>65535||count<1||count>10000||metadata<0||metadata>65535||slot<0||slot>=160||before<0||before==Long.MAX_VALUE||after!=before+1)throw new IllegalArgumentException("give_receipt_bounds");
    }
    public JsonObject event(){JsonObject r=new JsonObject();r.addProperty("type","give");r.addProperty("commandId",commandId);r.addProperty("request",fingerprint);r.addProperty("nativeInventory",true);r.addProperty("owner",character.toString());r.addProperty("itemId",item);r.addProperty("count",count);r.addProperty("metadata",metadata);r.addProperty("slot",slot);r.addProperty("beforeRevision",before);r.addProperty("afterRevision",after);return r;}
    public JsonObject progress(){JsonObject r=new JsonObject();r.add(PROGRESS,event());return r;}
    private static long number(JsonObject r,String key){try{return r.get(key).getAsBigDecimal().longValueExact();}catch(RuntimeException invalid){throw new IllegalArgumentException("give_receipt_integer",invalid);}}
    public static AdminGiveReceipt read(JsonObject r){
        if(!r.keySet().equals(Set.of("type","commandId","request","nativeInventory","owner","itemId","count","metadata","slot","beforeRevision","afterRevision"))||!Json.text(r,"type",32).equals("give")||!r.get("nativeInventory").isJsonPrimitive()||!r.getAsJsonPrimitive("nativeInventory").isBoolean()||!r.get("nativeInventory").getAsBoolean())throw new IllegalArgumentException("give_receipt_fields");
        return new AdminGiveReceipt(Json.text(r,"commandId",64),Json.text(r,"request",64),UUID.fromString(Json.text(r,"owner",36)),Math.toIntExact(number(r,"itemId")),Math.toIntExact(number(r,"count")),Math.toIntExact(number(r,"metadata")),Math.toIntExact(number(r,"slot")),number(r,"beforeRevision"),number(r,"afterRevision"));
    }
    public static AdminGiveReceipt pending(UUID character,JsonObject progress,long revision){if(!progress.has(PROGRESS))return null;AdminGiveReceipt r=read(progress.getAsJsonObject(PROGRESS));if(!r.character().equals(character)||r.after()>revision)throw new IllegalArgumentException("give_receipt_character_or_revision");return r;}
}
