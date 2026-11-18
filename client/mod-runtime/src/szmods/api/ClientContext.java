package szmods.api;

import java.nio.file.Path;

/** Stable client-facing services. Native game objects remain build-specific. */
public record ClientContext(String modId, Path configDirectory, SettingsRegistry settings,
                            KeybindRegistry keybinds, HudRegistry hud) {}
