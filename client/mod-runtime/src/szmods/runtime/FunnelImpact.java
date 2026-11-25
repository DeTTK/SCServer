package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

/** Plays the native funnel's point impact on the current world's effects and sound engines. */
public record FunnelImpact(UUID zone, float x, float y, float z) {
    public static FunnelImpact read(DataInput input) throws IOException {
        UUID id = new UUID(input.readLong(), input.readLong());
        float x = input.readFloat(), y = input.readFloat(), z = input.readFloat();
        if (!coordinate(x) || !coordinate(y) || !coordinate(z)) throw new IOException("Invalid funnel impact position");
        return new FunnelImpact(id, x, y, z);
    }

    private static boolean coordinate(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 30_000_000;
    }

    private static Object advertised;
    public static void advertise(Object game) {
        try {
            Object connection = Native.connection.invoke(game);
            if (connection == advertised) return;
            if (connection != null && Native.world.get(connection) == null) return;
            if (connection != null) {
                Object packet = Native.ready.newInstance();
                Native.send.invoke(connection, Native.envelope.newInstance(packet));
                Native.send.invoke(connection, Native.envelope.newInstance(Native.boltReady.newInstance()));
            }
            advertised = connection;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot advertise funnel impacts", e);
        }
    }

    public void play(Object connection) {
        try {
            Object world = Native.world.get(connection);
            if (world == null) return;
            Object object = Native.find.invoke(Native.registry.invoke(null, world), zone);
            if (object == null) return;
            Object funnel = Native.behaviour.invoke(object);
            if (!Native.funnel.isInstance(funnel)) return;
            Native.spawn.invoke(Native.particles.invoke(world), Native.impactEffect.get(funnel), 0, x, y, z);
            Native.sound.invoke(world, (double)x, (double)y, (double)z, Native.impactSound, 1f, 1f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot play funnel impact", e);
        }
    }

    static Method nativeMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == returns
                    && Arrays.equals(method.getParameterTypes(), parameters)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + " -> " + returns.getName());
    }

    private static final class Native {
        static final Method connection, send, registry, find, behaviour, particles, spawn, sound;
        static final java.lang.reflect.Constructor<?> envelope, ready, boltReady;
        static final Class<?> funnel;
        static final Field world, impactEffect;
        static final String impactSound;

        static {
            try {
                Class<?> scene = Class.forName("493563411");
                Class<?> worldBase = Class.forName("1500514786");
                funnel = Class.forName("844335175");
                Class<?> net = Class.forName("1219970978");
                connection = nativeMethod(Class.forName("1402131154"), "transient", net);
                send = net.getMethod("else", Class.forName("1884662238"));
                envelope = Class.forName("385693344").getConstructor(Class.forName("499058674"));
                ready = Class.forName("szmods.bridge.FunnelImpactReady").getConstructor();
                boltReady = Class.forName("szmods.bridge.BoltImpactReady").getConstructor();
                world = net.getField("const");
                registry = scene.getMethod("try", worldBase);
                find = scene.getMethod("throws", UUID.class);
                behaviour = nativeMethod(Class.forName("1886271463"), "private", Class.forName("1079929180"));
                particles = nativeMethod(worldBase, "transient", Class.forName("432112703"));
                spawn = Class.forName("432112703").getMethod("break", Class.forName("779455578"),
                        int.class, float.class, float.class, float.class);
                sound = worldBase.getMethod("instanceof", double.class, double.class, double.class,
                        String.class, float.class, float.class);
                impactEffect = funnel.getField("void");
                // The sound used by the installed native funnel's bolt collision callback.
                impactSound = ((String[])Class.forName("1281655698").getField("if").get(null))[42076];
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
