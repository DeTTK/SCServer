package replay;

import java.lang.reflect.*;
import java.util.*;

/** Read-only native BVH views share unchanged nodes and pages. Construction
 * reads a quarantined builder; published views never call its insert/remove.
 * The canonical node's synthetic owner is not read by native query methods. */
final class SceneNativeTreeViews {
    static final int PAGE_SHIFT=6,PAGE_SIZE=1<<PAGE_SHIFT;
    static final class Nodes extends AbstractList<Object> implements RandomAccess {
        final Object[][] pages;final int size;
        Nodes(Object[][] pages,int size){this.pages=pages;this.size=size;}
        public int size(){return size;}
        public Object get(int index){Objects.checkIndex(index,size);return pages[index>>>PAGE_SHIFT][index&(PAGE_SIZE-1)];}
    }
    record View(Object tree,Nodes nodes,SceneDomainMap<Object,Integer> leaves){}
    final Class<?> treeClass,nodeClass;
    final Constructor<?> nodeConstructor;
    final Field[] nodeFields;
    final IdentityHashMap<Object,View> previous=new IdentityHashMap<>();
    long views,copiedNodes,sharedNodes,copiedPages,sharedPages;
    SceneNativeTreeViews()throws Exception {
        treeClass=Native.type("1581944406");nodeClass=Native.type("1948284727");
        nodeConstructor=nodeClass.getConstructor(treeClass);
        var fields=new ArrayList<Field>();
        for(Field field:nodeClass.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!field.isSynthetic()){
            // The compiler's owner field is synthetic; also reject unknown
            // layouts instead of silently copying another native node type.
            if(field.getType()!=int.class&&field.getType()!=float.class&&!field.getName().equals("short"))throw new IllegalStateException("Unknown native BVH node field: "+field);
            field.setAccessible(true);fields.add(field);
        }
        nodeFields=fields.toArray(Field[]::new);
    }
    boolean same(Object a,Object b)throws Exception {
        if(a==null||b==null)return a==b;
        for(Field field:nodeFields){
            Class<?> type=field.getType();
            if(type==int.class){if(field.getInt(a)!=field.getInt(b))return false;}
            else if(type==float.class){if(Float.floatToRawIntBits(field.getFloat(a))!=Float.floatToRawIntBits(field.getFloat(b)))return false;}
            else if(field.get(a)!=field.get(b))return false;
        }
        return true;
    }
    Object copyNode(Object node,Object owner)throws Exception {
        Object copy=nodeConstructor.newInstance(owner);
        for(Field field:nodeFields){
            if(field.getType()==int.class)field.setInt(copy,field.getInt(node));
            else if(field.getType()==float.class)field.setFloat(copy,field.getFloat(node));
            else field.set(copy,field.get(node));
        }
        copiedNodes++;return copy;
    }
    View freeze(Object source)throws Exception {
        if(!treeClass.isInstance(source)||!Set.of("1581944406","1221995986").contains(source.getClass().getName()))throw new IllegalArgumentException("Unsupported native BVH "+source.getClass());
        View old=previous.get(source);
        int growth=(int)Native.field(source,"goto");Object result=source.getClass().getConstructor(int.class).newInstance(growth);
        List<?> values=(List<?>)Native.field(source,"extends");int size=values.size();
        Object[][] pages=new Object[(size+PAGE_SIZE-1)>>>PAGE_SHIFT][];
        for(int page=0;page<pages.length;page++){
            Object[] prior=old!=null&&page<old.nodes.pages.length?old.nodes.pages[page]:null,copy=prior;
            int begin=page<<PAGE_SHIFT,end=Math.min(size,begin+PAGE_SIZE);
            for(int index=begin;index<end;index++){
                Object value=values.get(index),before=prior==null?null:prior[index-begin];
                if(same(value,before)){sharedNodes++;continue;}
                if(copy==prior)copy=prior==null?new Object[PAGE_SIZE]:prior.clone();
                copy[index-begin]=copyNode(value,result);
            }
            if(copy==prior){sharedPages++;}else copiedPages++;
            pages[page]=copy;
        }
        Nodes nodes=new Nodes(pages,size);
        var leaves=(old==null?new SceneDomainMap<Object,Integer>():old.leaves).edit();
        Map<?,?> original=(Map<?,?>)Native.field(source,"transient");
        if(old!=null)for(Object key:old.leaves.keySet())if(!original.containsKey(key))leaves.remove(key);
        for(var entry:original.entrySet()){Integer slot=(Integer)entry.getValue();if(!Objects.equals(leaves.base.get(entry.getKey()),slot))leaves.put(entry.getKey(),slot);}
        var selectedLeaves=leaves.finish();
        for(String field:List.of("volatile","void","finally","private"))Native.reflectedField(result,field).setInt(result,(int)Native.field(source,field));
        Native.reflectedField(result,"extends").set(result,nodes);Native.reflectedField(result,"transient").set(result,selectedLeaves);
        if(old!=null&&old.leaves==selectedLeaves&&old.nodes.size==size){
            boolean unchanged=true;
            for(int page=0;page<pages.length;page++)if(pages[page]!=old.nodes.pages[page]){unchanged=false;break;}
            for(String field:List.of("volatile","void","finally","private"))if(!Objects.equals(Native.field(source,field),Native.field(old.tree,field)))unchanged=false;
            if(unchanged){views++;return old;}
        }
        View view=new View(result,nodes,selectedLeaves);previous.put(source,view);views++;return view;
    }
    void clear(){previous.clear();}
    Map<String,Object> diagnostic(){return Map.of("views",views,"copied_nodes",copiedNodes,"shared_nodes",sharedNodes,"copied_pages",copiedPages,"shared_pages",sharedPages);}
}
