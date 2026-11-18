package szserver.net;

import szserver.world.World;
import szserver.world.NativePortal;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Transport-owner async lookup. One flight per player; cold indexes retry without input or IO. */
public final class PortalLookup {
    public record Request(World.Vec source,boolean interaction,long life,double selector){}
    public record Result(NativePortal.Activation activation,boolean failed){}
    private static final Result NONE=new Result(null,false);
    private static final long TIMEOUT=2_000_000_000L,RETRY=50_000_000L;
    private Request active;private CompletableFuture<World.PortalQuery> flight;private World.Vec queuedUse;
    private long deadline,next,retries,stale,timeouts,sceneStale;
    public boolean busy(){return active!=null;}
    public long sceneStale(){return sceneStale;}
    public long retries(){return retries;}public long stale(){return stale;}public long timeouts(){return timeouts;}
    public void clear(){active=null;flight=null;queuedUse=null;}
    public void request(World.Vec source,boolean interaction,long life,double selector,long now,Function<Request,CompletableFuture<World.PortalQuery>> submit){
        if(busy()){if(interaction)queuedUse=source;return;}
        active=new Request(source,interaction,life,selector);deadline=now+TIMEOUT;next=now;flight=submit.apply(active);
    }
    public Result advance(World.Vec source,long life,boolean available,long now,LongSupplier sceneEpoch,Function<Request,CompletableFuture<World.PortalQuery>> submit){
        if(!busy())return NONE;if(!available){clear();return NONE;}
        if(active.life()!=life){stale++;clear();return NONE;}
        if(now>=deadline){timeouts++;clear();return NONE;}
        if(flight==null){if(now<next)return NONE;retries++;flight=submit.apply(active);}
        if(!flight.isDone())return NONE;
        World.PortalQuery result;try{result=flight.join();}catch(RuntimeException failure){clear();return new Result(null,true);}
        if(result.sceneEpoch()!=sceneEpoch.getAsLong()){
            // Recheck current state; never rebase an obsolete trigger or accept obsolete absence.
            boolean use=source.equals(queuedUse)||(source.equals(active.source())&&active.interaction());
            active=new Request(source,use,life,active.selector());queuedUse=null;
            stale++;sceneStale++;flight=null;next=now+RETRY;return NONE;
        }
        if(!active.source().equals(source)){
            NativePortal.Activation hit=result.activation();
            if(result.ready()&&hit!=null&&hit.portal().contains(source,active.interaction())){
                // Moving through the same trigger is valid; recompute authored offsets at current position.
                World.Vec before=active.source(),target=hit.target();var settings=hit.portal().parameters();
                hit=new NativePortal.Activation(hit.portal(),TeleportCommands.warpPosition(new World.Vec(target.x()+(settings.horizontalOffset()?source.x()-before.x():0),target.y()+(settings.verticalOffset()?source.y()-before.y():0),target.z()+(settings.horizontalOffset()?source.z()-before.z():0))),hit.destination());clear();return new Result(hit,false);
            }
            boolean use=source.equals(queuedUse);double selector=active.selector();stale++;clear();request(source,use,life,selector,now,submit);return NONE;
        }
        if(!result.ready()){flight=null;next=now+RETRY;return NONE;}
        NativePortal.Activation activation=result.activation();World.Vec use=queuedUse;double selector=active.selector();clear();
        if(activation==null&&source.equals(use))request(source,true,life,selector,now,submit);
        return new Result(activation,false);
    }
}
