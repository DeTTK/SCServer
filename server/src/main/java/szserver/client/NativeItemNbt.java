package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Original complete-stack NBT import/export with scoped factories and lossless stream normalization. */
final class NativeItemNbt {
    private final NativeItemFactories host;private final Class<?> stack,compound,nbt;
    private final Constructor<?> streamConstructor,nbtConstructor;private final Method read,export,write,writeNbt;
    private final Field fields;private final String idKey,tagKey;
    NativeItemNbt(ClassLoader loader,NativeItemFactories host)throws Exception{
        // The stock NBT wrapper otherwise tries to load a second JNI copy by a bare DLL name.
        // Let the pinned host dependency own JNI, initialize only the wrapper in its client path, then restore.
        com.github.luben.zstd.Zstd.compress(new byte[0]);Class<?> environment=Class.forName("220710426",true,loader);Field mode=environment.getField("if");Object previous=mode.get(null);mode.set(null,Class.forName("1553900928",true,loader).getField("CLIENT").get(null));try{Class.forName("1464336358",true,loader);}finally{mode.set(null,previous);}
        this.host=host;if(host!=null){Class<?> settings=host.type("1033600170");Object companion=settings.getField("case").get(null);NativeMethods.instance(companion.getClass(),"throw",void.class).invoke(companion);}stack=Class.forName("211097523",true,loader);compound=Class.forName("98008865",true,loader);nbt=Class.forName("724465038",true,loader);
        streamConstructor=stack.getConstructor(DataInput.class);nbtConstructor=stack.getConstructor(compound);
        read=NativeMethods.staticMethod(nbt,"native",nbt,DataInput.class);export=NativeMethods.instance(stack,"char",compound,compound);write=NativeMethods.instance(stack,"switch",void.class,DataOutput.class);writeNbt=NativeMethods.staticMethod(nbt,"abstract",void.class,nbt,DataOutput.class);fields=compound.getField("transient");
        String[] names=(String[])Class.forName("1281655698",true,loader).getField("if").get(null);idKey=names[39125];tagKey=names[34343];
    }
    private Object decode(byte[] raw)throws Exception{if(raw.length<10||raw.length>8192)throw new IOException("native_stack_size");var in=new BoundedNativeInput(raw);Object value=streamConstructor.newInstance(in);if(in.available()!=0)throw new IOException("native_stack_trailing");return value;}
    private Object root(Object value)throws Exception{return export.invoke(value,compound.getConstructor().newInstance());}
    private byte[] bytes(Object value)throws Exception{return szserver.net.Wire.bytes(out->write.invoke(value,out));}
    private Map<String,Object> map(Object value)throws Exception{return (Map<String,Object>)fields.get(value);}
    private Object parse(byte[] raw)throws Exception{NativeNbtLimits.validate(raw);var in=new BoundedNativeInput(raw);Object value=read.invoke(null,in);if(!compound.isInstance(value)||in.available()!=0)throw new IOException("item_config_root");return value;}
    /** Only ordering may normalize. Every native property, UUID and nested state must survive. */
    private byte[] canonical(Object value)throws Exception{
        Object before=root(value);byte[] first=bytes(value);Object decoded=decode(first);if(!before.equals(root(decoded)))throw new IOException("native_edited_stack_semantics_changed");
        byte[] second=bytes(decoded);Object stable=decode(second);if(!Arrays.equals(second,bytes(stable))||!before.equals(root(stable)))throw new IOException("native_edited_stack_unstable");return second;
    }
    private void collect(Object value,Set<Integer> ids,int depth)throws Exception{
        if(depth>8)throw new IOException("nested_item_depth");if(compound.isInstance(value)){
            Map<String,Object> values=map(value);if(values.containsKey(idKey)){int id=((Short)NativeMethods.instance(compound,"default",short.class,String.class).invoke(value,idKey))&65535;if(host.classes.containsKey(id)){Class<?> type=host.type(host.classes.get(id));Object registered=host.registry[id];Object state=registered==null?null:NativeMethods.instance(host.base,"default",host.type("154675812")).invoke(registered);boolean extended=state!=null&&state.getClass()!=host.type("154675812");boolean resourceConstructor;try{type.getConstructor(host.resource);resourceConstructor=true;}catch(NoSuchMethodException e){resourceConstructor=false;}if(extended&&(resourceConstructor||registered!=null&&registered.getClass()==type)){host.factory(id);ids.add(id);}else if(registered==null)throw new IOException("native_nbt_factory_missing:"+id);}}
            for(Object child:values.values())if(compound.isInstance(child))collect(child,ids,depth+1);else if(child!=null&&child.getClass().getName().equals("1553799403")){List<?> list=(List<?>)child.getClass().getField("for").get(child);for(Object element:list)if(compound.isInstance(element))collect(element,ids,depth+1);}
        }
    }
    private <T>T bound(Object root,java.util.concurrent.Callable<T> action)throws Exception{if(host==null)return action.call();Set<Integer> ids=new HashSet<>();collect(root,ids,0);return host.bound(ids,action);}
    byte[] stream(byte[] raw)throws Exception{Object value=decode(raw),root=root(value);return bound(root,()->canonical(value));}
    byte[] config(byte[] raw)throws Exception{Object value=decode(raw);var output=new ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(output)){writeNbt.invoke(null,root(value),new DataOutputStream(gzip));}return output.toByteArray();}
    byte[] configImport(byte[] raw)throws Exception{
        if(raw.length<1||raw.length>65536)throw new IOException("item_config_size");Object root=parse(raw[0]==10?raw:NativeNbtLimits.expand(raw)),value=nbtConstructor.newInstance(root);restoreArtifactBonuses(value,root,0);return bound(root,()->canonical(value));
    }
    byte[] apply(byte[] raw,byte[] tags)throws Exception{
        Object root=root(decode(raw)),changes=parse(tags),tag=map(root).get(tagKey);if(tag==null){tag=compound.getConstructor().newInstance();map(root).put(tagKey,tag);}map(tag).putAll(map(changes));Object value=nbtConstructor.newInstance(root);restoreArtifactBonuses(value,root,0);return bound(root,()->canonical(value));
    }
    /** The native NBT constructor validates artifact tags but leaves the state's bonus enum set empty; replay it from the edited compound. */
    private void restoreArtifactBonuses(Object value,Object root,int depth)throws Exception{
        if(depth>8)throw new IOException("nested_item_depth");if(host==null)return;Object state=null;Class<?> base=host.type("154675812");
        for(Method method:value.getClass().getMethods())if(method.getName().equals("finally")&&method.getParameterCount()==0&&method.getReturnType()==base){state=method.invoke(value);break;}
        if(state==null)return;Object tag=map(root).get(tagKey);if(!compound.isInstance(tag))return;Map<String,Object> fields=map(tag);
        if(state.getClass().getName().equals("1609149377")){Object bonus=fields.get("bonus_properties");if(bonus!=null){List<?> values=(List<?>)bonus.getClass().getField("for").get(bonus);if(values.size()>32)throw new IOException("artifact_bonus_count");Set<Object> selected=new HashSet<>();Class<?> type=host.type("1924679062");for(Object value0:values)selected.add(type.getMethod("valueOf",String.class).invoke(null,value0.getClass().getMethod("char").invoke(value0)));state.getClass().getField("interface").set(state,selected);}}
        Object attachments=fields.get("attach");if(!compound.isInstance(attachments))return;Map<String,Object> nested=map(attachments);Class<?> stackType=host.type("211097523");
        for(Field field:state.getClass().getFields())if(Map.class.isAssignableFrom(field.getType())){Object map=field.get(state);if(map==null)continue;for(var entry:((Map<?,?>)map).entrySet()){if(entry.getKey()==null||!stackType.isInstance(entry.getValue()))continue;for(Method method:state.getClass().getMethods())if(method.getName().equals("catch")&&method.getReturnType()==String.class&&method.getParameterCount()==1&&method.getParameterTypes()[0].isInstance(entry.getKey())){Object child=nested.get(method.invoke(state,entry.getKey()));if(compound.isInstance(child))restoreArtifactBonuses(entry.getValue(),child,depth+1);break;}}}
    }
}
