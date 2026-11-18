package replay;

import java.time.Instant;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.LongSupplier;

/** Frozen simulation still needs asynchronous main-thread completions.
 * Scheduled world events remain owned by the simulation, including past events
 * encountered after a backwards seek. Never drain them using replay time. */
final class SceneMainTasks {
    interface Access<T> {
        Instant time(T task)throws Exception;
        boolean cancelled(T task)throws Exception;
        Object owner(T task)throws Exception;
        void run(T task)throws Exception;
    }
    static <T> int drain(PriorityQueue<T> queued,List<T> incoming,Set<Object> cancelledOwners,
                         Access<T> access,int limit,long budget,LongSupplier clock)throws Exception {
        long began=clock.getAsLong();
        synchronized(incoming){queued.addAll(incoming);incoming.clear();}
        // Match native cancellation before executing callbacks. Keep delayed tasks
        // in the native queue so resume/close retains normal ownership semantics.
        if(!cancelledOwners.isEmpty()){
            for(var iterator=queued.iterator();iterator.hasNext();)
                if(cancelledOwners.contains(access.owner(iterator.next())))iterator.remove();
            cancelledOwners.clear();
        }
        int count=0;
        while(count<limit&&clock.getAsLong()-began<budget){
            T task=queued.peek();if(task==null||!Instant.MIN.equals(access.time(task)))break;
            queued.remove();count++;
            if(!access.cancelled(task))access.run(task);
        }
        return count;
    }
    private static final Access<Object> NATIVE=new Access<>() {
        public Instant time(Object task)throws Exception{return (Instant)Native.field(task,"private");}
        public boolean cancelled(Object task)throws Exception{return (boolean)Native.field(task,"synchronized");}
        public Object owner(Object task)throws Exception{var ref=(WeakReference<?>)Native.field(task,"boolean");return ref==null?null:ref.get();}
        public void run(Object task)throws Exception{
            try{((Runnable)Native.field(task,"else")).run();}
            catch(Exception failure){System.err.println("[network-replay] Native main-thread completion failed");failure.printStackTrace();}
        }
    };
    @SuppressWarnings("unchecked")
    static void pump()throws Exception {
        Object scheduler=Native.field(Native.type("1852960784"),"static");
        drain((PriorityQueue<Object>)Native.field(scheduler,"new"),(List<Object>)Native.field(scheduler,"if"),
                (Set<Object>)Native.field(scheduler,"class"),NATIVE,32,1_000_000L,System::nanoTime);
    }
}
