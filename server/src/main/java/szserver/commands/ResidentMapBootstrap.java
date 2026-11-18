package szserver.commands;

import szserver.persistence.Journal;
import szserver.world.*;
import java.io.IOException;
import java.nio.file.Path;

/** Select and validate one map/history/command root before any native chunk becomes visible. */
public final class ResidentMapBootstrap {
    private ResidentMapBootstrap(){}
    public static final class Bundle implements AutoCloseable {
        private final ResidentOperationLog.Loaded history;private final ResidentWorldMetadata.Snapshot metadata;
        private final ResidentCommandCheckpoint.Binding binding;private CommandStateCheckpoint.State commands;
        private final ResidentCommandIndex.Loaded receipts;
        private Bundle(ResidentOperationLog.Loaded history,ResidentWorldMetadata.Snapshot metadata,ResidentCommandCheckpoint.Binding binding,CommandStateCheckpoint.State commands,ResidentCommandIndex.Loaded receipts){this.history=history;this.metadata=metadata;this.binding=binding;this.commands=commands;this.receipts=receipts;}
        public ResidentOperationLog.Loaded history(){return history;}
        public ResidentWorldMetadata.Snapshot metadata(){return metadata;}
        public ResidentCommandCheckpoint.Binding binding(){return binding;}
        public ResidentCommandIndex.Loaded receipts(){return receipts;}
        CommandStateCheckpoint.State commands(){return java.util.Objects.requireNonNull(commands,"resident_command_bundle_transferred");}
        /** A live command service takes ownership of detached archive handles exactly once. */
        CommandStateCheckpoint.State takeCommands(){var state=commands();commands=null;return state;}
        @Override public void close(){if(commands!=null){commands.close();commands=null;}}
    }
    /** A nonnull legacy journal also verifies the pinned canonical history prefix used by migration. */
    public static Bundle load(ResidentChunks.Storage storage,Path base,String world,String registry,Journal legacy)throws Exception{
        return storage.bootstrapState(files->{
            var history=ResidentOperationLog.restore(files,ResidentWorldMetadata::decode);if(history.revision()==0)throw new IOException("resident_map_not_migrated");
            var metadata=ResidentWorldMetadata.decode(history.world());try{metadata.requireBinding(world,registry);}catch(IllegalArgumentException mismatch){throw new IOException("resident_map_world_binding",mismatch);}
            var binding=ResidentCommandCheckpoint.binding(files);if(legacy!=null&&(binding.source()==null||!legacy.matches(binding.source())))throw new IOException("resident_command_source_changed");
            var receipts=history.commands()?ResidentCommandIndex.restore(files,history.lastEvent()):ResidentCommandIndex.Loaded.empty();var commands=ResidentCommandCheckpoint.restore(files,base,world,registry,history.revision());return new Bundle(history,metadata,binding,commands,receipts);
        });
    }
}
