package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.security.MessageDigest;
import szserver.world.ActorControllerState;

/** Detached, bounded movement checkpoint. Uses native property codecs instead of encoded store bits.
 * Component effects/world side effects beyond the movement host are a separate persistence obligation. */
final class NativeActorPersistence {
    private static final int MAGIC=0x53414331,VERSION=8;
    private final ClassLoader loader;
    private final Class<?> property,bankType,stateType,entityType,flagsType;
    private final Method propertyWrite,propertyRead;
    private final Field propertyId,propertyType,propertyValue,propertyPrevious,propertyRevision;
    private final NativeActorEffects effects;
    private final NativeActorEquipment equipment;
    private final NativeActorLife life;
    private record Assignment(Object owner,Field field,Object value){void apply()throws Exception{field.set(owner,value);}}
    record Decoded(byte[] state,boolean crouch,List<Assignment> changes,List<Runnable> containers,boolean living){
        void apply()throws Exception{for(var change:changes)change.apply();for(var change:containers)change.run();}
    }
    NativeActorPersistence(ClassLoader loader,NativeActorEquipment equipment)throws Exception {
        this.loader=loader;this.equipment=equipment;life=new NativeActorLife(loader);property=type("1798539107");bankType=type("1536302723");stateType=type("1943200621");entityType=type("1217489680");flagsType=type("769798186");
        propertyWrite=bankType.getMethod("class",DataOutput.class,Class.class,Object.class);propertyRead=bankType.getMethod("enum",Object.class,DataInput.class,Class.class);
        propertyId=property.getField("throw");propertyType=property.getField("return");propertyValue=property.getField("do");propertyPrevious=property.getField("break");propertyRevision=property.getField("instanceof");
        effects=new NativeActorEffects(loader);
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object owner,String name)throws Exception{return field(owner.getClass(),name).get(owner);}
    private Object bank(Object actor)throws Exception{return field(entityType,"private").get(actor);}
    private Object flags(Object actor)throws Exception{
        Object storage=field(type("200241649"),"finally").get(actor);
        for(Field f:storage.getClass().getDeclaredFields())if(f.getType()==flagsType){f.setAccessible(true);return f.get(storage);}
        throw new IllegalStateException("actor_controller_flags");
    }
    private List<Field> scalars(Class<?> type){
        return Arrays.stream(type.getDeclaredFields()).filter(f->!Modifier.isStatic(f.getModifiers())&&f.getType().isPrimitive()).sorted(Comparator.comparing(Field::getName)).peek(f->f.setAccessible(true)).toList();
    }
    private static void scalar(DataOutput out,Class<?> type,Object value)throws IOException{
        if(type==boolean.class)out.writeBoolean((Boolean)value);else if(type==byte.class)out.writeByte((Byte)value);
        else if(type==short.class)out.writeShort((Short)value);else if(type==char.class)out.writeChar((Character)value);
        else if(type==int.class)out.writeInt((Integer)value);else if(type==long.class)out.writeLong((Long)value);
        else if(type==float.class){float f=(Float)value;if(!Float.isFinite(f))throw new IOException("actor_controller_float");out.writeFloat(f);}
        else if(type==double.class){double d=(Double)value;if(!Double.isFinite(d))throw new IOException("actor_controller_double");out.writeDouble(d);}
        else throw new IOException("actor_controller_scalar");
    }
    private static Object scalar(DataInput in,Class<?> type)throws IOException{
        if(type==boolean.class)return in.readBoolean();if(type==byte.class)return in.readByte();if(type==short.class)return in.readShort();if(type==char.class)return in.readChar();
        if(type==int.class)return in.readInt();if(type==long.class)return in.readLong();if(type==float.class)return in.readFloat();if(type==double.class)return in.readDouble();throw new IOException("actor_controller_scalar");
    }
    private void writeScalars(DataOutput out,Object owner,Class<?> type)throws Exception{
        var fields=scalars(type);out.writeUTF(type.getName());out.writeInt(fields.size());for(var f:fields){out.writeUTF(f.getName());scalar(out,f.getType(),f.get(owner));}
    }
    private void readScalars(BoundedNativeInput in,Object owner,Class<?> type,List<Assignment> changes)throws Exception{
        var fields=scalars(type);if(!in.readUTF().equals(type.getName())||in.readInt()!=fields.size())throw new IOException("actor_controller_scalar_layout");
        for(var f:fields){if(!in.readUTF().equals(f.getName()))throw new IOException("actor_controller_scalar_field");changes.add(new Assignment(owner,f,scalar(in,f.getType())));}
    }
    private static void blob(DataOutput out,byte[] bytes)throws IOException{if(bytes.length>65536)throw new IOException("actor_controller_blob");out.writeInt(bytes.length);out.write(bytes);}
    private static OutputStream limited(ByteArrayOutputStream buffer,int limit){return new OutputStream(){
        private void admit(int n)throws IOException{if((long)buffer.size()+n>limit)throw new IOException("actor_controller_size");}
        @Override public void write(int b)throws IOException{admit(1);buffer.write(b);}
        @Override public void write(byte[] b,int offset,int size)throws IOException{admit(size);buffer.write(b,offset,size);}
    };}
    private static byte[] blob(BoundedNativeInput in)throws IOException{int size=in.readInt();if(size<0||size>65536||size>in.available())throw new IOException("actor_controller_blob");byte[] bytes=new byte[size];in.readFully(bytes);return bytes;}
    private byte[] nativeBytes(Object value)throws Exception{
        var bytes=new ByteArrayOutputStream();value.getClass().getMethod("switch",DataOutput.class).invoke(value,new DataOutputStream(limited(bytes,65536)));return bytes.toByteArray();
    }
    private Object nativeValue(String name,byte[] bytes)throws Exception{
        Object result=type(name).getConstructor().newInstance();var input=new BoundedNativeInput(bytes);
        try{type(name).getMethod("extends",DataInput.class).invoke(result,input);}catch(InvocationTargetException e){throw new IOException("actor_controller_native:"+name,e.getCause());}
        if(input.available()!=0)throw new IOException("actor_controller_native_trailing");return result;
    }
    private byte[] propertyBytes(Class<?> type,Object value)throws Exception{
        var bytes=new ByteArrayOutputStream();propertyWrite.invoke(null,new DataOutputStream(limited(bytes,65536)),type,value);return bytes.toByteArray();
    }
    private Object propertyValue(Class<?> type,byte[] bytes)throws Exception{
        var input=new BoundedNativeInput(bytes);Object value;
        try{value=propertyRead.invoke(null,null,input,type);}catch(InvocationTargetException e){throw new IOException("actor_controller_property:"+type.getName(),e.getCause());}
        if(input.available()!=0)throw new IOException("actor_controller_property_trailing");return value;
    }
    private SortedMap<Integer,Object> properties(Object bank)throws Exception{
        var result=new TreeMap<Integer,Object>();for(Object p:(List<?>)bankType.getField("catch").get(bank))if(result.put(Short.toUnsignedInt(propertyId.getShort(p)),p)!=null)throw new IOException("actor_controller_property_duplicate");
        if(result.size()>512)throw new IOException("actor_controller_properties");return result;
    }
    private void writeSet(DataOutput out,Object bank,String name)throws Exception{
        var ids=new TreeSet<Integer>();for(Object p:(Set<?>)bankType.getField(name).get(bank))ids.add(Short.toUnsignedInt(propertyId.getShort(p)));
        out.writeInt(ids.size());for(int id:ids)out.writeShort(id);
    }
    @SuppressWarnings("unchecked") private void readSet(BoundedNativeInput in,Object bank,String name,Map<Integer,Object> properties,List<Runnable> changes)throws Exception{
        int count=in.readInt();if(count<0||count>properties.size())throw new IOException("actor_controller_dirty_set");Set<Object> values=new HashSet<>();
        for(int i=0;i<count;i++){Object p=properties.get(in.readUnsignedShort());if(p==null||!values.add(p))throw new IOException("actor_controller_dirty_id");}
        Set<Object> target=(Set<Object>)bankType.getField(name).get(bank);changes.add(()->{target.clear();target.addAll(values);});
    }
    private void noPending(Object controller,Object input)throws Exception{
        for(String name:new String[]{"this","const"}){Object values=get(controller,name);if(values!=null&&!((List<?>)values).isEmpty())throw new IOException("actor_controller_pending_events");}
        noInputEvents(input);Object previous=get(controller,"return");if(previous!=null)noInputEvents(previous);
    }
    private void noInputEvents(Object input)throws Exception{Object events=get(input,"long");if(events!=null&&!((List<?>)get(events,"switch")).isEmpty())throw new IOException("actor_controller_input_events");}
    private void writeInput(DataOutput out,Object input)throws Exception{writeScalars(out,input,type("1899091998"));out.writeUTF(((Enum<?>)get(input,"case")).name());}
    private void readInput(BoundedNativeInput in,Object input,List<Assignment> changes)throws Exception{
        readScalars(in,input,type("1899091998"),changes);String gait=in.readUTF();Object nativeGait=null;
        for(Object value:type("1785380642").getEnumConstants())if(((Enum<?>)value).name().equals(gait))nativeGait=value;if(nativeGait==null)throw new IOException("actor_controller_gait");
        changes.add(new Assignment(input,field(type("1899091998"),"case"),nativeGait));
    }
    private List<Field> ziplineVectors()throws Exception{return Arrays.stream(type("1157611462").getDeclaredFields()).filter(f->!Modifier.isStatic(f.getModifiers())&&f.getType().getName().equals("153304200")).sorted(Comparator.comparing(Field::getName)).peek(f->f.setAccessible(true)).toList();}
    private void writeZipline(DataOutput out,Object controller)throws Exception{
        Object module=((Map<?,?>)get(controller,"switch")).values().stream().filter(o->o.getClass().getName().equals("1157611462")).findFirst().orElseThrow();
        writeScalars(out,module,type("1157611462"));var vectors=ziplineVectors();out.writeInt(vectors.size());for(var field:vectors){out.writeUTF(field.getName());writeScalars(out,field.get(module),type("153304200"));}
    }
    private void readZipline(BoundedNativeInput in,Object controller,List<Assignment> changes)throws Exception{
        Object module=((Map<?,?>)get(controller,"switch")).values().stream().filter(o->o.getClass().getName().equals("1157611462")).findFirst().orElseThrow();
        readScalars(in,module,type("1157611462"),changes);var vectors=ziplineVectors();if(in.readInt()!=vectors.size())throw new IOException("actor_controller_zipline_layout");for(var field:vectors){if(!in.readUTF().equals(field.getName()))throw new IOException("actor_controller_zipline_layout");readScalars(in,field.get(module),type("153304200"),changes);}
    }
    byte[] write(byte[] state,Object controller,Object actor,Object input,boolean crouch)throws Exception{
        noPending(controller,input);var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(limited(buffer,ActorControllerState.MAX_BYTES));
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(ActorDispatcherRelease.fingerprint());blob(out,state);out.writeBoolean(crouch);
        UUID identity=(UUID)entityType.getMethod("interface").invoke(actor);out.writeLong(identity.getMostSignificantBits());out.writeLong(identity.getLeastSignificantBits());
        Object current=get(controller,"assert"),camera=stateType.getField("ak").get(current);out.writeUTF(camera==null?"":((Enum<?>)camera).name());
        List<?> constraints=(List<?>)stateType.getField("j").get(current);if(constraints.size()>64)throw new IOException("actor_controller_constraints");out.writeInt(constraints.size());
        for(Object c:constraints){blob(out,nativeBytes(c));Object geometry=c.getClass().getField("return").get(c);writeScalars(out,geometry,type("1181466981"));}
        writeScalars(out,controller,type("1704556748"));
        for(String name:new String[]{"native","final","abstract"}){Object module=get(controller,name);writeScalars(out,module,type(name.equals("native")?"1960601713":name.equals("final")?"1429083604":"632909433"));}
        Object support=get(get(controller,"protected"),"double");writeScalars(out,support,type("1276542651"));
        writeScalars(out,actor,type("820497227"));writeInput(out,input);
        Object previous=get(controller,"return");out.writeBoolean(previous!=null);if(previous!=null)writeInput(out,previous);
        Object flags=flags(actor);for(String name:new String[]{"float","public"})out.writeFloat((Float)flagsType.getMethod(name).invoke(flags));
        for(String name:new String[]{"if","private","implements","final"})out.writeBoolean((Boolean)flagsType.getMethod(name).invoke(flags));
        Object bank=bank(actor);var properties=properties(bank);out.writeInt(properties.size());
        for(var e:properties.entrySet()){
            Object p=e.getValue();Class<?> type=(Class<?>)propertyType.get(p);out.writeShort(e.getKey());out.writeUTF(type.getName());out.writeByte(propertyRevision.getByte(p));
            blob(out,propertyBytes(type,propertyValue.get(p)));blob(out,propertyBytes(type,propertyPrevious.get(p)));
        }
        out.writeBoolean(bankType.getField("char").getBoolean(bank));out.writeByte(bankType.getField("int").getByte(bank));writeSet(out,bank,"short");writeSet(out,bank,"import");
        blob(out,effects.write(actor));writeZipline(out,controller);blob(out,NativeActorEquipment.held(actor));
        writeScalars(out,actor,type("351062380"));life.write(out,actor);
        var stats=new TreeMap<>(NativeActorLife.stats(actor));out.writeInt(stats.size());for(var e:stats.entrySet()){out.writeUTF(e.getKey());out.writeFloat(e.getValue());}
        blob(out,equipment.buffs.write(actor));
        life.writeRecovery(out,actor);
        if(buffer.size()+32>ActorControllerState.MAX_BYTES)throw new IOException("actor_controller_size");byte[] body=buffer.toByteArray();out.write(MessageDigest.getInstance("SHA-256").digest(body));return buffer.toByteArray();
    }
    Decoded read(byte[] bytes,Object controller,Object actor,Object input)throws Exception{
        if(bytes==null||bytes.length<32||bytes.length>ActorControllerState.MAX_BYTES)throw new IOException("actor_controller_size");noPending(controller,input);
        byte[] body=Arrays.copyOf(bytes,bytes.length-32);if(!MessageDigest.isEqual(Arrays.copyOfRange(bytes,bytes.length-32,bytes.length),MessageDigest.getInstance("SHA-256").digest(body)))throw new IOException("actor_controller_checksum");
        var in=new BoundedNativeInput(body);if(in.readInt()!=MAGIC)throw new IOException("actor_controller_version");int version=in.readInt();if((version<1||version>VERSION)||!in.readUTF().equals(ActorDispatcherRelease.fingerprint()))throw new IOException("actor_controller_version");
        byte[] state=blob(in);boolean crouch=in.readBoolean();Object replacement=nativeValue("1943200621",state);var changes=new ArrayList<Assignment>();var containers=new ArrayList<Runnable>();
        UUID identity=new UUID(in.readLong(),in.readLong());if(!identity.equals(entityType.getMethod("interface").invoke(actor)))throw new IOException("actor_controller_identity");
        String camera=in.readUTF();Object view=null;if(!camera.isEmpty()){for(Object value:type("1308185038").getEnumConstants())if(((Enum<?>)value).name().equals(camera))view=value;if(view==null)throw new IOException("actor_controller_camera");}stateType.getField("ak").set(replacement,view);
        int count=in.readInt();if(count<0||count>64)throw new IOException("actor_controller_constraints");var constraints=new ArrayList<Object>();
        for(int i=0;i<count;i++){Object c=nativeValue("1158354165",blob(in));readScalars(in,c.getClass().getField("return").get(c),type("1181466981"),changes);constraints.add(c);}
        stateType.getField("j").set(replacement,constraints);
        // Binding occurs only after all sections have decoded. The session adds its normal state checks.
        changes.add(new Assignment(controller,field(type("1704556748"),"assert"),replacement));
        readScalars(in,controller,type("1704556748"),changes);
        for(String name:new String[]{"native","final","abstract"})readScalars(in,get(controller,name),type(name.equals("native")?"1960601713":name.equals("final")?"1429083604":"632909433"),changes);
        readScalars(in,get(get(controller,"protected"),"double"),type("1276542651"),changes);readScalars(in,actor,type("820497227"),changes);if(version<5)changes.add(new Assignment(actor,field(type("820497227"),"c"),.6f));readInput(in,input,changes);
        Object previous=null;if(in.readBoolean()){previous=type("1899091998").getConstructor().newInstance();readInput(in,previous,changes);}
        changes.add(new Assignment(controller,field(type("1704556748"),"return"),previous));
        Object flags=flags(actor);float speed=in.readFloat(),factor=in.readFloat();boolean fly=in.readBoolean(),creative=in.readBoolean(),enabled=in.readBoolean(),other=in.readBoolean();
        containers.add(()->{try{flagsType.getMethod("finally",float.class).invoke(flags,speed);flagsType.getMethod("implements",float.class).invoke(flags,factor);flagsType.getMethod("abstract",boolean.class).invoke(flags,fly);flagsType.getMethod("int",boolean.class).invoke(flags,creative);flagsType.getMethod("protected",boolean.class).invoke(flags,enabled);flagsType.getMethod("char",boolean.class).invoke(flags,other);}catch(Exception e){throw new IllegalStateException(e);}});
        Object bank=bank(actor);var allProperties=properties(bank);var properties=new TreeMap<>(allProperties);if(version==1)for(int id:effects.propertyIds(actor))properties.remove(id);if(version<5)for(int id:life.propertyIds(actor))properties.remove(id);if(in.readInt()!=properties.size())throw new IOException("actor_controller_property_count");
        for(var e:properties.entrySet()){
            Object p=e.getValue();Class<?> type=(Class<?>)propertyType.get(p);if(in.readUnsignedShort()!=e.getKey()||!in.readUTF().equals(type.getName()))throw new IOException("actor_controller_property_layout");
            changes.add(new Assignment(p,propertyRevision,in.readByte()));changes.add(new Assignment(p,propertyValue,propertyValue(type,blob(in))));changes.add(new Assignment(p,propertyPrevious,propertyValue(type,blob(in))));
        }
        changes.add(new Assignment(bank,bankType.getField("char"),in.readBoolean()));changes.add(new Assignment(bank,bankType.getField("int"),in.readByte()));readSet(in,bank,"short",properties,containers);readSet(in,bank,"import",properties,containers);
        if(version>=2)containers.add(effects.read(actor,blob(in)));
        if(version>=3)readZipline(in,controller,changes);
        Object held=version>=4?equipment.decode(blob(in)):null;changes.add(new Assignment(actor,actor.getClass().getField("heldItem"),held));
        if(version>=5){readScalars(in,actor,type("351062380"),changes);containers.add(life.read(in,actor));}
        var stats=new TreeMap<String,Float>();if(version>=6){int n=in.readInt();if(n<0||n>512)throw new IOException("actor_controller_stats_count");for(int i=0;i<n;i++)if(stats.put(in.readUTF(),in.readFloat())!=null)throw new IOException("actor_controller_stat_duplicate");}
        var checked=szserver.world.ActorEquipment.checkedStats(stats);changes.add(new Assignment(actor,actor.getClass().getField("equipmentStats"),checked));
        if(version>=7)containers.add(equipment.buffs.read(actor,controller,blob(in),checked));else containers.add(()->{try{equipment.buffs.legacy(actor,controller);}catch(Exception error){throw new IllegalStateException(error);}});
        if(version>=8)containers.add(life.readRecovery(in,actor));else containers.add(()->{try{life.legacyRecovery(actor);}catch(Exception error){throw new IllegalStateException(error);}});
        if(in.available()!=0)throw new IOException("actor_controller_trailing");return new Decoded(state,crouch,changes,containers,version>=5);
    }
}
