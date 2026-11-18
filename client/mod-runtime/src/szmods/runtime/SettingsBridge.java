package szmods.runtime;

import szmods.api.SettingsRegistry;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Version-scoped adapter from the public mod settings API to the native ESC settings screen.
 * Native class names stay here, so external mods never link against an obfuscated client. */
public final class SettingsBridge {
    private static final String SCREEN = "1893925944", CATEGORY = "880619588", GROUP = "181514757";
    private static final Map<String,List<Object>> tabSettings=new LinkedHashMap<>();
    private static final Map<String,List<Object>> tabRows=new LinkedHashMap<>();
    private static java.lang.ref.WeakReference<Map<Object,List<Object>>> lastEntries=new java.lang.ref.WeakReference<>(null);
    private static boolean reportedFailure;
    private static final Map<Object,String> literalTitles=new IdentityHashMap<>();
    public static synchronized String literalTitle(Object setting){return literalTitles.get(setting);}
    private static final Map<String, Object> categories = new LinkedHashMap<>();
    private static final Map<String, Object> groups = new HashMap<>();
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private SettingsBridge() {}

    /** Called from the real settings screen constructor and tick method. Never lets a UI error break the game. */
    public static synchronized void install(Object screen) {
        if (screen == null || !screen.getClass().getName().equals(SCREEN)) return;
        try {
            Map<String, ModRegistries.Settings> mods = ModLoader.settings();
            if (mods.isEmpty()) return;
            @SuppressWarnings("unchecked") Map<Object, List<Object>> nativeEntries = (Map<Object, List<Object>>) field(screen, "native").get(screen);
            if (nativeEntries == null) return;
            @SuppressWarnings("unchecked") Map<Object, List<Object>> pendingEntries = (Map<Object, List<Object>>) field(screen, "goto").get(screen);
            installEntries(nativeEntries,pendingEntries,mods);

        } catch (Exception failure) {
            if(!reportedFailure) {reportedFailure=true; System.err.println("[ExternalMods] Settings bridge failed: "+failure);}
        }
    }

    static void installEntries(Map<Object,List<Object>> nativeEntries, Map<Object,List<Object>> pendingEntries,
                               Map<String,ModRegistries.Settings> mods) throws Exception {
            for (Map.Entry<String, ModRegistries.Settings> mod : mods.entrySet()) {
                for (ModRegistries.Settings.Tab tab : mod.getValue().tabs().values()) {
                    String key = mod.getKey() + "." + tab.id;
                    Object category = categories.computeIfAbsent(key, ignored -> category(key, tab));
                    List<Object> settings=tabSettings.get(key);
                    if(settings==null) {
                        settings=widgets(tab, key);
                        Object manager=Class.forName("664905591").getField("strictfp").get(null);
                        Method register=manager.getClass().getMethod("abstract",Class.forName("1215468514"));
                        for(Object setting:settings) {
                            Object initial=setting.getClass().getMethod("implements").invoke(setting);
                            register.invoke(manager,setting);
                            // The mod configuration is authoritative on startup.
                            setting.getClass().getMethod("enum",Class.forName("com.google.gson.JsonElement")).invoke(setting,initial);
                        }
                        tabSettings.put(key,settings);
                    }
                    List<Object> rows=tabRows.get(key);
                    if(rows==null) {
                        rows=new ArrayList<>();
                        for(Object setting:settings) rows.add(wrap(setting));
                        tabRows.put(key,rows);
                    }
                    // Reopening the native screen rebuilds every category from its
                    // groups. Mod tabs share a safe stock group, so those rebuilt
                    // lists contain the game settings and every other mod as well.
                    if(nativeEntries.get(category)!=rows) nativeEntries.put(category,rows);
                    if(pendingEntries!=null) pendingEntries.put(category,settings);
                }
            }
            if(lastEntries.get()!=nativeEntries) {
                Set<Object> owned=Collections.newSetFromMap(new IdentityHashMap<>());
                for(List<Object> values:tabSettings.values())owned.addAll(values);
                Class<?> wrapper=Class.forName("1502524412");
                Field setting=wrapper.getField("throw");
                for(var entry:nativeEntries.entrySet()) {
                    if(categories.containsValue(entry.getKey()))continue;
                    List<Object> filtered=new ArrayList<>();
                    for(Object row:entry.getValue())if(!wrapper.isInstance(row)||!owned.contains(setting.get(row)))filtered.add(row);
                    if(filtered.size()!=entry.getValue().size())entry.setValue(filtered);
                }
                if(pendingEntries!=null)for(var entry:pendingEntries.entrySet()) {
                    if(categories.containsValue(entry.getKey()))continue;
                    List<Object> filtered=new ArrayList<>();
                    for(Object value:entry.getValue())if(!owned.contains(value))filtered.add(value);
                    if(filtered.size()!=entry.getValue().size())entry.setValue(filtered);
                }
                lastEntries=new java.lang.ref.WeakReference<>(nativeEntries);
            }
    }

    private static List<Object> widgets(ModRegistries.Settings.Tab tab, String namespace) throws Exception {
        List<Object> result = new ArrayList<>();
        Object group = group("mod." + tab.id + ".general", "Основное");
        for (ModRegistries.Settings.Entry entry : tab.entries) {
            if (entry.type.equals("section")) { group = group("mod." + tab.id + "." + entry.id, entry.title); continue; }
            if (entry.setting instanceof SettingsRegistry.BooleanSetting setting) result.add(booleanWidget(entry, group, setting, namespace));
            else if (entry.setting instanceof SettingsRegistry.NumberSetting setting) result.add(numberWidget(entry, group, setting, namespace));
            else if (entry.setting instanceof SettingsRegistry.ChoiceSetting setting) result.add(choiceWidget(entry, group, setting, namespace));
        }
        return result;
    }
    private static String nativeId(String namespace, String id) {
        return "szmod_" + clean(namespace) + "_" + clean(id);
    }
    private static Object booleanWidget(ModRegistries.Settings.Entry entry, Object group, SettingsRegistry.BooleanSetting setting, String namespace) throws Exception {
        Class<?> type = Class.forName("847298856");
        Object nativeSetting = type.getConstructor(String.class, Class.forName(GROUP), boolean.class).newInstance(nativeId(namespace, entry.id), group, setting.get());
        type.getMethod("goto", Consumer.class).invoke(nativeSetting, (Consumer<Object>) changed -> {
            try { setting.set((Boolean) field(changed, "double").get(changed)); } catch (ReflectiveOperationException ignored) {}
        });
        literalTitles.put(nativeSetting,entry.title);
        return nativeSetting;
    }
    private static Object numberWidget(ModRegistries.Settings.Entry entry, Object group, SettingsRegistry.NumberSetting setting, String namespace) throws Exception {
        Class<?> type = Class.forName("755816512"), format = Class.forName("570773110");
        Object nativeSetting = type.getConstructor(String.class, Class.forName(GROUP), float.class, float.class, float.class, format)
            .newInstance(nativeId(namespace, entry.id), group, setting.get().floatValue(), (float)setting.min(), (float)setting.max(), Enum.valueOf((Class)format, "FLOAT"));
        type.getMethod("void", Consumer.class).invoke(nativeSetting, (Consumer<Object>) changed -> {
            try { setting.set((double)(Float) field(changed, "float").get(changed)); } catch (ReflectiveOperationException ignored) {}
        });
        literalTitles.put(nativeSetting,entry.title);
        return nativeSetting;
    }
    private static Object choiceWidget(ModRegistries.Settings.Entry entry, Object group, SettingsRegistry.ChoiceSetting setting, String namespace) throws Exception {
        Class<?> rich = Class.forName("595713561"), type = Class.forName("812797379");
        Object labels = Array.newInstance(rich, setting.values().size());
        Method literal = Class.forName("1074092844").getMethod("public", String.class);
        for (int i=0;i<setting.values().size();i++) Array.set(labels, i, literal.invoke(null, setting.values().get(i)));
        Object nativeSetting = type.getConstructor(String.class, Class.forName(GROUP), int.class, labels.getClass())
            .newInstance(nativeId(namespace, entry.id), group, setting.values().indexOf(setting.get()), labels);
        type.getMethod("instanceof", Consumer.class).invoke(nativeSetting, (Consumer<Object>) changed -> {
            try { setting.set(setting.values().get((Integer) field(changed, "default").get(changed))); } catch (ReflectiveOperationException | IndexOutOfBoundsException ignored) {}
        });
        literalTitles.put(nativeSetting,entry.title);
        return nativeSetting;
    }
    private static Object wrap(Object nativeSetting) throws Exception {
        return Class.forName("1502524412").getConstructor(Class.forName("1215468514")).newInstance(nativeSetting);
    }
    private static Object category(String key, ModRegistries.Settings.Tab tab) {
        try {
            Object item = enumInstance(Class.forName(CATEGORY), "MOD_" + clean(key), Array.getLength(staticGet(Class.forName(CATEGORY).getDeclaredField("$VALUES"))));
            Method literal = Class.forName("1074092844").getMethod("public", String.class);
            put(item, "int", literal.invoke(null, tab.title));
            LinkedHashSet<Object> categoryGroups = new LinkedHashSet<>();
            categoryGroups.add(group("mod." + tab.id + ".general", "Основное"));
            for (ModRegistries.Settings.Entry entry : tab.entries) if (entry.type.equals("section")) categoryGroups.add(group("mod." + tab.id + "." + entry.id, entry.title));
            put(item, "synchronized", categoryGroups);
            appendEnum(Class.forName(CATEGORY), item);
            return item;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Object group(String key, String title) {
        Object known = groups.get(key); if (known != null) return known;
        try {
            // Group values are consumed by native enum switches in the stock
            // settings screen.  Synthetic group enum instances work for the
            // first render but can crash when the screen is reopened or when
            // a long mod tab is scrolled.  Keep the mod category synthetic,
            // but attach every section to the stock GAME_GENERIC group; the
            // section title remains in the setting rows themselves.
            Object item = Enum.valueOf((Class)Class.forName(GROUP), "GAME_GENERIC");
            groups.put(key, item); return item;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Object enumInstance(Class<?> type, String name, int ordinal) throws ReflectiveOperationException {
        Object value = UNSAFE.allocateInstance(type);
        Field enumName = Enum.class.getDeclaredField("name"), enumOrdinal = Enum.class.getDeclaredField("ordinal");
        put(value, enumName, name); put(value, enumOrdinal, ordinal); return value;
    }
    private static void appendEnum(Class<?> type, Object item) throws ReflectiveOperationException {
        Field values = type.getDeclaredField("$VALUES");
        Object previous = staticGet(values); Object next = Array.newInstance(type, Array.getLength(previous) + 1);
        System.arraycopy(previous, 0, next, 0, Array.getLength(previous)); Array.set(next, Array.getLength(previous), item); staticPut(values, next);
        clearEnumCache(type);
    }
    private static void clearEnumCache(Class<?> type) {
        try { put(type, Class.class.getDeclaredField("enumConstants"), null); put(type, Class.class.getDeclaredField("enumConstantDirectory"), null); }
        catch (ReflectiveOperationException ignored) {}
    }
    private static String clean(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT); }
    private static Field field(Object target, String name) throws ReflectiveOperationException { Field f=target instanceof Class<?> c?c.getDeclaredField(name):target.getClass().getField(name); f.setAccessible(true); return f; }
    private static void put(Object target,String name,Object value) throws ReflectiveOperationException { put(target, field(target,name), value); }
    private static void put(Object target,Field field,Object value) {
        long offset=UNSAFE.objectFieldOffset(field);
        if(field.getType()==int.class) UNSAFE.putInt(target,offset,(Integer)value);
        else if(field.getType()==boolean.class) UNSAFE.putBoolean(target,offset,(Boolean)value);
        else UNSAFE.putObject(target,offset,value);
    }
    private static Object staticGet(Field field) { return UNSAFE.getObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field)); }
    private static void staticPut(Field field,Object value) { UNSAFE.putObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), value); }
    private static sun.misc.Unsafe unsafe() { try { Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return (sun.misc.Unsafe)f.get(null); } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); } }
}

