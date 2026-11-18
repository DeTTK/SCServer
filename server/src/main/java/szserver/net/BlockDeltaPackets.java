package szserver.net;

/** Concatenate existing native messages on their reliable byte stream, up to 128 cells. */
final class BlockDeltaPackets {
    static byte[] frame(int x,int z,TerrainProjection.Changes changes,long sent){
        if(!changes.usable(sent)||changes.deltas().size()>128)throw new IllegalArgumentException("block_delta_barrier");
        return Wire.bytes(out->{for(var delta:changes.deltas())if(delta.voxel().revision()>sent){
            out.write(GamePackets.block(x*16+(delta.cell()&15),delta.cell()>>>8,z*16+((delta.cell()>>>4)&15),delta.voxel().id(),delta.voxel().metadata()));
        }for(var tile:changes.tiles())if(tile.revision()>sent)out.write(tile.frame());});
    }
}
