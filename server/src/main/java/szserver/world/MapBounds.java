package szserver.world;

/** Native world horizontal domain; capacities and edit volumes are independent limits. */
public final class MapBounds {
    public static final int HORIZONTAL=30_000_000,CHUNK=HORIZONTAL/16,OFFSET=30_000_000;
    /** Authored scenes have three-dimensional coordinates, independently of voxel height. */
    public static final int SCENE=30_000_000;
    public static boolean scene(float x,float y,float z){return Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)&&Math.abs(x)<=SCENE&&Math.abs(y)<=SCENE&&Math.abs(z)<=SCENE;}
    public static final int REGION_MIN=Math.floorDiv(-CHUNK,32),REGION_MAX=Math.floorDiv(CHUNK,32);
    private MapBounds(){}
}
