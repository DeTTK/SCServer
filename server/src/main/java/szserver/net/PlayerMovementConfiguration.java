package szserver.net;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;

/** Immutable per-session projection into the original 32-byte 632909433 wire layout.
 * The native client remains the movement/energy controller. */
public final class PlayerMovementConfiguration {
    public static final int BYTES=32;
    private PlayerMovementConfiguration(){}
    public static byte[] project(byte[] template,Map<String,Float> stats,float energyCapacity){
        Objects.requireNonNull(template);Objects.requireNonNull(stats);
        if(template.length!=BYTES||!Float.isFinite(energyCapacity)||energyCapacity<.01f||energyCapacity>100)throw new IllegalArgumentException("player_movement_configuration");
        float speed=stats.getOrDefault("speed_modifier",0f),sprint=stats.getOrDefault("sprint_speed_modifier",0f);
        if(!Float.isFinite(speed)||!Float.isFinite(sprint))throw new IllegalArgumentException("player_movement_stats");
        byte[] result=template.clone();ByteBuffer bytes=ByteBuffer.wrap(result);
        bytes.putFloat(1,Math.max(0,1+speed/100));bytes.putFloat(6,energyCapacity);bytes.putFloat(24,sprint/100);
        return result;
    }
    public static byte[] slowed(byte[] configuration,float coefficient){validate(configuration);if(coefficient==1)return configuration;if(!Float.isFinite(coefficient)||coefficient<0||coefficient>1)throw new IllegalArgumentException("movement_slow");var result=configuration.clone();var b=ByteBuffer.wrap(result);b.putFloat(28,b.getFloat(28)*coefficient);return result;}
    static void validate(byte[] value){if(value==null||value.length!=BYTES)throw new IllegalArgumentException("player_movement_configuration");}
    /** Native 1704556748.void multiplies (base speed + sprint bonus) by this final coefficient. */
    public static byte[] withCharacterScale(byte[] configuration,float scale){
        validate(configuration);new playerscale.ScaleValue(scale);if(scale==1)return configuration;
        float base=ByteBuffer.wrap(configuration).getFloat(28),scaled=base*scale;
        if(!Float.isFinite(base)||base<0||!Float.isFinite(scaled))throw new IllegalArgumentException("player_movement_scale");
        byte[] result=configuration.clone();ByteBuffer.wrap(result).putFloat(28,scaled);return result;
    }
}
