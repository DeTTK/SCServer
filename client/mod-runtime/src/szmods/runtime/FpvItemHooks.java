package szmods.runtime;

/** Narrow adapters for the current native item and placement interfaces. */
public final class FpvItemHooks {
    private FpvItemHooks() {}
    public static boolean usable(Object definition) {
        return Boolean.TRUE.equals(ModLoader.clientHook("fpv.item.usable.v1", definition));
    }
    public static boolean quickUsable(Object definition, boolean original) {
        return definition.getClass().getName().equals("850786143") ? usable(definition) : original;
    }
    public static boolean use(Object stack) {
        return Boolean.TRUE.equals(ModLoader.clientHook("fpv.item.use.v1", stack));
    }
    public static Object profile(Object definition) {
        return ModLoader.clientHook("fpv.item.profile.v1", definition);
    }
    public static Object renderer(Object stack, Object original) {
        Object result=ModLoader.clientHook("fpv.item.renderer.v1", stack);
        return result==null?original:result;
    }
    public static boolean place(Object address,Object stack) {
        return Boolean.TRUE.equals(ModLoader.clientHook("fpv.item.place.v1", address, stack));
    }
    public static void installIcon(Object definition,Object atlas) {
        try {
            Class<?> item=Class.forName("1366800141"),icon=Class.forName("1596173232");
            if(!Integer.valueOf(12447).equals(item.getMethod("static").invoke(definition)))return;
            Object value=atlas.getClass().getMethod("short",String.class).invoke(atlas,"customitems:fpv_drone");
            item.getMethod("import",icon).invoke(definition,value);
            item.getMethod("void",icon).invoke(definition,value);
            item.getMethod("else",icon).invoke(definition,value);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("FPV inventory icon contract changed",e); }
    }
}
