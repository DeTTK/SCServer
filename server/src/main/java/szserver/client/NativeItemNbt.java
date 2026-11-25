package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;

/** Original complete-stack NBT import/export with scoped factories and lossless stream normalization. */
final class NativeItemNbt {
    static byte[] unsignedStackMetadata(byte[] source)throws IOException{
        var reader=new ClassReader(source);
        var writer=new ClassWriter(reader,0);
        int[] replacements={0};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                var method=super.visitMethod(access,name,desc,signature,exceptions);
                if(!name.equals("<init>")||!desc.equals("(L98008865;)V"))return method;
                return new MethodVisitor(Opcodes.ASM9,method){
                    @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                        if(opcode==Opcodes.INVOKESTATIC&&owner.equals("java/lang/Math")&&name.equals("max")&&descriptor.equals("(II)I")){
                            // The native NBT constructor clamps a signed short after reading it.
                            // Stack packets and Item Editor metadata use the full unsigned 16-bit range.
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.POP);
                            super.visitLdcInsn(0xffff);
                            super.visitInsn(Opcodes.IAND);
                            replacements[0]++;
                        }else super.visitMethodInsn(opcode,owner,name,descriptor,itf);
                    }
                };
            }
        },0);
        if(replacements[0]!=1)throw new IOException("native_stack_nbt_metadata_shape");
        return writer.toByteArray();
    }
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
    /** Only native collection ordering may normalize between imports and stream writes. */
    private byte[] canonical(Object value)throws Exception{
        Object before=comparableRoot(value);
        Object decoded=decode(bytes(value));
        if(!before.equals(comparableRoot(decoded)))throw new IOException("native_edited_stack_semantics_changed");
        byte[] second=bytes(decoded);
        Object stable=decode(second);
        if(!Arrays.equals(second,bytes(stable))||!before.equals(comparableRoot(stable)))
            throw new IOException("native_edited_stack_unstable");
        return second;
    }
    private Object comparableRoot(Object item)throws Exception{
        Object root=root(item);
        sortBonusSets(root,0);
        return root;
    }
    private void sortBonusSets(Object compoundTag,int depth)throws Exception{
        if(depth>8)throw new IOException("nested_item_depth");
        for(var entry:map(compoundTag).entrySet()){
            Object child=entry.getValue();
            if(compound.isInstance(child))sortBonusSets(child,depth+1);
            else if(child!=null&&child.getClass().getName().equals("1553799403")){
                var values=(List<Object>)child.getClass().getField("for").get(child);
                // Artifact bonuses are a native set. NBT represents that set as an ordered list.
                if(entry.getKey().equals("bonus_properties")&&values.stream().allMatch(v->v.getClass().getName().equals("1233473164"))){
                    var keys=new IdentityHashMap<Object,String>();
                    for(Object value:values)keys.put(value,(String)value.getClass().getMethod("char").invoke(value));
                    values.sort(Comparator.comparing(keys::get));
                }
                for(Object element:values)
                    if(compound.isInstance(element))sortBonusSets(element,depth+1);
            }
        }
    }
    private void collect(Object value,Set<Integer> ids,int depth)throws Exception{
        if(depth>8)throw new IOException("nested_item_depth");
        if(!compound.isInstance(value))return;
        Map<String,Object> values=map(value);
        if(values.containsKey(idKey)){
            int id=((Short)NativeMethods.instance(compound,"default",short.class,String.class).invoke(value,idKey))&65535;
            if(host.hasHeadlessArchiveFactory(id)){
                host.factory(id);
                ids.add(id);
            }else if(host.classes.containsKey(id)){
                Class<?> type=host.type(host.classes.get(id));
                Object registered=host.registry[id];
                if(registered==null)throw new IOException("native_nbt_factory_missing:"+id);
                if(registered.getClass()==type){
                    host.factory(id);
                    ids.add(id);
                }
            }
        }
        for(Object child:values.values()){
            if(compound.isInstance(child))collect(child,ids,depth+1);
            else if(child!=null&&child.getClass().getName().equals("1553799403")){
                for(Object element:(List<?>)child.getClass().getField("for").get(child))
                    if(compound.isInstance(element))collect(element,ids,depth+1);
            }
        }
    }
    private <T>T bound(Object root,java.util.concurrent.Callable<T> action)throws Exception{
        if(host==null)return action.call();
        Set<Integer> ids=new HashSet<>();
        collect(root,ids,0);
        return host.bound(ids,action);
    }
    @FunctionalInterface interface StackReader<T>{ T read(byte[] wire)throws Exception; }

    <T>T stream(byte[] raw,StackReader<T> reader)throws Exception{
        // The first read finds the item and its attachments. Run the actual import and
        // validation with every discovered factory installed in the native registry.
        return bound(root(decode(raw)),()->reader.read(canonical(decode(raw))));
    }
    <T>T inspect(byte[] raw,Assessment<T> rule)throws Exception{
        return bound(root(decode(raw)),()->rule.evaluate(decode(raw)));
    }
    @FunctionalInterface interface Assessment<T>{ T evaluate(Object item)throws Exception; }

    byte[] config(byte[] raw)throws Exception{
        return bound(root(decode(raw)),()->{
            var output=new ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(output)){
                writeNbt.invoke(null,root(decode(raw)),new DataOutputStream(gzip));
            }
            return output.toByteArray();
        });
    }
    <T>T configImport(byte[] raw,StackReader<T> reader)throws Exception{
        if(raw.length<1||raw.length>65536)throw new IOException("item_config_size");
        Object root=parse(raw[0]==10?raw:NativeNbtLimits.expand(raw));
        return bound(root,()->reader.read(canonical(nbtConstructor.newInstance(root))));
    }
    <T>T create(int id,int count,int metadata,byte[] tags,StackReader<T> reader)throws Exception{
        host.factory(id);
        return host.bound(List.of(id),()->{
            Object item=stack.getConstructor(int.class,int.class,int.class).newInstance(id,count,metadata);
            if(tags==null){
                try{return reader.read(canonical(item));}
                catch(InvocationTargetException failure){throw new IOException("native_item_default_unavailable:"+id,failure.getCause());}
            }
            Object root=root(item),changes=parse(tags);
            mergeTags(root,changes);
            return bound(root,()->readTagged(root,changes,reader));
        });
    }
    <T>T apply(byte[] raw,byte[] tags,StackReader<T> reader)throws Exception{
        Object scope=root(decode(raw)),changes=parse(tags);
        mergeTags(scope,changes);
        return bound(scope,()->{
            Object root=root(decode(raw));
            mergeTags(root,changes);
            return readTagged(root,changes,reader);
        });
    }
    private <T>T readTagged(Object root,Object changes,StackReader<T> reader)throws Exception{
        Object item=nbtConstructor.newInstance(root);
        Object written=map(comparableRoot(item)).get(tagKey);
        sortBonusSets(changes,0);
        for(var requested:map(changes).entrySet()){
            boolean moduleAttributes=requested.getKey().equals("attributes")&&
                    host.type("623024217").isInstance(NativeMethods.instance(stack,"finally",host.type("154675812")).invoke(item));
            if(written==null||!retained(requested.getValue(),map(written).get(requested.getKey()),moduleAttributes))
                throw new IOException("item_editor_field_not_applied:"+requested.getKey());
        }
        return reader.read(canonical(item));
    }
    private boolean retained(Object requested,Object written,boolean moduleAttributes)throws Exception{
        if(Objects.equals(requested,written))return true;
        if(written==null)return false;
        if(compound.isInstance(requested)&&compound.isInstance(written)){
            var actual=map(written);
            for(var entry:map(requested).entrySet()){
                Object value=actual.get(entry.getKey());
                if(value==null&&moduleAttributes&&Set.of("type","quality").contains(entry.getKey())){
                    byte kind=(Byte)nbt.getMethod("super").invoke(entry.getValue());
                    if(kind>=1&&kind<=4&&number(entry.getValue(),kind).signum()==0)continue;
                }
                if(!retained(entry.getValue(),value,false))return false;
            }
            return true;
        }
        if(requested.getClass().getName().equals("1553799403")&&written.getClass()==requested.getClass()){
            var field=requested.getClass().getField("for");
            var expected=(List<?>)field.get(requested);
            var actual=(List<?>)field.get(written);
            if(expected.size()!=actual.size())return false;
            for(int i=0;i<expected.size();i++)if(!retained(expected.get(i),actual.get(i),moduleAttributes))return false;
            return true;
        }
        // Stock tags can change numeric widths while retaining the value.
        var valueType=nbt.getMethod("super");
        byte expectedType=(Byte)valueType.invoke(requested),actualType=(Byte)valueType.invoke(written);
        return expectedType>=1&&expectedType<=6&&actualType>=1&&actualType<=6
                &&number(requested,expectedType).compareTo(number(written,actualType))==0;
    }
    private java.math.BigDecimal number(Object value,int type)throws Exception{
        Method payload=nbt.getMethod("this",DataOutput.class);
        var raw=new DataInputStream(new ByteArrayInputStream(szserver.net.Wire.bytes(out->payload.invoke(value,out))));
        return switch(type){
            case 1 -> java.math.BigDecimal.valueOf(raw.readByte());
            case 2 -> java.math.BigDecimal.valueOf(raw.readShort());
            case 3 -> java.math.BigDecimal.valueOf(raw.readInt());
            case 4 -> java.math.BigDecimal.valueOf(raw.readLong());
            case 5 -> new java.math.BigDecimal(Float.toString(raw.readFloat()));
            case 6 -> java.math.BigDecimal.valueOf(raw.readDouble());
            default -> throw new IllegalArgumentException("native_tag_numeric_type");
        };
    }
    private void mergeTags(Object root,Object changes)throws Exception{
        Object tag=map(root).get(tagKey);
        if(tag==null){
            tag=compound.getConstructor().newInstance();
            map(root).put(tagKey,tag);
        }
        map(tag).putAll(map(changes));
    }
}
