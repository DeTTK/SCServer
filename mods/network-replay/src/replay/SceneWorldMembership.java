package replay;
import java.util.*;

/** Native bootstrap actors can move from pending admission to the live registry. */
final class SceneWorldMembership {
    static List<Object> snapshot(Collection<?> live,Collection<?> pending){
        Set<Object> identities=Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(live);identities.addAll(pending);return List.copyOf(identities);
    }
    static boolean matches(List<?> original,Collection<?> live,Collection<?> pending){
        Set<Object> identities=Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(live);identities.addAll(pending);
        return identities.size()==original.size()&&identities.containsAll(original);
    }
}
