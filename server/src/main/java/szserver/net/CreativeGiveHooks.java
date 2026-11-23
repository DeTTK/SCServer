package szserver.net;

import szserver.inventory.ItemStack;
import szserver.client.NativeAdminPackets;
import java.util.*;

/** Owner-only typed bridge. Creative hints never authorize survival inventory or combat. */
public final class CreativeGiveHooks implements GameInventory.CreativeGrants {
    public interface Host {
        GameInventory creativeInventory();Collection<GameServer.Session> creativeSessions();boolean creativeDeveloper(GameServer.Session session);UUID creativeCharacter(GameServer.Session session);void creativeSend(GameServer.Session session,byte[] packet);void creativeDeny(GameServer.Session session,String error);
    }
    private static final Map<Host,CreativeGiveHooks> bridges=Collections.synchronizedMap(new WeakHashMap<>());
    private final java.lang.ref.WeakReference<Host> server;private final Map<GameServer.Session,CreativeGrantSlots> slots=new WeakHashMap<>();
    private CreativeGiveHooks(Host server){this.server=new java.lang.ref.WeakReference<>(server);}
    public static void bind(Host server){synchronized(bridges){if(bridges.containsKey(server))return;CreativeGiveHooks b=new CreativeGiveHooks(server);server.creativeInventory().bindCreativeGrants(b);bridges.put(server,b);}}
    private CreativeGrantSlots slots(GameServer.Session session){return slots.computeIfAbsent(session,ignored->new CreativeGrantSlots());}
    private boolean dev(GameServer.Session session){Host s=server.get();return s!=null&&s.creativeDeveloper(session);}
    private GameServer.Session recipient(UUID id){Host s=server.get();if(s!=null)for(var session:s.creativeSessions())if(dev(session)&&id.equals(s.creativeCharacter(session)))return session;return null;}
    @Override public boolean active(UUID id){return recipient(id)!=null;}
    @Override public GameInventory.CreativeDelivery prepare(UUID id,List<ItemStack> stacks,Integer requested){var target=recipient(id);if(target==null)throw new IllegalArgumentException("creative_player_not_available");Map<Integer,ItemStack> prepared=slots(target).place(stacks,requested);int first=prepared.keySet().iterator().next();return new GameInventory.CreativeDelivery(first,()->{Host s=server.get();if(s==null||recipient(id)!=target)return;Map<Integer,ItemStack> current=slots(target).place(stacks,requested);slots(target).granted(current);for(var e:current.entrySet())s.creativeSend(target,NativeAdminPackets.creativeSlot(e.getKey(),e.getValue()));});}
    public static void echo(Host server,GameServer.Session session,byte[] body)throws java.io.IOException{CreativeGiveHooks b=bridges.get(server);if(b==null)return;var echo=NativeAdminPackets.echo(body);b.slots(session).echo(echo.slot(),echo.stack());}
    public static boolean ammo(Host server,GameServer.Session session,byte[] body)throws java.io.IOException{CreativeGiveHooks b=bridges.get(server);if(b==null||!b.dev(session)||session.adminSelected!=null)return false;if(body.length!=0)throw new java.io.IOException("admin_ammo_body");try{GameInventory inventories=server.creativeInventory();int selected=session.creativeSelected,item=b.slots(session).factory(selected);if(item<0||!inventories.firearm(item))throw new IllegalArgumentException("admin_ammo_weapon_missing");inventories.giveAmmunition(server.creativeCharacter(session),null,item,b.slots(session).nativeStack(selected),plan->server.creativeSend(session,GamePackets.chat("admin_granted: "+plan.moved())),error->server.creativeDeny(session,error));}catch(IllegalArgumentException error){server.creativeDeny(session,error.getMessage());}return true;}
    private CreativeGiveHooks(){server=null;}
}
