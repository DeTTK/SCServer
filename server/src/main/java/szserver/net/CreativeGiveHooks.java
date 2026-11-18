package szserver.net;

import com.google.gson.JsonObject;
import szserver.inventory.ItemStack;
import szserver.inventory.WeaponProfile;
import szserver.client.NativeAdminPackets;
import java.lang.reflect.*;
import java.util.*;

/** Owner-only bridge to the native creative model. Keeps release overlays independent
 * of unrelated GameServer work and never trusts client hints as survival items. */
public final class CreativeGiveHooks implements GameInventory.CreativeGrants {
    private static final Map<Object,CreativeGiveHooks> bridges=Collections.synchronizedMap(new WeakHashMap<>());
    private final java.lang.ref.WeakReference<Object> server;
    private final Map<Object,CreativeGrantSlots> slots=new WeakHashMap<>();
    private CreativeGiveHooks(Object server){this.server=new java.lang.ref.WeakReference<>(server);}
    private static Object field(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(ReflectiveOperationException e){throw new IllegalStateException("creative_adapter_field:"+n,e);}}
    private static Object invoke(Object o,String n,Object... args){
        search:for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i]))continue search;
            try{m.setAccessible(true);return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException cause)throw cause;throw new IllegalStateException("creative_adapter_call:"+n,e.getCause());}catch(ReflectiveOperationException e){throw new IllegalStateException("creative_adapter_call:"+n,e);}
        }throw new IllegalStateException("creative_adapter_method:"+n);
    }
    public static void bind(Object server){synchronized(bridges){if(bridges.containsKey(server))return;CreativeGiveHooks b=new CreativeGiveHooks(server);((GameInventory)field(server,"inventories")).bindCreativeGrants(b);bridges.put(server,b);}}
    private CreativeGrantSlots slots(Object session){return slots.computeIfAbsent(session,ignored->new CreativeGrantSlots());}
    private boolean dev(Object session){Object s=server.get();return s!=null&&Boolean.TRUE.equals(field(session,"ready"))&&Boolean.TRUE.equals(invoke(s,"administrator",session));}
    private Object recipient(UUID id){Object s=server.get();if(s!=null)for(Object session:((Map<?,?>)field(s,"sessions")).values())if(dev(session)&&id.equals(invoke(s,"character",session)))return session;return null;}
    @Override public boolean active(UUID id){return recipient(id)!=null;}
    @Override public GameInventory.CreativeDelivery prepare(UUID id,List<ItemStack> stacks,Integer requested){
        Object target=recipient(id);if(target==null)throw new IllegalArgumentException("creative_player_not_available");
        Map<Integer,ItemStack> prepared=slots(target).place(stacks,requested);int first=prepared.keySet().iterator().next();
        return new GameInventory.CreativeDelivery(first,()->{
            Object s=server.get();if(s==null||recipient(id)!=target)return;
            Map<Integer,ItemStack> current=slots(target).place(stacks,requested);slots(target).granted(current);
            for(var e:current.entrySet())invoke(s,"enqueueOrRetire",target,NativeAdminPackets.creativeSlot(e.getKey(),e.getValue()));
        });
    }
    public static void echo(Object server,Object session,byte[] body)throws java.io.IOException{
        CreativeGiveHooks b=bridges.get(server);if(b==null)return;var echo=NativeAdminPackets.echo(body);b.slots(session).echo(echo.slot(),echo.stack());
    }
    public static boolean ammo(Object server,Object session,byte[] body)throws java.io.IOException{
        CreativeGiveHooks b=bridges.get(server);if(b==null||!b.dev(session)||field(session,"adminSelected")!=null)return false;
        if(body.length!=0)throw new java.io.IOException("admin_ammo_body");
        try{
            GameInventory inventories=(GameInventory)field(server,"inventories");int item=b.slots(session).factory((Integer)field(session,"creativeSelected"));
            WeaponProfile profile=item<0?null:WeaponProfile.from(inventories.catalog().get(item).properties());if(profile==null||profile.ammunition().isEmpty())throw new IllegalArgumentException("admin_ammo_weapon_missing");
            int ammo=profile.ammunition().get(0);UUID id=(UUID)invoke(server,"character",session);
            inventories.give(id,ammo,inventories.catalog().get(ammo).stackLimit(),0,new JsonObject(),plan->invoke(server,"enqueueOrRetire",session,GamePackets.chat("admin_granted: "+plan.moved())),error->invoke(server,"denyNativeGrant",session,error));
        }catch(IllegalArgumentException error){invoke(server,"denyNativeGrant",session,error.getMessage());}return true;
    }
}
