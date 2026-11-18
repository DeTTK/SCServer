package szserver.world;

import java.util.concurrent.CompletableFuture;

/** Async geometry/navigation provider; only NPC/BOT simulation uses these results. */
public interface ActorMaps {
    default CompletableFuture<Void> prepareNavigation(boolean nativeController){return CompletableFuture.completedFuture(null);}
    CompletableFuture<ActorScene> actorScene(World.Vec center);
    CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to);
    default CompletableFuture<ActorNavigation.Path> actorFollowPath(World.Vec from,World.Vec to){return actorPath(from,to);}
    default CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to,ActorPose pose){return actorPath(from,to);}
    default CompletableFuture<ActorNavigation.Path> actorFollowPath(World.Vec from,World.Vec to,ActorPose pose){return actorFollowPath(from,to);}
    default CompletableFuture<ActorNavigation.Path> actorNativePath(World.Vec from,World.Vec to,ActorPose pose,boolean partial,ActorControllerState state){return CompletableFuture.failedFuture(new IllegalStateException("native_route_validator_unavailable"));}
}
