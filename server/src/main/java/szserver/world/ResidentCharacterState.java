package szserver.world;

import szserver.inventory.InventoryJournal;
import szserver.persistence.CharacterLocations;
import szserver.persistence.Journal;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The live character stores and their participation in the world save. */
public final class ResidentCharacterState {
    private static final ResidentPlayerPages INVENTORY=new ResidentPlayerPages("inventory");
    private static final ResidentPlayerPages LOCATIONS=new ResidentPlayerPages("locations");
    private final Set<String> initialFiles;
    private Journal.SnapshotReader inventoryRows,locationRows;
    private InventoryJournal inventory;
    private CharacterLocations locations;

    private ResidentCharacterState(Set<String> files,Journal.SnapshotReader inventory,Journal.SnapshotReader locations){
        initialFiles=files;inventoryRows=inventory;locationRows=locations;
    }

    public static ResidentCharacterState restore(ResidentChunks.StateFiles files)throws Exception{
        var index=files.index();
        var names=new HashSet<String>();
        for(String name:index.files().keySet())
            if(INVENTORY.owns(name)||LOCATIONS.owns(name))names.add(name);
        if(!names.isEmpty()&&!Objects.equals(index.files().get(INVENTORY.root()),index.files().get(LOCATIONS.root())))
            throw new IOException("resident_character_save_sections");
        var inventory=INVENTORY.reader(files);
        var locations=LOCATIONS.reader(files);
        if((inventory==null||locations==null)&&!names.isEmpty())throw new IOException("resident_character_save_sections");
        return new ResidentCharacterState(Set.copyOf(names),inventory,locations);
    }

    public Set<String> initialFiles(){return initialFiles;}
    public Journal.SnapshotReader inventoryRows(){return inventoryRows;}
    public Journal.SnapshotReader locationRows(){return locationRows;}
    public void attach(InventoryJournal inventory,CharacterLocations locations){
        if(active())throw new IllegalStateException("resident_character_state_already_attached");
        this.inventory=Objects.requireNonNull(inventory);this.locations=Objects.requireNonNull(locations);
        inventoryRows=null;locationRows=null;
    }
    public boolean active(){return inventory!=null;}
    public record Revisions(long inventory,long locations){}
    public Revisions revisions(){return new Revisions(inventory.memoryRevision(),locations.memoryRevision());}
    public record Cut(InventoryJournal.PlayerSnapshot inventory,CharacterLocations.LocationSnapshot locations){
        public Revisions revisions(){return new Revisions(inventory.revision(),locations.revision());}
        public ResidentChunks.StateDelta encode(long revision,Set<String> previous)throws Exception{
            var inv=INVENTORY.encode(revision,inventory.rows(),previous);
            var loc=LOCATIONS.encode(revision,locations.rows(),previous);
            var files=new ArrayList<>(inv.files());files.addAll(loc.files());
            var removed=new HashSet<>(inv.removed());removed.addAll(loc.removed());
            return new ResidentChunks.StateDelta(revision,files,removed);
        }
    }
    public CompletableFuture<Cut> capture(){
        if(!active())return CompletableFuture.completedFuture(null);
        return capture(4);
    }

    private CompletableFuture<Cut> capture(int attempts){
        long revision=inventory.memoryRevision();
        // Inventory contains the current vitals handoff needed if its update
        // was still in transit to the location worker at the cut.
        return locations.capture().thenCompose(loc->inventory.capture().thenCompose(inv->{
            if(inv.revision()==revision)return CompletableFuture.completedFuture(new Cut(inv,loc));
            if(attempts>1)return capture(attempts-1);
            return CompletableFuture.failedFuture(new IllegalStateException("resident_character_capture_busy"));
        }));
    }
}
