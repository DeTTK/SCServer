package szmods.api;

/** Client-only extension. All callbacks except shutdown run on the game's calling thread. */
public interface ClientMod {
    default void onLoad(ModContext context) throws Exception {}
    /** Called after all mods are loaded, before the client starts calling frames. */
    default void onClientReady(ClientContext context) throws Exception {}
    /** Fixed-rate simulation callback. The runtime may call this multiple times per frame. */
    default void onTick(ClientTick tick) throws Exception {}
    /** Receives normalized input snapshots on the game thread. */
    default void onInput(InputSnapshot input) throws Exception {}
    /** Registers entries shown in the ESC settings screen. */
    default void registerSettings(SettingsRegistry settings) throws Exception {}
    /** Registers key bindings owned by this mod. */
    default void registerKeybinds(KeybindRegistry keybinds) throws Exception {}
    /** Registers HUD overlays. Rendering is provided by a later runtime bridge. */
    default void registerHud(HudRegistry hud) throws Exception {}
    /** Stock LegacyModLoader phase, with its original context (or null). */
    default void onLegacyStage(String stage, Object context) throws Exception {}
    /** Once per client frame, including menus. Not a camera override or HUD render hook. */
    default void onFrame(ClientFrame frame) throws Exception {}
    /** Version-scoped hook. null preserves stock behavior; first non-null mod wins.
     * Return Boolean.TRUE for input.ui-capture.v1 while an interactive mod menu
     * owns the pointer; return null otherwise. Native Alt/ESC capture is retained.
     * This also suspends native gameplay mouse/Raw Input until the menu closes. */
    default Object onClientHook(String hook, Object... args) throws Exception { return null; }
    default void onUnload() throws Exception {}
}
