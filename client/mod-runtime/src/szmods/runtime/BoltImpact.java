package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Invokes the stock collision effect on the actor that owns the mounted modifier. */
public record BoltImpact(int entity, float x, float y, float z, boolean predicted) {
    public static BoltImpact read(DataInput input) throws IOException {
        int entity = input.readInt();
        float x = input.readFloat(), y = input.readFloat(), z = input.readFloat();
        if (!coordinate(x) || !coordinate(y) || !coordinate(z)) throw new IOException("Invalid bolt impact position");
        return new BoltImpact(entity,x,y,z,input.readBoolean());
    }

    private static boolean coordinate(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 30_000_001;
    }

    public void play(Object connection) {
        try {
            Object actor = Native.find.invoke(Native.entities.invoke(connection),entity);
            if (!Native.bolt.isInstance(actor)) return;
            if (!predicted) {
                Native.position.invoke(actor,(double)x,(double)y,(double)z);
                Native.collisionEffect.invoke(actor);
            }
            String effect = (String)Native.deathEffect.invoke(Native.properties.get(actor));
            if (!predicted && Native.additional.getBoolean(actor) && effect.equals(Native.additionalEffect.invoke(actor))) return;
            Object resource = Native.particleResource.invoke(null,effect);
            Object world = Native.world.get(connection);
            if (resource != null && world != null)
                Native.spawn.invoke(Native.particles.invoke(world),resource,0,x,y,z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot play bolt impact", e);
        }
    }

    private static final class Native {
        static final Class<?> bolt;
        static final Method entities, find, position, collisionEffect, deathEffect, additionalEffect;
        static final Method particleResource, particles, spawn;
        static final Field properties, additional, world;

        static {
            try {
                bolt = Class.forName("1734232513");
                Class<?> connection = Class.forName("1219970978");
                Class<?> projectile = Class.forName("267674355");
                Class<?> legacyBolt = Class.forName("939250848");
                Class<?> resource = Class.forName("779455578");
                Class<?> particlesEngine = Class.forName("432112703");
                entities = connection.getMethod("instanceof");
                world = connection.getField("const");
                properties = projectile.getField("static");
                additional = legacyBolt.getField("new");
                deathEffect = properties.getType().getMethod("synchronized");
                additionalEffect = FunnelImpact.nativeMethod(bolt,"interface",String.class);
                particleResource = FunnelImpact.nativeMethod(Class.forName("221705413"),"this",resource,String.class);
                particles = FunnelImpact.nativeMethod(Class.forName("1500514786"),"transient",particlesEngine);
                spawn = particlesEngine.getMethod("break",resource,int.class,float.class,float.class,float.class);
                find = Class.forName("1054393445").getMethod("continue",int.class);
                position = bolt.getMethod("throw",double.class,double.class,double.class);
                collisionEffect = FunnelImpact.nativeMethod(bolt,"goto",void.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
