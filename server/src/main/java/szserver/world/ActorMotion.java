package szserver.world;
import java.util.*;
/** Pure actor displacement against already published local geometry; no player/FPV simulation. */
public interface ActorMotion {
    record Cell(SceneCollision.Box local,double[] matrix,World.Vec translation){public Cell{matrix=matrix.clone();}@Override public double[] matrix(){return matrix.clone();}}
    record Result(double x,double y,double z,boolean horizontalCollision,boolean verticalCollision,boolean grounded){}
    interface Voxels {
        List<SceneCollision.Box> boxes(SceneCollision.Box area);
        default List<ActorBody> bodies(SceneCollision.Box area){return List.of();}
        default String actorId(){return null;}
    }
    Object prepare(Cell cell);
    default ActorZipline ziplineGuide(Object prepared){throw new IllegalStateException("native_zipline_guide_unavailable");}
    default List<ActorSeat> seatGuides(Object prepared){return List.of();}
    default boolean mapObjectsEnabled(){return false;}
    default ActorSceneObject describeMapObject(SceneRegistry.Definition definition,SceneObject object){throw new IllegalStateException("native_actor_map_object_unavailable");}
    default Object prepareMapObject(SceneRegistry.Definition definition,SceneObject object){throw new IllegalStateException("native_actor_map_object_unavailable");}
    /** Worker-only original FULL_SAVE reader for health-dependent scene contacts. */
    default ActorContacts.Obstacle livingObstacle(SceneRegistry.Definition definition,SceneObject object){throw new IllegalStateException("native_living_obstacle_unavailable");}
    default Object prepareLadder(Cell cell){throw new IllegalStateException("native_scene_ladder_unavailable");}
    default ActorLadderGuide ladderGuide(Object prepared){throw new IllegalStateException("native_scene_ladder_unavailable");}
    /** Construct only on the navigation worker; never share the live controller's mutable loader. */
    default ActorPathValidator pathValidator(){throw new IllegalStateException("native_route_validator_unavailable");}
    /** Worker-only scene feedback transformation; unsupported adapters must not guess geometry. */
    default List<SceneCollision.Box> feedbackBounds(float[] affine,List<SceneCollision.Box> boxes){throw new IllegalStateException("native_feedback_geometry_unavailable");}
    Result move(World.Vec position,World.Vec delta,boolean grounded,List<Object> scene,Voxels voxels);
    default Result move(World.Vec position,World.Vec delta,boolean grounded,List<Object> scene,Voxels voxels,ActorPose pose){return move(position,delta,grounded,scene,voxels);}
    /** Support query for the controller's short ground latch. Uses resident geometry only. */
    default boolean support(World.Vec position,List<Object> scene,Voxels voxels){return false;}
}
