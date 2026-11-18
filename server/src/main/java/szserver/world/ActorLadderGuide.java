package szserver.world;

/** Read-only native model-face geometry prepared by a scene worker. */
public interface ActorLadderGuide {
    record Contact(World.Vec anchor,float yaw) {
        public World.Vec anchor(double y){return new World.Vec(anchor.x(),y,anchor.z());}
    }
    SceneCollision.Box bounds();
    /** Original plane projection/inside tests at the actor's eye, without changing controller state. */
    default Contact contact(World.Vec feet){return approach(feet,.46);}
    Contact approach(World.Vec feet,double reach);
}
