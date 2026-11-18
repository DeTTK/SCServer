package szmods.runtime;

/** Game-thread callbacks at the native intent and view boundaries. */
public final class NativeFlightHooks {
    private NativeFlightHooks() {}
    public static Object intent(Object original) {
        Object replacement = ModLoader.clientHook("player.movement-intent.v1", original);
        return replacement != null && replacement.getClass() == original.getClass() ? replacement : original;
    }
    public static boolean camera(Object camera, Object matrix) {
        return Boolean.TRUE.equals(ModLoader.clientHook("camera.native-view.v1", camera, matrix));
    }
    public static boolean actionBlocked() {
        return Boolean.TRUE.equals(ModLoader.clientHook("input.action-block.v1"));
    }
}
