package szserver.net;

import java.util.*;
import szserver.world.SceneHashWire;

/** Transport-owner allocation. Positive actor/corpse ranges never overlap, including cursor wrap. */
public final class NativeEntityIds {
    private final int actorFirst,actorLast,corpseFirst,corpseLast;
    private int nextActor,nextCorpse,actors,corpses;
    private final Set<Integer> live=new HashSet<>();
    public NativeEntityIds(){this(2,999_999,1_000_000,SceneHashWire.FIRST_SCENE_HANDLE-1);}
    /** Small ranges for deterministic wrap/exhaustion tests; not configured by the server. */
    public NativeEntityIds(int actorFirst,int actorLast,int corpseFirst,int corpseLast){
        if(actorFirst<2||actorLast<actorFirst||corpseFirst<=actorLast||corpseLast<corpseFirst)throw new IllegalArgumentException("entity_id_ranges");
        this.actorFirst=nextActor=actorFirst;this.actorLast=actorLast;this.corpseFirst=nextCorpse=corpseFirst;this.corpseLast=corpseLast;
    }
    public int actor(){int value=allocate(nextActor,actorFirst,actorLast);nextActor=advance(value,actorFirst,actorLast);actors++;return value;}
    public int corpse(){int value=allocate(nextCorpse,corpseFirst,corpseLast);nextCorpse=advance(value,corpseFirst,corpseLast);corpses++;return value;}
    private int allocate(int cursor,int first,int last){
        // At most live.size()+1 probes: any longer run contains an unused ID or the range is full.
        long probes=Math.min((long)last-first+1,(long)live.size()+1);
        for(long attempt=0;attempt<probes;attempt++){if(live.add(cursor))return cursor;cursor=advance(cursor,first,last);}
        throw new IllegalStateException("native_entity_id_capacity");
    }
    private static int advance(int value,int first,int last){return value==last?first:value+1;}
    public boolean release(int entity){if(!live.remove(entity))return false;if(entity>=corpseFirst)corpses--;else actors--;return true;}
    public Map<String,Object> metrics(){return Map.of("active",live.size(),"actors",actors,"corpses",corpses);}
}
