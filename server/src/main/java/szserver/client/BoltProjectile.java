package szserver.client;

import szserver.world.World;

import java.util.Objects;

/** Detached launch result from the installed bolt factory. Speeds are blocks/second. */
public record BoltProjectile(int actorType, int lifetime, double speed, double gravity, double drag,
                             double bounce, double friction, boolean breakOnContact,
                             World.Vec velocity, byte[] subtype) {
    public BoltProjectile {
        subtype = Objects.requireNonNull(subtype).clone();
        if (actorType <= 0 || actorType > 255 || lifetime <= 0 || lifetime > 12000
                || subtype.length == 0 || subtype.length > 4096
                || !Double.isFinite(speed) || speed < 0 || speed > 100
                || !Double.isFinite(gravity) || gravity < 0 || gravity > 400
                || !unit(drag) || !unit(bounce) || !unit(friction)
                || !finite(velocity))
            throw new IllegalArgumentException("native_bolt_profile");
    }

    private static boolean unit(double value) {return Double.isFinite(value) && value >= 0 && value <= 1;}
    @Override public byte[] subtype() {return subtype.clone();}

    private static boolean finite(World.Vec v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }
}
