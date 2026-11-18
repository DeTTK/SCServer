package szmods.runtime;
public final class FpvOsdHooks {
    public static void beforePost(){ModLoader.clientHook("video.before-post.v1");}
    public static boolean hide(boolean original,Object option){
        if(original || !Boolean.TRUE.equals(ModLoader.clientHook("hud.hide-native.v1")))return original;
        try{return option==Class.forName("1546247182").getField("k").get(null);}catch(Exception ignored){return original;}
    }
}
