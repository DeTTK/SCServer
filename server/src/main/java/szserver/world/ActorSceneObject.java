package szserver.world;
import java.util.*;
/** Immutable authored instance; native loader objects are prepared separately on workers. */
public record ActorSceneObject(SceneRegistry.Definition definition,SceneObject source,SceneCollision.Box bounds) {
    public ActorSceneObject{Objects.requireNonNull(definition);Objects.requireNonNull(source);Objects.requireNonNull(bounds);if(!supported(definition)||source.recordSize()>512*1024||definition.definition().toString().length()>65536)throw new IllegalArgumentException("actor_scene_object_limit");}
    public static boolean supported(SceneRegistry.Definition definition){return definition!=null&&Set.of("zipline","zipline_vertical","seat","carousel").contains(definition.behaviour());}
    public long retainedBytes(){return 32768L+8L*source.recordSize()+16L*definition.definition().toString().length();}
    /** Global index retains this immutable descriptor, not a prepared native object graph. */
    public long descriptorBytes(){return 512L+4L*source.recordSize()+2L*source.asset().length();}
    /** Shared resource definition is charged once per packed partition. */
    public long definitionBytes(){return 128L+16L*definition.definition().toString().length();}
}
