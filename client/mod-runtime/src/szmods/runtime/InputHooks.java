package szmods.runtime;
public final class InputHooks {
    private InputHooks() {}
    public static boolean key(int key, boolean original) {
        Object result=ModLoader.clientHook("input.key.v1",key,original);
        return result instanceof Boolean value ? value : original;
    }
}
