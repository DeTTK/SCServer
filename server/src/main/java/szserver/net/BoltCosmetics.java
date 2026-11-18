package szserver.net;

import szserver.client.*;
import szserver.inventory.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Native bolt modifier request 1271395251. Uses the inventory worker and journal. */
public final class BoltCosmetics {
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Object... args)throws Exception{
        for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){boolean matches=true;Class<?>[] t=m.getParameterTypes();for(int i=0;i<t.length;i++)if(args[i]!=null&&!box(t[i]).isInstance(args[i]))matches=false;if(matches){m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}}throw new NoSuchMethodException(name);
    }
    private static Class<?> box(Class<?> c){return c==int.class?Integer.class:c==boolean.class?Boolean.class:c;}
    private static void run(Object o,String name,Object...args){try{call(o,name,args);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private static Inventory.Address address(DataInputStream in)throws IOException{int present=in.readUnsignedByte();if(present>1)throw new IOException("inventory_presence");return present==0?null:new Inventory.Address(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedShort());}
    public static InventoryPackets.Request read(byte[] body)throws IOException{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));Inventory.Address target=address(in);int absent=in.readUnsignedByte();if(absent>1)throw new IOException("bolt_source_presence");Inventory.Address source=absent==1?null:address(in);int owner=in.readShort(),sequence=in.readShort();if(target==null||absent==0&&source==null||in.available()!=0)throw new IOException("bolt_request_layout");return new InventoryPackets.Request(owner,sequence,target,source==null?List.of():List.of(source),0,false,-1);
    }
    public static boolean handle(Object server,Object session,int id,byte[] body)throws Exception{
        if(id!=1627)return false;
        InventoryPackets.Request request=read(body);
        GameInventory inv=(GameInventory)get(server,"inventories");UUID owner=(UUID)call(server,"character",session);
        ItemStack item=inv.inventory(owner).at(request.source());
        if(item==null)throw new IOException("bolt_target_missing");
        GameItems items=(GameItems)get(inv,"items");JsonObject definitions=(JsonObject)get(items,"definitions");
        JsonObject definition=definitions.getAsJsonObject(Integer.toString(item.id()));
        if(definition==null||!"bolt".equals(definition.get("type").getAsString()))throw new IOException("bolt_target_incompatible");
        Inventory.Address source=request.destinations().isEmpty()?null:request.destinations().get(0);
        int sequence=request.sequence();Runnable ack=()->run(server,"enqueue",session,0,InventoryPackets.ack(0,sequence));
        Consumer<String> reject=error->{run(server,"inventorySnapshot",session,0);ack.run();run(server,"enqueue",session,0,GamePackets.chat(error));};
        if(request.owner()!=0||request.source().aggregate()==2||source!=null&&(source.aggregate()!=1||source.equals(request.source()))){reject.accept("bolt_cosmetic_owner");return true;}
        Consumer<Inventory.Plan> success=plan->run(server,"inventoryChanged",session,plan,ack);
        apply(inv,owner,request.source(),source,success,reject);return true;
    }
    public static GameItems.ModuleEdit fit(GameItems items,ItemStack item,ItemStack source)throws Exception{
        JsonObject definitions=(JsonObject)get(items,"definitions");JsonObject registry=(JsonObject)get(items,"registry");
        JsonObject row=registry.getAsJsonObject(Integer.toString(item.id()));
        if(item.count()!=1||row==null||!row.has("state")||!"556314267".equals(row.get("state").getAsString()))throw new IOException("bolt_target_incompatible");
        if(source!=null){JsonObject d=definitions.getAsJsonObject(Integer.toString(source.id()));if(d==null||!"bolt_gem".equals(d.get("type").getAsString())||!d.has("bolt_specific_params")||!d.getAsJsonObject("bolt_specific_params").has(Integer.toString(item.id())))throw new IOException("bolt_modifier_incompatible");}
        Object adapter=get(items,"adapter");ClassLoader loader=(ClassLoader)get(adapter,"loader");
        Object codec=call(adapter,"codec","556314267"),state=call(adapter,"decode",codec,item.state());
        Map<Object,Object> map=(Map<Object,Object>)state.getClass().getMethod("for").invoke(state);
        Object key=Class.forName("1750946572",true,loader).getField("try").get(null),old=map.remove(key);
        if(source!=null){byte[] wire=InventoryPackets.stack(source.count(1));map.put(key,Class.forName("211097523",true,loader).getConstructor(DataInput.class).newInstance(new DataInputStream(new ByteArrayInputStream(wire,1,wire.length-1))));}
        ItemStack previous=null;if(old!=null){ByteArrayOutputStream b=new ByteArrayOutputStream();old.getClass().getMethod("switch",DataOutput.class).invoke(old,new DataOutputStream(b));previous=items.readStack(b.toByteArray());}
        return new GameItems.ModuleEdit(item.state((byte[])call(adapter,"encode",codec,state)),previous);
    }
    public static Inventory.Plan plan(GameItems items,Inventory before,Inventory.Address target,Inventory.Address source)throws Exception{
        ItemStack item=before.at(target),modifier=source==null?null:before.at(source);
        if(item==null||source!=null&&(source.equals(target)||modifier==null))throw new IOException("bolt_inventory_address");
        var edit=fit(items,item,modifier);Map<Inventory.Address,ItemStack> change=new LinkedHashMap<>();change.put(target,edit.weapon());if(source!=null)change.put(source,modifier.count(modifier.count()-1));
        Inventory after=before.replace(change,0).after();if(edit.removed()!=null)after=after.give(edit.removed()).after();
        Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());for(var a:addresses)if(before.at(a)!=after.at(a))delta.put(a,after.at(a));return before.replace(delta,0);
    }
    public static void apply(GameInventory inv,UUID id,Inventory.Address target,Inventory.Address source,Consumer<Inventory.Plan> success,Consumer<String> failure)throws Exception{
        Object c=call(inv,"character",id);if((boolean)get(c,"busy")||(boolean)get(c,"failed")||(boolean)get(c,"deathClear")){failure.accept("inventory_busy");return;}
        Inventory before=(Inventory)get(c,"inventory");GameItems items=(GameItems)get(inv,"items");set(c,"busy",true);
        try{((Executor)get(inv,"factories")).execute(()->{try{Inventory.Plan prepared=plan(items,before,target,source);run(inv,"completion",(Runnable)()->run(inv,"persist",id,c,prepared,success,failure));}catch(Exception|LinkageError e){run(inv,"completion",(Runnable)()->{try{set(c,"busy",false);}catch(Exception x){throw new IllegalStateException(x);}failure.accept(Objects.toString(e.getMessage(),"bolt_cosmetic_failed"));});}});}catch(RejectedExecutionException e){set(c,"busy",false);failure.accept("inventory_factory_queue_full");}
    }
    private BoltCosmetics(){}
}
