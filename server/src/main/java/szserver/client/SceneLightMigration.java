package szserver.client;

import szserver.world.SceneObject;
import szserver.world.ScenePayload;
import java.nio.ByteBuffer;
import java.util.*;

/** Converts the captured light_source float/boolean body to its native named NBT. */
public final class SceneLightMigration {
    private static final String BEHAVIOUR = "light_source";
    private static final String[] FLOAT_FIELDS = {
        "light_range", "light_linear", "light_quadratic", "light_angle", "light_outer_angle",
        "light_color_r", "light_color_g", "light_color_b",
        "light_ambient_color_r", "light_ambient_color_g", "light_ambient_color_b"
    };
    private static final String[] BOOLEAN_FIELDS = {"light_normals_ambient", "light_cast_shadows"};
    private static final int LEGACY_SIZE = 4 * FLOAT_FIELDS.length + BOOLEAN_FIELDS.length;

    public static byte[] frame(byte[] record, String declaredBehaviour) {
        if (!BEHAVIOUR.equals(declaredBehaviour) &&
            (record.length < 6 || (ByteBuffer.wrap(record).getInt(2) & 0x200) == 0)) return record;
        var prefix = SceneObject.prefix(record);
        if (!BEHAVIOUR.equals(SceneObject.behaviour(prefix, declaredBehaviour))) return record;
        int start = prefix.behaviourAt();
        try {
            if (record[start] == 10) {
                SceneAttributes.layout(record, BEHAVIOUR);
                return record;
            }
            if ((prefix.flags() & 0x400) != 0 || record.length - start < LEGACY_SIZE + 4) {
                throw new IllegalArgumentException("scene_light_legacy_layout");
            }
            ByteBuffer input = ByteBuffer.wrap(record).position(start);
            Map<String, SceneNbt.Tag> fields = new LinkedHashMap<>();
            for (String name : FLOAT_FIELDS) {
                float value = input.getFloat();
                if (!Float.isFinite(value)) throw new IllegalArgumentException("scene_light_nonfinite:" + name);
                fields.put(name, SceneNbt.number(5, value));
            }
            for (String name : BOOLEAN_FIELDS) {
                fields.put(name, SceneNbt.number(1, (byte) (ScenePayload.bool(input) ? 1 : 0)));
            }
            byte[] nbt = SceneNbt.write(new SceneNbt.Root("", fields));
            byte[] converted = new byte[record.length - LEGACY_SIZE + nbt.length];
            System.arraycopy(record, 0, converted, 0, start);
            System.arraycopy(nbt, 0, converted, start, nbt.length);
            System.arraycopy(record, input.position(), converted, start + nbt.length, input.remaining());
            SceneAttributes.layout(converted, BEHAVIOUR);
            return converted;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("scene_light_body:" + prefix.identity() + ":" + invalid.getMessage(), invalid);
        }
    }

    private SceneLightMigration() {}
}
