package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Genuine bolt/container factories, predicates, slot setter and banks. Inventory owns the transaction. */
final class NativeItemSlots {
    private final NativeItemFactories host;private final Class<?> bolt,gem,container,artifact,bank,slots;
    private final Method readId,write,get,set,accept,mutable,gemAllows,researched,key,containerTotal,containerArtifacts;
    private final Class<?> statsFactory;private final Method stats;private final Object boltKey;private final Field capacity;private final NativeMedicines.Export export;
    NativeItemSlots(NativeItemFactories host,NativeMedicines.Export export)throws Exception{
        this.host=host;this.export=export;statsFactory=host.type("59871510");stats=NativeMethods.instance(statsFactory,"for",host.type("964863275"),host.stack);bolt=host.type("942610364");gem=host.type("725248314");container=host.type("1441854005");artifact=host.type("702433834");bank=host.type("964863275");slots=host.type("1579742958");
        readId=NativeMethods.instance(host.stack,"continue",int.class);write=NativeMethods.instance(host.stack,"switch",void.class,DataOutput.class);get=NativeMethods.instance(slots,"implements",host.stack,host.stack,Object.class);set=NativeMethods.instance(slots,"public",void.class,host.stack,host.stack,Object.class);accept=NativeMethods.instance(slots,"else",boolean.class,host.stack,host.base,Object.class);mutable=NativeMethods.instance(slots,"volatile",boolean.class,Object.class,host.stack,host.stack);
        gemAllows=NativeMethods.instance(gem,"long",boolean.class,bolt);researched=NativeMethods.instance(artifact,"const",boolean.class,host.stack);key=NativeMethods.staticMethod(host.type("830745898"),"private",host.type("830745898"),int.class);boltKey=host.type("1750946572").getField("try").get(null);capacity=container.getField("else");
        containerTotal=NativeMethods.instance(container,"for",bank,host.stack);containerArtifacts=NativeMethods.instance(container,"long",bank,host.stack);
        for(int id:new TreeSet<>(host.classes.keySet())){Class<?> t=host.type(host.classes.get(id));if(bolt.isAssignableFrom(t)||gem.isAssignableFrom(t)||container.isAssignableFrom(t)||artifact.isAssignableFrom(t)||statsFactory.isAssignableFrom(t))host.factory(id);}
    }
    private Object decode(byte[] raw)throws Exception{return host.stack.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw));}
    private byte[] bytes(Object value)throws Exception{if(value==null)return null;var b=new ByteArrayOutputStream();write.invoke(value,new DataOutputStream(b));return b.toByteArray();}
    private <T> T bound(Object[] roots,java.util.concurrent.Callable<T> action)throws Exception{
        Set<Integer> ids=new HashSet<>();Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<Object> queue=new ArrayDeque<>();for(Object root:roots)if(root!=null)queue.add(root);Class<?> stateType=host.type("822754569");Method state=NativeMethods.instance(host.stack,"for",Object.class,Class.class),children=NativeMethods.instance(stateType,"for",Map.class);
        while(!queue.isEmpty()){Object item=queue.remove();if(!seen.add(item))continue;ids.add((Integer)readId.invoke(item));Object value=state.invoke(item,stateType);if(value!=null)for(Object child:((Map<?,?>)children.invoke(value)).values())if(child!=null)queue.add(child);}
        return host.bound(ids,action);
    }
    record Edit(byte[] stack,byte[] previous){}
    Edit edit(byte[] parent,byte[] source,int slot,boolean boltEdit)throws Exception{Object p=decode(parent),s=source==null?null:decode(source);return bound(new Object[]{p,s},()->{
        Object factory=host.factory((Integer)readId.invoke(p));Object k;
        if(boltEdit){if(!bolt.isInstance(factory))throw new IOException("bolt_target_incompatible");k=boltKey;if(s!=null){Object sf=host.factory((Integer)readId.invoke(s));if(!gem.isInstance(sf)||!(Boolean)gemAllows.invoke(sf,factory))throw new IOException("bolt_modifier_incompatible");}}
        else{if(!container.isInstance(factory)||slot<0||slot>=capacity.getInt(factory))throw new IOException("artifact_socket_capacity");k=key.invoke(null,slot);if(s!=null){Object sourceFactory=host.factory((Integer)readId.invoke(s));if(!artifact.isInstance(sourceFactory)||!(Boolean)researched.invoke(sourceFactory,s))throw new IOException("artifact_not_researched");}}
        Object old=get.invoke(factory,p,k);if(old!=null&&!(Boolean)mutable.invoke(factory,k,p,old))throw new IOException("attachment_locked");
        if(s!=null&&!(Boolean)accept.invoke(factory,p,host.factory((Integer)readId.invoke(s)),k))throw new IOException("attachment_incompatible");
        set.invoke(factory,p,s,k);Object result=get.invoke(factory,p,k);if((s==null)!=(result==null)||s!=null&&!Arrays.equals(bytes(s),bytes(result)))throw new IOException("attachment_native_set_rejected");return new Edit(bytes(p),bytes(old));
    });}
    GameItems.EquipmentStats container(byte[] raw)throws Exception{Object p=decode(raw);return bound(new Object[]{p},()->{int id=(Integer)readId.invoke(p);if(!statsFactory.isAssignableFrom(host.type(host.classes.get(id))))return new GameItems.EquipmentStats(Map.of(),Map.of());Object f=host.factory(id);return new GameItems.EquipmentStats(export.read(stats.invoke(f,p)),container.isInstance(f)?export.read(containerArtifacts.invoke(f,p)):Map.of());});}
}
