package szmods.runtime;

import szmods.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.*;

/** In-memory registries. UI/input bridges consume these without exposing native classes. */
public final class ModRegistries {
    private ModRegistries() {}
    public static final class Settings implements SettingsRegistry {
        private final Map<String, Tab> tabs = new LinkedHashMap<>();
        private Path file;
        private boolean loading;
        void bind(Path configDirectory) { file = configDirectory.resolve("settings.properties"); }
        void load() {
            if (file == null || !Files.isRegularFile(file)) return;
            Properties saved = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { saved.load(reader); }
            catch (IOException ignored) { return; }
            loading = true;
            try {
                for (Tab tab : tabs.values()) for (Entry entry : tab.entries) {
                    String value = saved.getProperty(tab.id + "." + entry.id);
                    if (value != null) entry.load(value);
                }
            } finally { loading = false; }
        }
        synchronized void save() {
            if (loading || file == null) return;
            Properties out = new Properties();
            for (Tab tab : tabs.values()) for (Entry entry : tab.entries) if (entry.setting != null)
                out.setProperty(tab.id + "." + entry.id, String.valueOf(entry.setting.get()));
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) { out.store(writer, "BSCPCRAFT external mod settings"); }
                try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException ignored) {}
        }
        public SettingsTab registerTab(String id, String title) {
            if (!id.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid tab id");
            if (tabs.containsKey(id)) throw new IllegalArgumentException("Duplicate tab id: " + id);
            if (title == null || title.isBlank() || title.length() > 80) throw new IllegalArgumentException("Invalid tab title");
            Tab tab = new Tab(this, id, title); tabs.put(id, tab); return tab;
        }
        public Map<String, Tab> tabs() { return Collections.unmodifiableMap(tabs); }
            public static final class Tab implements SettingsRegistry.SettingsTab {
            public final String id, title; public final List<Entry> entries = new ArrayList<>(); private final Settings owner;
            Tab(Settings owner, String id, String title) { this.owner=owner; this.id=id; this.title=Objects.requireNonNull(title); }
            private void verify(String id, String title) {
                if (!id.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid setting id");
                if (title == null || title.isBlank() || title.length() > 120) throw new IllegalArgumentException("Invalid setting title");
                if (entries.stream().anyMatch(e -> e.id.equals(id))) throw new IllegalArgumentException("Duplicate setting id: " + id);
            }
            public void section(String id,String title){ verify(id,title); entries.add(new Entry(id,title,"section",null,null)); }
            public void booleanValue(String id,String title,boolean v){ booleanSetting(id,title,v); }
            public void number(String id,String title,double v,double min,double max){ numberSetting(id,title,v,min,max); }
            public void choice(String id,String title,String v,List<String> values){ choiceSetting(id,title,v,values); }
            public SettingsRegistry.BooleanSetting booleanSetting(String id,String title,boolean value) {
                verify(id,title); Bool setting = new Bool(id,title,value); entries.add(new Entry(id,title,"boolean",setting,setting)); return setting;
            }
            public SettingsRegistry.NumberSetting numberSetting(String id,String title,double value,double min,double max) {
                verify(id,title); if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || min > max || value < min || value > max) throw new IllegalArgumentException("Invalid range");
                Number setting = new Number(id,title,value,min,max); entries.add(new Entry(id,title,"number",setting,setting)); return setting;
            }
            public SettingsRegistry.ChoiceSetting choiceSetting(String id,String title,String value,List<String> values) {
                verify(id,title); List<String> copy = List.copyOf(values); if (copy.isEmpty() || copy.stream().anyMatch(v -> v == null || v.isBlank()) || !copy.contains(value)) throw new IllegalArgumentException("Invalid choice");
                Choice setting = new Choice(id,title,value,copy); entries.add(new Entry(id,title,"choice",setting,setting)); return setting;
            }
            public void reset() { for (Entry entry : entries) if (entry.setting != null) entry.setting.reset(); }
            private abstract class Base<T> implements SettingsRegistry.Setting<T> {
                final String id,title; final T initial; T value; final List<Consumer<T>> listeners = new ArrayList<>();
                Base(String id,String title,T initial) { this.id=id;this.title=title;this.initial=initial;this.value=initial; }
                public String id(){return id;} public String title(){return title;} public synchronized T get(){return value;}
                public synchronized void set(T next) { validate(next); if (Objects.equals(value,next)) return; value=next; owner.save(); for (Consumer<T> listener : List.copyOf(listeners)) listener.accept(next); }
                public void reset(){ set(initial); } public synchronized void onChange(Consumer<T> listener){ listeners.add(Objects.requireNonNull(listener)); }
                abstract void validate(T next);
            }
            private final class Bool extends Base<Boolean> implements SettingsRegistry.BooleanSetting { Bool(String id,String title,boolean value){super(id,title,value);} void validate(Boolean value){if(value==null)throw new IllegalArgumentException("Boolean is required");} }
            private final class Number extends Base<Double> implements SettingsRegistry.NumberSetting { final double min,max; Number(String id,String title,double value,double min,double max){super(id,title,value);this.min=min;this.max=max;} void validate(Double value){if(value==null||!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Number outside range");} public double min(){return min;} public double max(){return max;} }
            private final class Choice extends Base<String> implements SettingsRegistry.ChoiceSetting { final List<String> values; Choice(String id,String title,String value,List<String> values){super(id,title,value);this.values=values;} void validate(String value){if(!values.contains(value))throw new IllegalArgumentException("Unsupported choice: "+value);} public List<String> values(){return values;} }
        }
        public static final class Entry {
            public final String id,title,type; public final Object value; public final SettingsRegistry.Setting<?> setting;
            Entry(String id,String title,String type,Object value,SettingsRegistry.Setting<?> setting){this.id=id;this.title=title;this.type=type;this.value=value;this.setting=setting;}
            void load(String raw) { try { if (setting instanceof SettingsRegistry.BooleanSetting b) b.set(Boolean.parseBoolean(raw)); else if (setting instanceof SettingsRegistry.NumberSetting n) n.set(Double.parseDouble(raw)); else if (setting instanceof SettingsRegistry.ChoiceSetting c) c.set(raw); } catch (RuntimeException ignored) {} }
        }
    }
    public static final class Keybinds implements KeybindRegistry {
        public final Map<String, Integer> values = new LinkedHashMap<>();
        public void register(String id,String title,int defaultKey){if(values.putIfAbsent(id,defaultKey)!=null)throw new IllegalArgumentException("Duplicate keybind: "+id);}
    }
    public static final class Hud implements HudRegistry {
        public final Map<String,HudOverlay> overlays=new LinkedHashMap<>();
        public final Map<String,Element> elements=new LinkedHashMap<>();
        public final Set<String> hidden=new LinkedHashSet<>();
        public void register(String id,HudOverlay overlay){if(overlays.putIfAbsent(id,overlay)!=null)throw new IllegalArgumentException("Duplicate HUD id: "+id);}
        public void element(String id,String figmaNode,Anchor anchor,float x,float y,float width,float height){elements.put(id,new Element(figmaNode,anchor,x,y,width,height));}
        public void hide(String channel){hidden.add(channel);}
        public void setVisible(String channel,boolean visible){if(visible)hidden.remove(channel);else hidden.add(channel);}
        public record Element(String figmaNode,Anchor anchor,float x,float y,float width,float height){}
    }
}
