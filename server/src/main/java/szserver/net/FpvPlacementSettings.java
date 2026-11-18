package szserver.net;

import java.nio.file.Path;
import java.util.*;

public record FpvPlacementSettings(Path boxes,long baseBytes,long editedBytes,int toggleItemId) {
    public FpvPlacementSettings(Path boxes,long baseBytes,long editedBytes){this(boxes,baseBytes,editedBytes,0);}
    public FpvPlacementSettings{Objects.requireNonNull(boxes);if(baseBytes<1024||baseBytes>1024L*1024*1024||editedBytes<1024||editedBytes>1024L*1024*1024)throw new IllegalArgumentException("fpv_collision_memory_limit");if(toggleItemId<0)throw new IllegalArgumentException("fpv_toggle_item_id");}
    /** The old normal toggle requires a configured held item; only administrators bypass it. */
    public void requireToggleItem(boolean administrator,Integer held){if(!administrator&&(toggleItemId==0||held==null||held!=toggleItemId))throw new IllegalArgumentException("fpv_item_not_equipped");}
    // Global collision admission covers packed pages, BVH and shared authored descriptors.
    // Prepared native object graphs have a separate bounded local ActorScene budget.
    public static FpvPlacementSettings defaults(Path itemRegistry){long base=Math.max(1024,Math.min(256L*1024*1024,Runtime.getRuntime().maxMemory()/2));return new FpvPlacementSettings(itemRegistry.toAbsolutePath().getParent().resolve("current-boxes"),base,Math.min(base,32L*1024*1024));}
    public static FpvPlacementSettings configured(Properties p,Path itemRegistry){var defaults=defaults(itemRegistry);return new FpvPlacementSettings(Path.of(p.getProperty("world.collisionBoxesRoot",defaults.boxes().toString())),Long.parseLong(p.getProperty("world.collisionIndexBytes",Long.toString(defaults.baseBytes()))),Long.parseLong(p.getProperty("world.editedCollisionBytes",Long.toString(defaults.editedBytes()))),Integer.parseInt(p.getProperty("game.fpv.toggleItemId","0")));}
}
