package szmods.runtime;
public final class VideoHooks {
    public static boolean blend(Object pipeline,Object profile,int width,int height){
        return Boolean.TRUE.equals(ModLoader.clientHook("video.photo-blend.v1",pipeline,profile,width,height));
    }
    public static Object filter(Object original) {
        Object value=ModLoader.clientHook("video.photo-filter.v1");
        return value==null?original:value;
    }
    public static boolean enabled(boolean original) {
        Object value=ModLoader.clientHook("video.photo-enabled.v1");
        return value instanceof Boolean b?b:original;
    }
    public static boolean night(Object effect,Object parameters) {
        return Boolean.TRUE.equals(ModLoader.clientHook("video.night-vision.v1",effect,parameters));
    }
}
