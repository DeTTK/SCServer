package szserver.net;

import szserver.world.World;
import szserver.world.NativePortal;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.UUID;

/** Transport-owner async lookup. One flight per player; cold indexes retry without input or IO. */
public final class PortalLookup {
    public record Request(World.Vec source,boolean interaction,long life,double selector,UUID objectId){public Request(World.Vec source,boolean interaction,long life,double selector){this(source,interaction,life,selector,null);}}
    public record Result(NativePortal.Activation activation,boolean failed){}
    private static final Result NONE=new Result(null,false);
    private static final long TIMEOUT=2_000_000_000L,RETRY=50_000_000L;
    private Request active;private CompletableFuture<World.PortalQuery> flight;private Request queuedUse;
    private long deadline,next,retries,stale,timeouts,sceneStale;
    public boolean busy(){return active!=null;}
    public long sceneStale(){return sceneStale;}
    public long retries(){return retries;}public long stale(){return stale;}public long timeouts(){return timeouts;}
    public void clear(){active=null;flight=null;queuedUse=null;}
    public void request(World.Vec source,boolean interaction,long life,double selector,long now,Function<Request,CompletableFuture<World.PortalQuery>> submit){
        request(source,interaction,life,selector,null,now,submit);
    }
    public void request(World.Vec source,boolean interaction,long life,double selector,UUID objectId,long now,Function<Request,CompletableFuture<World.PortalQuery>> submit){
        if(busy()){if(interaction)queuedUse=new Request(source,true,life,selector,objectId);return;}
        active=new Request(source,interaction,life,selector,objectId);deadline=now+TIMEOUT;next=now;flight=submit.apply(active);
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
            boolean queued=queuedUse!=null&&source.equals(queuedUse.source());boolean use=queued||(source.equals(active.source())&&active.interaction());
            active=new Request(source,use,life,queued?queuedUse.selector():active.selector(),use?(queued?queuedUse.objectId():active.objectId()):null);queuedUse=null;
            stale++;sceneStale++;flight=null;next=now+RETRY;return NONE;
        }
        if(!active.source().equals(source)){
            NativePortal.Activation hit=result.activation();
            if(result.ready()&&hit!=null&&hit.portal().contains(source,active.interaction())){
                // Moving through the same trigger is valid; recompute authored offsets at current position.
                World.Vec before=active.source(),target=hit.target();var settings=hit.portal().parameters();
                hit=new NativePortal.Activation(hit.portal(),TeleportCommands.warpPosition(new World.Vec(target.x()+(settings.horizontalOffset()?source.x()-before.x():0),target.y()+(settings.verticalOffset()?source.y()-before.y():0),target.z()+(settings.horizontalOffset()?source.z()-before.z():0))),hit.destination());clear();return new Result(hit,false);
            }
            boolean use=queuedUse!=null&&source.equals(queuedUse.source());double selector=use?queuedUse.selector():active.selector();UUID objectId=use?queuedUse.objectId():null;stale++;clear();request(source,use,life,selector,objectId,now,submit);return NONE;
        }
        if(!result.ready()){flight=null;next=now+RETRY;return NONE;}
        NativePortal.Activation activation=result.activation();Request use=queuedUse;clear();
        if(activation==null&&use!=null&&source.equals(use.source()))request(source,true,life,use.selector(),use.objectId(),now,submit);
        return new Result(activation,false);
    }
}
