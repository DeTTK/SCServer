package szmods.runtime;

import java.util.Map;
import java.util.function.BiConsumer;

/** Ordered hook snapshots without allocations when the registry is unchanged.
 * Called under ModLoader's monitor; callbacks may mutate the registry reentrantly.
 * Validation uses Map.forEach, so reflected registry mutations remain visible. */
final class HookSnapshots {
    private static Map<?,?> registry;
    private static final Cache cache=new Cache();
    private static final class Cache implements BiConsumer<Object,Object> {
        Object[] normal=new Object[0],priorityOrder;
        Object priority;
        int index;
        boolean changed;
        public void accept(Object key,Object value){
            if(index>=normal.length||normal[index]!=value)changed=true;
            index++;
        }
    }
    static synchronized Object[] ordered(Map<?,?> map,Object first){
        boolean changed=registry!=map||map.size()!=cache.normal.length;
        if(!changed){cache.index=0;cache.changed=false;map.forEach(cache);changed=cache.changed||cache.index!=cache.normal.length;}
        if(changed){registry=map;cache.normal=map.values().toArray();cache.priorityOrder=null;cache.priority=null;}
        if(first==null)return cache.normal;
        if(cache.priorityOrder!=null&&cache.priority==first)return cache.priorityOrder;
        int count=0;for(Object value:cache.normal)if(value==first)count++;
        if(count==0)return cache.normal;
        Object[] ordered=new Object[cache.normal.length];int index=0;
        for(int i=0;i<count;i++)ordered[index++]=first;
        for(Object value:cache.normal)if(value!=first)ordered[index++]=value;
        cache.priority=first;cache.priorityOrder=ordered;return ordered;
    }
}
