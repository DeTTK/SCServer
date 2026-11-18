package szmods.runtime;
public final class DroneWorldHooks {
    public static boolean mouse(Object game) {
        return Boolean.TRUE.equals(ModLoader.clientHook("view.block-body-look.v1",game));
    }
    public static void render(Object camera,float delta) {
        ModLoader.clientHook("scene.drones.v1",camera,delta);
    }
    public static Object subject(Object original) {
        Object value=ModLoader.clientHook("view.subject.v1",original);
        return value!=null && original!=null && original.getClass().isInstance(value)?value:original;
    }
}
