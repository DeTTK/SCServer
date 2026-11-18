package szmods.runtime;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Capture once at firing time; retransmission must use the same rendered-world hit. */
public final class ClientHits {
    private static final Map<Object, byte[]> reports = Collections.synchronizedMap(new WeakHashMap<>());
    public static long captured, failures;
    public static String lastError = "";

    public static void capture(Object shot) {
        int entity = -1, group = 0;
        double[] point = new double[3];
        try {
            Object game = call(Class.forName("1402131154"),"try","1402131154");
            Object player = field(game,"e"), world = field(game,"g");
            Object origin = field(shot,"native"), end = field(shot,"assert");
            point = xyz(end);
            Object hit = call(Class.forName("1312242444"),"throws","763891058",world,origin,end,player);
            Object intersection = hit == null ? null : field(hit,"super");
            if(intersection != null) {
                point = xyz(call(intersection,"while","153304200"));
                if(Class.forName("177149985").isInstance(intersection)) {
                    Object target = call(intersection,"void","1217489680");
                    entity = (Integer)call(target,"for","int");
                    String name = ((Enum<?>)field(hit,"throws")).name();
                    group = name.equals("HEAD") ? 2 : name.equals("LIMB") ? 3 : 1;
                }
            }
            captured++;
        } catch(Exception e) {
            failures++; lastError=e.toString();
            System.err.println("[ClientHits] " + e);
            entity=-1; group=0;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(34);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x535A4854); out.writeByte(1); out.writeInt(entity); out.writeByte(group);
            for(double coordinate:point)out.writeDouble(coordinate);
            reports.put(shot,bytes.toByteArray());
        } catch(IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static void write(Object shot, DataOutput out) throws IOException {
        byte[] report=reports.get(shot);
        if(report==null)throw new IOException("Missing firing-time client hit report");
        out.write(report);
    }
    private static Object field(Object object,String name)throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try { Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object); }
            catch(NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName()+"."+name);
    }
    private static double[] xyz(Object vector)throws Exception {return new double[]{((Number)field(vector,"case")).doubleValue(),((Number)field(vector,"void")).doubleValue(),((Number)field(vector,"import")).doubleValue()};}
    private static Object call(Object target,String name,String result,Object...args)throws Exception {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        for(Method m:type.getMethods()) {
            if(!m.getName().equals(name)||!m.getReturnType().getName().equals(result)||m.getParameterCount()!=args.length)continue;
            boolean match=true; Class<?>[] types=m.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i]))match=false;
            if(match)return m.invoke(target instanceof Class<?>?null:target,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name+":"+result);
    }
}
