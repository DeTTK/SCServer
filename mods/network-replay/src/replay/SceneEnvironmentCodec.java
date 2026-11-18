package replay;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Final native weather shader parameters, independent of stateful preset transitions. */
final class SceneEnvironmentCodec {
    static final Set<String> TYPES=Set.of("648055939","1634906201","119040796","929770397","330322532","1297821034","1729723475","871672257","1373316070","244192632","778051323","1703180721","919100531","1423921485");
    record Property(SceneTracks.Blend blend,SceneTracks.Value value){}
    static Map<String,Property> read(Object nativeEnvironment)throws Exception {
        if(!nativeEnvironment.getClass().getName().equals("648055939"))throw new IllegalArgumentException("Unexpected native environment root");
        Map<String,Property> result=new TreeMap<>();collect(nativeEnvironment,"",result);return Map.copyOf(result);
    }
    private static final ClassValue<List<Field>> SCHEMA_FIELDS=new ClassValue<>(){protected List<Field> computeValue(Class<?> type){List<Field> fields=new ArrayList<>();for(Field f:type.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.isSynthetic()){f.setAccessible(true);fields.add(f);}fields.sort(Comparator.comparing(Field::getName));return List.copyOf(fields);}};
    private static volatile Set<String> schemaNames;
    static List<Field> fields(Object object){return SCHEMA_FIELDS.get(object.getClass());}
    static void collect(Object object,String prefix,Map<String,Property> result)throws Exception {
        if(!TYPES.contains(object.getClass().getName()))throw new IOException("Unknown native environment node: "+object.getClass().getName());
        for(Field f:fields(object)){String path=prefix+f.getName();Object value=f.get(object);Class<?> type=f.getType();
            if(type==float.class)result.put(path,new Property(SceneTracks.Blend.LINEAR,new SceneTracks.Numbers(((Number)value).floatValue())));
            else if(type==int.class)result.put(path,new Property(SceneTracks.Blend.HOLD,new SceneTracks.Numbers(((Number)value).intValue())));
            else if(type==boolean.class)result.put(path,new Property(SceneTracks.Blend.HOLD,new SceneTracks.Numbers((boolean)value?1:0)));
            else if(type==float[].class){float[] raw=(float[])value;double[] a=new double[raw.length];for(int n=0;n<a.length;n++)a[n]=raw[n];result.put(path,new Property(SceneTracks.Blend.LINEAR,new SceneTracks.Numbers(a)));}
            else if(Map.class.isAssignableFrom(type))result.put(path,new Property(SceneTracks.Blend.HOLD,resourceWeights((Map<?,?>)value)));
            else if(TYPES.contains(type.getName()))collect(value,path+".",result);
            else throw new IOException("Unsupported native environment field: "+path+"/"+type.getName());
        }
    }
    static SceneTracks.Bytes resourceWeights(Map<?,?> map)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);Map<String,Float> sorted=new TreeMap<>();
        for(var e:map.entrySet()){if(e.getKey()==null||!e.getKey().getClass().getName().equals("779455578")||!(e.getValue() instanceof Float weight)||!Float.isFinite(weight))throw new IOException("Unsupported environment resource weight");String path=e.getKey().toString();if(sorted.put(path,weight)!=null)throw new IOException("Duplicate environment resource path");}
        out.writeInt(sorted.size());for(var e:sorted.entrySet()){out.writeUTF(e.getKey());out.writeFloat(e.getValue());}return new SceneTracks.Bytes(bytes.toByteArray());
    }
    /** Compose a fresh DTO; invalid scene data cannot partially modify the visible environment. */
    static Object compose(Map<String,SceneTracks.Value> selected)throws Exception {
        Object root=Native.type("648055939").getConstructor().newInstance();Set<String> schema=schemaNames;if(schema==null){schema=Set.copyOf(read(root).keySet());schemaNames=schema;}
        if(!schema.equals(selected.keySet()))throw new IOException("Incomplete native environment track frame");
        fill(root,"",selected);return root;
    }
    static void fill(Object object,String prefix,Map<String,SceneTracks.Value> selected)throws Exception {
        for(Field f:fields(object)){String path=prefix+f.getName();Class<?> type=f.getType();
            if(TYPES.contains(type.getName())){fill(f.get(object),path+".",selected);continue;}
            SceneTracks.Value value=selected.get(path);
            if(type==float.class){double[] a=numbers(value,1);if(!Float.isFinite((float)a[0]))throw new IOException("Environment float range");f.setFloat(object,(float)a[0]);}
            else if(type==int.class){double[] a=numbers(value,1);if(a[0]!=Math.rint(a[0])||a[0]<Integer.MIN_VALUE||a[0]>Integer.MAX_VALUE)throw new IOException("Environment integer range");f.setInt(object,(int)a[0]);}
            else if(type==boolean.class){double[] a=numbers(value,1);if(a[0]!=0&&a[0]!=1)throw new IOException("Environment boolean range");f.setBoolean(object,a[0]==1);}
            else if(type==float[].class){float[] nativeArray=(float[])f.get(object);double[] a=numbers(value,nativeArray.length);float[] target=new float[a.length];for(int n=0;n<a.length;n++){target[n]=(float)a[n];if(!Float.isFinite(target[n]))throw new IOException("Environment vector range");}System.arraycopy(target,0,nativeArray,0,target.length);}
            else if(Map.class.isAssignableFrom(type)){
                if(!(value instanceof SceneTracks.Bytes b))throw new IOException("Environment map codec mismatch");Map<Object,Float> map=new HashMap<>();
                try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(b.data()))){int count=in.readInt();if(count<0||count>4096)throw new IOException("Environment resource count");for(int n=0;n<count;n++){Object resource=Native.type("779455578").getConstructor(String.class).newInstance(in.readUTF());float weight=in.readFloat();if(!Float.isFinite(weight)||map.put(resource,weight)!=null)throw new IOException("Invalid environment resource weight");}if(in.available()!=0)throw new IOException("Trailing environment map bytes");}
                @SuppressWarnings("unchecked") Map<Object,Float> target=(Map<Object,Float>)f.get(object);target.clear();target.putAll(map);
            }else throw new IOException("Native environment schema changed");
        }
    }
    static double[] numbers(SceneTracks.Value value,int size)throws IOException {if(!(value instanceof SceneTracks.Numbers n))throw new IOException("Environment numeric codec mismatch");double[] a=n.data();if(a.length!=size)throw new IOException("Environment vector dimensions");return a;}
}
