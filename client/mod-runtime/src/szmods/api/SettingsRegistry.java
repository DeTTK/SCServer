package szmods.api;

import java.util.List;
import java.util.function.Consumer;

/** Declarative settings model consumed by the ESC-menu bridge. */
public interface SettingsRegistry {
    SettingsTab registerTab(String id, String title);
    interface SettingsTab {
        void section(String id, String title);
        /** Legacy declaration form. Prefer the returned handles below for live settings. */
        void booleanValue(String id, String title, boolean defaultValue);
        void number(String id, String title, double defaultValue, double min, double max);
        void choice(String id, String title, String defaultValue, List<String> values);
        BooleanSetting booleanSetting(String id, String title, boolean defaultValue);
        NumberSetting numberSetting(String id, String title, double defaultValue, double min, double max);
        ChoiceSetting choiceSetting(String id, String title, String defaultValue, List<String> values);
        void reset();
    }
    interface Setting<T> {
        String id();
        String title();
        T get();
        void set(T value);
        void reset();
        void onChange(Consumer<T> listener);
    }
    interface BooleanSetting extends Setting<Boolean> {}
    interface NumberSetting extends Setting<Double> {
        double min();
        double max();
    }
    interface ChoiceSetting extends Setting<String> {
        List<String> values();
    }
}
