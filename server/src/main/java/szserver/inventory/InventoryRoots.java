package szserver.inventory;

import com.google.gson.JsonObject;
import szserver.util.Json;
import java.util.UUID;

interface InventoryRoots extends AutoCloseable {
    record Snapshot(Inventory inventory, JsonObject gameplay, long bytes) {
        Snapshot(Inventory inventory, JsonObject gameplay) {
            this(inventory, gameplay, inventory.residentBytes()+128L+2L*Json.bytes(gameplay).length);
        }
    }
    final class Pin { int references; volatile Snapshot snapshot; }
    @FunctionalInterface interface Visitor { void accept(UUID id,Snapshot snapshot)throws Exception; }
    @FunctionalInterface interface MarkerVisitor { void accept(UUID id,long revision,JsonObject state)throws Exception; }

    Snapshot get(UUID id);
    boolean contains(UUID id);
    void record(JsonObject event, Inventory inventory, JsonObject gameplay);
    void snapshot(JsonObject event);
    void delete(UUID id);
    boolean incomplete();
    void visit(Visitor visitor)throws Exception;
    void visitMarkers(int mask, MarkerVisitor visitor)throws Exception;
    Pin reserve(UUID id);
    void prepare(UUID id, Pin pin);
    void release(UUID id, Pin pin);
    java.util.Map<String,Object> metrics();

    static int markers(JsonObject state) {
        return (state.has(VitalsReceipt.KEY)?1:0)|(state.has(AdminGiveReceipt.PROGRESS)?2:0)
                |(state.has(FpvReceipt.PROGRESS)?4:0)|(state.has(GrenadeReceipt.PROGRESS)?8:0);
    }
}
