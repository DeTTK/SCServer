package szserver.client;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import szserver.world.ActorPose;

/** Pin native posture dimensions and the ordinary controller's step before any actor starts. */
final class ActorPostureRelease {
    private static final Map<String,String> HASHES=Map.of(
        "35395125","bb6e9723605ef865c89823665350e40ff412f49d7c11d0f1ab076d6e4ce1fa27",
        "430277821","f10d63fc7159e9a2dd82d363ee14c546ca8a244828c74ccdaac58102b2ebe6ff",
        "573844998","38acbc94612e7079d3fb33f1dbb44b9ae3488d000cf9330338ccf89bd2758e2a");
    static void verify(Path runtime)throws Exception{
        for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_posture_release_mismatch:"+e.getKey());
    }
    static void verifyValues(ClassLoader loader)throws Exception{
        var state=Class.forName("1943200621",true,loader);var poses=Class.forName("430277821",true,loader);
        for(var pose:ActorPose.values()){
            Object value=poses.getField(pose.name()).get(null);
            if(((Enum<?>)value).ordinal()!=pose.nativeId()||((Float)state.getMethod("const",poses).invoke(null,value)).doubleValue()!=pose.height())throw new IllegalStateException("actor_posture_value_mismatch:"+pose);
        }
        if(state.getField("at").getFloat(state.getConstructor().newInstance())!=ActorPose.STEP)throw new IllegalStateException("actor_step_value_mismatch");
    }
}
