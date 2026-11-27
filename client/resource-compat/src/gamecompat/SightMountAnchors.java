package gamecompat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;

public final class SightMountAnchors {
    private static final String RPL20_RISER =
            "weapons:models/attachments/planks/planka_pov/style_spring24/planka_pov_style.mcsb";
    private static final ClassValue<Field> RESOURCE = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> model) {
            try {
                return model.getField("finally");
            } catch (NoSuchFieldException failure) {
                throw new IllegalStateException("Native model resource field unavailable", failure);
            }
        }
    };
    private static final ClassValue<Object> RPL20_ANCHOR = new ClassValue<>() {
        @Override protected Object computeValue(Class<?> model) {
            try {
                ClassLoader loader = model.getClassLoader();
                var readerType = Class.forName("1182339164", true, loader);
                var inputType = Class.forName("1239401881", true, loader);
                byte[] name = "sight_on_mount".getBytes(StandardCharsets.US_ASCII);
                // A one-joint native skeleton. The locator is centred on the styled
                // rail, at the same depth below the top bevel as the stock locator.
                var bytes = ByteBuffer.allocate(30 + name.length).order(ByteOrder.LITTLE_ENDIAN);
                bytes.put((byte) 1).putShort((short) name.length).put(name).put((byte) 0);
                float railHeight = 0.03925f;
                float railCenter = -0.015625f;
                bytes.putFloat(0).putFloat(railHeight).putFloat(railCenter);
                bytes.putFloat(0).putFloat(railHeight).putFloat(railCenter + 0.03f);
                try (var input = (InputStream) inputType.getConstructor(InputStream.class)
                        .newInstance(new ByteArrayInputStream(bytes.array()))) {
                    return readerType.getMethod("try", inputType).invoke(readerType.getConstructor().newInstance(), input);
                }
            } catch (ReflectiveOperationException | java.io.IOException failure) {
                System.err.println("[Game resource compatibility] Could not load the RPL-20 sight locator: " + failure);
                return null;
            }
        }
    };

    private SightMountAnchors() {}

    public static Object resolve(Object model, Object skeleton) {
        if (skeleton != null || model == null) return skeleton;
        try {
            if (RPL20_RISER.equals(String.valueOf(RESOURCE.get(model.getClass()).get(model)))) {
                return RPL20_ANCHOR.get(model.getClass());
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native model resource field inaccessible", failure);
        }
        return null;
    }
}
