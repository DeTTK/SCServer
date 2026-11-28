package szserver.inventory;

import java.io.IOException;
import java.util.*;

interface WorldDropRoots extends AutoCloseable {
    @FunctionalInterface interface Visitor {void accept(WorldDrop drop)throws Exception;}
    boolean contains(UUID id)throws IOException;
    boolean hasOwner(UUID id);
    Set<UUID> ownerIds();
    void add(WorldDrop drop)throws IOException;
    List<WorldDrop> list(UUID owner)throws IOException;
    void visit(Visitor visitor)throws Exception;
    long count();
    long bytes();
    Map<String,Object> metrics();
}
