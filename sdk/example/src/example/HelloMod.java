package example;

import szmods.api.*;

public final class HelloMod implements ClientMod {
    private ModContext context;
    private boolean firstFrame;
    public void onLoad(ModContext context) {
        this.context = context;
        context.log("HelloMod loaded; config=" + context.configDirectory());
    }
    public void onLegacyStage(String stage, Object nativeContext) {
        context.log("Native lifecycle stage: " + stage);
    }
    public void onFrame(ClientFrame frame) {
        if (!firstFrame) {
            firstFrame = true;
            context.log("First game frame: " + frame.game().getClass().getName());
        }
    }
    public void onUnload() { if (context != null) context.log("HelloMod cleanup"); }
}
