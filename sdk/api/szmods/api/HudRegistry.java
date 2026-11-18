package szmods.api;

public interface HudRegistry {
    void register(String id, HudOverlay overlay);
    default void element(String id, String figmaNode, Anchor anchor, float x, float y, float width, float height) {
        throw new UnsupportedOperationException("HUD element bridge unavailable");
    }
    default void hide(String channel) { throw new UnsupportedOperationException("HUD visibility bridge unavailable"); }
    default void setVisible(String channel, boolean visible) { if(!visible) hide(channel); }
    enum Anchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }
    interface HudOverlay { void render(Object hud, int width, int height, float partialTick); }
}
