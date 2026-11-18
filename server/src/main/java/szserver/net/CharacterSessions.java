package szserver.net;

import java.util.*;

/** Native-owner-only session ownership. Late authentication cannot reclaim a replaced character. */
public final class CharacterSessions<T> {
    private static final int HISTORY_LIMIT=4096;
    private static final class Entry<T>{final long order;T owner;Entry(long order,T owner){this.order=order;this.owner=owner;}}
    private final Map<UUID,Entry<T>> entries=new HashMap<>();
    private long accepted,replaced,stale;
    private volatile Map<String,Object> view=Map.of("owners",0,"histories",0,"accepted",0L,"replaced",0L,"stale",0L,"historyLimit",HISTORY_LIMIT);
    public boolean accept(UUID character,long order,T owner){
        Objects.requireNonNull(character);Objects.requireNonNull(owner);if(order<=0)throw new IllegalArgumentException("invalid_authentication_order");
        Entry<T> previous=entries.get(character);
        if(previous!=null&&order<=previous.order){if(previous.order==order&&previous.owner==owner)return true;stale++;publish();return false;}
        if(previous==null&&entries.size()>=HISTORY_LIMIT)throw new IllegalStateException("character_session_history_busy");
        if(previous!=null&&previous.owner!=null&&previous.owner!=owner)replaced++;
        entries.put(character,new Entry<>(order,owner));accepted++;publish();return true;
    }
    public void retire(UUID character,T owner){Entry<T> current=entries.get(character);if(current!=null&&current.owner==owner){current.owner=null;publish();}}
    /** Retain a retired epoch only while an older, still-live authentication could finish. */
    public void prune(long minimumPendingOrder){entries.values().removeIf(entry->entry.owner==null&&entry.order<minimumPendingOrder);publish();}
    public Map<String,Object> metrics(){return view;}
    private void publish(){view=Map.of("owners",(int)entries.values().stream().filter(entry->entry.owner!=null).count(),"histories",entries.size(),"accepted",accepted,"replaced",replaced,"stale",stale,"historyLimit",HISTORY_LIMIT);}
}
