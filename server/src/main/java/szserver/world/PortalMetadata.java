package szserver.world;

import java.util.UUID;

/** Affine Store metadata, compiled on the command worker; no native body is fabricated. */
public record PortalMetadata(UUID id,String asset,World.Vec position,double scale,boolean removed) {
    public PortalMetadata {
        if(id==null||asset==null||asset.length()>1024||!Double.isFinite(scale)||scale<0)throw new IllegalArgumentException("scene_portal_metadata");
        if(position!=null&&(!Double.isFinite(position.x())||!Double.isFinite(position.y())||!Double.isFinite(position.z())))throw new IllegalArgumentException("scene_portal_metadata_position");
    }
    /** Original exponent overflow differs from overflowing sums of finite squares. */
    public static double matrixScale(double[] matrix){if(matrix==null)return 0;if(matrix.length!=16)throw new IllegalArgumentException("scene_portal_metadata_matrix");double scale=0;for(int axis=0;axis<3;axis++){double square=0;for(int n=0;n<3;n++){double v=matrix[axis*4+n],term=v*v;if(!Double.isFinite(v))throw new IllegalArgumentException("scene_portal_metadata_matrix");if(!Double.isFinite(term))return 0;square+=term;}double value=Math.sqrt(square);if(Double.isFinite(value)&&value>0)scale=Math.max(scale,value);}return scale;}
}
