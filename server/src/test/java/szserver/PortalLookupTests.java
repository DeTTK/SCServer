package szserver;

import szserver.net.PortalLookup;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.nio.file.*;
import szserver.util.Json;

/** Deterministic delayed owner/terrain/lifecycle checks on the production lookup state machine. */
public final class PortalLookupTests {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private static void epochChecks(World.Vec source,World.Vec away,NativePortal portal)throws Exception{
        Terrain terrain=new Terrain(){public boolean solid(int x,int y,int z){return false;}public Map<UUID,NativePortal> portalIndex(int x,int z){return x==0&&z==0?Map.of(portal.id(),portal):Map.of();}};
        try(WorldLoop owner=new WorldLoop(new World(terrain))){
            owner.start();var lookup=new PortalLookup();var pending=new ArrayList<CompletableFuture<World.PortalQuery>>();var requests=new ArrayList<PortalLookup.Request>();
            Function<PortalLookup.Request,CompletableFuture<World.PortalQuery>> submit=r->{requests.add(r);var f=owner.submit(w->w.portalQuery(r.source(),r.interaction(),null,r.selector()));pending.add(f);return f;};
            lookup.request(source,true,1,.25,0,submit);var before=pending.get(0).get(2,TimeUnit.SECONDS);check(before.activation()!=null&&before.sceneEpoch()==owner.portalEpoch(),"owner result captures current published epoch");
            owner.submit(w->{w.scene(0,0,portal.id(),null);w.finishSceneMutation(Map.of(portal.id(),true));return true;}).get(2,TimeUnit.SECONDS);
            check(owner.portalEpoch()>before.sceneEpoch(),"native deletion/finalization publishes epoch without periodic snapshot wait");
            check(lookup.advance(source,1,true,1,owner::portalEpoch,submit).activation()==null&&lookup.busy()&&lookup.sceneStale()==1,"completed pre-deletion portal cannot teleport");
            lookup.advance(source,1,true,50_000_000,owner::portalEpoch,submit);check(requests.size()==1,"scene retry paced50ms");
            lookup.advance(source,1,true,50_000_001,owner::portalEpoch,submit);pending.get(1).get(2,TimeUnit.SECONDS);check(requests.get(1).interaction()&&requests.get(1).selector()==.25,"one-shot interaction and destination selector retained after edit");
            check(lookup.advance(source,1,true,50_000_002,owner::portalEpoch,submit).activation()==null&&!lookup.busy(),"fresh absence completes deletion retry");
            lookup.request(source,true,1,0,100_000_000,submit);var empty=pending.get(2).get(2,TimeUnit.SECONDS);check(empty.activation()==null,"ready empty lookup captured before creation");
            owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(portal.id(),portal.asset(),portal.position(),1,false),portal)));return true;}).get(2,TimeUnit.SECONDS);
            check(owner.portalEpoch()>empty.sceneEpoch(),"metadata creation publishes epoch");
            check(lookup.advance(source,1,true,100_000_001,owner::portalEpoch,submit).activation()==null&&lookup.busy(),"obsolete absence retries instead of missing new portal");
            lookup.advance(source,1,true,150_000_001,owner::portalEpoch,submit);pending.get(3).get(2,TimeUnit.SECONDS);check(lookup.advance(source,1,true,150_000_002,owner::portalEpoch,submit).activation()!=null,"fresh created portal discovered without new input");
            var changed=new NativePortal(portal.id(),portal.asset(),portal.behaviour(),portal.position(),1,new NativePortal.Parameters(false,1,true,true,3,List.of(new NativePortal.Destination(new World.Vec(160.5,64,8.5),1,"changed"))));
            lookup.request(source,true,1,.5,200_000_000,submit);pending.get(4).get(2,TimeUnit.SECONDS);
            owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(changed.id(),changed.asset(),changed.position(),1,false),changed)));return true;}).get(2,TimeUnit.SECONDS);
            var walking=new World.Vec(source.x()+.25,source.y(),source.z());check(lookup.advance(walking,1,true,200_000_001,owner::portalEpoch,submit).activation()==null,"scene epoch checked before old same-trigger offset rebase");
            lookup.advance(walking,1,true,250_000_001,owner::portalEpoch,submit);pending.get(5).get(2,TimeUnit.SECONDS);check(!requests.get(5).interaction()&&requests.get(5).source().equals(walking),"old F discarded when source moves during scene edit");
            var fresh=lookup.advance(walking,1,true,250_000_002,owner::portalEpoch,submit).activation();check(fresh!=null&&fresh.destination().equals("changed")&&fresh.target().x()>160,"new settings supply destination and current-position offsets");
            lookup.request(source,false,1,.75,300_000_000,submit);pending.get(6).get(2,TimeUnit.SECONDS);lookup.request(away,true,1,.75,300_000_001,submit);
            var moved=new NativePortal(portal.id(),portal.asset(),portal.behaviour(),new World.Vec(away.x(),64.9,away.z()),1,portal.parameters());
            owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(moved.id(),moved.asset(),moved.position(),1,false),moved)));return true;}).get(2,TimeUnit.SECONDS);
            lookup.advance(away,1,true,300_000_002,owner::portalEpoch,submit);lookup.advance(away,1,true,350_000_002,owner::portalEpoch,submit);pending.get(7).get(2,TimeUnit.SECONDS);check(requests.get(7).interaction()&&requests.get(7).source().equals(away),"queued F at current source retained through scene invalidation");
            check(lookup.advance(away,1,true,350_000_003,owner::portalEpoch,submit).activation()!=null,"fresh moved trigger accepts scoped queued interaction");
            lookup.request(away,true,1,0,400_000_000,submit);pending.get(8).get(2,TimeUnit.SECONDS);
            owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(moved.id(),moved.asset(),null,0,true),null)));return true;}).get(2,TimeUnit.SECONDS);
            lookup.advance(away,1,true,400_000_001,owner::portalEpoch,submit);int count=requests.size();check(lookup.advance(away,2,true,450_000_001,owner::portalEpoch,submit).activation()==null&&!lookup.busy()&&requests.size()==count,"respawn clears scene-invalidated pending retry");
            lookup.request(away,true,2,0,500_000_000,submit);pending.get(9).get(2,TimeUnit.SECONDS);
            owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(moved.id(),moved.asset(),moved.position(),1,false),moved)));return true;}).get(2,TimeUnit.SECONDS);
            lookup.advance(away,2,true,500_000_001,owner::portalEpoch,submit);check(lookup.advance(away,2,false,550_000_001,owner::portalEpoch,submit).activation()==null&&!lookup.busy(),"cooldown invalidates queued scene retry");
            // Repeated edits must not reset the original deadline or create an unpaced owner task flood.
            lookup.request(away,false,3,.125,600_000_000,submit);pending.get(10).get(2,TimeUnit.SECONDS);
            for(int n=0;n<5;n++){
                owner.submit(w->{w.applyMetadataPortals(List.of(new World.MetadataPortalChange(new PortalMetadata(moved.id(),moved.asset(),moved.position(),1,false),moved)));return true;}).get(2,TimeUnit.SECONDS);
                long now=600_000_001+n*50_000_001L;check(lookup.advance(away,3,true,now,owner::portalEpoch,submit).activation()==null,"repeated edited result discarded");
                lookup.advance(away,3,true,now+50_000_000,owner::portalEpoch,submit);pending.get(pending.size()-1).get(2,TimeUnit.SECONDS);
            }
            count=requests.size();check(lookup.advance(away,3,true,2_600_000_000L,owner::portalEpoch,submit).activation()==null&&!lookup.busy()&&lookup.timeouts()==1&&requests.size()==count,"continuous edits retain original2s deadline");
            long epoch=owner.portalEpoch();owner.submit(w->{w.applyMetadataPortals(List.of());w.finishSceneMutation(Map.of());w.block(new World.Block(1,10,1),1);return true;}).get(2,TimeUnit.SECONDS);check(owner.portalEpoch()==epoch,"empty scene work and unrelated blocks do not invalidate portal queries");
        }
    }
    public static void main(String[] args)throws Exception{
        var first=new World.Vec(8.5,64,8.5);var away=new World.Vec(40.5,64,8.5);
        NativePortal portal=new NativePortal(new UUID(0,1),"qa","teleport_generic",new World.Vec(8.5,64.9,8.5),1,new NativePortal.Parameters(false,1,true,true,3,List.of(new NativePortal.Destination(new World.Vec(80.5,64,8.5),1,"qa"))));
        var activation=new NativePortal.Activation(portal,new World.Vec(80.5,64,8.5),"qa");var yes=new World.PortalQuery(activation,true,0);var absent=new World.PortalQuery(null,true,0);var cold=new World.PortalQuery(null,false,0);
        var pending=new ArrayList<CompletableFuture<World.PortalQuery>>();var requests=new ArrayList<PortalLookup.Request>();Function<PortalLookup.Request,CompletableFuture<World.PortalQuery>> submit=r->{requests.add(r);var f=new CompletableFuture<World.PortalQuery>();pending.add(f);return f;};
        var lookup=new PortalLookup();lookup.request(first,true,1,.25,0,submit);pending.get(0).complete(cold);check(lookup.advance(first,1,true,1,()->0,submit).activation()==null&&lookup.busy(),"cold is pending, not absent");lookup.advance(first,1,true,49_000_000,()->0,submit);check(requests.size()==1,"cold retry paced without task flood");lookup.advance(first,1,true,50_000_001,()->0,submit);check(requests.size()==2&&lookup.retries()==1&&requests.get(1).interaction(),"one F press retries on owner without further input");pending.get(1).complete(yes);check(lookup.advance(first,1,true,60_000_000,()->0,submit).activation()==activation&&!lookup.busy(),"loaded portal activates exact pending interaction");
        lookup.request(first,false,2,0,100_000_000,submit);lookup.request(first,true,2,0,100_000_001,submit);int at=pending.size()-1;pending.get(at).complete(absent);lookup.advance(first,2,true,110_000_000,()->0,submit);check(lookup.busy()&&requests.get(requests.size()-1).interaction(),"interaction queued behind automatic probe retains exact source");pending.get(pending.size()-1).complete(yes);check(lookup.advance(first,2,true,120_000_000,()->0,submit).activation()==activation,"queued F activation");
        lookup.request(first,false,3,0,200_000_000,submit);at=pending.size()-1;pending.get(at).complete(yes);check(lookup.advance(away,3,true,210_000_000,()->0,submit).activation()==null&&lookup.stale()==1,"completed old-position result cannot teleport moved player");check(requests.get(requests.size()-1).source().equals(away)&&!requests.get(requests.size()-1).interaction(),"current position automatically rechecked");pending.get(pending.size()-1).complete(absent);lookup.advance(away,3,true,220_000_000,()->0,submit);check(!lookup.busy(),"ready empty lookup finishes");
        lookup.request(first,true,4,0,300_000_000,submit);lookup.request(first,true,4,0,300_000_001,submit);at=pending.size()-1;pending.get(at).complete(yes);check(lookup.advance(away,4,true,310_000_000,()->0,submit).activation()==null&&!requests.get(requests.size()-1).interaction(),"old queued F is not replayed at a different portal");lookup.clear();pending.get(pending.size()-1).complete(yes);check(lookup.advance(away,4,true,320_000_000,()->0,submit).activation()==null,"cleared abandoned future cannot activate");
        lookup.request(first,true,5,0,400_000_000,submit);at=pending.size()-1;pending.get(at).complete(yes);int n=requests.size();check(lookup.advance(first,6,true,410_000_000,()->0,submit).activation()==null&&!lookup.busy()&&requests.size()==n,"life/respawn invalidates late completion");
        for(boolean available:List.of(false)){lookup.request(first,true,6,0,500_000_000,submit);pending.get(pending.size()-1).complete(yes);check(lookup.advance(first,6,available,510_000_000,()->0,submit).activation()==null&&!lookup.busy(),"cooldown/teleport/not-ready drops stale request");}
        lookup.request(first,true,7,0,600_000_000,submit);pending.get(pending.size()-1).completeExceptionally(new IllegalArgumentException("queue_full"));check(lookup.advance(first,7,true,610_000_000,()->0,submit).failed()&&!lookup.busy(),"owner failure releases pending slot");
        lookup.request(first,true,8,0,700_000_000,submit);at=pending.size()-1;check(lookup.advance(first,8,true,2_700_000_000L,()->0,submit).activation()==null&&!lookup.busy()&&lookup.timeouts()==1,"stalled owner future expires without another input");pending.get(at).complete(yes);check(lookup.advance(first,8,true,2_800_000_000L,()->0,submit).activation()==null,"expired late result ignored");
        final boolean[] loaded={false};final int[] warmed={0};Terrain delayed=new Terrain(){public boolean solid(int x,int y,int z){return false;}public boolean ready(int x,int z){warmed[0]++;return loaded[0];}public Map<UUID,NativePortal> portalIndex(int x,int z){return loaded[0]&&x==0&&z==0?Map.of(portal.id(),portal):Map.of();}};World world=new World(delayed);
        check(!world.portalQuery(first,false,null,0).ready()&&warmed[0]==9,"all nine cold indexes requested on owner, no disk/parser wait");loaded[0]=true;check(world.portalQuery(first,false,null,0).activation()!=null,"same world query discovers asynchronously loaded portal");check(world.portalQuery(away,false,null,0).ready()&&world.portalQuery(away,false,null,0).activation()==null,"ready absence distinct from unloaded index");
        final boolean[] published={false};Terrain publisher=new Terrain(){public boolean solid(int x,int y,int z){return false;}public boolean portalIndexReady(int x,int z){published[0]=true;return true;}public Map<UUID,NativePortal> portalIndex(int x,int z){return published[0]&&x==0&&z==0?Map.of(portal.id(),portal):Map.of();}};check(new World(publisher).portalQuery(first,false,null,0).activation()!=null,"publication race cannot turn pre-publication empty indexes into verified absence");
        var walking=new World.Vec(first.x()+.25,first.y()+.1,first.z()+.125);lookup.request(first,false,9,0,3_000_000_000L,submit);pending.get(pending.size()-1).complete(yes);var moved=lookup.advance(walking,9,true,3_050_000_000L,()->0,submit).activation();check(moved!=null&&moved.portal().id().equals(portal.id())&&!lookup.busy(),"walking within same trigger still activates");check(Math.abs(moved.target().x()-80.75)<1e-9&&Math.abs(moved.target().y()-64.1)<1e-9&&Math.abs(moved.target().z()-8.625)<1e-9,"late successful query rebases authored horizontal/vertical offsets at current position");
        epochChecks(first,away,new NativePortal(portal.id(),portal.asset(),portal.behaviour(),portal.position(),1,new NativePortal.Parameters(true,1,true,true,3,portal.parameters().destinations())));
System.out.println("PASS portal lookup "+checks+" assertions: cold one-press retry, bounded pacing/timeout, stale scene/movement/lifecycle/F, owner failure and world readiness");
    }
}
