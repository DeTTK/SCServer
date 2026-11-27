package szserver.inventory;

import szserver.util.Json;
import java.util.*;

/** Current imported drops indexed by owner, with no derived disk writes. */
final class MemoryWorldDrops implements WorldDropRoots {
    private final Map<UUID,WorldDrop> items=new LinkedHashMap<>();
    private final Map<UUID,List<WorldDrop>> owners=new HashMap<>();
    private long bytes;

    public synchronized boolean contains(UUID id){return items.containsKey(id);}
    public synchronized boolean hasOwner(UUID id){return owners.containsKey(id);}
    public synchronized Set<UUID> ownerIds(){return Set.copyOf(owners.keySet());}
    public synchronized void add(WorldDrop drop){
        if(items.putIfAbsent(drop.id(),drop)!=null)throw new IllegalArgumentException("world_drop_duplicate");
        owners.computeIfAbsent(drop.character(),ignored->new ArrayList<>()).add(drop);
        bytes+=Json.bytes(drop.json()).length;
    }
    public synchronized List<WorldDrop> list(UUID owner){
        return owners.getOrDefault(owner,List.of()).stream().sorted(Comparator.comparing(d->d.id().toString())).toList();
    }
    public synchronized void visit(Visitor visitor)throws Exception{for(var row:items.values())visitor.accept(row);}
    public synchronized long count(){return items.size();}
    public synchronized long bytes(){return bytes;}
    public synchronized Map<String,Object> metrics(){return Map.of("rows",items.size(),"jsonBytes",bytes,"owners",owners.size(),"storage","memory");}
    public void close(){}
}
