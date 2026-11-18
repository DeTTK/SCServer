package szmods.runtime;

/** OpenXR's xrWaitFrame owns pacing while native world VR is active. */
public final class VrFrameHooks {
    private VrFrameHooks(){}
    public static double interval(double nativeInterval){
        return Boolean.getBoolean("sz.vr.active")?0.0:nativeInterval;
    }
}
