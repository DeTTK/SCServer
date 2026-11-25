package szserver.net;

import szserver.client.HandPackets;
import szserver.client.ShotPackets;
import szserver.inventory.PhysicalReload;
import szserver.inventory.Inventory;
import java.util.ArrayDeque;

/** Per-session wire order across firing, hand changes and reload modes. No IO or native construction. */
public final class WeaponActions {
    public enum Kind {HAND,HAND_OVERRIDE,RELOAD,LAUNCHER_RELOAD,UNJAM,PHYSICAL,SHOT}
    public record Action(Kind kind,HandPackets.Selection hand,int ammo,PhysicalReload.Request physical,ShotPackets.Shot shot,int bytes,long life,long deadline,Inventory.Address override) {
        Action(Kind kind,HandPackets.Selection hand,int ammo,PhysicalReload.Request physical,ShotPackets.Shot shot,int bytes,long life,long deadline){
            this(kind,hand,ammo,physical,shot,bytes,life,deadline,null);
        }
    }
    private final ArrayDeque<Action> pending=new ArrayDeque<>();
    private int pendingBytes;
    private long rejected,expired;
    public boolean hand(HandPackets.Selection hand,long life,long now){return offer(new Action(Kind.HAND,hand,0,null,null,0,life,deadline(now)));}
    public boolean handOverride(Inventory.Address address,long life,long now){return offer(new Action(Kind.HAND_OVERRIDE,null,0,null,null,0,life,deadline(now),address));}
    public boolean reload(int ammo,long life,long now){return offer(new Action(Kind.RELOAD,null,ammo,null,null,0,life,deadline(now)));}
    public boolean launcherReload(int ammo,long life,long now){return offer(new Action(Kind.LAUNCHER_RELOAD,null,ammo,null,null,0,life,deadline(now)));}
    public boolean unjam(long life,long now){return offer(new Action(Kind.UNJAM,null,0,null,null,0,life,deadline(now)));}
    public boolean physical(PhysicalReload.Request request,long life,long now){return offer(new Action(Kind.PHYSICAL,null,0,request,null,0,life,deadline(now)));}
    public boolean shot(ShotPackets.Shot shot,int bytes,long life,long now){
        if(shot==null||bytes<84||bytes>65569)throw new IllegalArgumentException("shot_queue_payload");
        return offer(new Action(Kind.SHOT,null,0,null,shot,bytes,life,now+1_000_000_000L));
    }
    private static long deadline(long now){return now+5_000_000_000L;}
    private boolean offer(Action action){if(pending.size()>=32||pendingBytes+action.bytes>262144){rejected++;return false;}pending.addLast(action);pendingBytes+=action.bytes;return true;}
    public Action peek(long life,long now){while(!pending.isEmpty()){Action action=pending.peekFirst();if(action.life==life&&now-action.deadline<=0)return action;pending.removeFirst();pendingBytes-=action.bytes;expired++;}return null;}
    public Action poll(long life,long now){Action action=peek(life,now);if(action!=null){pending.removeFirst();pendingBytes-=action.bytes;}return action;}
    public boolean isEmpty(){return pending.isEmpty();}
    public int size(){return pending.size();}
    public int bytes(){return pendingBytes;}
    public long shotCount(){return pending.stream().filter(a->a.kind==Kind.SHOT).count();}
    public long physicalCount(){return pending.stream().filter(a->a.kind==Kind.PHYSICAL).count();}
    public long rejected(){return rejected;}
    public long expired(){return expired;}
}
