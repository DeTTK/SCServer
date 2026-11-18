package cinema;
import static cinema.Native.*;
/** Consume camera-owned FP meshes before their matrices and bone masks are restored. */
final class FpMeshDraw {
    static Object visible(CinematicCamera c,Object mesh,Object pose)throws Exception {
        Object decision=BodyVisibility.visible(c,mesh);
        if(Boolean.FALSE.equals(decision)||BodyVisibility.owner!=c||BodyVisibility.group==null)return decision;
        // The native batched route queues pose references for a later scene pass.
        // That pass no longer owns our model-view or temporary visibility mask.
        call(mesh,"throws","void",false,pose,null);
        return Boolean.FALSE;
    }
}
