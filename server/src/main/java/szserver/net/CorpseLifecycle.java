package szserver.net;

import java.util.*;

/** Transport-owned presentation lifetime, no physics and no inventory/loot ownership. */
public final class CorpseLifecycle<T> {
    public static final long LIFETIME_NANOS=30_000_000_000L,RESPAWN_NANOS=5_000_000_000L;
    private static final int MAX_CORPSES=1024;
    public record Corpse<T>(int entity,long expires,List<T> recipients){public Corpse{recipients=List.copyOf(recipients);}}
    public record Added<T>(Corpse<T> corpse,Corpse<T> evicted){}
    private final ArrayDeque<Corpse<T>> corpses=new ArrayDeque<>();
    private final NativeEntityIds identifiers;private long created,expired,evicted;
    public CorpseLifecycle(){this(new NativeEntityIds());}
    public CorpseLifecycle(NativeEntityIds identifiers){this.identifiers=Objects.requireNonNull(identifiers);}
    public Added<T> create(long now,Collection<T> recipients){List<T> frozen=List.copyOf(new LinkedHashSet<>(recipients));Corpse<T> retired=corpses.size()==MAX_CORPSES?corpses.removeFirst():null;if(retired!=null){identifiers.release(retired.entity());evicted++;}int entity=identifiers.corpse();Corpse<T> corpse=new Corpse<>(entity,now+LIFETIME_NANOS,frozen);corpses.addLast(corpse);created++;return new Added<>(corpse,retired);}
    /** Remove only a still-owned presentation corpse; expired/evicted identifiers cannot be released twice. */
    public Corpse<T> retire(Corpse<T> corpse){if(corpse==null||!corpses.remove(corpse))return null;identifiers.release(corpse.entity());return corpse;}
    public List<Corpse<T>> expire(long now){List<Corpse<T>> result=new ArrayList<>();while(!corpses.isEmpty()&&now-corpses.peekFirst().expires()>=0){Corpse<T> corpse=corpses.removeFirst();identifiers.release(corpse.entity());result.add(corpse);expired++;}return List.copyOf(result);}
    public Map<String,Object> metrics(){return Map.of("active",corpses.size(),"created",created,"expired",expired,"evicted",evicted,"limit",MAX_CORPSES);}
}
