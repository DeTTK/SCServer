package szserver.world;

import java.util.List;

/** Resident, owner-thread view used by the game's complete character controller.
 * Unknown chunks must suspend a frame, never masquerade as air. Implementations do no IO. */
public interface ActorWorldAccess extends BlockShapes.Voxels {
    /** Fixed world clock; private controller probes advance from their captured effect clock. */
    default long simulationTick(){return -1;}
    boolean resident(int chunkX,int chunkZ);
    void use();
    /** Prepared scene cells produced by ActorMotion.prepare on catalog workers. */
    default List<Object> scene(SceneCollision.Box area){return List.of();}
    default List<Object> ladders(SceneCollision.Box area){return List.of();}
    /** Native feedback candidates, kept distinct from solid geometry. */
    default List<ActorContacts.Volume> feedback(SceneCollision.Box area){return List.of();}
    default List<ActorBody> bodies(SceneCollision.Box area){return List.of();}
    /** Worker-prepared scene instance, looked up only within the resident publication. */
    default Object object(java.util.UUID id){return null;}
    default List<java.util.UUID> carousels(){return List.of();}
    /** The same world-owner publication that supplies native object geometry owns seat occupancy. */
    default boolean seatAvailable(java.util.UUID object,int index){return true;}
    default String actorId(){return null;}
    /** A real block-entity factory publication. Bare collision fixtures may have no tile. */
    default boolean mineTile(World.Block block){return false;}
    /** Validate all blast queries before committing the shared tile or any actor damage. */
    default ActorMineTrigger prepareMine(ActorMineTrigger trigger,World.Vec contactPosition){return trigger;}
    /** Enable the living phase for a full gameplay step, including navigation's private oracle.
     * Low-level movement-only probes may keep it disabled. */
    default boolean livingTick(){return false;}
}
