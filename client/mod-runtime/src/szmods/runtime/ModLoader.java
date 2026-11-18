package szmods.runtime;

import szmods.api.*;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Small external extension of the current native LegacyModLoader. */
public final class ModLoader {
    private static final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private static Path root;
    private static boolean started, stopped;
    private static long lastFrame, frames, ticks;
    private static double tickDebt;
    private static final double FIXED_STEP_SECONDS = 1.0 / 240.0;
    private static final class Loaded {
        final String id;
        final ClientMod mod;
        final URLClassLoader loader;
        final ModRegistries.Settings settings = new ModRegistries.Settings();
        final ModRegistries.Keybinds keybinds = new ModRegistries.Keybinds();
        final ModRegistries.Hud hud = new ModRegistries.Hud();
        boolean active = true, unloaded;
        Loaded(String id, ClientMod mod, URLClassLoader loader) {
            this.id = id; this.mod = mod; this.loader = loader;
        }
    }
    private record Candidate(Path path, String id, String main, byte[] memory) {}

    private static Path root() {
        if (root == null) root = Path.of(System.getProperty("sz.mods.dir", "mods")).toAbsolutePath().normalize();
        return root;
    }
    private static synchronized void log(String id, String text) {
        String line = "[ExternalMods][" + id + "] " + text;
        System.err.println(line);
        try {
            Files.createDirectories(root());
            Files.writeString(root().resolve("loader-" + ProcessHandle.current().pid() + ".log"),
                java.time.Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { System.err.println("[ExternalMods] Cannot write log: " + e); }
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    /** Invoked before native allowed-mods filtering. Return values are native adapters. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void appendLegacy(List nativeMods) {
        if (started || stopped) return;
        started = true;
        try {
            Files.createDirectories(root());
            Properties enabled = new Properties();
            Path config = root().resolve("enabled.properties");
            if (Files.exists(config)) try (Reader r = Files.newBufferedReader(config)) { enabled.load(r); }
            Set<String> nativeIds = new HashSet<>();
            var nativeId = Class.forName("1057736279").getMethod("if");
            for (Object mod : nativeMods) nativeIds.add((String)nativeId.invoke(mod));
            List<Candidate> candidates = new ArrayList<>();
            Map<String, Integer> counts = new HashMap<>();
            try (var paths = Files.list(root())) {
                for (Path path : paths.filter(p -> !Boolean.getBoolean("sz.launcher.memory")).filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        var entry = jar.getJarEntry("stalcraft-mod.properties");
                        if (entry == null) throw new IllegalArgumentException("Missing stalcraft-mod.properties");
                        Properties p = new Properties();
                        try (Reader r = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) { p.load(r); }
                        String id = required(p, "id");
                        if (!id.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid mod id");
                        if (!required(p, "api").equals("1")) throw new IllegalArgumentException("Unsupported API version");
                        required(p, "version");
                        String main = required(p, "main");
                        candidates.add(new Candidate(path, id, main, null));
                        counts.merge(id, 1, Integer::sum);
                    } catch (Exception e) { log(path.getFileName().toString(), "SKIPPED: " + e); }
                }
            }
            // Standalone launcher supplies remote JARs in memory, never in the mod folder.
            if (Boolean.getBoolean("sz.launcher.memory")) {
                ClassLoader system = ClassLoader.getSystemClassLoader();
                Map<String, byte[]> remote = (Map<String, byte[]>)system.getClass().getMethod("getRemoteMods").invoke(system);
                for (var entry : remote.entrySet()) {
                    try (MemoryModLoader probe = new MemoryModLoader(entry.getValue(), ModLoader.class.getClassLoader())) {
                        Properties p = new Properties();
                        var url = probe.findResource("stalcraft-mod.properties");
                        if (url == null) throw new IllegalArgumentException("Missing mod descriptor");
                        try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) { p.load(reader); }
                        String id = required(p, "id");
                        if (!id.matches("[a-z][a-z0-9_-]{0,63}") || !required(p, "api").equals("1"))
                            throw new IllegalArgumentException("Invalid mod descriptor");
                        required(p, "version");
                        candidates.add(new Candidate(Path.of(entry.getKey()), id, required(p, "main"), entry.getValue()));
                        counts.merge(id, 1, Integer::sum);
                    } catch (Exception e) { log(entry.getKey(), "SKIPPED: " + e); }
                }
            }
            // Respect the native allowlist before initializing external code as well.
            String allowedFile = System.getProperty("allowed-mods");
            Set<String> allowed = allowedFile == null ? null : new HashSet<>(
                Arrays.asList(Files.readString(Path.of(allowedFile)).trim().split(",")));
            for (Candidate c : candidates) {
                if (counts.get(c.id) != 1 || nativeIds.contains(c.id)) {
                    log(c.id, "SKIPPED: duplicate/native mod id"); continue;
                }
                String setting = enabled.getProperty(c.id, "true").trim();
                if (!setting.equalsIgnoreCase("true") || (allowed != null && !allowed.contains(c.id))) {
                    log(c.id, "DISABLED"); continue;
                }
                URLClassLoader loader = null;
                Loaded value = null;
                try {
                    loader = c.memory == null ? new URLClassLoader(new java.net.URL[]{c.path.toUri().toURL()}, ModLoader.class.getClassLoader())
                        : new MemoryModLoader(c.memory, ModLoader.class.getClassLoader());
                    Class<?> cls = Class.forName(c.main, false, loader);
                    if (cls.getClassLoader() != loader || !ClientMod.class.isAssignableFrom(cls))
                        throw new IllegalArgumentException("main must implement szmods.api.ClientMod in this JAR");
                    ClientMod mod = (ClientMod)cls.getConstructor().newInstance();
                    value = new Loaded(c.id, mod, loader);
                    Path dir = root().resolve("config").resolve(c.id);
                    Files.createDirectories(dir);
                    value.settings.bind(dir);
                    mod.onLoad(new ModContext(c.id, dir, text -> log(c.id, text)));
                    ClientContext clientContext = new ClientContext(c.id, dir, value.settings, value.keybinds, value.hud);
                    mod.registerSettings(value.settings);
                    value.settings.load();
                    mod.registerKeybinds(value.keybinds);
                    mod.registerHud(value.hud);
                    mod.onClientReady(clientContext);
                    Object adapter = Class.forName("szmods.bridge.LegacyAdapter").getConstructor(String.class).newInstance(c.id);
                    loaded.put(c.id, value);
                    nativeMods.add(adapter);
                    log(c.id, "LOADED " + c.path.getFileName());
                } catch (Throwable e) {
                    fatal(e);
                    log(c.id, "FAILED to load: " + e);
                    if (value != null) unload(value);
                    else if (loader != null) try { loader.close(); } catch (IOException ignored) {}
                }
            }
            Runtime.getRuntime().addShutdownHook(new Thread(ModLoader::shutdown, "ExternalMods-shutdown"));
            log("loader", "Ready: " + loaded.size() + " external mod(s)");
        } catch (Exception e) { log("loader", "Discovery failed: " + e); }
    }
    private static void fatal(Throwable e) {
        if (e instanceof VirtualMachineError v) throw v;
        if (e instanceof ThreadDeath t) throw t;
    }
    private static void failed(Loaded value, String stage, Throwable e) {
        fatal(e);
        value.active = false;
        log(value.id, "DISABLED after " + stage + ": " + e);
        unload(value);
    }
    public static synchronized void legacyStage(String id, String stage, Object context) {
        Loaded value = loaded.get(id);
        if (value == null || !value.active || stopped) return;
        try { value.mod.onLegacyStage(stage, context); }
        catch (Throwable e) { failed(value, stage, e); }
    }
    public static synchronized void frame(Object game) {
        if (!started || stopped || loaded.isEmpty()) return;
        long now = System.nanoTime();
        double delta = lastFrame == 0 ? 0 : Math.min(.25, (now - lastFrame) / 1e9);
        lastFrame = now;
        ClientFrame frame = new ClientFrame(game, delta, ++frames);
        AnimationHooks.frame(game);
        FpvPackets.drain();
        for (Loaded value : loaded.values()) if (value.active) {
            try { value.mod.onFrame(frame); }
            catch (Throwable e) { failed(value, "onFrame", e); }
        }
        // FPV and other physical mods need a stable integrator independent of render FPS.
        tickDebt = Math.min(.25, tickDebt + delta);
        int steps = 0;
        while (tickDebt >= FIXED_STEP_SECONDS && steps++ < 60) {
            tick(game, FIXED_STEP_SECONDS, ++ticks);
            tickDebt -= FIXED_STEP_SECONDS;
        }
    }
    public static synchronized void tick(Object game, double deltaSeconds, long number) {
        if (!started || stopped) return;
        ClientTick tick = new ClientTick(game, Math.max(0, Math.min(.25, deltaSeconds)), number);
        for (Loaded value : loaded.values()) if (value.active) try { value.mod.onTick(tick); }
            catch (Throwable e) { failed(value, "onTick", e); }
    }
    public static synchronized void input(InputSnapshot input) {
        if (!started || stopped || loaded.isEmpty()) return;
        for (Loaded value : loaded.values()) if (value.active) try { value.mod.onInput(input); }
            catch (Throwable e) { failed(value, "onInput", e); }
    }
    public static synchronized Map<String, ModRegistries.Settings> settings() {
        Map<String, ModRegistries.Settings> result = new LinkedHashMap<>();
        for (Loaded value : loaded.values()) if (value.active) result.put(value.id, value.settings);
        return Collections.unmodifiableMap(result);
    }
    public static synchronized Object clientHook(String hook, Object... args) {
        if (!started || stopped) return null;
        // Flight view is an exclusive full-camera mode. Give it first chance over
        // shoulder-camera mods, while preserving ordinary deterministic JAR order elsewhere.
        Object[] ordered = HookSnapshots.ordered(loaded,
            hook.equals("camera.third-person.v1") || hook.equals("camera.fov.v1") ? loaded.get("fpv-drones") : null);
        for (Object entry : ordered) {
            Loaded value = (Loaded)entry;
            if (!value.active) continue;
            try {
                Object result = value.mod.onClientHook(hook, args);
                if (result != null) return result;
            } catch (Throwable e) { failed(value, hook, e); }
        }
        return null;
    }
    public static synchronized void renderHud(Object hud, int width, int height, float partialTick) {
        if (!started || stopped) return;
        for (Loaded value : loaded.values()) if (value.active) for (var overlay : value.hud.overlays.values()) {
            try { overlay.render(hud,width,height,partialTick); } catch(Throwable e) { failed(value,"hud",e); }
        }
    }
    public static synchronized boolean hudHidden(String channel) {
        for (Loaded value : loaded.values()) if (value.active && value.hud.hidden.contains(channel)) return true;
        return false;
    }
    private static void unload(Loaded value) {
        if (value.unloaded) return;
        value.unloaded = true;
        value.active = false;
        try { value.mod.onUnload(); }
        catch (Throwable e) { fatal(e); log(value.id, "Cleanup failed: " + e); }
        try { value.loader.close(); } catch (IOException e) { log(value.id, "Close failed: " + e); }
    }
    public static synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        List<Loaded> reverse = new ArrayList<>(loaded.values());
        Collections.reverse(reverse);
        for (Loaded value : reverse) unload(value);
    }
}
