package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;

/** A server-accepted action dispatched into the remote actor's original item graph. */
public record WeaponAction(int actor, int item, int kind) {
    private record Pending(Object connection, WeaponAction action, long deadline) {}
    private static final ArrayBlockingQueue<Pending> INCOMING = new ArrayBlockingQueue<>(256);
    private static volatile String lastError = "";

    public static WeaponAction read(DataInput in) throws IOException {
        int actor = in.readInt();
        int item = in.readUnsignedShort();
        int kind = in.readUnsignedByte();
        if (actor <= 0 || item == 0 || kind >= 4) throw new IOException("Invalid remote weapon action");
        return new WeaponAction(actor, item, kind);
    }

    public void play(Object connection) {
        INCOMING.offer(new Pending(connection, this, System.nanoTime() + 500_000_000L));
    }

    static void drain() {
        int count = INCOMING.size();
        for (int i = 0; i < count; i++) {
            Pending pending = INCOMING.poll();
            if (pending == null) break;
            try {
                if (!pending.action.apply(pending.connection) && System.nanoTime() < pending.deadline) {
                    INCOMING.offer(pending);
                }
            } catch (ReflectiveOperationException | LinkageError failure) {
                String error = failure.toString();
                if (!error.equals(lastError)) System.err.println("[WeaponEvents] Remote item action: " + error);
                lastError = error;
            }
        }
    }

    private boolean apply(Object connection) throws ReflectiveOperationException {
        Object world = Native.world.get(connection);
        if (world == null) return true;
        Object target = world.getClass().getMethod("continue", int.class).invoke(world, actor);
        if (!Native.remote.isInstance(target)) return false;
        Object stack = Native.held.invoke(target);
        if (stack == null || (Integer) Native.item.invoke(stack) != item) return false;
        Native.dispatch.invoke(null, target, Native.events[kind].invoke(null));
        return true;
    }

    private static final class Native {
        static final Class<?> remote;
        static final Field world;
        static final Method dispatch, held, item;
        static final Method[] events;

        static {
            try {
                Class<?> actor = Class.forName("200241649");
                Class<?> stack = Class.forName("211097523");
                Class<?> event = Class.forName("1739524891");
                remote = Class.forName("1073028351");
                world = Class.forName("1219970978").getField("const");
                held = FunnelImpact.nativeMethod(actor, "while", stack);
                item = FunnelImpact.nativeMethod(stack, "continue", int.class);
                dispatch = Class.forName("1478690128").getMethod("if", Class.forName("1217489680"), event);
                events = new Method[]{event.getMethod("catch"), event.getMethod("continue"),
                        event.getMethod("instanceof"), event.getMethod("static")};
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }
}
