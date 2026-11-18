package szmods.api;

import java.util.Map;

/** Immutable normalized input state. Axis values are in [-1, 1], triggers in [0, 1]. */
public record InputSnapshot(Map<Integer, Boolean> keys, Map<Integer, Boolean> mouseButtons,
                            Map<String, Float> axes, Map<String, Boolean> buttons,
                            Map<Integer, GamepadState> gamepads) {
    public boolean keyDown(int key) { return Boolean.TRUE.equals(keys.get(key)); }
    public boolean buttonDown(String id) { return Boolean.TRUE.equals(buttons.get(id)); }
    public float axis(String id) { return axes.getOrDefault(id, 0f); }
}
