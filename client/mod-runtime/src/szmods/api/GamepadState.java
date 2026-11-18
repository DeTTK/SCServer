package szmods.api;

import java.util.Map;

public record GamepadState(String name, Map<String, Float> axes, Map<String, Boolean> buttons) {
    public float axis(String id) { return axes.getOrDefault(id, 0f); }
    public boolean buttonDown(String id) { return Boolean.TRUE.equals(buttons.get(id)); }
}
