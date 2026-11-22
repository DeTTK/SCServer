package szserver.world;
import java.util.concurrent.CompletableFuture;
/** Detached native scene cells for projectile queries; preparation never runs on the owner. */
public interface ProjectileMaps {
    CompletableFuture<ActorScene> projectileScene(World.Vec center);
    default CompletableFuture<ItemCollisionScene> itemScene(World.Vec center){return projectileScene(center).thenApply(scene->new ItemCollisionScene(scene,null));}
}
