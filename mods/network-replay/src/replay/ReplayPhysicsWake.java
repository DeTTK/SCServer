package replay;

/** Wake the existing native worker at an event frontier, without taking its solver. */
final class ReplayPhysicsWake {
    interface Ready {boolean get()throws Exception;}
    static boolean await(Object monitor,Ready ready,long budgetNs)throws Exception {
        if(ready.get())return true;
        long deadline=System.nanoTime()+budgetNs;
        // Native ownership order is worker monitor -> body clocks. Never enter
        // this block while holding the body-clock monitor in the caller.
        synchronized(monitor){
            while(!ready.get()){
                long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
                monitor.notifyAll();
                monitor.wait(0,(int)Math.min(999_999L,remaining));
            }
        }
        return true;
    }
}
