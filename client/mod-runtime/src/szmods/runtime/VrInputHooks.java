package szmods.runtime;

/** Called exclusively by the separate VR synthetic-action dispatcher. */
public final class VrInputHooks {
    private VrInputHooks(){}
    public static boolean focus(boolean nativeFocus){
        return nativeFocus||Boolean.TRUE.equals(ModLoader.clientHook("vr.action-focus.v1"));
    }
    public static boolean blockAutoReload(){return Boolean.getBoolean("sz.vr.active");}
}
