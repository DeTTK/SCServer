package szserver.commands;

import szserver.persistence.*;
import szserver.world.*;

/** All twelve typed sections. This codec does not itself enable startup:source/template binding,
 * complete history-index rebuild,canonical suffix application and fallback belong to bootstrap. */
final class WorldStateCheckpoint {
    record Bundle(World world,CommandStateCheckpoint.State commands,boolean nativeSceneBase){Bundle(World world,CommandStateCheckpoint.State commands){this(world,commands,false);}}
    static void write(WorldCheckpointStore.Sink sink,WorldMapCheckpoint.Export map,CommandStateCheckpoint.State commands)throws Exception{map.write(sink);CommandStateCheckpoint.write(sink,commands);}
    static void write(WorldCheckpointStore.Sink sink,WorldMapCheckpoint.Export map,CommandStateCheckpoint.State commands,String binding)throws Exception{map.writeBound(sink,binding);CommandStateCheckpoint.write(sink,commands);}
    static Bundle read(WorldCheckpointStore store,WorldCheckpointStore.Validated expected,World source)throws Exception{
        return read(store,expected,source,null);
    }
    static Bundle read(WorldCheckpointStore store,WorldCheckpointStore.Validated expected,World source,String binding)throws Exception{
        var map=new WorldMapCheckpoint.Reader(source);var commands=new CommandStateCheckpoint.Reader(store.path().toAbsolutePath().getParent());
        boolean accepted=false;try{
        var fragments=new CheckpointDataRows.Reader((section,row)->{if(!map.accept(section,row))commands.accept(section,row);});store.replay(expected,fragments);fragments.finish();
        if(!map.nativeBase().isEmpty())throw new IllegalArgumentException("checkpoint_native_base_restore_required");if(binding!=null&&!binding.equals(map.binding()))throw new IllegalArgumentException("checkpoint_base_binding_mismatch");
        CommandStateCheckpoint.State state=commands.finish();World restored=map.finish();state.actors().restoreWorld(restored);accepted=true;return new Bundle(restored,state);
        }finally{if(!accepted)commands.discard();}
    }
    record Restored(WorldCheckpointStore.Validated checkpoint,Bundle bundle){}
    static java.util.Optional<Restored> restore(WorldCheckpointStore store,World source,String binding)throws Exception{return restore(store,source,binding,null);}
    static java.util.Optional<Restored> restore(WorldCheckpointStore store,World source,String binding,NativeChunkCommits nativeCommits)throws Exception{
        var map=new WorldMapCheckpoint.Reader(source);var commands=new CommandStateCheckpoint.Reader(store.path().toAbsolutePath().getParent());boolean accepted=false;
        try{var fragments=new CheckpointDataRows.Reader((section,row)->{if(!map.accept(section,row))commands.accept(section,row);});var checked=store.restoreDetached(source.identity(),source.sceneRegistryDigest(),fragments);if(checked.isEmpty())return java.util.Optional.empty();fragments.finish();
            RegionTerrain regions=source.checkpointTerrain() instanceof RegionTerrain value?value:null;NativeCommitIndex base=NativeCommitIndex.empty();
            if(!map.nativeBase().isEmpty()){if(nativeCommits==null||regions==null)throw new IllegalArgumentException("checkpoint_native_base_unavailable");base=nativeCommits.indexAt(map.nativeBase());if(base.prefix().end()>checked.get().metadata().prefix().end()||base.prefix().records()>checked.get().metadata().prefix().records())throw new IllegalArgumentException("checkpoint_native_base_after_source_cut");}
            String expectedBinding=regions==null?binding:regions.checkpointBindingFor(base,map.nativeSceneBase());
            if(expectedBinding!=null&&!expectedBinding.equals(map.binding()))throw new IllegalArgumentException("checkpoint_base_binding_mismatch");map.validateNativeBase(base);var state=commands.finish();World restored=map.finish();state.actors().restoreWorld(restored);
            if(regions!=null)regions.prepareLive(base).get().publish();accepted=true;return java.util.Optional.of(new Restored(checked.get(),new Bundle(restored,state,map.nativeSceneBase())));
        }finally{if(!accepted)commands.discard();}
    }
    private WorldStateCheckpoint(){}
}
