package szserver.world;

import java.util.*;

/** Captured dynamic entity data. This is separate from render/picking and ordinary actor occupancy. */
public record ActorBody(String id,Kind kind,SceneCollision.Box bounds,boolean collidable,boolean inactive,boolean suppressed) {
    public enum Kind {HUMANOID,CREATURE}
    public ActorBody{if(id==null||id.isBlank()||id.length()>64)throw new IllegalArgumentException("actor_body_identity");Objects.requireNonNull(kind);Objects.requireNonNull(bounds);}
    public static final class Frame {
        public static final Frame EMPTY=new Frame(List.of());
        private final List<ActorBody> bodies;
        public Frame(List<ActorBody> values){if(values.size()>512)throw new IllegalArgumentException("actor_body_capacity");Set<String> ids=new HashSet<>();for(var value:values)if(!ids.add(value.id()))throw new IllegalArgumentException("actor_body_duplicate");bodies=List.copyOf(values);}
        public List<ActorBody> select(String self,SceneCollision.Box area,ActorScene.Budget budget){
            List<ActorBody> found=new ArrayList<>();for(var body:bodies){budget.use();if(!body.id().equals(self)&&ActorScene.intersects(body.bounds(),area))found.add(body);}return List.copyOf(found);
        }
        public int size(){return bodies.size();}
    }
}
