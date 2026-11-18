package szserver.world;

import java.util.function.BooleanSupplier;

/** One navigation worker job, including map waits and native proof; absent on gameplay owners. */
public final class NavigationBudget implements AutoCloseable {
    public static final class Exhausted extends RuntimeException {
        private final boolean cancelled;
        Exhausted(boolean cancelled){super(cancelled?"actor_route_cancelled":"actor_route_time_budget",null,false,false);this.cancelled=cancelled;}
        public boolean cancelled(){return cancelled;}
    }
    private static final ThreadLocal<NavigationBudget> CURRENT=new ThreadLocal<>();
    private final long deadline;private final BooleanSupplier cancelled;
    private NavigationBudget(long nanos,BooleanSupplier cancelled){
        if(CURRENT.get()!=null||nanos<=0)throw new IllegalArgumentException("navigation_budget_scope");
        deadline=System.nanoTime()+nanos;this.cancelled=cancelled;CURRENT.set(this);
    }
    public static NavigationBudget begin(long nanos,BooleanSupplier cancelled){return new NavigationBudget(nanos,cancelled);}
    public static void check(){
        var budget=CURRENT.get();if(budget==null)return;
        if(budget.cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Exhausted(true);
        if(System.nanoTime()-budget.deadline>=0)throw new Exhausted(false);
    }
    @Override public void close(){if(CURRENT.get()!=this)throw new IllegalStateException("navigation_budget_owner");CURRENT.remove();}
}
