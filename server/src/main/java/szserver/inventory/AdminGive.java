package szserver.inventory;

import com.google.gson.*;
import szserver.util.Json;
import java.util.*;

/** Validated administrative request. Unknown and null fields never alias an accepted retry. */
public record AdminGive(String commandId,UUID character,int item,int count,int metadata,Integer slot,JsonObject tags) {
    public AdminGive { Objects.requireNonNull(character);tags=tags.deepCopy(); }
    @Override public JsonObject tags(){return tags.deepCopy();}
    private static int integer(JsonObject r,String key,int min,int max){Json.number(r,key,min,max);try{return r.get(key).getAsBigDecimal().intValueExact();}catch(ArithmeticException fractional){throw new IllegalArgumentException("integer_required:"+key);}}
    public static AdminGive read(JsonObject r){
        if(!Set.of("type","commandId","owner","itemId","count","metadata","slot","stateTags").containsAll(r.keySet()))throw new IllegalArgumentException("give_unknown_field");
        if(!Json.text(r,"type",32).equals("give"))throw new IllegalArgumentException("give_type");
        return new AdminGive(Json.text(r,"commandId",64),UUID.fromString(Json.text(r,"owner",36)),integer(r,"itemId",0,65535),integer(r,"count",1,10000),r.has("metadata")?integer(r,"metadata",0,65535):0,r.has("slot")?integer(r,"slot",0,159):null,r.has("stateTags")?Json.object(r,"stateTags"):new JsonObject());
    }
}
