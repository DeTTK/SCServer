package szserver.inventory;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recent request IDs used to handle duplicate deliveries during the current server run. */
final class SessionReceipts {
    private static final int LIMIT=8192;
    private final LinkedHashMap<String,JsonObject> events=new LinkedHashMap<>();

    synchronized JsonObject find(String id){return events.get(id);}

    synchronized void record(JsonObject event){
        events.put(event.get("commandId").getAsString(),event);
        if(events.size()>LIMIT)events.remove(events.keySet().iterator().next());
    }

    synchronized Map<String,Object> metrics(){return Map.of("rows",events.size(),"limit",LIMIT);}
}
