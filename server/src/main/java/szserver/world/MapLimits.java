package szserver.world;

import java.util.Properties;

/** Immutable per-world voxel capacity, shared by live/worker/detached/restore paths. */
public record MapLimits(int voxels) {
    public static final MapLimits DEFAULT=new MapLimits(10_000_000);
    public MapLimits {if(voxels<1||voxels>50_000_000)throw new IllegalArgumentException("world_voxel_limit_bounds");}
    public static MapLimits configured(Properties properties){
        try{return new MapLimits(Integer.parseInt(properties.getProperty("world.maxVoxelOverrides",Integer.toString(DEFAULT.voxels)).strip()));}
        catch(NumberFormatException error){throw new IllegalArgumentException("world_voxel_limit_integer",error);}
    }
    /** Every persisted chunk contains at least one voxel,tile or scene override. */
    /** Null clears share the map capacity; actual compounds have an independent100000 limit. */
    public int tileStates(){return voxels+100_000;}
    public int chunks(){return voxels+200_000;}
}
