package szmods.runtime;

/** Opt-in mod menus share the native developer UI's input ownership decision. */
public final class ModUiHooks {
    private ModUiHooks() {}
    public static boolean capture(boolean nativeCapture) {
        return nativeCapture || Boolean.TRUE.equals(ModLoader.clientHook("input.ui-capture.v1"));
    }
}
