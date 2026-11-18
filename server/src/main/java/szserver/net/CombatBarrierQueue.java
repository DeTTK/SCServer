package szserver.net;

import szserver.client.GrenadeAreaScenes;
import szserver.inventory.GrenadeAreaStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Network-owner admission; shared shield durability is written only by its store worker. */
public final class CombatBarrierQueue {
    public interface Lookup { GrenadeAreaScenes.ShieldContact find(double[] from,double[] to,long at); }
    public interface Debit { CompletableFuture<GrenadeAreaStore.ShieldHit> hit(UUID shield,UUID request,float damage,long at); }
    private static final int LIMIT=128;
    private final Lookup lookup;private final Debit debit;private final LongSupplier clock;
    private final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private long blocked,errors,rejected;
    private static final class Pending {
        final UUID request;final double[] from,to;final ToDoubleFunction<GrenadeAreaScenes.ShieldContact> damage;final long admitted;float debitAmount;
        final BooleanSupplier valid;final Runnable clear;final Consumer<GrenadeAreaScenes.ShieldContact> absorbed;
        GrenadeAreaScenes.ShieldContact contact;CompletableFuture<GrenadeAreaStore.ShieldHit> future;int retries,barriers;
        Pending(UUID request,double[] from,double[] to,ToDoubleFunction<GrenadeAreaScenes.ShieldContact> damage,long at,BooleanSupplier valid,Runnable clear,Consumer<GrenadeAreaScenes.ShieldContact> absorbed){this.request=request;this.from=from.clone();this.to=to.clone();this.damage=damage;admitted=at;this.valid=valid;this.clear=clear;this.absorbed=absorbed;}
    }
    public CombatBarrierQueue(Lookup lookup,Debit debit){this(lookup,debit,System::currentTimeMillis);}
    public CombatBarrierQueue(Lookup lookup,Debit debit,LongSupplier clock){this.lookup=lookup;this.debit=debit;this.clock=clock;}
    public boolean submit(UUID request,double[] from,double[] to,float damage,BooleanSupplier valid,Runnable clear,Consumer<GrenadeAreaScenes.ShieldContact> absorbed){
        if(!Float.isFinite(damage)||damage<=0||damage>100000)throw new IllegalArgumentException("barrier_damage_request");
        return submit(request,from,to,c->damage,valid,clear,absorbed);
    }
    public boolean submit(UUID request,double[] from,double[] to,ToDoubleFunction<GrenadeAreaScenes.ShieldContact> damage,BooleanSupplier valid,Runnable clear,Consumer<GrenadeAreaScenes.ShieldContact> absorbed){
        Objects.requireNonNull(request);Objects.requireNonNull(damage);if(from.length!=3||to.length!=3)throw new IllegalArgumentException("barrier_damage_request");
        for(double v:from)if(!Double.isFinite(v))throw new IllegalArgumentException("barrier_origin");for(double v:to)if(!Double.isFinite(v))throw new IllegalArgumentException("barrier_target");
        if(!valid.getAsBoolean())return true;
        Pending p=new Pending(request,from,to,damage,clock.getAsLong(),valid,clear,absorbed);
        if(lookup.find(p.from,p.to,p.admitted)==null){clear.run();return true;}
        if(pending.size()>=LIMIT){rejected++;return false;}
        if(prepare(p)){pending.addLast(p);}else clear.run();return true;
    }
    private boolean prepare(Pending p){
        p.contact=lookup.find(p.from,p.to,p.admitted);if(p.contact==null)return false;
        try{p.debitAmount=(float)p.damage.applyAsDouble(p.contact);if(!Float.isFinite(p.debitAmount)||p.debitAmount<=0||p.debitAmount>100000)throw new IllegalArgumentException("barrier_damage_value");p.future=debit.hit(p.contact.activation().projectile(),p.request,p.debitAmount,p.admitted);}
        catch(RuntimeException failure){p.future=CompletableFuture.failedFuture(failure);}p.barriers++;return true;
    }
    /** Fair polling: one slow journal receipt cannot hold up unrelated damage. */
    public void advance(){
        int count=pending.size();for(int i=0;i<count;i++){
            Pending p=pending.removeFirst();if(!p.future.isDone()){pending.addLast(p);continue;}
            GrenadeAreaStore.ShieldHit result;
            try{result=p.future.join();}catch(RuntimeException error){
                Throwable cause=error instanceof CompletionException?error.getCause():error;if(cause instanceof RejectedExecutionException&&p.retries++<3&&clock.getAsLong()-p.admitted<2000){try{p.future=debit.hit(p.contact.activation().projectile(),p.request,p.debitAmount,p.admitted);}catch(RuntimeException failed){p.future=CompletableFuture.failedFuture(failed);}pending.addLast(p);}else errors++;continue;
            }
            if(result.blocked()){if(result.applied())blocked++;if(p.valid.getAsBoolean())p.absorbed.accept(p.contact);continue;}
            if(!p.valid.getAsBoolean())continue;
            // A shield can break while another attack waits for its receipt. Check the
            // remaining barriers instead of bypassing every shield on the fallback path.
            if(p.barriers>=128){errors++;continue;}
            if(prepare(p))pending.addLast(p);else p.clear.run();
        }
    }
    public Map<String,Object> metrics(){return Map.of("pending",pending.size(),"blocked",blocked,"errors",errors,"rejected",rejected,"limit",LIMIT);}
    public int pending(){return pending.size();}
}
