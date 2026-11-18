package szserver.world;
import java.util.concurrent.CompletableFuture;
/** Detached native scene cells for projectile queries; preparation never runs on the owner. */
public interface ProjectileMaps {
    CompletableFuture<ActorScene> projectileScene(World.Vec center);
}
