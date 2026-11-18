package replay;

/** Indexed aim owns both the weapon fraction and the native FP graph parameter. */
final class SceneFpAim {
    static float fov(float base,float magnification,float phase){
        if(!Float.isFinite(base)||base<=0||base>=180||!Float.isFinite(magnification)||magnification<=0||!Float.isFinite(phase)||phase<0||phase>1)throw new IllegalArgumentException("Invalid indexed sight projection");
        // The canonical weapon FOV event interpolates the world multiplier,
        // independently of the item-specific hands/weapon projection.
        return base*(1f+(1f/magnification-1f)*phase);
    }
    static void apply(Object context,Object owner,float phase)throws Exception {
        if(!Float.isFinite(phase)||phase<0||phase>1)throw new IllegalArgumentException("Invalid recorded aim phase");
        for(String field:new String[]{"char","for"})Native.type("1810277368").getDeclaredField(field).setFloat(owner,phase);
        Object parameters=Native.call(Native.field(context,"synchronized"),"abstract","230325512");
        Native.call(parameters,"protected","void",phase);
    }
}
