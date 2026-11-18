package szserver.world;
import java.util.*;
/** Detached original seat unit in world coordinates, prepared from the scene object's native transform. */
public record ActorSeat(UUID object,int index,World.Vec point,float facing,String animation,boolean animated,boolean available){
    public ActorSeat{Objects.requireNonNull(object);Objects.requireNonNull(point);Objects.requireNonNull(animation);if(index<0||index>31||animation.length()>128||!Float.isFinite(facing)||!Double.isFinite(point.x())||!Double.isFinite(point.y())||!Double.isFinite(point.z()))throw new IllegalArgumentException("actor_seat_unit");}
}
