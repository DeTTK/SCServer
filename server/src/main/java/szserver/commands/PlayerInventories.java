package szserver.commands;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Character gateway. Implementations keep native construction and IO off game owners. */
public interface PlayerInventories {
    CompletableFuture<JsonObject> give(JsonObject request,String fingerprint);
    JsonObject receipt(String commandId);
    List<JsonObject> inventory(UUID character);
    boolean healthy();
    default CompletableFuture<szserver.world.ActorEquipment> actorEquipment(List<Integer> worn,Integer held,boolean encumbered){return CompletableFuture.failedFuture(new UnsupportedOperationException("actor_equipment_unavailable"));}
}
