package szserver.commands;

import szserver.world.ScenePayload;
import java.util.Map;

/** Called exclusively by the command worker when the completed scene catalog has no asset template. */
@FunctionalInterface public interface SceneSeeds {
    ScenePayload.Entry create(String asset)throws Exception;
    default Map<String,Object> metrics(){return Map.of("enabled",true);}
}
