package replay;

import java.lang.reflect.*;
import java.util.*;

/** Read-only, bounded QA snapshot of native animation nodes and parameter inputs. */
final class ReplayGraphProbe {
    final IdentityHashMap<Object,String> seen=new IdentityHashMap<>();
    final Map<String,Object> nodes=new LinkedHashMap<>();
    final Class<?> graph=Native.type("1391506621"),shared=Native.type("1595398176"),input=Native.type("958670152");
    boolean truncated;
    ReplayGraphProbe()throws Exception {}
    boolean node(Object value){return value!=null&&(graph.isInstance(value)||shared.isInstance(value)||input.isInstance(value));}
    Object inspect(Object value,String path)throws Exception {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number){
            if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))return String.valueOf(value);
            return value;
        }
        if(value instanceof Enum<?> e)return e.name();
        if(node(value)){
            String previous=seen.get(value);if(previous!=null)return Map.of("ref",previous);
            if(nodes.size()>=256){truncated=true;return Map.of("truncated_type",value.getClass().getName());}
            seen.put(value,path);Map<String,Object> row=new LinkedHashMap<>();nodes.put(path,row);row.put("class",value.getClass().getName());
            Map<String,Object> fields=new TreeMap<>();row.put("fields",fields);
            for(Class<?> c=value.getClass();c!=Object.class&&c!=null;c=c.getSuperclass()){
                Field[] declared=c.getDeclaredFields();Arrays.sort(declared,Comparator.comparing((Field f)->f.getName().equals("szFp_throws")?0:1).thenComparing(Field::getName));
                for(Field f:declared){
                    if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object fieldValue=f.get(value);
                    if(Modifier.isFinal(f.getModifiers())&&(fieldValue==null||fieldValue instanceof Number||fieldValue instanceof Boolean||fieldValue instanceof String||fieldValue instanceof Enum<?>))continue;
                    String key=c.getName()+"."+f.getName();fields.put(key,inspect(fieldValue,path+"/"+key));
                }
            }
            return Map.of("ref",path);
        }
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();if(list.size()>1024){truncated=true;return Map.of("list_size",list.size());}for(int n=0;n<list.size();n++)result.add(inspect(list.get(n),path+"["+n+"]"));return result;}
        if(value.getClass().isArray()){
            int length=Array.getLength(value);if(length<=16){List<Object> result=new ArrayList<>();for(int n=0;n<length;n++)result.add(inspect(Array.get(value,n),path+"["+n+"]"));return result;}
            return Map.of("array_type",value.getClass().getName(),"length",length);
        }
        return Map.of("type",value.getClass().getName());
    }
    static Map<String,Object> capture(Object root,Object inputs)throws Exception {
        ReplayGraphProbe probe=new ReplayGraphProbe();probe.inspect(inputs,"inputs");
        int inputNodes=probe.nodes.size();boolean inputsTruncated=probe.truncated;probe.inspect(root,"root");
        return Map.of("nodes",probe.nodes,"node_count",probe.nodes.size(),"truncated",probe.truncated,"input_node_count",inputNodes,"inputs_truncated",inputsTruncated);
    }
}
