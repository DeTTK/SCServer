package szmods.runtime;

/** No client classes referenced here: disabled mods leave native rendering intact. */
public final class RenderHooks {
    public static Object perspective(Object camera, Object original) {
        Object replacement=ModLoader.clientHook("camera.perspective.v1",camera,original);
        return replacement!=null&&original!=null&&replacement.getClass()==original.getClass()?replacement:original;
    }
    public static boolean fpScreen(Object player) {
        return Boolean.TRUE.equals(ModLoader.clientHook("fp.screen-pass.v1", player));
    }
    public static boolean localAudio(Object owner, boolean original) {
        return original || Boolean.TRUE.equals(ModLoader.clientHook("audio.local-player.v1", owner));
    }
    public static Object audioPerspective(Object camera, Object original) {
        if (!localAudio(camera, false) || original == null) return original;
        try { return original.getClass().getField("FIRST_PERSON").get(null); }
        catch (ReflectiveOperationException ignored) { return original; }
    }
    public static void aim(Object player, Object origin, Object end) {
        Object value = ModLoader.clientHook("weapon.aim.v1", player, origin, end);
        if (!(value instanceof double[] v) || (v.length != 3 && v.length != 6)) return;
        for (double n : v) if (!Double.isFinite(n)) return;
        try {
            // Native vector return types are part of the obfuscated signature.
            for (var method : end.getClass().getMethods()) {
                if (method.getName().equals("enum") && method.getReturnType() == end.getClass()
                        && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{double.class,double.class,double.class})) {
                    if(v.length==6){
                        if(origin==null||origin.getClass()!=end.getClass())return;
                        method.invoke(origin,v[0],v[1],v[2]);
                        method.invoke(end,v[3],v[4],v[5]);
                    }else method.invoke(end,v[0],v[1],v[2]);
                    return;
                }
            }
        } catch (ReflectiveOperationException ignored) { /* preserve native fallback */ }
    }
    public static double[] camera(Object player, Object eye, float yaw, float pitch, Object mode) {
        Object value = ModLoader.clientHook("camera.third-person.v1", player, eye, yaw, pitch, mode);
        if (!(value instanceof double[] v) || v.length != 3) return null;
        for (double n : v) if (!Double.isFinite(n)) return null;
        return v;
    }
    public static float fov(Object camera, float original) {
        Object value = ModLoader.clientHook("camera.fov.v1", camera, original);
        return value instanceof Number n && Float.isFinite(n.floatValue())
            && n.floatValue() >= 20 && n.floatValue() <= 120 ? n.floatValue() : original;
    }
    public static boolean crosshair(Object hud, int width, int height) {
        ModLoader.renderHud(hud,width,height,0f);
        if(ModLoader.hudHidden("crosshair")) return true;
        return Boolean.TRUE.equals(ModLoader.clientHook("hud.crosshair.v1", hud, width, height));
    }
}
