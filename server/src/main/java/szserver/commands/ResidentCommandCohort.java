package szserver.commands;

import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One owner revision for native map/history and a captured command-worker root. */
public final class ResidentCommandCohort implements ResidentChunks.State {
    private final Thread owner=Thread.currentThread();private final ResidentOperationLog history;private final CommandService source;
    private long observedPublication,savedPublication=-1;private CommandService.ResidentCut cut;
    private Set<String> savedNames;private Pending pending;
    private record Encoded(Set<String> names,long publication){}
    private record Pending(long revision,AtomicReference<Encoded> encoded){}
    /** Construct on WorldLoop; names must be from the already selected durable command cohort. */
    public ResidentCommandCohort(ResidentOperationLog history,CommandService source,Set<String> savedNames){
        this.history=Objects.requireNonNull(history);this.source=Objects.requireNonNull(source);this.savedNames=Set.copyOf(savedNames);observedPublication=source.residentPublication();
        for(String name:savedNames)if(!name.equals(ResidentCommandCheckpoint.ROOT)&&!name.equals(ResidentCommandIndex.ROOT)&&!name.matches("(?:command-page-|command-index-page-)[0-9a-f]{64}"))throw new IllegalArgumentException("resident_command_saved_name");
        if(!savedNames.isEmpty()&&!savedNames.contains(ResidentCommandCheckpoint.ROOT))throw new IllegalArgumentException("resident_command_saved_root_missing");
        if(savedNames.isEmpty())history.touch();
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("resident_command_cohort_wrong_owner");}
    @Override public long revision(){owner();long publication=source.residentPublication();if(publication!=observedPublication){if(publication<observedPublication)throw new IllegalStateException("resident_command_publication_regressed");history.touch();observedPublication=publication;}return history.revision();}
    private void bind(World world,CommandService.ResidentCut next){
        owner();if(!world.identity().equals(next.identity())||!world.sceneRegistryDigest().equals(next.registry())||next.publication()!=source.residentPublication()||next.publication()<observedPublication)throw new IllegalStateException("resident_command_cut_stale");
        if(history.commandsRequired()&&next.index()==null)throw new IllegalStateException("resident_command_index_not_bound");if(next.index()!=null)history.requireCommands();cut=next;observedPublication=next.publication();history.world(ResidentWorldMetadata.capture(world).payload());
    }
    /** Every periodic/explicit/eviction/stop save uses this same command/owner handshake. */
    public <C,S> void install(ResidentChunks<C,S> chunks,WorldLoop loop){
        owner();chunks.captureGate(action->source.captureResidentCut((world,next)->action.run(()->bind(world,next))));
        source.residentCloseHook(()->loop.submit(world->chunks.save()).thenCompose(future->future).exceptionallyCompose(error->loop.submit(world->{chunks.poison("resident_command_final_save_failed");throw new CompletionException(error);})));
    }
    public ResidentChunks.CaptureGate gate(){owner();return action->source.captureResidentCut((world,next)->action.run(()->bind(world,next)));}
    public void install(ResidentMapRuntime runtime,WorldLoop loop){
        owner();loop.installResidentTerrain(runtime);if(source.residentHistoryEnabled())runtime.canonicalCommands();runtime.captureGate(gate());source.residentCloseHook(()->loop.submit(world->runtime.save()).thenCompose(future->future).exceptionallyCompose(error->loop.submit(world->{runtime.poison("resident_command_final_save_failed");throw new CompletionException(error);})));
    }
    @Override public ResidentChunks.FrozenState freeze()throws Exception{
        owner();if(cut==null||cut.publication()!=source.residentPublication())throw new IllegalStateException("resident_command_cut_missing");
        var map=history.freeze();var captured=cut;var known=savedNames;boolean reuse=savedPublication==captured.publication()&&!known.isEmpty();var marker=new AtomicReference<Encoded>();pending=new Pending(map.revision(),marker);
        return new ResidentChunks.FrozenState(map.revision(),()->{
            var base=map.encoder().encode();if(reuse){marker.set(new Encoded(known,captured.publication()));return base;}
            List<ResidentChunks.StateFile> files=new ArrayList<>(base.files());long[] retained={files.stream().mapToLong(ResidentChunks.StateFile::size).sum()};
            var encoded=captured.snapshot().encode(new ResidentCommandCheckpoint.Binding(captured.identity(),captured.registry(),map.revision(),captured.source()),known,file->{retained[0]=Math.addExact(retained[0],file.size());if(retained[0]>128L*1024*1024)throw new IllegalArgumentException("resident_command_save_delta_capacity");files.add(file);});files.add(encoded.root());
            Set<String> names=new HashSet<>(encoded.names());if(captured.index()!=null){var index=captured.index().encode(map.revision(),known,file->{retained[0]=Math.addExact(retained[0],file.size());if(retained[0]>128L*1024*1024)throw new IllegalArgumentException("resident_command_save_delta_capacity");files.add(file);});names.addAll(index.names());}
            Set<String> removed=new HashSet<>(base.removed());for(String name:known)if(!names.contains(name))removed.add(name);
            var result=new ResidentChunks.StateDelta(map.revision(),files,removed);marker.set(new Encoded(Set.copyOf(names),captured.publication()));return result;
        });
    }
    @Override public void persisted(long revision){owner();if(pending==null||pending.revision()!=revision||pending.encoded().get()==null)throw new IllegalStateException("resident_command_save_completion");var encoded=pending.encoded().get();history.persisted(revision);savedNames=encoded.names();savedPublication=encoded.publication();pending=null;}
}
