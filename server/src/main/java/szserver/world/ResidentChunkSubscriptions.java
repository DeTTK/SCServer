package szserver.world;

import java.util.*;
import java.util.concurrent.*;

/** Owner-side player tickets. Movement changes interest, never loads or writes terrain synchronously. */
public final class ResidentChunkSubscriptions<C,S> {
    private record Center(int x,int z) {}
    private final Thread owner=Thread.currentThread();
    private final ResidentChunks<C,S> chunks;
    private final int radius,maxDesired,maxLoading,admissionsPerTick;
    private final long retryNanos;
    private final Map<ResidentChunks.Key,ResidentChunks<C,S>.Ticket> held=new HashMap<>();
    private final Map<ResidentChunks.Key,CompletableFuture<ResidentChunks<C,S>.Ticket>> loading=new HashMap<>();
    private final Map<ResidentChunks.Key,Long> retries=new HashMap<>();
    private final Set<ResidentChunks.Key> absent=new HashSet<>();
    private Map<UUID,Center> players=Map.of();
    private Set<ResidentChunks.Key> desired=Set.of();
    private List<ResidentChunks.Key> priority=List.of();
    private boolean stopped;private int requested;private long failures,acquisitions,releases;

    public ResidentChunkSubscriptions(ResidentChunks<C,S> chunks,int radius,int maxDesired,int maxLoading,int admissionsPerTick,long retryNanos){
        this.chunks=Objects.requireNonNull(chunks);
        if(radius<0||radius>12||maxDesired<1||maxLoading<1||admissionsPerTick<1||retryNanos<1)throw new IllegalArgumentException("resident_subscription_limits");
        this.radius=radius;this.maxDesired=maxDesired;this.maxLoading=maxLoading;this.admissionsPerTick=admissionsPerTick;this.retryNanos=retryNanos;
    }
    private void owner(){if(owner!=Thread.currentThread())throw new IllegalStateException("resident_subscription_wrong_owner");}
    /** Publish only authoritative session positions; rebuild once when a player crosses a chunk boundary. */
    public void players(Map<UUID,World.Vec> positions){
        owner();if(stopped){if(!positions.isEmpty())throw new IllegalStateException("resident_subscriptions_stopping");return;}
        if(positions.size()>128)throw new IllegalArgumentException("resident_subscription_player_limit");
        Map<UUID,Center> next=new HashMap<>();
        for(var entry:positions.entrySet()){
            World.Vec p=Objects.requireNonNull(entry.getValue());
            if(!Double.isFinite(p.x())||!Double.isFinite(p.z())||Math.abs(p.x())>MapBounds.HORIZONTAL||Math.abs(p.z())>MapBounds.HORIZONTAL)throw new IllegalArgumentException("resident_subscription_position");
            next.put(Objects.requireNonNull(entry.getKey()),new Center(Math.floorDiv((int)Math.floor(p.x()),16),Math.floorDiv((int)Math.floor(p.z()),16)));
        }
        if(players.equals(next))return;
        Map<ResidentChunks.Key,Long> distances=new HashMap<>();
        for(Center center:next.values())for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            var key=new ResidentChunks.Key(center.x()+dx,center.z()+dz);if(!inside(key))continue;distances.merge(key,(long)dx*dx+(long)dz*dz,Math::min);
        }
        requested=distances.size();players=Map.copyOf(next);
        priority=distances.keySet().stream().sorted(Comparator.<ResidentChunks.Key>comparingLong(distances::get).thenComparingInt(ResidentChunks.Key::x).thenComparingInt(ResidentChunks.Key::z)).limit(maxDesired).toList();desired=Set.copyOf(priority);
        // Release obsolete pins before admitting new chunks (especially teleports).
        for(var it=held.entrySet().iterator();it.hasNext();){var entry=it.next();if(!desired.contains(entry.getKey())){entry.getValue().close();it.remove();releases++;}}
        retries.keySet().retainAll(desired);
        absent.retainAll(desired);
        // Do not cancel storage work: its completion must close the ticket if interest disappeared.
    }
    public void tick(long now){
        owner();if(stopped)return;int admitted=0;
        for(var key:priority){
            if(loading.size()>=maxLoading||admitted>=admissionsPerTick)break;
            if(held.containsKey(key)||absent.contains(key)||loading.containsKey(key)||now<retries.getOrDefault(key,Long.MIN_VALUE))continue;
            admitted++;var future=chunks.acquire(key);loading.put(key,future);
            future.whenComplete((ticket,error)->{
                owner();loading.remove(key,future);
                if(error!=null){if(ResidentChunks.missingBase(error)){if(!stopped&&desired.contains(key))absent.add(key);return;}failures++;if(!stopped&&desired.contains(key))retries.put(key,now+retryNanos);return;}
                if(stopped||!desired.contains(key)){ticket.close();releases++;return;}
                retries.remove(key);held.put(key,ticket);acquisitions++;
            });
        }
    }
    /** Entire player window ready; absence must not be interpreted as empty terrain. */
    public boolean ready(UUID player){
        owner();Center center=players.get(player);if(center==null||stopped)return false;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){var key=new ResidentChunks.Key(center.x()+dx,center.z()+dz);if(inside(key)&&!held.containsKey(key)&&!absent.contains(key))return false;}
        return true;
    }
    private static boolean inside(ResidentChunks.Key key){return Math.abs((long)key.x())<=MapBounds.CHUNK&&Math.abs((long)key.z())<=MapBounds.CHUNK;}
    public Map<String,Object> metrics(){owner();return Map.of("players",players.size(),"requested",requested,"desired",desired.size(),"held",held.size(),"loading",loading.size(),"retrying",retries.size(),"acquisitions",acquisitions,"releases",releases,"failures",failures,"stopping",stopped);}
    /** Release player pins first; in-flight loads close their tickets on completion while map drains. */
    public void stop(){owner();if(stopped)return;stopped=true;requested=0;players=Map.of();desired=Set.of();priority=List.of();retries.clear();for(var ticket:held.values()){ticket.close();releases++;}held.clear();}
}
