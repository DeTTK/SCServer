package gamecompat;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Presentation-only recovery. No item identities, packets, saves or asset files are rewritten. */
public final class OptionalResources {
    private static final int MAX_WARNINGS=128,MAX_BOX_BYTES=8*1024*1024,MAX_BOX_COUNT=65536;
    private static final Set<String> warned=ConcurrentHashMap.newKeySet();
    private static final AtomicLong boxDefaults=new AtomicLong(),materialDefaults=new AtomicLong();
    private static final ThreadLocal<Boolean> missingBox=ThreadLocal.withInitial(()->false);
    private OptionalResources() {}

    public static void warning(String text) {
        String message=text==null?"optional asset":text.replace('\n',' ').replace('\r',' ');
        if(message.length()>700)message=message.substring(0,700);
        synchronized(warned){if(warned.size()<MAX_WARNINGS&&warned.add(message))System.err.println("[Optional resources] "+message);}
    }
    public static void materialWarning(Object reporter,Object category,String message,Object[] arguments){formattedWarning(message,arguments);}
    public static void formattedWarning(String message,Object[] arguments){warning("Unsupported optional material directive: "+Arrays.toString(arguments));}
    public static Map<String,Long> statistics(){return Map.of("boxDefaults",boxDefaults.get(),"materialLineDefaults",materialDefaults.get(),"uniqueWarnings",(long)warned.size());}
    private static Method method(Class<?> type,String name,Class<?> result,boolean isStatic,Class<?>... parameters)throws NoSuchMethodException {
        return Arrays.stream(type.getMethods()).filter(m->m.getName().equals(name)&&m.getReturnType()==result
            &&Modifier.isStatic(m.getModifiers())==isStatic&&Arrays.equals(m.getParameterTypes(),parameters))
            .findFirst().orElseThrow(()->new NoSuchMethodException(type.getName()+"."+name+":"+result.getName()));
    }
    private static RuntimeException failure(Throwable cause){
        if(cause instanceof Error error)throw error;
        if(cause instanceof RuntimeException error)return error;
        return new IllegalStateException(cause);
    }
    private static final class Boxes {
        final Object empty;final Method open,parse;
        final Map<String,Long> unavailable=new ConcurrentHashMap<>();
        Boxes(Class<?> resource)throws ReflectiveOperationException {
            ClassLoader loader=resource.getClassLoader();Class<?> location=Class.forName("779455578",false,loader),type=Class.forName("1669874697",true,loader);
            empty=type.getField("native").get(null);
            open=method(Class.forName("1198595589",true,loader),"throw",InputStream.class,true,location);
            parse=method(type,"strictfp",type,true,InputStream.class);
        }
    }
    private static final ClassValue<Boxes> BOXES=new ClassValue<>(){
        @Override protected Boxes computeValue(Class<?> type){try{return new Boxes(type);}catch(ReflectiveOperationException error){throw new IllegalStateException("Optional AABB reader ABI changed",error);}}
    };
    /** Validate the binary envelope before the old reader can allocate from an unchecked count. */
    static byte[] boundedBoxes(InputStream input)throws IOException {
        byte[] raw=input.readNBytes(MAX_BOX_BYTES+1);
        if(raw.length>MAX_BOX_BYTES||raw.length<8)throw new IOException("AABB size");
        ByteBuffer data=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if(data.get()!=65||data.get()!=65||data.get()!=66||data.get()!=66)throw new IOException("AABB magic");
        int count=data.getInt();if(count<0||count>MAX_BOX_COUNT)throw new IOException("AABB count");
        try {
            for(int i=0;i<count;i++){
                int label=Short.toUnsignedInt(data.getShort());
                if(label>data.remaining()-24)throw new IOException("AABB truncated label/box");
                data.position(data.position()+label);
                for(int axis=0;axis<6;axis++)if(!Float.isFinite(data.getFloat()))throw new IOException("AABB non-finite extent");
            }
        }catch(BufferUnderflowException|IllegalArgumentException error){throw new IOException("AABB truncated data",error);}
        // The stock format ignores a tail; preserve that forward-compatible behavior.
        return raw;
    }
    public static Object boxes(Object location) {
        Objects.requireNonNull(location,"AABB location");missingBox.set(false);
        Boxes bridge=BOXES.get(location.getClass());String key=location.toString();long now=System.nanoTime();
        Long failed=bridge.unavailable.get(key);
        if(failed!=null&&now-failed<2_000_000_000L){missingBox.set(true);return bridge.empty;}
        try {
            InputStream source=(InputStream)bridge.open.invoke(null,location);
            if(source==null)throw new FileNotFoundException(key);
            try(source){Object result=bridge.parse.invoke(null,new ByteArrayInputStream(boundedBoxes(source)));bridge.unavailable.remove(key);return result;}
        }catch(InvocationTargetException error){
            Throwable cause=error.getCause();if(cause instanceof Error fatal)throw fatal;
            return missing(bridge,key,now,cause);
        }catch(IOException|RuntimeException error){return missing(bridge,key,now,error);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Optional AABB reader invocation changed",error);}
    }
    private static Object missing(Boxes bridge,String key,long now,Throwable cause){
        missingBox.set(true);boxDefaults.incrementAndGet();
        if(bridge.unavailable.size()<256||bridge.unavailable.containsKey(key))bridge.unavailable.put(key,now);
        warning("AABB unavailable, using native fallback: "+key+" ("+cause.getClass().getSimpleName()+")");return bridge.empty;
    }
    public static void beginFactory(){missingBox.remove();}
    public static void shaderHook(Object property,String name) {
        try {
            ClassLoader loader=property.getClass().getClassLoader();Class<?> type=Class.forName("894154326",false,loader);
            Object hook=method(type,"strictfp",type,true,String.class).invoke(null,name);
            if(hook==null){warning("Unknown optional shader hook skipped: "+name);return;}
            Class<?> group=Class.forName("676254530",false,loader);
            Object list=group.getDeclaredField("try").get(property);
            Object map=group.getDeclaredField("volatile").get(property);
            @SuppressWarnings("unchecked") List<Object> active=(List<Object>)list;
            @SuppressWarnings("unchecked") Map<String,Object> registered=(Map<String,Object>)map;
            active.add(hook);registered.put(name,hook);
        }catch(InvocationTargetException error){throw failure(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Shader hook registry ABI changed",error);}
    }
    public static void assignCulling(Object factory,Object requested) {
        try {
            Class<?> box=Class.forName("1669874697",false,factory.getClass().getClassLoader());
            Field target=Arrays.stream(factory.getClass().getFields()).filter(f->f.getName().equals("throws")&&f.getType()==box).findFirst().orElseThrow();
            Object value=requested;
            if(missingBox.get()){
                Field main=Arrays.stream(factory.getClass().getFields()).filter(f->f.getName().equals("goto")&&f.getType()==box).findFirst().orElseThrow();value=main.get(factory);
            }
            target.set(factory,value);missingBox.remove();
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Optional culling ABI changed",error);}
    }
    private static final ClassValue<Method> MATERIAL_LINE=new ClassValue<>(){
        @Override protected Method computeValue(Class<?> type){try{return method(type,"class",void.class,false,String.class);}catch(NoSuchMethodException error){throw new IllegalStateException("Material line reader ABI changed",error);}}
    };
    @SuppressWarnings("unchecked") private static void disableBrokenHook(Object material,String line)throws ReflectiveOperationException {
        // Built-in scalar/color/map properties retain their previous value. Hook parameters
        // need their owner disabled too, otherwise its later renderer may dereference a missing value.
        Map<String,?> builtins=(Map<String,?>)material.getClass().getField("void").get(material);
        for(String key:builtins.keySet())if(line.startsWith(key))return;
        ClassLoader loader=material.getClass().getClassLoader();
        Class<?> owner=Class.forName("676254530",false,loader),hookType=Class.forName("894154326",false,loader),argument=Class.forName("1721724382",false,loader);
        Field declarations=hookType.getField("volatile"),parameter=argument.getField("try");
        for(String field:new String[]{"private","long"}) {
            Object group=material.getClass().getField(field).get(material);
            List<Object> active=(List<Object>)owner.getDeclaredField("try").get(group);
            Map<String,Object> registered=(Map<String,Object>)owner.getDeclaredField("volatile").get(group);
            for(Iterator<Object> iterator=active.iterator();iterator.hasNext();) {
                Object hook=iterator.next();if(hook==null)continue;boolean broken=false;
                for(Object definition:(List<?>)declarations.get(hook)) {
                    String key=(String)parameter.get(definition);
                    if(line.startsWith(key+" ")){broken=true;break;}
                }
                if(broken){iterator.remove();registered.values().removeIf(value->value==hook);warning("Disabled optional shader effect after invalid parameter: "+material+" : "+line);}
            }
        }
    }
    public static void materialLine(Object material,String line) {
        try {
            // Only mutable asset loading is recoverable. Editing a frozen render material remains an error.
            if(material.getClass().getField("continue").getBoolean(material))throw new IllegalStateException("Frozen material cannot be changed");
            MATERIAL_LINE.get(material.getClass()).invoke(material,line);
        }catch(InvocationTargetException error){
            Throwable cause=error.getCause();if(cause instanceof Error fatal)throw fatal;
            try{disableBrokenHook(material,line);}catch(ReflectiveOperationException unavailable){throw new IllegalStateException("Cannot isolate failed shader effect",unavailable);}
            materialDefaults.incrementAndGet();warning("Skipped optional material line: "+material+" : "+line+" ("+cause.getClass().getSimpleName()+")");
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Material reader invocation changed",error);}
    }
}
