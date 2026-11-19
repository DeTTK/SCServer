/** Stock-JVM entry point that preserves useful diagnostics from game handlers. */
public final class StandardJvmBootstrap {
    private static native void injectRawMouse(
            int dx, int dy, int button, int action,
            double wheelHorizontal, double wheelVertical);
    /**
     * Harmless stand-in for java.lang.reflect.Field.modifiers.  That private
     * implementation field was used by the Java 8-era client to clear ACC_FINAL,
     * but it is no longer reflectively exposed by stock JDKs.  The current
     * client only reaches this path while wiring exception causes, whose field
     * is not final, so retaining a writable integer slot preserves the helper's
     * control flow without depending on JDK internals.
     */
    public static int LEGACY_FIELD_MODIFIERS;
    public static final java.util.Vector<Class<?>> COMPATIBILITY_LOADED_CLASSES =
            new java.util.Vector<>();
    private static final java.util.concurrent.atomic.AtomicLong COMPATIBILITY_NATIVE_HANDLES =
            new java.util.concurrent.atomic.AtomicLong(1L);
    private static final java.util.concurrent.atomic.AtomicBoolean MONITOR_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile org.lwjgl.glfw.GLFWVidMode fallbackGlfwVideoMode;
    private static final java.util.concurrent.atomic.AtomicBoolean FMOD_NOSOUND_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> CONTROL_QUEUE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> CONTROL_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong CONTROL_FRAMES =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile Object controlGame;
    private static volatile long controlWindow;
    private static volatile boolean controlWindowHidden;
    private static volatile String controlState = "{\"ready\":false}";
    private static volatile String controlFpSnapshot = "{\"ready\":false}";
    private static volatile String controlEffectsSnapshot = "{\"ready\":false}";
    private static volatile boolean controlQaLogin;
    private static volatile String controlInputSnapshot="{\"ready\":false}";
    private static volatile String controlLastError = "";
    private static volatile String controlKeyCallbackDescription = "";
    private static volatile String controlMouseCallbackDescription = "";
    private static volatile String controlCursorCallbackDescription = "";
    private static volatile String controlLastBlockDelta = "";
    private static volatile String controlLastEncodedBlockDelta = "";
    private static volatile String controlLastBuilderClick = "";
    private static volatile long visualWorldTime = Long.MIN_VALUE;
    private static volatile String visualWorldClockError = "";
    private static volatile int desiredWeatherCode;
    private static volatile long environmentEpochMillis;
    private static volatile long environmentReceivedNanos;
    private static volatile float environmentClockSpeed;
    private static volatile String visualWeather = "clear";
    private static volatile String visualWeatherError = "";
    private static volatile float builderHitLocalX = 0.5f;
    private static volatile float builderHitLocalY = 0.5f;
    private static volatile float builderHitLocalZ = 0.5f;
    private static volatile String builderHitSource = "coarse";
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_BLOCK_DELTAS_QUEUED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_BLOCK_APPEND_CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_BLOCK_APPEND_BATCHES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ConcurrentMap<Integer, String> COMPONENT_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Object, java.util.Map<String, Object>> PENDING_COMPONENTS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static volatile String componentBootstrapError = "";
    private static volatile Object currentCharacterController;
    private static volatile boolean replacingLegacyFrontend;
    private static volatile org.lwjgl.opengl.GLDebugMessageCallback controlGlDebugCallback;
    private static final java.util.concurrent.atomic.AtomicInteger CONTROL_GL_DEBUG_MESSAGES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CONTROL_GL_TRACE_MESSAGES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean CORE_ECS_LISTENERS_READY =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean EXTENDED_MOVEMENT_ENABLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean PRE_SKIN_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile java.lang.reflect.Method preSkinApplier;
    private static final Object STATIC_TEXTURE_POOL_LOCK = new Object();
    private static final java.util.Map<String, StaticTexturePoolEntry> STATIC_TEXTURE_POOL =
            new java.util.HashMap<>();
    private static final java.util.IdentityHashMap<Object, StaticTexturePoolEntry>
            STATIC_TEXTURE_OWNERS = new java.util.IdentityHashMap<>();
    private static long staticTexturePoolSavedBytes;
    private static int staticTexturePoolHits;
    private static volatile Object corePlayerEcsListener;
    private static volatile Object mouseCaptureWorld;
    private static volatile boolean headlessCaptureRequested;
    private static volatile long nativeMousePolls;
    private static volatile long nativeMouseEvents;
    private static volatile double nativeMouseDeltaX;
    private static volatile double nativeMouseDeltaY;
    private static volatile double nativeMouseLastX;
    private static volatile double nativeMouseLastY;
    private static final ThreadLocal<ClientBlockCapture> CLIENT_BLOCK_CAPTURE =
            new ThreadLocal<>();

    public static String compatibilityApplyPreSkinHook(Object shaderDefinition,
            String originalSource, String currentSource, java.util.List<?> hooks,
            int permutationCount) {
        try {
            java.lang.reflect.Method applier = preSkinApplier;
            if (applier == null) {
                applier = shaderDefinition.getClass().getMethod("try",
                        String.class, String.class, String.class, String.class);
                preSkinApplier = applier;
            }
            // The sixth fragment-hook stream was introduced after these game
            // classes were built. Legacy hook objects have no matching getter;
            // 894154326.long() is a vertex-stage hook and must never be placed
            // here (tree wind code then compiles as a fragment shader). An
            // empty stream is the exact newer-client behavior when no hook
            // implementation contributes to this stage.
            return (String) applier.invoke(shaderDefinition, originalSource,
                    currentSource, "#pre_skin_hook", "");
        } catch (ReflectiveOperationException failure) {
            if (PRE_SKIN_WARNING.compareAndSet(false, true)) {
                System.err.println("Failed to apply compatibility pre-skin shader hook: "
                        + failure);
            }
            return currentSource.replace("#pre_skin_hook", "");
        }
    }

    private static final class StaticTexturePoolEntry {
        final String key;
        final int textureId;
        final int bytes;
        int owners;

        StaticTexturePoolEntry(String key, int textureId, int bytes) {
            this.key = key;
            this.textureId = textureId;
            this.bytes = bytes;
            this.owners = 1;
        }
    }

    /**
     * Coalesces immutable asset textures loaded repeatedly by independent map-region
     * model resources. Dynamic textures and unnamed resources deliberately remain on
     * the stock path. This method is injected at the end of 1344017662's constructor.
     */
    public static void compatibilityInternStaticTexture(Object texture) {
        if (texture == null || Boolean.getBoolean("standard.jvm.disableStaticTexturePool")) return;
        try {
            Class<?> textureClass = texture.getClass();
            Object source = textureClass.getField("do").get(texture);
            Object sampler = textureClass.getField("throws").get(texture);
            if (source == null || sampler == null) return;
            Object rawPath = source.getClass().getField("default").get(source);
            if (!(rawPath instanceof String)) return;
            String path = ((String) rawPath).replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            if (!path.contains(".ol")) return;

            int textureId = textureClass.getField("boolean").getInt(texture);
            int bytes = textureClass.getField("private").getInt(texture);
            if (textureId == 0 || bytes <= 0) return;
            String key = path + '|' + primitiveFingerprint(source) + '|'
                    + primitiveFingerprint(sampler) + '|'
                    + textureClass.getField("package").getInt(texture) + '|'
                    + textureClass.getField("abstract").getInt(texture);

            synchronized (STATIC_TEXTURE_POOL_LOCK) {
                StaticTexturePoolEntry existing = STATIC_TEXTURE_POOL.get(key);
                if (existing == null) {
                    StaticTexturePoolEntry created = new StaticTexturePoolEntry(key, textureId, bytes);
                    STATIC_TEXTURE_POOL.put(key, created);
                    STATIC_TEXTURE_OWNERS.put(texture, created);
                    return;
                }
                if (existing.textureId == textureId) {
                    existing.owners++;
                    STATIC_TEXTURE_OWNERS.put(texture, existing);
                    return;
                }

                // Use the game's own deletion helper so bindless and ordinary GL
                // backends both release the just-uploaded duplicate correctly.
                textureClass.getMethod("if", int.class).invoke(texture, textureId);
                textureClass.getField("boolean").setInt(texture, existing.textureId);
                textureClass.getField("private").setInt(texture, 0);
                java.util.concurrent.atomic.AtomicLong allocated =
                        (java.util.concurrent.atomic.AtomicLong) textureClass.getField("switch").get(null);
                allocated.addAndGet(-bytes);
                activeTextureList(textureClass, texture).add(texture);
                existing.owners++;
                STATIC_TEXTURE_OWNERS.put(texture, existing);
                staticTexturePoolSavedBytes += bytes;
                staticTexturePoolHits++;
                if (Boolean.getBoolean("standard.jvm.texturePoolDebug")
                        && (staticTexturePoolHits == 1 || staticTexturePoolHits % 500 == 0)) {
                    System.err.println("[standard-jvm] static texture pool: hits="
                            + staticTexturePoolHits + ", savedBytes=" + staticTexturePoolSavedBytes
                            + ", unique=" + STATIC_TEXTURE_POOL.size());
                }
            }
        } catch (Throwable failure) {
            System.err.println("[standard-jvm] static texture pooling skipped: " + failure);
        }
    }

    /** Returns true only when the stock deletion body must run. */
    public static boolean compatibilityReleasePooledTexture(Object texture) {
        if (texture == null) return true;
        try {
            synchronized (STATIC_TEXTURE_POOL_LOCK) {
                StaticTexturePoolEntry entry = STATIC_TEXTURE_OWNERS.remove(texture);
                if (entry == null) return true;
                entry.owners--;
                if (entry.owners > 0) {
                    Class<?> textureClass = texture.getClass();
                    activeTextureList(textureClass, texture).remove(texture);
                    textureClass.getField("private").setInt(texture, 0);
                    return false;
                }
                STATIC_TEXTURE_POOL.remove(entry.key);
                // The original owner may have gone away before its duplicates.
                // Give the final wrapper the unique allocation size so the stock
                // accounting and GL deletion body remain exactly balanced.
                texture.getClass().getField("private").setInt(texture, entry.bytes);
                return true;
            }
        } catch (Throwable failure) {
            System.err.println("[standard-jvm] pooled texture release fallback: " + failure);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> activeTextureList(Class<?> textureClass,
            Object texture) throws ReflectiveOperationException {
        boolean bindless = (Boolean) textureClass.getMethod("else").invoke(texture);
        return (java.util.List<Object>) textureClass.getField(bindless ? "final" : "break").get(null);
    }

    private static String primitiveFingerprint(Object value) throws IllegalAccessException {
        StringBuilder result = new StringBuilder(value.getClass().getName());
        java.lang.reflect.Field[] fields = value.getClass().getFields();
        java.util.Arrays.sort(fields, java.util.Comparator.comparing(java.lang.reflect.Field::getName));
        for (java.lang.reflect.Field field : fields) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type.isPrimitive() || type == String.class || type.isEnum()) {
                result.append(';').append(field.getName()).append('=').append(field.get(value));
            } else if (field.getName().equals("double")) {
                result.append(';').append(field.getName()).append('=')
                        .append(primitiveFingerprint(field.get(value)));
            }
        }
        return result.toString();
    }
    private static final java.util.concurrent.ConcurrentLinkedQueue<ClientBlockDelta>
            CLIENT_BLOCK_DELTAS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final class ClientBlockState {
        final int blockId;
        final int metadata;
        final byte[] tileData;

        ClientBlockState(int blockId, int metadata, byte[] tileData) {
            this.blockId = blockId;
            this.metadata = metadata;
            this.tileData = tileData;
        }

        boolean same(ClientBlockState other) {
            return other != null && blockId == other.blockId && metadata == other.metadata
                    && java.util.Arrays.equals(tileData, other.tileData);
        }
    }

    private static final class ClientBlockDelta {
        final int x, y, z;
        final ClientBlockState before, after;

        ClientBlockDelta(int x, int y, int z, ClientBlockState before, ClientBlockState after) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.before = before;
            this.after = after;
        }
    }

    private static final class ClientBlockCapture {
        final Object world;
        final int x, y, z;
        final java.util.LinkedHashMap<Long, ClientBlockState> before =
                new java.util.LinkedHashMap<>();

        ClientBlockCapture(Object world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private StandardJvmBootstrap() {
    }

    private static long clientBlockKey(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38)
                | ((long) (z & 0x3ffffff) << 12)
                | (long) (y & 0xfff);
    }

    private static int clientBlockCoordinateX(long key) {
        return (int) (key >> 38);
    }

    private static int clientBlockCoordinateY(long key) {
        return (int) (key & 0xfffL);
    }

    private static int clientBlockCoordinateZ(long key) {
        return (int) (key << 26 >> 38);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>,
            java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>>
            WORLD_BLOCK_INT_GETTERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int invokeWorldBlockInt(Object world, String name, int x, int y, int z) {
        if (world == null) return 0;
        // Obfuscated overloads share the name and arguments but return int,
        // float or boolean. getMethod(name, arguments) cannot select the ID
        // or metadata contract. Never turn a failed read into an air snapshot.
        java.lang.reflect.Method method = WORLD_BLOCK_INT_GETTERS
                .computeIfAbsent(world.getClass(), ignored ->
                        new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(name, ignored -> {
                    for (java.lang.reflect.Method candidate : world.getClass().getMethods()) {
                        if (candidate.getName().equals(name)
                                && candidate.getReturnType() == int.class
                                && java.util.Arrays.equals(candidate.getParameterTypes(),
                                        new Class<?>[]{int.class, int.class, int.class})) {
                            candidate.setAccessible(true);
                            return candidate;
                        }
                    }
                    throw new IllegalStateException("client_block_int_getter_unavailable:"
                            + world.getClass().getName() + "." + name);
                });
        try {
            return (Integer) method.invoke(world, x, y, z);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("client_block_state_read_failed:" + name, failure);
        }
    }

    private static ClientBlockState clientBlockState(Object world, int x, int y, int z) {
        return new ClientBlockState(
                invokeWorldBlockInt(world, "strictfp", x, y, z) & 0xfff,
                invokeWorldBlockInt(world, "interface", x, y, z) & 0xf,
                clientTileData(world, x, y, z));
    }

    /** Serialize the tile through the client's own complete persistent-NBT path. */
    private static byte[] clientTileData(Object world, int x, int y, int z) {
        if (world == null) return null;
        try {
            java.lang.reflect.Method tileGetter = world.getClass().getMethod(
                    "abstract", int.class, int.class, int.class);
            tileGetter.setAccessible(true);
            Object tile = tileGetter.invoke(world, x, y, z);
            if (tile == null) return null;

            ClassLoader loader = StandardJvmBootstrap.class.getClassLoader();
            Class<?> compoundType = Class.forName("98008865", true, loader);
            Object compound = compoundType.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method writePersistent = tile.getClass().getMethod(
                    "static", compoundType);
            writePersistent.setAccessible(true);
            writePersistent.invoke(tile, compound);

            Class<?> compressedNbt = Class.forName("1464336358", true, loader);
            java.lang.reflect.Method encode = compressedNbt.getMethod(
                    "break", compoundType);
            encode.setAccessible(true);
            byte[] data = (byte[]) encode.invoke(null, compound);
            if (data.length > 1_048_576) {
                throw new IllegalStateException("client_tile_state_too_large:" + data.length);
            }
            return data;
        } catch (NoSuchMethodException missingPublicMethod) {
            throw new IllegalStateException("client_tile_serializer_unavailable", missingPublicMethod);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("client_tile_serialize_failed", failure);
        }
    }

    private static String sha256(byte[] data) {
        if (data == null) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0xf, 16));
                result.append(Character.forDigit(value & 0xf, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeNullableBlockState(
            java.io.DataOutputStream output, byte[] state) throws java.io.IOException {
        if (state == null) {
            output.writeInt(-1);
            return;
        }
        output.writeInt(state.length);
        output.write(state);
    }

    /** Apply action 127 of packet 237043901 as a complete persistent tile snapshot. */
    public static void compatibilityApplyFullTilePacket(Object world, Object packet) {
        Object actionValue = reflectedField(packet, "char");
        if (!(actionValue instanceof Number) || ((Number) actionValue).intValue() != 127) return;
        Object compound = reflectedField(packet, "short");
        if (world == null || compound == null) return;
        try {
            int x = ((Number) reflectedField(packet, "native")).intValue();
            int y = ((Number) reflectedField(packet, "instanceof")).intValue();
            int z = ((Number) reflectedField(packet, "break")).intValue();
            java.lang.reflect.Method tileGetter = world.getClass().getMethod(
                    "abstract", int.class, int.class, int.class);
            tileGetter.setAccessible(true);
            Object tile = tileGetter.invoke(world, x, y, z);
            if (tile == null) {
                throw new IllegalStateException("full_tile_target_unavailable:" + x + "," + y + "," + z);
            }
            Class<?> compoundType = Class.forName("98008865", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Method readPersistent = tile.getClass().getMethod(
                    "volatile", compoundType);
            readPersistent.setAccessible(true);
            readPersistent.invoke(tile, compound);
            try {
                java.lang.reflect.Method changed = tile.getClass().getMethod("long");
                changed.setAccessible(true);
                changed.invoke(tile);
            } catch (NoSuchMethodException ignored) {
                // Persistent read already updated the complete tile state.
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("full_tile_apply_failed", failure);
        }
    }

    /** Snapshot the small voxel neighbourhood before the native client mutates it. */
    public static void compatibilityBeginBlockMutation(
            Object world, int x, int y, int z, int face) {
        if (world == null || y < 0 || y > 255) return;
        ClientBlockCapture capture = new ClientBlockCapture(world, x, y, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int currentY = y + dy;
                    if (currentY < 0 || currentY > 255) continue;
                    long key = clientBlockKey(x + dx, currentY, z + dz);
                    capture.before.put(key,
                            clientBlockState(world, x + dx, currentY, z + dz));
                }
            }
        }
        CLIENT_BLOCK_CAPTURE.set(capture);
    }

    /** Diff the post-interaction world; unchanged/use-only interactions produce no delta. */
    public static void compatibilityEndBlockMutation(
            Object world, int x, int y, int z, int face) {
        ClientBlockCapture capture = CLIENT_BLOCK_CAPTURE.get();
        CLIENT_BLOCK_CAPTURE.remove();
        if (capture == null || capture.world != world
                || capture.x != x || capture.y != y || capture.z != z) return;
        int emitted = 0;
        for (java.util.Map.Entry<Long, ClientBlockState> entry : capture.before.entrySet()) {
            long key = entry.getKey();
            int currentX = clientBlockCoordinateX(key);
            int currentY = clientBlockCoordinateY(key);
            int currentZ = clientBlockCoordinateZ(key);
            ClientBlockState after = clientBlockState(world, currentX, currentY, currentZ);
            if (entry.getValue().same(after)) continue;
            CLIENT_BLOCK_DELTAS.add(new ClientBlockDelta(
                    currentX, currentY, currentZ, entry.getValue(), after));
            CLIENT_BLOCK_DELTAS_QUEUED.incrementAndGet();
            if (++emitted >= 32) break;
        }
    }

    /**
     * Destruction is dispatched through a controller method which does not
     * receive the world instance. Resolve the live world at the boundary and
     * retain it in the thread-local capture until the controller returns.
     */
    public static void compatibilityBeginControllerBlockMutation(
            Object controller, int x, int y, int z, int face) {
        compatibilityBeginBlockMutation(reflectedField(controlGame, "g"), x, y, z, face);
    }

    public static void compatibilityEndControllerBlockMutation(
            Object controller, int x, int y, int z, int face) {
        ClientBlockCapture capture = CLIENT_BLOCK_CAPTURE.get();
        if (capture == null) return;
        compatibilityEndBlockMutation(capture.world, x, y, z, face);
    }

    /**
     * Bracket the client event which lets held items handle a block click.
     * Builder tools can change complete tile state from inside this event,
     * before the vanilla controller is called, so the controller-only capture
     * used for ordinary placement/destruction cannot observe those changes.
     */
    public static void compatibilityBeginBlockInteractionEvent(
            Object player, Object action, int x, int y, int z, int face) {
        if (!(action instanceof Enum)
                || !"LEFT_CLICK_BLOCK".equals(((Enum<?>) action).name())) return;
        compatibilityBeginBlockMutation(reflectedField(controlGame, "g"), x, y, z, face);
    }

    public static void compatibilityEndBlockInteractionEvent(
            Object result, Object action, int x, int y, int z, int face) {
        if (!(action instanceof Enum)
                || !"LEFT_CLICK_BLOCK".equals(((Enum<?>) action).name())) return;
        ClientBlockCapture capture = CLIENT_BLOCK_CAPTURE.get();
        if (capture != null) {
            compatibilityEndBlockMutation(capture.world, x, y, z, face);
        }

        // Dispatch at the event boundary itself. Some input paths stop after a
        // held listener consumes LEFT_CLICK_BLOCK and never return to the
        // Minecraft controller call site patched below.
        Object player = reflectedField(controlGame, "e");
        Object held = invokeStaticReflected("764970879", "short", player);
        Object factory = invokeReflected(held, "interface");
        String factoryClass = className(factory);
        if (factoryClass.equals("56524039") || factoryClass.equals("521898846")) {
            compatibilityDispatchLeftClickBlock(null, x, y, z, face);
        }
    }

    /** Decide whether LEFT_CLICK_BLOCK proceeds to destruction/builder dispatch. */
    public static boolean compatibilityShouldRunLeftClick(Object eventResult) {
        Object player = reflectedField(controlGame, "e");
        Object held = invokeStaticReflected("764970879", "short", player);
        Object factory = invokeReflected(held, "interface");
        String factoryClass = className(factory);
        if (factoryClass.equals("56524039") || factoryClass.equals("521898846")) {
            // The builder operation already ran at the event boundary above.
            // Stop before vanilla destruction and avoid applying it twice.
            return false;
        }
        return !Boolean.TRUE.equals(invokeReflected(eventResult, "protected"));
    }

    /**
     * Route a left click through the held builder item's native local operation.
     *
     * 237758042.static(...) is the original left-click entry point. It refuses
     * to run on a remote/client player before dispatching to byte(tile, player,
     * side, toolMode), even though the latter contains the complete ProBuilder
     * mutation. The proprietary server used to execute that lower operation.
     * For a thin server, call the same polymorphic operation on the client and
     * let the existing before/after capture transport the complete tile NBT.
     */
    public static void compatibilityDispatchLeftClickBlock(
        Object controller, int x, int y, int z, int face) {
        Object player = reflectedField(controlGame, "e");
        // The active hand is resolved through the same helper used by
        // 1402131154.byte(I). player.case() is a different inventory view and
        // made every real hammer click look like an ordinary empty-hand hit.
        Object held = invokeStaticReflected("764970879", "short", player);
        Object factory = invokeReflected(held, "interface");
        String factoryClass = className(factory);
        boolean builderTool = factoryClass.equals("56524039")
                || factoryClass.equals("521898846");
        if (!builderTool) {
            invokeReflected(controller, "for",
                    Integer.valueOf(x), Integer.valueOf(y),
                    Integer.valueOf(z), Integer.valueOf(face));
            return;
        }

        Object world = reflectedField(controlGame, "g");
        if (player == null || held == null || world == null) {
            throw new IllegalStateException("builder_left_click_context_unavailable");
        }

        ClientBlockState before = clientBlockState(world, x, y, z);
        int blockId = before.blockId;
        Object blockRegistry = staticField("1959919792", "switch");
        if (blockRegistry == null || !blockRegistry.getClass().isArray()
                || blockId < 0 || blockId >= java.lang.reflect.Array.getLength(blockRegistry)) {
            throw new IllegalStateException("builder_left_click_block_unavailable:" + blockId);
        }
        Object block = java.lang.reflect.Array.get(blockRegistry, blockId);
        Object tile = invokeReflected(world, "abstract",
                Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        Object modeValue = invokeIntNoArg(held, "synchronized");
        controlLastBuilderClick = "{\"phase\":\"resolve\",\"x\":" + x
                + ",\"y\":" + y + ",\"z\":" + z + ",\"blockId\":"
                + blockId + ",\"blockClass\":\"" + jsonEscape(className(block))
                + "\",\"tileClass\":\"" + jsonEscape(className(tile)) + "\"}";
        if (block == null || tile == null || !(modeValue instanceof Number)) {
            // A builder tool must never fall through to vanilla destruction.
            // Unsupported blocks simply do not consume the click.
            return;
        }

        try {
            int localFace = ((Number) invokeReflected(block, "switch",
                    Integer.valueOf(face))).intValue();
            int mode = ((Number) modeValue).intValue();
            Object handled = null;
            compatibilityBeginBlockMutation(world, x, y, z, face);
            try {
                handled = invokeReflected(block, "byte", tile, player,
                        Integer.valueOf(localFace), Integer.valueOf(mode));
            } finally {
                compatibilityEndBlockMutation(world, x, y, z, face);
            }
            ClientBlockState after = clientBlockState(world, x, y, z);
            controlLastBuilderClick = "{\"phase\":\"complete\",\"x\":" + x
                    + ",\"y\":" + y + ",\"z\":" + z + ",\"blockId\":" + blockId
                    + ",\"blockClass\":\"" + jsonEscape(className(block))
                    + "\",\"tileClass\":\"" + jsonEscape(className(tile))
                    + "\",\"mode\":" + mode + ",\"face\":" + face
                    + ",\"localFace\":" + localFace + ",\"handled\":"
                    + jsonScalar(handled) + ",\"hitLocal\":["
                    + builderHitLocalX + "," + builderHitLocalY + ","
                    + builderHitLocalZ + "],\"hitSource\":\""
                    + jsonEscape(builderHitSource) + "\",\"changed\":" + !before.same(after)
                    + ",\"beforeStateSha256\":\"" + sha256(before.tileData)
                    + "\",\"afterStateSha256\":\"" + sha256(after.tileData) + "\"}";
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastBuilderClick = "{\"phase\":\"error\",\"x\":" + x
                    + ",\"y\":" + y + ",\"z\":" + z + ",\"blockId\":" + blockId
                    + ",\"blockClass\":\"" + jsonEscape(className(block))
                    + "\",\"tileClass\":\"" + jsonEscape(className(tile))
                    + "\",\"error\":\"" + jsonEscape(root.getClass().getName()
                    + ": " + root.getMessage()) + "\"}";
            controlLastError = "builder left click: " + root.getClass().getName()
                    + ": " + root.getMessage();
        }
    }

    /** Upper input-action hook used before the Forge/controller click pipeline. */
    public static void compatibilityDispatchPrimaryAction(Object game, int action) {
        Object player = reflectedField(game, "e");
        Object held = invokeStaticReflected("764970879", "short", player);
        Object factory = invokeReflected(held, "interface");
        String factoryClass = className(factory);
        boolean builderTool = factoryClass.equals("56524039")
                || factoryClass.equals("521898846");
        if (!builderTool || action != 0) {
            invokeReflected(game, "byte", Integer.valueOf(action));
            return;
        }

        Object hit = invokeReflected(staticField("94534399", "public"), "volatile");
        if (!className(hit).equals("107664378")) {
            controlLastBuilderClick = "{\"phase\":\"target\",\"hitClass\":\""
                    + jsonEscape(className(hit)) + "\"}";
            return;
        }
        int x = ((Number) invokeReflected(hit, "abstract")).intValue();
        int y = ((Number) invokeReflected(hit, "this")).intValue();
        int z = ((Number) invokeReflected(hit, "volatile")).intValue();
        int face = ((Number) invokeReflected(hit, "super")).intValue();
        // 1612910413 selects one of the 4x4 cells on the hit face through the
        // same static local hit coordinates populated by 120538621's original
        // event listener.  This upper input hook runs before that listener, so
        // copy the precise raycast point from 1473643318 instead of leaving a
        // stale cell from an earlier interaction.
        Object coordinateHit = hit;
        Object world = reflectedField(game, "g");
        try {
            Class<?> listenerType = Class.forName("120538621", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Constructor<?> constructor = listenerType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object listener = constructor.newInstance();
            setStaticObject("120538621", "short", player);
            setStaticBoolean("120538621", "transient", true);
            Object preciseHit = invokeReflected(listener, "this", world, player);
            if (preciseHit != null) {
                coordinateHit = preciseHit;
                builderHitSource = "builder-raycast";
            } else {
                builderHitSource = "coarse-null-builder-raycast";
            }
        } catch (Throwable failure) {
            builderHitSource = "coarse-error:" + failure.getClass().getSimpleName();
        } finally {
            setStaticBoolean("120538621", "transient", false);
        }
        // 107664378 has both int abstract() (block X) and inherited double
        // abstract() (precise hit X). The obfuscator permits return-type-only
        // overloads, so generic reflection picked the integer method and made
        // local X permanently zero. Resolve all components by return type.
        Object hitX = invokeDoubleNoArg(coordinateHit, "abstract");
        Object hitY = invokeDoubleNoArg(coordinateHit, "do");
        Object hitZ = invokeDoubleNoArg(coordinateHit, "import");
        if (hitX instanceof Number && hitY instanceof Number && hitZ instanceof Number) {
            builderHitLocalX = exactBlockHit(((Number) hitX).doubleValue() - x);
            builderHitLocalY = exactBlockHit(((Number) hitY).doubleValue() - y);
            builderHitLocalZ = exactBlockHit(((Number) hitZ).doubleValue() - z);
            setStaticFloat("120538621", "else", builderHitLocalX);
            setStaticFloat("120538621", "protected", builderHitLocalY);
            setStaticFloat("120538621", "interface", builderHitLocalZ);
        }
        compatibilityDispatchLeftClickBlock(null, x, y, z, face);
    }

    private static float exactBlockHit(double value) {
        if (!Double.isFinite(value)) return 0.5f;
        // Keep out-of-voxel values intact. ProBuilder's custom ray geometry
        // legitimately produces them and its 4x4 face selector normalizes
        // them internally. The stock 120538621 listener does the same raw
        // subtraction without clamping.
        return (float) value;
    }

    private static String blockRuntimeState(int x, int y, int z) {
        Object world = reflectedField(controlGame, "g");
        ClientBlockState state = clientBlockState(world, x, y, z);
        int blockId = state.blockId;
        Object registry = staticField("1959919792", "switch");
        Object block = registry != null && registry.getClass().isArray()
                && blockId >= 0 && blockId < java.lang.reflect.Array.getLength(registry)
                ? java.lang.reflect.Array.get(registry, blockId) : null;
        Object tile = invokeReflected(world, "abstract",
                Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"blockId\":" + blockId + ",\"metadata\":" + state.metadata
                + ",\"blockClass\":\"" + jsonEscape(className(block))
                + "\",\"tileClass\":\"" + jsonEscape(className(tile))
                + "\",\"stateBytes\":" + (state.tileData == null ? -1 : state.tileData.length)
                + ",\"stateSha256\":\"" + sha256(state.tileData) + "\"}";
    }

    private static boolean invokeWorldBlockSet(
            Object world, int x, int y, int z, int blockId, int metadata) {
        if (world == null) throw new IllegalStateException("world_unavailable");
        try {
            java.lang.reflect.Method method = world.getClass().getMethod(
                    "volatile", int.class, int.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(world, x, y, z, blockId, metadata));
        } catch (NoSuchMethodException missingPublicMethod) {
            try {
                java.lang.reflect.Method method = world.getClass().getDeclaredMethod(
                        "volatile", int.class, int.class, int.class, int.class, int.class);
                method.setAccessible(true);
                return Boolean.TRUE.equals(method.invoke(world, x, y, z, blockId, metadata));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("world_block_setter_unavailable", failure);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("world_block_set_failed", failure);
        }
    }

    private static void invokeDiagnosticBlockDelta(
            int x, int y, int z, int blockId, int metadata) {
        if (y < 0 || y > 255 || blockId < 0 || blockId > 0xfff
                || metadata < 0 || metadata > 0xf) {
            throw new IllegalArgumentException("invalid_native_block_state");
        }
        Object world = reflectedField(controlGame, "g");
        ClientBlockState before = clientBlockState(world, x, y, z);
        compatibilityBeginBlockMutation(world, x, y, z, 1);
        boolean changed;
        try {
            changed = invokeWorldBlockSet(world, x, y, z, blockId, metadata);
        } finally {
            compatibilityEndBlockMutation(world, x, y, z, 1);
        }
        ClientBlockState after = clientBlockState(world, x, y, z);
        controlLastBlockDelta = "{\"x\":" + x + ",\"y\":" + y
                + ",\"z\":" + z + ",\"beforeId\":" + before.blockId
                + ",\"beforeMetadata\":" + before.metadata
                + ",\"requestedId\":" + blockId
                + ",\"requestedMetadata\":" + metadata
                + ",\"afterId\":" + after.blockId
                + ",\"afterMetadata\":" + after.metadata
                + ",\"beforeStateBytes\":"
                + (before.tileData == null ? -1 : before.tileData.length)
                + ",\"beforeStateSha256\":\"" + sha256(before.tileData) + "\""
                + ",\"afterStateBytes\":"
                + (after.tileData == null ? -1 : after.tileData.length)
                + ",\"afterStateSha256\":\"" + sha256(after.tileData) + "\""
                + ",\"changed\":" + changed + "}";
    }

    /** Append versioned client-authored voxel deltas to the opaque input action tail. */
    public static byte[] compatibilityAppendBlockDeltas(byte[] actions) {
        CLIENT_BLOCK_APPEND_CALLS.incrementAndGet();
        ClientBlockDelta first = CLIENT_BLOCK_DELTAS.poll();
        if (first == null) return actions == null ? new byte[0] : actions;
        java.util.ArrayList<ClientBlockDelta> values = new java.util.ArrayList<>();
        values.add(first);
        while (values.size() < 32) {
            ClientBlockDelta value = CLIENT_BLOCK_DELTAS.poll();
            if (value == null) break;
            values.add(value);
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(
                    (actions == null ? 0 : actions.length) + 6 + values.size() * 26);
            if (actions != null) bytes.write(actions);
            int sectionStart = bytes.size();
            java.io.DataOutputStream output = new java.io.DataOutputStream(bytes);
            output.writeInt(0x535A4244); // SZBD
            output.writeByte(3);
            output.writeByte(values.size());
            for (ClientBlockDelta value : values) {
                output.writeInt(value.x);
                output.writeInt(value.y);
                output.writeInt(value.z);
                output.writeShort(value.before.blockId);
                output.writeByte(value.before.metadata);
                output.writeShort(value.after.blockId);
                output.writeByte(value.after.metadata);
                writeNullableBlockState(output, value.before.tileData);
                writeNullableBlockState(output, value.after.tileData);
            }
            output.writeInt(0x535A4245); // SZBE
            output.writeInt(bytes.size() - sectionStart + 4);
            output.flush();
            byte[] encoded = bytes.toByteArray();
            CLIENT_BLOCK_APPEND_BATCHES.incrementAndGet();
            controlLastEncodedBlockDelta = "{\"records\":" + values.size()
                    + ",\"beforeActionBytes\":" + (actions == null ? 0 : actions.length)
                    + ",\"afterActionBytes\":" + encoded.length + "}";
            return encoded;
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("cannot_encode_client_block_delta", impossible);
        }
    }

    /**
     * Handle generator used by optional protected-JVM service and zlib stubs.
     * The historical method name is retained because normalized bytecode calls
     * it; Effekseer itself no longer uses this path and is backed by real JNI.
     */
    public static long newEffekseerHandle() {
        return COMPATIBILITY_NATIVE_HANDLES.getAndIncrement();
    }

    public static int newEffekseerPlaybackHandle() {
        return (int) Math.min(COMPATIBILITY_NATIVE_HANDLES.getAndIncrement(), Integer.MAX_VALUE);
    }

    public static void warnNoMonitorsFallback() {
        if (MONITOR_WARNING.compareAndSet(false, true)) {
            System.err.println(
                    "GLFW reported no monitors; keeping the compatibility window at 800x600.");
        }
    }

    /**
     * Win32 can keep a monitor handle in GLFW's enumeration while temporarily
     * refusing its display settings (notably in hidden/RDP sessions). The old
     * ImGui backend dereferences that nullable result. Supply a stable struct
     * backed by a retained direct buffer so monitor enumeration can continue.
     */
    public static org.lwjgl.glfw.GLFWVidMode compatibilityGlfwVideoMode(long monitor) {
        org.lwjgl.glfw.GLFWVidMode mode = org.lwjgl.glfw.GLFW.glfwGetVideoMode(monitor);
        if (mode != null) return mode;
        mode = fallbackGlfwVideoMode;
        if (mode != null) return mode;
        synchronized (StandardJvmBootstrap.class) {
            mode = fallbackGlfwVideoMode;
            if (mode == null) {
                int[] width = {800};
                int[] height = {600};
                if (controlWindow != 0L) {
                    org.lwjgl.glfw.GLFW.glfwGetWindowSize(controlWindow, width, height);
                }
                java.nio.ByteBuffer storage = org.lwjgl.BufferUtils.createByteBuffer(
                        org.lwjgl.glfw.GLFWVidMode.SIZEOF);
                mode = new org.lwjgl.glfw.GLFWVidMode(storage);
                long address = mode.address();
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.WIDTH,
                        Math.max(1, width[0]));
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.HEIGHT,
                        Math.max(1, height[0]));
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.REDBITS, 8);
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.GREENBITS, 8);
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.BLUEBITS, 8);
                org.lwjgl.system.MemoryUtil.memPutInt(address + org.lwjgl.glfw.GLFWVidMode.REFRESHRATE, 60);
                fallbackGlfwVideoMode = mode;
                if (MONITOR_WARNING.compareAndSet(false, true)) {
                    System.err.println("GLFW monitor video mode unavailable; using window-size fallback.");
                }
            }
            return mode;
        }
    }

    public static org.lwjgl.PointerBuffer compatibilityGlfwMonitors() {
        org.lwjgl.PointerBuffer monitors = org.lwjgl.glfw.GLFW.glfwGetMonitors();
        if (monitors != null) return monitors;
        warnNoMonitorsFallback();
        return org.lwjgl.BufferUtils.createPointerBuffer(0);
    }

    /** ImGui viewports require a monitor even in an off-screen/RDP session. */
    public static void compatibilityImGuiMonitors() {
        imgui.ImGuiPlatformIO platform=imgui.ImGui.getPlatformIO();
        if(platform.getMonitorsSize()!=0)return;
        long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();if(window==0)return;
        int[] x={0},y={0},width={1},height={1};float[] sx={1},sy={1};
        org.lwjgl.glfw.GLFW.glfwGetWindowPos(window,x,y);org.lwjgl.glfw.GLFW.glfwGetWindowSize(window,width,height);
        org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(window,sx,sy);
        float w=Math.max(1,width[0]),h=Math.max(1,height[0]),dpi=Float.isFinite(sx[0])&&sx[0]>0?sx[0]:1;
        platform.pushMonitors(x[0],y[0],w,h,x[0],y[0],w,h,dpi);
    }

    /** Stock-JDK replacement for the protected launcher's private Registry API. */
    public static String compatibilityMachineGuid(int ignoredRoot,
            String ignoredPath, String ignoredValue) {
        String seed = System.getProperty("user.name", "stalcraft") + '\0'
                + System.getenv().getOrDefault("COMPUTERNAME", "standard-jvm");
        return java.util.UUID.nameUUIDFromBytes(
                seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** FMOD 2.2.29 lacks two entry points present in the generated 2.2.41 table. */
    public static long optionalFmodFunctionAddress(
            org.lwjgl.system.FunctionProvider provider, String functionName) {
        long address = provider.getFunctionAddress(functionName);
        if (address == 0L && !(functionName.equals("FMOD_Studio_EventInstance_GetSystem")
                || functionName.equals("FMOD_System_CreateDSPConnection"))) {
            throw new NullPointerException("A required FMOD function is missing: " + functionName);
        }
        return address;
    }

    /**
     * FMOD returns ERR_OUTPUT_INIT (51) when Windows exposes no usable audio
     * endpoint (common in disconnected Remote Desktop/headless sessions).  The
     * Studio and Core systems are otherwise valid, so switch the already-created
     * Core system to FMOD_OUTPUTTYPE_NOSOUND and retry the normal Studio init.
     * Keeping FMOD initialized is important: later game code can continue to
     * create banks, events and channels even though this session is silent.
     */
    public static int initializeFmodStudioWithNoSoundFallback(long studioSystem,
            int maxChannels, int studioFlags, int coreFlags, long extraDriverData,
            long coreSystem) {
        int result = invokeFmodStaticInt("1069936767", "finally",
                new Class<?>[] {long.class, int.class, int.class, int.class, long.class},
                studioSystem, maxChannels, studioFlags, coreFlags, extraDriverData);
        if (result != 51) return result;

        int outputResult = invokeFmodStaticInt("1141491285", "synchronized",
                new Class<?>[] {long.class, int.class}, coreSystem, 2);
        if (outputResult != 0) return result;

        if (FMOD_NOSOUND_WARNING.compareAndSet(false, true)) {
            System.err.println(
                    "FMOD output device is unavailable; continuing with no-sound output.");
        }
        return invokeFmodStaticInt("1069936767", "finally",
                new Class<?>[] {long.class, int.class, int.class, int.class, long.class},
                studioSystem, maxChannels, studioFlags, coreFlags, extraDriverData);
    }

    private static int invokeFmodStaticInt(String owner, String name,
            Class<?>[] parameterTypes, Object... arguments) {
        try {
            return (Integer) Class.forName(owner, true,
                    StandardJvmBootstrap.class.getClassLoader())
                    .getDeclaredMethod(name, parameterTypes)
                    .invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("FMOD compatibility call failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FMOD compatibility method is unavailable", failure);
        }
    }

    /** Java 17-compatible replacement for Field.class.getDeclaredField("modifiers"). */
    public static java.lang.reflect.Field compatibilityModifiersField(
            Class<?> ignoredOwner, String ignoredName) {
        try {
            return StandardJvmBootstrap.class.getDeclaredField("LEGACY_FIELD_MODIFIERS");
        } catch (NoSuchFieldException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    /**
     * Java 9 removed ClassLoader.classes. The client uses it only for optional
     * preload/compression inventories, so an empty live Vector is the stock-JVM
     * equivalent when no Instrumentation agent supplies a loaded-class list.
     */
    public static java.lang.reflect.Field compatibilityLoadedClassesField(
            Class<?> ignoredOwner, String ignoredName) {
        try {
            return StandardJvmBootstrap.class.getDeclaredField(
                    "COMPATIBILITY_LOADED_CLASSES");
        } catch (NoSuchFieldException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    public static void main(String[] args) {
        try {
            for (int i=0;i+1<args.length;i++)
                if ("--username".equals(args[i])) controlQaLogin="Animation3009Test".equals(args[i+1]);
            configureNativeOverrides();
            loadBundledNatives();
            if (Boolean.getBoolean("standard.jvm.preflight")) {
                preflight();
                return;
            }
            startHeadlessControlBridge();
            // Initialize JNI libraries before the protected loader can mark an
            // unsuccessful custom-path attempt as complete.
            imgui.ImGui.init();
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd", true,
                    StandardJvmBootstrap.class.getClassLoader());
            zstd.getMethod("compressBound", long.class).invoke(null, 1L);
            exbo.stalcraft.client.Main.main(args);
        } catch (Throwable failure) {
            System.err.println("STANDARD JVM BOOTSTRAP FAILURE");
            print(failure, "");
            System.exit(1);
        }
    }

    public static float compatibilityImguiFontSize() {
        return (float) imgui.ImGui.getFontSize();
    }

    public static boolean compatibilityImguiSpinnerItemAdd(
            float minimumX, float minimumY, float maximumX, float maximumY, int id) {
        return imgui.ImGui.isRectVisible(minimumX, minimumY, maximumX, maximumY);
    }

    public static void compatibilityImguiItemSize(
            float minimumX, float minimumY, float maximumX, float maximumY,
            float textBaselineY) {
        // BSCP integration targets the native fork, which exposes the bounding-box form of ItemSize. Public
        // imgui-java 1.82.2 exposes no equivalent JNI symbol, but SameLine()
        // relies on the cursor advancing. Reserve the exact box through the
        // stable public dummy primitive instead of dropping layout state.
        imgui.ImGui.setCursorScreenPos(minimumX, minimumY);
        imgui.ImGui.dummy(maximumX - minimumX, maximumY - minimumY);
    }

    public static boolean compatibilityImguiItemHoverable(
            float minimumX, float minimumY, float maximumX, float maximumY, int id) {
        return imgui.ImGui.isMouseHoveringRect(
                minimumX, minimumY, maximumX, maximumY);
    }

    public static void compatibilityImguiSetHoveredId(int id) {
        // BSCP integration targets the native 1.82.2 fork, which exposes ImGui::SetHoveredID, while the matching
        // public 1.82.2 JNI library does not. The caller has already submitted
        // and hover-tested the item, so omitting the redundant internal ID
        // assignment preserves interaction without mixing in the incompatible
        // 1.88 native ABI.
    }

    public static Object compatibilityImguiCurrentWindow() {
        // Marker consumed immediately by compatibilityImguiWindowName/Id.
        return null;
    }

    private static String compatibilityImguiCurrentWindowName() {
        imgui.ImDrawList drawList = imgui.ImGui.getWindowDrawList();
        if (drawList == null) return "";
        long drawListPointer = ((imgui.binding.ImGuiStruct) drawList).ptr;
        if (drawListPointer == 0L) return "";
        // Dear ImGui 1.82 x64 ImDrawList: three 16-byte ImVectors, Flags,
        // _VtxCurrentIdx, _Data, then the _OwnerName pointer at byte 64.
        long namePointer = org.lwjgl.system.MemoryUtil.memGetAddress(drawListPointer + 64L);
        return namePointer == 0L ? "" : org.lwjgl.system.MemoryUtil.memUTF8(namePointer);
    }

    private static int compatibilityImguiHash(String value) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int index = 0; index < utf8.length; index++) {
            if (utf8[index] == '#' && index + 2 < utf8.length
                    && utf8[index + 1] == '#' && utf8[index + 2] == '#') {
                crc.reset();
            }
            crc.update(utf8[index] & 0xFF);
        }
        return (int) crc.getValue();
    }

    /** Observe, but never alter, the original NativeInput listener output. */
    public static void observeNativeMouseDeltas(Object mouseInput) {
        nativeMousePolls++;
        double horizontal = reflectedNumber(mouseInput, "case");
        double vertical = reflectedNumber(mouseInput, "synchronized");
        nativeMouseLastX = horizontal;
        nativeMouseLastY = vertical;
        if (horizontal != 0.0 || vertical != 0.0) {
            nativeMouseEvents++;
            nativeMouseDeltaX += horizontal;
            nativeMouseDeltaY += vertical;
        }
    }

    private static double reflectedNumber(Object owner, String name) {
        Object value = reflectedField(owner, name);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    public static String compatibilityImguiWindowName(Object window) {
        if (window == null) return compatibilityImguiCurrentWindowName();
        long pointer = reflectedLongField(window, "ptr");
        if (pointer == 0L) return "";
        long namePointer = org.lwjgl.system.MemoryUtil.memGetAddress(pointer);
        return namePointer == 0L ? "" : org.lwjgl.system.MemoryUtil.memUTF8(namePointer);
    }

    public static int compatibilityImguiWindowId(Object window) {
        if (window == null) return compatibilityImguiHash(
                compatibilityImguiCurrentWindowName());
        long pointer = reflectedLongField(window, "ptr");
        return pointer == 0L ? 0
                : org.lwjgl.system.MemoryUtil.memGetInt(pointer + Long.BYTES);
    }

    private static long reflectedLongField(Object owner, String name) {
        if (owner == null) return 0L;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getLong(owner);
            } catch (NoSuchFieldException ignored) {
                // Continue through the binding base classes.
            } catch (ReflectiveOperationException failure) {
                throw new RuntimeException(failure);
            }
        }
        throw new IllegalStateException("field_not_found:"
                + owner.getClass().getName() + "." + name);
    }

    /** Called once per real game frame by the normalized client. */
    public static void headlessControlTick(Object game) {
        // Compatibility initialization is required by both visible and hidden
        // standard-JVM launches. Keep the current game reference available to
        // lazy renderer repair before any render-path lookup can run.
        controlGame = game;
        szmods.runtime.ModLoader.frame(game);
        szmods.runtime.ModLoader.input(modInputSnapshot());
        synchronizeVisualWorldClock(game);
        synchronizeVisualWeather(game);
        ensureCoreEcsListeners();
        restoreCurrentFrontendAfterDisconnect(game);
        long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        restoreWorldMouseCapture(game, window);
        if (!Boolean.getBoolean("standard.jvm.headless.control")) return;
        if (window != 0L) {
            controlWindow = window;
            installControlGlDebugCallback();
            if (Boolean.getBoolean("standard.jvm.headless.hideWindow")
                    && !controlWindowHidden) {
                org.lwjgl.glfw.GLFW.glfwHideWindow(window);
                controlWindowHidden = true;
            }
        }
        for (int count = 0; count < 256; count++) {
            Runnable command = CONTROL_QUEUE.poll();
            if (command == null) break;
            try {
                command.run();
            } catch (Throwable failure) {
                controlLastError = failure.getClass().getName() + ": " + failure.getMessage();
                failure.printStackTrace(System.err);
            }
        }
        if (headlessCaptureRequested) {
            if (reflectedField(game, "implements") == null
                    && reflectedField(game, "volatile") == null && !nativeUiOwnsMouse()) {
                invokeMouseCapture(true);
            } else {
                headlessCaptureRequested = false;
                invokeMouseCapture(false);
            }
        }
        long frame = CONTROL_FRAMES.incrementAndGet();
        if (frame < 4 || (frame & 15L) == 0L) {
            controlState = buildControlState(game, frame);
        }
    }

    /** Normalized hardware state for external mods.  Polling happens on the game thread. */
    private static szmods.api.InputSnapshot modInputSnapshot() {
        long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        java.util.Map<Integer, Boolean> keys = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Boolean> mouse = new java.util.LinkedHashMap<>();
        java.util.Map<String, Float> axes = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> buttons = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, szmods.api.GamepadState> pads = new java.util.LinkedHashMap<>();
        if (window != 0L) {
            int[] relevantKeys = {32, 65, 67, 68, 69, 70, 81, 82, 83, 87, 88, 90, 340, 341, 342, 344, 345};
            for (int key : relevantKeys) keys.put(key, org.lwjgl.glfw.GLFW.glfwGetKey(window, key) != org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            for (int button = 0; button <= 7; button++) mouse.put(button, org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, button) != org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            // The localhost control bridge injects key callbacks, which GLFW
            // does not reflect in glfwGetKey. Mirror them for mod input tests.
            CONTROL_KEYS.forEach((key, down) -> { if (Boolean.TRUE.equals(down)) keys.put(key, true); });
        }
        String[] axisNames = {"left-x", "left-y", "right-x", "right-y", "left-trigger", "right-trigger"};
        String[] buttonNames = {"a", "b", "x", "y", "left-bumper", "right-bumper", "back", "start", "left-stick", "right-stick", "dpad-up", "dpad-right", "dpad-down", "dpad-left"};
        for (int id = org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1; id <= org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST; id++) {
            if (!org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad(id)) continue;
            org.lwjgl.glfw.GLFWGamepadState state = org.lwjgl.glfw.GLFWGamepadState.create();
            if (!org.lwjgl.glfw.GLFW.glfwGetGamepadState(id, state)) continue;
            java.util.Map<String, Float> padAxes = new java.util.LinkedHashMap<>();
            java.util.Map<String, Boolean> padButtons = new java.util.LinkedHashMap<>();
            for (int axis = 0; axis < axisNames.length; axis++) padAxes.put(axisNames[axis], state.axes(axis));
            for (int button = 0; button < buttonNames.length; button++) padButtons.put(buttonNames[button], state.buttons(button) != org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            String name = org.lwjgl.glfw.GLFW.glfwGetGamepadName(id); if (name == null) name = "Gamepad " + id;
            pads.put(id, new szmods.api.GamepadState(name, java.util.Map.copyOf(padAxes), java.util.Map.copyOf(padButtons)));
        }
        if (!pads.isEmpty()) {
            szmods.api.GamepadState first = pads.values().iterator().next();
            axes.putAll(first.axes()); buttons.putAll(first.buttons());
        }
        return new szmods.api.InputSnapshot(java.util.Map.copyOf(keys), java.util.Map.copyOf(mouse), java.util.Map.copyOf(axes), java.util.Map.copyOf(buttons), java.util.Map.copyOf(pads));
    }


    /** Decode the environment-only weather marker. */
    public static float compatibilityWeatherSpeed(float encodedSpeed) {
        if (encodedSpeed <= -1.5f) {
            desiredWeatherCode = 2;
        } else if (encodedSpeed <= -0.5f) {
            desiredWeatherCode = 1;
        } else {
            desiredWeatherCode = 0;
        }
        return encodedSpeed < 0.0f ? 0.0f : encodedSpeed;
    }

    /** Lighting may be paused; weapon and action deadlines must keep advancing. */
    public static void prepareEnvironmentClockPacket(Object packet) {
        try {
            java.lang.reflect.Field epoch = packet.getClass().getField("catch");
            java.lang.reflect.Field speed = packet.getClass().getField("switch");
            environmentEpochMillis = epoch.getLong(packet);
            environmentClockSpeed = compatibilityWeatherSpeed(speed.getFloat(packet));
            environmentReceivedNanos = System.nanoTime();
            epoch.setLong(packet, System.currentTimeMillis());
            speed.setFloat(packet, 1.0f);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("environment_clock_packet", failure);
        }
    }

    /** Apply the global weather enum consumed by the world/environment stack. */
    private static void synchronizeVisualWeather(Object game) {
        Object world = reflectedField(game, "g");
        if (world == null) return;
        try {
            Object worldInfo = invokeNoArgReturning(world, "private", "249504617");
            if (worldInfo == null) {
                throw new IllegalStateException("world_info_unavailable");
            }
            String fieldName = desiredWeatherCode == 2
                    ? "RAIN_THUNDER" : desiredWeatherCode == 1 ? "RAIN" : "CLEAR";
            Object weather = staticField("1719648191", fieldName);
            Object current = reflectedField(worldInfo, "boolean");
            if (weather == null) {
                throw new IllegalStateException("weather_enum_unavailable:" + fieldName);
            }
            if (current != weather) {
                invokeReflected(worldInfo, "this", weather);
            }
            visualWeather = desiredWeatherCode == 2
                    ? "thunder" : desiredWeatherCode == 1 ? "rain" : "clear";
            visualWeatherError = "";
        } catch (Throwable failure) {
            visualWeatherError = failure.getClass().getName() + ": "
                    + String.valueOf(failure.getMessage());
        }
    }

    /**
     * BSCPCRAFT keeps its synchronized wall clock separately from Minecraft's
     * WorldInfo time.  The sky/daytime renderer reads the latter, so a native
     * 637620157 (decoded 149362) clock packet otherwise updates UI clocks while
     * leaving the sun and lighting at the value loaded with the world.
     *
     * Mirror the authoritative clock into the active world on the game thread.
     * The server maps tick 0 to 06:00 UTC. NativeAuthority normally sends
     * speed zero, so the selected lighting state remains fixed.
     */
    private static void synchronizeVisualWorldClock(Object game) {
        Object world = reflectedField(game, "g");
        if (world == null) {
            visualWorldTime = Long.MIN_VALUE;
            return;
        }
        try {
            Object synchronizedAt = staticField("1553688601", "finally");
            if (!(synchronizedAt instanceof Number)
                    || ((Number) synchronizedAt).longValue() <= 0L) return;

            Class<?> clock = Class.forName("1553688601", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Method epochMethod = null;
            for (java.lang.reflect.Method candidate : clock.getDeclaredMethods()) {
                if (candidate.getName().equals("byte")
                        && candidate.getParameterCount() == 0
                        && candidate.getReturnType() == long.class) {
                    epochMethod = candidate;
                    break;
                }
            }
            if (epochMethod == null) {
                throw new IllegalStateException("shared_clock_epoch_getter_unavailable");
            }
            epochMethod.setAccessible(true);
            long epochMillis = environmentReceivedNanos == 0L
                    ? ((Number) epochMethod.invoke(null)).longValue()
                    : environmentEpochMillis + (long) ((System.nanoTime()
                            - environmentReceivedNanos) / 1_000_000.0 * environmentClockSpeed);
            long millisOfDay = Math.floorMod(epochMillis, 86_400_000L);
            long targetDayTime = Math.floorMod(millisOfDay / 3_600L - 6_000L, 24_000L);

            Object currentValue = invokeLongNoArg(world, "if");
            if (!(currentValue instanceof Number)) {
                throw new IllegalStateException("world_time_getter_unavailable");
            }
            long current = ((Number) currentValue).longValue();
            long base = current - Math.floorMod(current, 24_000L);
            long target = base + targetDayTime;
            if (target - current > 12_000L) target -= 24_000L;
            if (current - target > 12_000L) target += 24_000L;

            boolean frozen = environmentReceivedNanos != 0L && environmentClockSpeed == 0.0f;
            // A frozen server clock is exact.  For an explicitly enabled
            // moving clock, retain a two-tick tolerance to avoid rounding
            // oscillation at the 50 ms boundary.
            if (Math.abs(target - current) > (frozen ? 0L : 2L)) {
                invokeReflected(world, "public", Long.valueOf(target));
                current = target;
            }
            visualWorldTime = current;
            visualWorldClockError = "";
        } catch (Throwable failure) {
            visualWorldClockError = failure.getClass().getName() + ": "
                    + String.valueOf(failure.getMessage());
        }
    }


    private static void installControlGlDebugCallback() {
        if (!Boolean.getBoolean("standard.jvm.headless.glDebug")
                || controlGlDebugCallback != null
                || !org.lwjgl.opengl.GL.getCapabilities().OpenGL43) return;
        controlGlDebugCallback = org.lwjgl.opengl.GLDebugMessageCallback.create(
                (source, type, id, severity, length, message, userParam) -> {
                    if (CONTROL_GL_DEBUG_MESSAGES.getAndIncrement() >= 64) return;
                    System.err.println("GL DEBUG source=" + source + " type=" + type
                            + " id=" + id + " severity=" + severity + ": "
                            + org.lwjgl.opengl.GLDebugMessageCallback.getMessage(length, message));
                });
        org.lwjgl.opengl.GL43.glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
        org.lwjgl.opengl.GL43.glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        org.lwjgl.opengl.GL43.glDebugMessageCallback(controlGlDebugCallback, 0L);
    }

    public static void traceGlError(String call) {
        if (!Boolean.getBoolean("standard.jvm.headless.glTrace")
                || org.lwjgl.glfw.GLFW.glfwGetCurrentContext() == 0L) return;
        int error = org.lwjgl.opengl.GL11.glGetError();
        if (error != org.lwjgl.opengl.GL11.GL_NO_ERROR
                && CONTROL_GL_TRACE_MESSAGES.getAndIncrement() < 64) {
            System.err.println("GL TRACE after " + call + ": " + error);
        }
    }

    private static void startHeadlessControlBridge() {
        if (!Boolean.getBoolean("standard.jvm.headless.control")) return;
        int port = Integer.getInteger("standard.jvm.headless.port", 29600);
        Thread serverThread = new Thread(() -> runControlServer(port),
                "Standard JVM headless control");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void runControlServer(int port) {
        try (java.net.ServerSocket server = new java.net.ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            System.err.println("Full-client headless control listening on 127.0.0.1:" + port);
            while (true) {
                try (java.net.Socket socket = server.accept()) {
                    handleControlRequest(socket);
                } catch (Throwable failure) {
                    controlLastError = failure.getClass().getName() + ": " + failure.getMessage();
                }
            }
        } catch (Throwable failure) {
            controlLastError = failure.getClass().getName() + ": " + failure.getMessage();
            failure.printStackTrace(System.err);
        }
    }

    private static void handleControlRequest(java.net.Socket socket) throws java.io.IOException {
        socket.setSoTimeout(5000);
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        String request = reader.readLine();
        if (request == null) return;
        String[] requestParts = request.split(" ", 3);
        String target = requestParts.length > 1 ? requestParts[1] : "/state";
        String path = target;
        String query = "";
        int queryAt = target.indexOf('?');
        if (queryAt >= 0) {
            path = target.substring(0, queryAt);
            query = target.substring(queryAt + 1);
        }
        while (true) {
            String header = reader.readLine();
            if (header == null || header.isEmpty()) break;
        }

        java.util.Map<String, String> parameters = parseControlQuery(query);
        int status = 200;
        String body;
        try {
            if (path.equals("/state")) {
                body = controlState;
            } else if (path.equals("/fp-snapshot")) {
                CONTROL_QUEUE.add(() -> {
                    Object player = reflectedField(controlGame,"e");
                    Object manager = invokeStaticReflected("276406255","implements",player);
                    Object view = componentAt(player,45122);
                    Object context = invokeReflected(view,"volatile");
                    Object handler = reflectedField(context,"synchronized");
                    controlFpSnapshot = "{\"ready\":"+(player!=null)
                            +",\"view\":"+fpDiagnosticObject(view,3,new java.util.IdentityHashMap<>(),new int[]{180})
                            +",\"handler\":"+fpDiagnosticObject(handler,2,new java.util.IdentityHashMap<>(),new int[]{130})
                            +",\"events\":\""+jsonEscape(String.valueOf(invokeStaticReflected("sz.animation.fp.NativeEventTrace","snapshot")))+"\""
                            +",\"model\":"+fpDiagnosticObject(componentAt(player,47930),2,new java.util.IdentityHashMap<>(),new int[]{100})
                            +",\"equipment\":"+fpDiagnosticObject(manager,2,new java.util.IdentityHashMap<>(),new int[]{100})+"}";
                });
                body = queued("fp-snapshot");
            } else if (path.equals("/fp-snapshot-result")) {
                body = controlFpSnapshot;
            } else if (path.equals("/effects-state")) {
                CONTROL_QUEUE.add(() -> controlEffectsSnapshot = gameplayEffectsState());
                body = queued("effects-state");
            } else if (path.equals("/effects-state-result")) {
                body = controlEffectsSnapshot;
            } else if (path.equals("/qa-toggle-game-mode")) {
                CONTROL_QUEUE.add(() -> {
                    // Invoke the production panel action, including its real mode packet.
                    // The isolated smoke login is the only permitted target.
                    if (!controlQaLogin)
                        throw new IllegalStateException("QA mode action requires the smoke login");
                    Object action = staticField("1175021820", "abstract");
                    invokeReflected(action, "if");
                });
                body = queued("qa-toggle-game-mode");
            } else if (path.equals("/input-snapshot")) {
                int key=controlInt(parameters,"key",-1);
                CONTROL_QUEUE.add(() -> {
                    Object player=reflectedField(controlGame,"e");
                    Object input=gameInputManager(controlGame);
                    controlInputSnapshot="{\"actions\":"+inputActionsForKey(input,key)
                            +",\"key\":"+inputKeyState(input,key)
                            +",\"fpReady\":"+jsonScalar(invokeStaticReflected("1378012157","boolean"))
                            +",\"weapon\":"+fpDiagnosticObject(invokeStaticReflected("1810277368","static",player),1,new java.util.IdentityHashMap<>(),new int[]{30})+"}";
                });
                body=queued("input-snapshot");
            } else if (path.equals("/input-snapshot-result")) {
                body=controlInputSnapshot;
            } else if (path.equals("/key")) {
                int key = controlInt(parameters, "key");
                int scancode = controlInt(parameters, "scancode", 0);
                int action = controlInt(parameters, "action", org.lwjgl.glfw.GLFW.GLFW_PRESS);
                int mods = controlInt(parameters, "mods", 0);
                CONTROL_QUEUE.add(() -> invokeKey(key, scancode, action, mods));
                body = queued("key");
            } else if (path.equals("/char")) {
                int codepoint = controlInt(parameters, "codepoint");
                CONTROL_QUEUE.add(() -> invokeChar(codepoint));
                body = queued("char");
            } else if (path.equals("/mouse")) {
                int button = controlInt(parameters, "button");
                int action = controlInt(parameters, "action", org.lwjgl.glfw.GLFW.GLFW_PRESS);
                int mods = controlInt(parameters, "mods", 0);
                CONTROL_QUEUE.add(() -> invokeMouse(button, action, mods));
                body = queued("mouse");
            } else if (path.equals("/cursor")) {
                double x = Double.parseDouble(parameters.getOrDefault("x", "0"));
                double y = Double.parseDouble(parameters.getOrDefault("y", "0"));
                CONTROL_QUEUE.add(() -> invokeCursor(x, y));
                body = queued("cursor");
            } else if (path.equals("/raw-mouse")) {
                int dx = controlInt(parameters, "dx", 0);
                int dy = controlInt(parameters, "dy", 0);
                int button = controlInt(parameters, "button", -1);
                int action = controlInt(parameters, "action", -1);
                double wheelX = Double.parseDouble(
                        parameters.getOrDefault("wheelX", "0"));
                double wheelY = Double.parseDouble(
                        parameters.getOrDefault("wheelY", "0"));
                if (Math.abs((long) dx) > 1_000_000L
                        || Math.abs((long) dy) > 1_000_000L
                        || button < -1 || button > 7
                        || action < -1 || action > 1
                        || !Double.isFinite(wheelX) || !Double.isFinite(wheelY)) {
                    throw new IllegalArgumentException("invalid_raw_mouse_event");
                }
                CONTROL_QUEUE.add(() -> injectRawMouse(
                        dx, dy, button, action, wheelX, wheelY));
                body = queued("raw-mouse");
            } else if (path.equals("/capture")) {
                boolean enabled = controlInt(parameters, "enabled", 1) != 0;
                CONTROL_QUEUE.add(() -> {
                    headlessCaptureRequested = enabled;
                    invokeMouseCapture(enabled);
                });
                body = queued("capture");
            } else if (path.equals("/scroll")) {
                double x = Double.parseDouble(parameters.getOrDefault("x", "0"));
                double y = Double.parseDouble(parameters.getOrDefault("y", "0"));
                CONTROL_QUEUE.add(() -> invokeScroll(x, y));
                body = queued("scroll");
            } else if (path.equals("/connect")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::invokeSelectedCharacterConnect);
                body = queued("connect");
            } else if (path.equals("/select-character")) {
                int index = controlInt(parameters, "index");
                CONTROL_QUEUE.add(() -> invokeCharacterSelection(index));
                body = queued("select-character");
            } else if (path.equals("/admin-tools")) {
                CONTROL_QUEUE.add(() -> invokeStaticReflected("870014315", "implements"));
                body = queued("admin-tools");
            } else if (path.equals("/admin-tools-close")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::closeAdminTools);
                body = queued("admin-tools-close");
            } else if (path.equals("/scene-editor")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::openSceneEditor);
                body = queued("scene-editor");
            } else if (path.equals("/disconnect")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::invokeSessionDisconnect);
                body = queued("disconnect");
            } else if (path.equals("/accept-legal")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::invokeLegalAccept);
                body = queued("accept-legal");
            } else if (path.equals("/screenshot")) {
                CONTROL_QUEUE.add(StandardJvmBootstrap::captureControlScreenshot);
                body = queued("screenshot");
            } else if (path.equals("/dismiss-loading")) {
                CONTROL_QUEUE.add(() -> {
                    Object screen = reflectedField(controlGame, "implements");
                    if (screen == null || !screen.getClass().getName().equals("1030535009")) {
                        throw new IllegalStateException("loading_screen_not_active");
                    }
                    invokeReflectedWithReturnType(screen, "import", void.class);
                });
                body = queued("dismiss-loading");
            } else if (path.equals("/game-object")) {
                body = gameObjectRegistryEntry(controlInt(parameters, "id"));
            } else if (path.equals("/game-object-registry")) {
                body = gameObjectRegistry(controlInt(parameters, "max", 10000));
            } else if (path.equals("/attribute-registry")) {
                body = attributeRegistry();
            } else if (path.equals("/item-network-stack")) {
                body = itemNetworkStack(controlInt(parameters, "id"));
            } else if (path.equals("/chat")) {
                String text = parameters.getOrDefault("text", "");
                if (text.isEmpty() || text.length() > 512) throw new IllegalArgumentException("invalid_chat_text");
                CONTROL_QUEUE.add(() -> {
                    try {
                        Object packet = Class.forName("677917411").getConstructor().newInstance();
                        packet.getClass().getField("continue").set(packet, text);
                        invokeNoArgReturning(packet, "boolean", "void");
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException("native_chat_failed",e); }
                });
                body = queued("chat");
            } else if (path.equals("/storage-request")) {
                CONTROL_QUEUE.add(() -> {
                    try {
                        Object packet = Class.forName("1879366252").getConstructor().newInstance();
                        invokeNoArgReturning(packet, "boolean", "void");
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("native_storage_request_failed", e);
                    }
                });
                body = queued("storage-request");
            } else if (path.equals("/storage-page")) {
                int page = controlInt(parameters, "index");
                if (page < 0 || page >= 15) throw new IllegalArgumentException("invalid_storage_page");
                CONTROL_QUEUE.add(() -> {
                    try {
                        Object packet = Class.forName("1945580517")
                                .getConstructor(int.class).newInstance(page);
                        invokeNoArgReturning(packet, "boolean", "void");
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("native_storage_page_failed", e);
                    }
                });
                body = queued("storage-page");
            } else if (path.equals("/client-hit-probe")) {
                double[] ray = new double[6];
                String[] names = {"x", "y", "z", "tx", "ty", "tz"};
                for (int i=0;i<6;i++) {
                    ray[i]=Double.parseDouble(parameters.getOrDefault(names[i], "0"));
                    if(!Double.isFinite(ray[i]) || Math.abs(ray[i])>30_000_000) throw new IllegalArgumentException("invalid_ray");
                }
                CONTROL_QUEUE.add(() -> {
                    try {
                        Class<?> type=Class.forName("385043600");
                        Object shot=type.getConstructor().newInstance();
                        for(int i=0;i<2;i++) {
                            Object vector=type.getField(i==0?"native":"assert").get(shot);
                            vector.getClass().getMethod("enum",double.class,double.class,double.class)
                                .invoke(vector,ray[i*3],ray[i*3+1],ray[i*3+2]);
                        }
                        // Exercise the production constructor hook and serializer without sending a fabricated shot.
                        Class.forName("1995052022").getConstructor(type).newInstance(shot);
                        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                        type.getMethod("default",java.io.DataOutput.class).invoke(shot,new java.io.DataOutputStream(bytes));
                        byte[] first=bytes.toByteArray();bytes.reset();
                        type.getMethod("default",java.io.DataOutput.class).invoke(shot,new java.io.DataOutputStream(bytes));
                        if(!java.util.Arrays.equals(first,bytes.toByteArray())) throw new IllegalStateException("unstable_hit_retransmission");
                        controlHitProbe=java.util.HexFormat.of().formatHex(first);
                    } catch(Exception e) { controlHitProbe="error:"+e; }
                });
                body=queued("client-hit-probe");
            } else if (path.equals("/client-hit-probe-result")) {
                body="{\"packet\":\""+jsonEscape(controlHitProbe)+"\"}";
            } else if (path.equals("/client-hit-state")) {
                body = "{\"captured\":" + szmods.runtime.ClientHits.captured + ",\"failures\":" + szmods.runtime.ClientHits.failures + ",\"lastError\":\"" + jsonEscape(szmods.runtime.ClientHits.lastError) + "\"}";
            } else if (path.equals("/held-item")) {
                body = currentHeldItemState();
            } else if (path.equals("/block-runtime")) {
                body = blockRuntimeState(
                        controlInt(parameters, "x"), controlInt(parameters, "y"),
                        controlInt(parameters, "z"));
            } else if (path.equals("/builder-left")) {
                int x = controlInt(parameters, "x");
                int y = controlInt(parameters, "y");
                int z = controlInt(parameters, "z");
                int face = controlInt(parameters, "face", 1);
                CONTROL_QUEUE.add(() -> compatibilityDispatchLeftClickBlock(
                        null, x, y, z, face));
                body = queued("builder-left");
            } else if (path.equals("/equipment-state")) {
                body = equipmentState();
            } else if (path.equals("/equipment-set")) {
                int slot = controlInt(parameters, "slot", 5);
                int itemId = controlInt(parameters, "id");
                CONTROL_QUEUE.add(() -> setDiagnosticEquipment(slot, itemId));
                body = queued("equipment-set");
            } else if (path.equals("/packet-registry")) {
                body = packetRegistry(controlInt(parameters, "max", 65535));
            } else if (path.equals("/block-delta")) {
                int x = controlInt(parameters, "x");
                int y = controlInt(parameters, "y");
                int z = controlInt(parameters, "z");
                int blockId = controlInt(parameters, "id");
                int metadata = controlInt(parameters, "metadata", 0);
                if (y < 0 || y > 255 || blockId < 0 || blockId > 0xfff
                        || metadata < 0 || metadata > 0xf) {
                    throw new IllegalArgumentException("invalid_native_block_state");
                }
                CONTROL_QUEUE.add(() -> invokeDiagnosticBlockDelta(
                        x, y, z, blockId, metadata));
                body = queued("block-delta");
            } else if (path.equals("/close")) {
                CONTROL_QUEUE.add(() -> {
                    if (controlWindow != 0L) {
                        org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(controlWindow, true);
                    }
                });
                body = queued("close");
            } else {
                status = 404;
                body = "{\"error\":\"unknown_control_route\"}";
            }
        } catch (Throwable failure) {
            status = 400;
            body = "{\"error\":\"" + jsonEscape(failure.getMessage()) + "\"}";
        }
        byte[] encoded = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + encoded.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(encoded);
        output.flush();
    }

    private static java.util.Map<String, String> parseControlQuery(String query) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (query.isEmpty()) return result;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            result.put(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }

    private static int controlInt(java.util.Map<String, String> values, String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException("missing_" + key);
        return Integer.parseInt(values.get(key));
    }

    private static int controlInt(java.util.Map<String, String> values, String key, int fallback) {
        return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
    }

    private static String queued(String command) {
        return "{\"queued\":true,\"command\":\"" + command + "\"}";
    }

    private static String gameplayEffectsState() {
        try {
            Object player = reflectedField(controlGame, "e");
            if (player == null) return "{\"ready\":false}";
            Object state = componentAt(player, 30326);
            Object movement = componentAt(player, 21233);
            Object component = invokeReflected(player, "try");
            Object effects = invokeReflected(component, "default");
            StringBuilder result = new StringBuilder("{\"ready\":true,\"stats\":");
            result.append(nativeStatBankJson(reflectedField(state, "float")))
                .append(",\"movement\":").append(reflectedPrimitiveFields(reflectedField(movement, "abstract")))
                .append(",\"idev\":").append(invokeReflectedWithReturnType(state, "continue", boolean.class))
                .append(",\"mode\":").append(jsonScalar(String.valueOf(invokeReflected(reflectedField(state, "default"), "throws"))))
                .append(",\"capabilities\":").append(reflectedPrimitiveFields(invokeReflectedWithReturnType(player, "final", Class.forName("769798186"))))
                .append(",\"effects\":[");
            boolean first = true;
            if (effects instanceof java.util.Collection<?>) for (Object effect : (java.util.Collection<?>) effects) {
                if (!first) result.append(',');
                first = false;
                result.append("{\"id\":").append(jsonScalar(reflectedField(effect, "double")))
                    .append(",\"type\":").append(jsonScalar(String.valueOf(reflectedField(effect, "case"))))
                    .append(",\"stats\":").append(nativeStatBankJson(reflectedField(effect, "try"))).append('}');
            }
            return result.append("]}").toString();
        } catch (Exception error) {
            return "{\"error\":\"" + jsonEscape(error.toString()) + "\"}";
        }
    }

    private static String nativeStatBankJson(Object bank) throws Exception {
        if (bank == null) return "{}";
        Class<?> stat = Class.forName("1924679062");
        java.lang.reflect.Method read = bank.getClass().getMethod("volatile", stat);
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Object key : stat.getEnumConstants()) {
            float value = (Float) read.invoke(bank, key);
            if (value == 0f) continue;
            if (!first) result.append(',');
            first = false;
            result.append('"').append(jsonEscape((String)stat.getField("for").get(key)))
                .append("\":").append(value);
        }
        return result.append('}').toString();
    }

    private static void invokeKey(int key, int scancode, int action, int mods) {
        CONTROL_KEYS.put(key, action != org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        long window = requireControlWindow();
        org.lwjgl.glfw.GLFWKeyCallback callback =
                org.lwjgl.glfw.GLFW.glfwSetKeyCallback(window, null);
        if (callback == null) throw new IllegalStateException("key_callback_unavailable");
        controlKeyCallbackDescription = describeGlfwCallback(callback);
        org.lwjgl.glfw.GLFW.glfwSetKeyCallback(window, callback);
        callback.invoke(window, key, scancode, action, mods);
        if (controlWindowHidden && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            invokeHeadlessInputAction(window, key, scancode, action, mods);
        }
    }

    /** State mirror for external mods whose input polling uses glfwGetKey. */
    public static boolean controlKeyDown(int key) {
        return Boolean.TRUE.equals(CONTROL_KEYS.get(key));
    }

    private static void invokeHeadlessInputAction(
            long window, int key, int scancode, int action, int mods) {
        Object inputManager = gameInputManager(controlGame);
        Object actionManager = reflectedField(inputManager, "double");
        if (actionManager == null) return;
        try {
            java.lang.reflect.Field consumed = actionManager.getClass()
                    .getSuperclass().getDeclaredField("interface");
            consumed.setAccessible(true);
            consumed.setBoolean(actionManager, false);
            java.lang.reflect.Method dispatch = actionManager.getClass().getDeclaredMethod(
                    "finally", long.class, int.class, int.class, int.class, int.class);
            dispatch.setAccessible(true);
            dispatch.invoke(actionManager, window, key, scancode, action, mods);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("headless_action_dispatch_unavailable", failure);
        }
    }

    private static void invokeChar(int codepoint) {
        long window = requireControlWindow();
        org.lwjgl.glfw.GLFWCharCallback callback =
                org.lwjgl.glfw.GLFW.glfwSetCharCallback(window, null);
        if (callback == null) throw new IllegalStateException("char_callback_unavailable");
        org.lwjgl.glfw.GLFW.glfwSetCharCallback(window, callback);
        callback.invoke(window, codepoint);
    }

    private static void invokeMouse(int button, int action, int mods) {
        long window = requireControlWindow();
        org.lwjgl.glfw.GLFWMouseButtonCallback callback =
                org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(window, null);
        if (callback == null) throw new IllegalStateException("mouse_callback_unavailable");
        controlMouseCallbackDescription = describeGlfwCallback(callback);
        org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(window, callback);
        callback.invoke(window, button, action, mods);
        if (controlWindowHidden) {
            // Native action dispatch uses -button as its mouse key token.
            // Mirror the same dispatch used by the hidden-window key bridge.
            invokeHeadlessInputAction(window, -button, 0, action, mods);
        }
    }

    private static void invokeCursor(double x, double y) {
        long window = requireControlWindow();
        org.lwjgl.glfw.GLFWCursorPosCallback callback =
                org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window, null);
        if (callback == null) throw new IllegalStateException("cursor_callback_unavailable");
        controlCursorCallbackDescription = describeGlfwCallback(callback);
        org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window, callback);
        // Change GLFW's virtual cursor position itself. Invoking the Java
        // callback alone leaves GLFW at the old coordinates; once the ImGui
        // platform backend also polls events, its real position event cancels
        // the synthetic delta before the camera consumes it.
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window, x, y);
        callback.invoke(window, x, y);
    }

    private static java.util.Collection<?> adminToolScreens() {
        Object manager = staticField("925863226", "void");
        Object screens = reflectedField(manager, "finally");
        return screens instanceof java.util.Collection<?>
                ? (java.util.Collection<?>) screens : java.util.List.of();
    }

    private static Object adminToolsScreen() {
        return collectionClassInstance(adminToolScreens(), "473922574");
    }

    private static void openSceneEditor() {
        if (collectionClassInstance(adminToolScreens(), "1540543037") != null) return;
        try {
            Object game = invokeStaticReflected("1402131154", "try");
            if (game == null) throw new IllegalStateException("game_unavailable");
            Class<?> editorClass = Class.forName("1540543037", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Constructor<?> constructor = editorClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object editor = constructor.newInstance();
            invokeReflected(game, "break", editor);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("scene_editor_open_unavailable", failure);
        }
    }

    private static void closeAdminTools() {
        Object screen = adminToolsScreen();
        if (screen == null) throw new IllegalStateException("admin_tools_not_open");
        Object open = reflectedField(screen, "int");
        if (open == null) throw new IllegalStateException("admin_tools_open_flag_unavailable");
        try {
            java.lang.reflect.Method set = open.getClass().getMethod("set", boolean.class);
            set.invoke(open, false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("admin_tools_close_unavailable", failure);
        }
    }

    private static String classNamesJson(java.util.Collection<?> values) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Object value : values) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(className(value))).append('\"');
        }
        return json.append(']').toString();
    }

    private static String describeGlfwCallback(org.lwjgl.system.Callback callback) {
        StringBuilder description = new StringBuilder(callback.getClass().getName())
                .append('@').append(Long.toUnsignedString(callback.address()));
        for (Class<?> type = callback.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.getName().equals("delegate")) continue;
                try {
                    field.setAccessible(true);
                    Object delegate = field.get(callback);
                    if (delegate != null) {
                        description.append("->").append(delegate.getClass().getName());
                    }
                } catch (Throwable ignored) {
                    description.append("->?");
                }
            }
        }
        return description.toString();
    }

    private static void invokeMouseCapture(boolean enabled) {
        Object game = controlGame;
        if (game == null) throw new IllegalStateException("game_unavailable");
        try {
            // These are the game's complete focus transitions.  Calling only
            // InputManager.extends(boolean) changes GLFW_CURSOR, but skips the
            // game focus flag and screen transition consumed by camera input.
            // Current mappings: extends() = grab/setIngameFocus,
            // import() = release/setIngameNotInFocus.
            java.lang.reflect.Method focus = game.getClass().getDeclaredMethod(
                    enabled ? "extends" : "import");
            focus.setAccessible(true);
            focus.invoke(game);
            if (enabled && Boolean.getBoolean("standard.jvm.headless.hideWindow")
                    && controlWindow != 0L
                    && org.lwjgl.glfw.GLFW.glfwGetInputMode(
                            controlWindow, org.lwjgl.glfw.GLFW.GLFW_CURSOR)
                            != org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED) {
                // A hidden GLFW window cannot consistently own OS focus. The
                // normal game focus method deliberately no-ops in that state,
                // so activate its same three input pieces explicitly for the
                // controllable full-client harness.
                invokeBooleanSetter(game, "abstract", true);
                Object inputManager = gameInputManager(game);
                invokeBooleanSetter(inputManager, "extends", true);
                invokeBooleanSetter(reflectedField(inputManager, "if"), "assert", true);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("game_focus_method_unavailable", failure);
        }
    }

    private static void invokeBooleanSetter(Object owner, String name, boolean value)
            throws ReflectiveOperationException {
        if (owner == null) throw new IllegalStateException("boolean_setter_owner_unavailable:" + name);
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != boolean.class
                        || method.getReturnType() != void.class) continue;
                method.setAccessible(true);
                method.invoke(owner, value);
                return;
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name + "(boolean)");
    }

    private static String attributeRegistry() {
        try {
            Class<?> registryClass = Class.forName("1993742576", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field instanceField = registryClass.getDeclaredField("it");
            instanceField.setAccessible(true);
            Object registry = instanceField.get(null);
            if (registry == null) return "{\"ready\":false,\"error\":\"registry_unavailable\"}";
            Class<?> attributeClass = Class.forName("1961828072", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.util.List<Object[]> entries = new java.util.ArrayList<>();
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (java.lang.reflect.Field field : registryClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(registry);
                if (attributeClass.isInstance(value) && seen.add(value)) {
                    entries.add(new Object[] {field.getName(), value});
                } else if (value != null && value.getClass().isArray()
                        && value.getClass().getComponentType() == attributeClass) {
                    int length = java.lang.reflect.Array.getLength(value);
                    for (int index = 0; index < length; index++) {
                        Object attribute = java.lang.reflect.Array.get(value, index);
                        if (attribute != null && seen.add(attribute)) {
                            entries.add(new Object[] {field.getName() + '[' + index + ']', attribute});
                        }
                    }
                }
            }
            java.lang.reflect.Field idField = attributeClass.getDeclaredField("while");
            java.lang.reflect.Field nameField = attributeClass.getDeclaredField("break");
            idField.setAccessible(true);
            nameField.setAccessible(true);
            entries.sort(java.util.Comparator.comparingInt(entry ->
                    ((Number) getFieldValue(idField, entry[1])).intValue()));
            StringBuilder json = new StringBuilder("{\"ready\":true,\"entries\":[");
            boolean first = true;
            for (Object[] entry : entries) {
                Object attribute = entry[1];
                if (!first) json.append(',');
                first = false;
                json.append("{\"field\":\"").append(jsonEscape(String.valueOf(entry[0])))
                        .append("\",\"name\":\"")
                        .append(jsonEscape(String.valueOf(nameField.get(attribute))))
                        .append("\",\"id\":").append(((Number) idField.get(attribute)).intValue())
                        .append('}');
            }
            return json.append("]}").toString();
        } catch (Throwable failure) {
            return "{\"ready\":false,\"error\":\""
                    + jsonEscape(failure.getClass().getName() + ": " + failure.getMessage()) + "\"}";
        }
    }

    private static String itemNetworkStack(int itemId) {
        try {
            if (itemId < 0 || itemId >= 32000) {
                throw new IllegalArgumentException("item_id_out_of_range");
            }
            ClassLoader loader = StandardJvmBootstrap.class.getClassLoader();
            Class<?> factoryClass = Class.forName("1366800141", true, loader);
            java.lang.reflect.Field factoriesField = factoryClass.getDeclaredField("import");
            factoriesField.setAccessible(true);
            Object factories = factoriesField.get(null);
            Object factory = java.lang.reflect.Array.get(factories, itemId);
            if (factory == null) {
                return "{\"ready\":false,\"id\":" + itemId
                        + ",\"error\":\"factory_unavailable\"}";
            }

            Class<?> stackClass = Class.forName("211097523", true, loader);
            java.lang.reflect.Constructor<?> stackConstructor =
                    stackClass.getDeclaredConstructor(factoryClass);
            stackConstructor.setAccessible(true);
            Object stack = stackConstructor.newInstance(factory);

            Class<?> networkStackClass = Class.forName("1160390453", true, loader);
            java.lang.reflect.Method serialize =
                    networkStackClass.getDeclaredMethod("this", stackClass);
            serialize.setAccessible(true);
            Object networkStack = serialize.invoke(null, stack);
            if (networkStack == null) {
                return "{\"ready\":false,\"id\":" + itemId
                        + ",\"error\":\"serialization_failed\"}";
            }
            java.lang.reflect.Field bytesField = networkStackClass.getDeclaredField("assert");
            bytesField.setAccessible(true);
            byte[] encoded = (byte[]) bytesField.get(networkStack);
            StringBuilder hex = new StringBuilder(encoded.length * 2);
            for (byte value : encoded) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return "{\"ready\":true,\"id\":" + itemId
                    + ",\"factoryClass\":\""
                    + jsonEscape(factory.getClass().getName())
                    + "\",\"bytes\":" + encoded.length
                    + ",\"hex\":\"" + hex + "\"}";
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "{\"ready\":false,\"id\":" + itemId + ",\"error\":\""
                    + jsonEscape(root.getClass().getName() + ": " + root.getMessage())
                    + "\"}";
        }
    }

    private static Object newItemStack(int itemId) throws ReflectiveOperationException {
        if (itemId < 0 || itemId >= 32000) {
            throw new IllegalArgumentException("item_id_out_of_range");
        }
        ClassLoader loader = StandardJvmBootstrap.class.getClassLoader();
        Class<?> factoryClass = Class.forName("1366800141", true, loader);
        java.lang.reflect.Field factoriesField = factoryClass.getDeclaredField("import");
        factoriesField.setAccessible(true);
        Object factory = java.lang.reflect.Array.get(factoriesField.get(null), itemId);
        if (factory == null) throw new IllegalArgumentException("factory_unavailable:" + itemId);
        Class<?> stackClass = Class.forName("211097523", true, loader);
        java.lang.reflect.Constructor<?> constructor = stackClass.getDeclaredConstructor(factoryClass);
        constructor.setAccessible(true);
        return constructor.newInstance(factory);
    }

    /**
     * Completes the replicated-attribute duplicate-registration path.
     *
     * Remote entities create their attribute table before gameplay components.
     * The original manager returns an existing attribute immediately and skips
     * the callback map update.  Components such as equipment then hold the right
     * attribute object, but never observe its subsequent network changes.
     */
    public static void compatibilityAttachReplicatedAttributeCallback(
            Object manager, Object attribute, Object callback) {
        if (manager == null || attribute == null || callback == null) return;
        Object callbacks = reflectedField(manager, "native");
        if (!(callbacks instanceof java.util.Map<?, ?>)) return;
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Object> callbackMap =
                (java.util.Map<Object, Object>) callbacks;
        Object previous = callbackMap.get(attribute);
        boolean equipmentCallback = callback.getClass().getName().equals("1668155752");
        @SuppressWarnings("unchecked")
        java.util.function.BiConsumer<Object, Object> addedCallback =
                (java.util.function.BiConsumer<Object, Object>) callback;
        java.util.function.BiConsumer<Object, Object> effectiveCallback = addedCallback;

        if (equipmentCallback) {
            Object controller = reflectedField(callback, "private");
            Object slotValue = reflectedField(callback, "enum");
            if (controller != null && slotValue instanceof Number) {
                int slot = ((Number) slotValue).intValue();
                // The stock callback only posts a visual equipment event.  A
                // late remote binding can miss that event before its model is
                // ready, leaving the controller's aggregate empty forever.
                // Mirror the same network stack into the aggregate first; its
                // native container notifications then build/update the model.
                effectiveCallback = (oldValue, newValue) -> {
                    Object aggregateValue = compatibilityEquipmentAggregateValue(newValue);
                    invokeStaticReflected("276406255", "short", controller,
                            Integer.valueOf(slot), aggregateValue);
                    addedCallback.accept(oldValue, newValue);
                };
            }
        }

        // A replicated attribute can already have a transport/component
        // listener by the time a lazy gameplay component asks for it.  The
        // old putIfAbsent path preserved that listener but silently discarded
        // the new one, which is exactly what left remote equipment attributes
        // populated while their visual aggregate stayed empty.  Preserve both
        // callbacks in registration order.
        if (previous instanceof java.util.function.BiConsumer<?, ?>
                && !previous.getClass().getName().equals("1668155752")) {
            @SuppressWarnings("unchecked")
            java.util.function.BiConsumer<Object, Object> previousCallback =
                    (java.util.function.BiConsumer<Object, Object>) previous;
            java.util.function.BiConsumer<Object, Object> finalEffectiveCallback =
                    effectiveCallback;
            callbackMap.put(attribute,
                    (java.util.function.BiConsumer<Object, Object>) (oldValue, newValue) -> {
                        previousCallback.accept(oldValue, newValue);
                        finalEffectiveCallback.accept(oldValue, newValue);
                    });
        } else {
            callbackMap.put(attribute, effectiveCallback);
        }

        if (!equipmentCallback) return;

        // Remote spawn and its first attribute delta can be consumed in one
        // network turn, before rendering asks for the lazily-created equipment
        // controller.  Replay only the equipment network stack that predates
        // this newly attached listener.  The listener then follows the exact
        // same aggregate/container event path as every later network update.
        Object current = reflectedField(attribute, "do");
        if (current == null || !current.getClass().getName().equals("1160390453")) return;
        effectiveCallback.accept(null, current);
    }

    /** Convert a transport-owned stack into the native visual-container form. */
    private static Object compatibilityEquipmentAggregateValue(Object networkValue) {
        Object stack = reflectedField(networkValue, "switch");
        if (stack == null) return networkValue;
        Object copied = invokeStaticReflected("1160390453", "this", stack);
        return copied != null ? copied : networkValue;
    }

    /**
     * Repairs late equipment bindings for a remote player exactly once, while
     * the equipment controller is being constructed.
     */
    public static void compatibilityFinalizeRemoteEquipmentBindings(Object controller) {
        if (controller == null) return;
        Object component = reflectedField(controller, "interface");
        Object entity = reflectedField(component, "int");
        if (entity == null || !entity.getClass().getName().equals("1073028351")) return;
        Object bindings = reflectedField(controller, "protected");
        if (!(bindings instanceof Iterable<?>)) return;

        for (Object binding : (Iterable<?>) bindings) {
            Object indexValue = reflectedField(binding, "assert");
            Object attribute = reflectedField(binding, "int");
            if (!(indexValue instanceof Number) || attribute == null) continue;
            int index = ((Number) indexValue).intValue();
            Object attributeManager = reflectedField(attribute, "interface");
            Object callbacks = reflectedField(attributeManager, "native");
            if (callbacks instanceof java.util.Map<?, ?>) {
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> callbackMap =
                        (java.util.Map<Object, Object>) callbacks;
                Object existing = callbackMap.get(attribute);
                if (existing == null
                        || !existing.getClass().getName().equals("1668155752")) {
                    java.util.function.BiConsumer<Object, Object> equipmentListener =
                            (oldValue, newValue) -> invokeStaticReflected(
                                    "276406255", "short", controller,
                                    Integer.valueOf(index), oldValue, newValue);
                    if (existing instanceof java.util.function.BiConsumer<?, ?>) {
                        @SuppressWarnings("unchecked")
                        java.util.function.BiConsumer<Object, Object> previous =
                                (java.util.function.BiConsumer<Object, Object>) existing;
                        callbackMap.put(attribute,
                                (java.util.function.BiConsumer<Object, Object>) (oldValue, newValue) -> {
                                    previous.accept(oldValue, newValue);
                                    equipmentListener.accept(oldValue, newValue);
                                });
                    } else {
                        callbackMap.put(attribute, equipmentListener);
                    }
                }
            }

            Object current = reflectedField(attribute, "do");
            if (current != null && current.getClass().getName().equals("1160390453")) {
                invokeStaticReflected("276406255", "short", controller,
                        Integer.valueOf(index), current);
            }
        }
    }

    private static void setDiagnosticEquipment(int slot, int itemId) {
        try {
            if (slot < 0 || slot > 11) throw new IllegalArgumentException("equipment_slot_out_of_range");
            Object game = invokeStaticReflected("1402131154", "try");
            Object player = reflectedField(game, "e");
            if (player == null) throw new IllegalStateException("local_player_unavailable");
            Object manager = invokeStaticReflected("276406255", "implements", player);
            Object aggregate = reflectedField(manager, "class");
            Object containers = reflectedField(aggregate, "assert");
            if (!(containers instanceof java.util.Map<?, ?>)) {
                throw new IllegalStateException("equipment_containers_unavailable");
            }
            Object target = null;
            for (Object container : ((java.util.Map<?, ?>) containers).values()) {
                Object containerId = invokeReflected(container, "protected");
                if (containerId instanceof Number
                        && ((Number) containerId).intValue() == slot) {
                    target = container;
                    break;
                }
            }
            if (target == null) throw new IllegalStateException("equipment_container_unavailable:" + slot);
            invokeReflected(
                    target,
                    "abstract",
                    Integer.valueOf(0),
                    newItemStack(itemId),
                    Boolean.TRUE);
            Object installed = invokeReflected(target, "goto", Integer.valueOf(0));
            if (installed == null) {
                throw new IllegalStateException("equipment_container_rejected_item:" + itemId);
            }
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastError = "equipment-set: " + root.getClass().getName()
                    + ": " + root.getMessage();
        }
    }

    private static String equipmentState() {
        try {
            Object game = invokeStaticReflected("1402131154", "try");
            Object player = reflectedField(game, "e");
            if (player == null) {
                return "{\"ready\":false,\"reason\":\"local_player_unavailable\"}";
            }
            Object manager = invokeStaticReflected("276406255", "implements", player);
            if (manager == null) {
                return "{\"ready\":false,\"reason\":\"equipment_manager_unavailable\"}";
            }
            StringBuilder result = new StringBuilder("{\"ready\":true,\"player\":\"")
                    .append(jsonEscape(className(player))).append("\",\"manager\":\"")
                    .append(jsonEscape(className(manager))).append("\",\"attributes\":[");
            Object equipmentInventory = reflectedField(manager, "if");
            Object generalInventory = invokeStaticReflected("1045883430", "import", player);
            Object alternateInventory = invokeStaticReflected("1045883430", "else", player);
            Object registrations = reflectedField(manager, "protected");
            boolean first = true;
            if (registrations instanceof Iterable<?>) {
                for (Object registration : (Iterable<?>) registrations) {
                    if (!first) result.append(',');
                    first = false;
                    Object index = reflectedField(registration, "assert");
                    Object attribute = reflectedField(registration, "int");
                    Object networkStack = reflectedField(attribute, "do");
                    Object stack = reflectedField(networkStack, "switch");
                    Object bytes = reflectedField(networkStack, "assert");
                    Object itemId = invokeIntNoArg(stack, "continue");
                    result.append("{\"index\":").append(jsonScalar(index))
                            .append(",\"attribute\":\"")
                            .append(jsonEscape(className(attribute))).append("\"")
                            .append(",\"networkStack\":\"")
                            .append(jsonEscape(className(networkStack))).append("\"")
                            .append(",\"itemId\":").append(jsonScalar(itemId))
                            .append(",\"hex\":\"")
                            .append(bytes instanceof byte[] ? bytesHex((byte[]) bytes) : "")
                            .append("\"}");
                }
            }
            result.append("],\"equipmentAggregate\":")
                    .append(inventoryAggregateState(reflectedField(manager, "class")))
                    .append(",\"secondaryAggregate\":")
                    .append(inventoryAggregateState(reflectedField(manager, "volatile")))
                    .append(",\"inventoryIds\":{\"equipment\":")
                    .append(jsonScalar(equipmentInventory == null ? null
                            : invokeReflected(equipmentInventory, "public")))
                    .append(",\"general\":")
                    .append(jsonScalar(generalInventory == null ? null
                            : invokeReflected(generalInventory, "public")))
                    .append(",\"alternate\":")
                    .append(jsonScalar(alternateInventory == null ? null
                            : invokeReflected(alternateInventory, "public")))
                    .append('}')
                    .append(",\"modelState\":")
                    .append(reflectedNestedPrimitiveFields(componentAt(player, 47930)))
                    .append('}');
            return result.toString();
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return "{\"ready\":false,\"error\":\""
                    + jsonEscape(root.getClass().getName() + ": " + root.getMessage()) + "\"}";
        }
    }

    private static String inventoryAggregateState(Object aggregate) {
        if (aggregate == null) return "null";
        Object containers = reflectedField(aggregate, "assert");
        StringBuilder json = new StringBuilder("{\"class\":\"")
                .append(jsonEscape(className(aggregate))).append("\",\"containers\":[");
        boolean firstContainer = true;
        if (containers instanceof java.util.Map<?, ?>) {
            for (java.util.Map.Entry<?, ?> entry
                    : ((java.util.Map<?, ?>) containers).entrySet()) {
                Object container = entry.getValue();
                if (!firstContainer) json.append(',');
                firstContainer = false;
                Object containerId = invokeReflected(container, "protected");
                Object items = invokeReflected(container, "assert");
                json.append("{\"name\":\"")
                        .append(jsonEscape(String.valueOf(entry.getKey())))
                        .append("\",\"id\":").append(jsonScalar(containerId))
                        .append(",\"class\":\"")
                        .append(jsonEscape(className(container))).append("\",\"items\":[");
                boolean firstItem = true;
                if (items instanceof java.util.Map<?, ?>) {
                    for (java.util.Map.Entry<?, ?> item
                            : ((java.util.Map<?, ?>) items).entrySet()) {
                        if (!firstItem) json.append(',');
                        firstItem = false;
                        Object stack = item.getValue();
                        Object itemId = invokeIntNoArg(stack, "continue");
                        json.append("{\"slot\":").append(jsonScalar(item.getKey()))
                                .append(",\"itemId\":").append(jsonScalar(itemId))
                                .append("}");
                    }
                }
                json.append("]}");
            }
        }
        return json.append("]}").toString();
    }

    private static String bytesHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0xf];
        }
        return new String(output);
    }

    private static Object getFieldValue(java.lang.reflect.Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * The protected launcher delivered a synthetic focus transition while the
     * world screen was installed. Stock GLFW does not do that when the existing
     * window already owns focus, leaving the cursor in NORMAL and making camera
     * input inert. Re-enter the game's own capture path once for each new world;
     * later menu/focus transitions remain entirely owned by the game.
     */
    private static void restoreWorldMouseCapture(Object game, long window) {
        if (window == 0L) return;
        Object world = reflectedField(game, "g");
        if (world == null) {
            mouseCaptureWorld = null;
            return;
        }
        if (nativeUiOwnsMouse()) return;
        boolean hiddenControl = Boolean.getBoolean("standard.jvm.headless.hideWindow");
        boolean hiddenCaptureLost = hiddenControl
                && (org.lwjgl.glfw.GLFW.glfwGetInputMode(
                        window, org.lwjgl.glfw.GLFW.GLFW_CURSOR)
                        != org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED
                    || !Boolean.TRUE.equals(invokeBooleanNoArg(game, "this")));
        if (world == mouseCaptureWorld && !hiddenCaptureLost
                || reflectedField(game, "implements") != null
                || reflectedField(game, "volatile") != null
                || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
                        window, org.lwjgl.glfw.GLFW.GLFW_FOCUSED) == 0 && !hiddenControl) return;
        invokeMouseCapture(true);
        mouseCaptureWorld = world;
    }

    private static boolean nativeUiOwnsMouse() {
        try {
            Object ui=Class.forName("925863226").getField("void").get(null);
            return Boolean.TRUE.equals(invokeBooleanNoArg(ui,"for"));
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private static void invokeScroll(double x, double y) {
        long window = requireControlWindow();
        org.lwjgl.glfw.GLFWScrollCallback callback =
                org.lwjgl.glfw.GLFW.glfwSetScrollCallback(window, null);
        if (callback == null) throw new IllegalStateException("scroll_callback_unavailable");
        org.lwjgl.glfw.GLFW.glfwSetScrollCallback(window, callback);
        callback.invoke(window, x, y);
    }

    private static long requireControlWindow() {
        long window = controlWindow;
        if (window == 0L) throw new IllegalStateException("glfw_window_unavailable");
        return window;
    }

    private static void invokeSelectedCharacterConnect() {
        Object screen = reflectedField(controlGame, "implements");
        if (screen == null || !screen.getClass().getName().equals("1790054239")) {
            throw new IllegalStateException("character_select_not_active");
        }
        Object controller = reflectedField(screen, "short");
        if (controller == null) throw new IllegalStateException("character_controller_unavailable");
        invokeReflectedWithReturnType(controller, "transient", void.class);
    }

    private static void invokeCharacterSelection(int index) {
        Object screen = reflectedField(controlGame, "implements");
        if (screen == null || !screen.getClass().getName().equals("1790054239")) {
            throw new IllegalStateException("character_select_not_active");
        }
        Object controller = reflectedField(screen, "short");
        if (controller == null) throw new IllegalStateException("character_controller_unavailable");
        Object available = invokeReflectedWithReturnType(controller, "double", java.util.List.class);
        if (!(available instanceof java.util.List<?> characters)) {
            throw new IllegalStateException("character_list_unavailable");
        }
        if (index < 0 || index >= characters.size()) {
            throw new IllegalArgumentException("character_index_out_of_range:" + index
                    + "/" + characters.size());
        }
        invokeReflected(controller, "transient", characters.get(index));
    }

    private static void invokeLegalAccept() {
        Object screen = reflectedField(controlGame, "implements");
        if (screen == null || !screen.getClass().getName().equals("1572081161")) {
            throw new IllegalStateException("legal_screen_not_active");
        }
        invokeReflected(screen, "class");
    }

    private static void invokeSessionDisconnect() {
        Object game = controlGame;
        Object world = reflectedField(game, "g");
        Object session = reflectedField(world, "byte");
        if (session == null) throw new IllegalStateException("session_unavailable");
        // Same teardown path used after the transport reports a disconnect.
        invokeReflected(session, "else");
    }

    private static void restoreCurrentFrontendAfterDisconnect(Object game) {
        Object screen = reflectedField(game, "implements");
        String screenClass = className(screen);
        if (screenClass.equals("1790054239")) {
            Object controller = reflectedField(screen, "short");
            if (controller != null) currentCharacterController = controller;
            replacingLegacyFrontend = false;
            return;
        }
        if ((!screenClass.equals("1577484820") && !screenClass.equals("1030535009"))
                || currentCharacterController == null
                || replacingLegacyFrontend || reflectedField(game, "g") != null) return;
        replacingLegacyFrontend = true;
        try {
            Class<?> screenType = Class.forName("1790054239", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Constructor<?> constructor = screenType.getDeclaredConstructor(
                    currentCharacterController.getClass());
            constructor.setAccessible(true);
            Object currentScreen = constructor.newInstance(currentCharacterController);
            invokeReflected(game, "else", currentScreen);
        } catch (Throwable failure) {
            replacingLegacyFrontend = false;
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastError = "current frontend restore: " + root.getClass().getName()
                    + ": " + root.getMessage();
        }
    }

    public static void ensureCurrentPlayerComponents(Object game) {
        try {
            ensureStaticSingleton("94534399", "public");
            ensureStaticSingleton("1592843593", "native");
            ensureStaticSingleton("648442220", "switch");
            ensureWorldRenderer(game);
            Object player = reflectedField(game, "e");
            if (player != null) {
                ensurePlayerArchetype(player);
                enableExtendedMovementPackets(player);
            }
            Object world = reflectedField(game, "g");
            if (world != null) {
                Object entities = invokeReflected(world, "continue");
                Class<?> playerClass = Class.forName("200241649", true,
                        StandardJvmBootstrap.class.getClassLoader());
                if (entities instanceof Iterable<?>) {
                    for (Object entity : (Iterable<?>) entities) {
                        if (playerClass.isInstance(entity) && entity != player) {
                            ensurePlayerArchetype(entity);
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            Throwable root = failure;
            while ((root instanceof java.lang.reflect.InvocationTargetException
                    || root instanceof RuntimeException) && root.getCause() != null) {
                root = root.getCause();
            }
            String detail = "component bootstrap: " + root.getClass().getName()
                    + ": " + root.getMessage();
            controlLastError = detail;
            if (!detail.equals(componentBootstrapError)) {
                componentBootstrapError = detail;
                System.err.println(detail);
                root.printStackTrace(System.err);
            }
        }
    }

    /**
     * The stock-JVM startup reaches the game loop with two built-in ECS
     * listeners absent from the global event bus even though their owning
     * modules are loaded. Register the original listener objects once, before
     * a server can create the local player. Entity construction then uses the
     * game's normal 1515768082 registry event and component factories.
     */
    private static void ensureCoreEcsListeners() {
        if (CORE_ECS_LISTENERS_READY.get()) return;
        try {
            Object bus = staticField("185710526", "do");
            Object listeners = reflectedField(bus, "double");
            Object actorModule = collectionClassInstance(
                    staticField("1852924122", "float"), "812639286");
            if (bus == null || actorModule == null) return;
            if (!mapContainsClass(listeners, "812639286")) {
                invokeReflected(bus, "interface", actorModule);
            }
            if (!mapContainsClass(listeners, "1674813597")) {
                Class<?> listenerType = Class.forName("1674813597", true,
                        StandardJvmBootstrap.class.getClassLoader());
                corePlayerEcsListener = listenerType.getDeclaredConstructor().newInstance();
                invokeReflected(bus, "interface", corePlayerEcsListener);
            }
            if (mapContainsClass(listeners, "812639286")
                    && mapContainsClass(listeners, "1674813597")) {
                CORE_ECS_LISTENERS_READY.set(true);
                System.err.println("Restored built-in player ECS listener registration");
            }
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String detail = "ECS listener registration: " + root.getClass().getName()
                    + ": " + root.getMessage();
            controlLastError = detail;
            if (!detail.equals(componentBootstrapError)) {
                componentBootstrapError = detail;
                System.err.println(detail);
                root.printStackTrace(System.err);
            }
        }
    }

    private static void ensurePlayerArchetype(Object player) throws ReflectiveOperationException {
            ensurePlayerExtension(player, componentKey(7822), "1761650403");
            Object registry = invokeReflected(player, "byte");
            if (registry == null || !registry.getClass().getName().equals("358035993")) return;
            ensureComponent(registry, componentKey(21233), "1704556748");
            ensureComponent(registry, componentKey(27759), "1556194388");
            ensureComponent(registry, componentKey(47930), "254941120");
            ensureComponent(registry, componentKey(9355), "1268372086");
            ensureComponent(registry, componentKey(29607), "1936107049");
            ensureComponent(registry, componentKey(18927), "208724657");
            ensureComponent(registry, componentKey(46917), "1496692541");
            ensureComponent(registry, componentKey(42550), "305402078");
            ensureComponent(registry, componentKey(36884), "1189586720");
            ensureComponent(registry, componentKey(16929), "197821467");
            ensureComponent(registry, componentKey(34049), "656672376");
            ensureComponent(registry, componentKey(28225), "967553190");
            ensureComponent(registry, componentKey(21684), "194283087");
            ensureComponent(registry, componentKey(42094), "596586228");
            ensureComponent(registry, componentKey(4995), "1448531254");
            ensureComponent(registry, componentKey(54), "389547743");
            ensureComponent(registry, componentKey(3903), "1810277368");
            ensureComponent(registry, componentKey(10131), "529371315");
            ensureComponent(registry, componentKey(31443), "1709316098");
            ensureComponent(registry, componentKey(26556), "1579323378");
            ensureComponent(registry, componentKey(47744), "1457997816");
            ensureComponent(registry, componentKey(30326), "1409908300");
            ensureComponent(registry, componentKey(45122), "1638940636");
            // Pause/escape UI state. The current menu pipeline dereferences
            // this component immediately after installing its blur layer.
            ensureComponent(registry, componentKey(848), "657703534");
    }

    /**
     * Select the game's native extended input packet (current class
     * 1316834138, runtime ID 1978) for the local player. The retail server
     * controls this through the hq/42094 replicated Instant. Keeping that
     * deadline active makes the unmodified movement sender prepend its full
     * 1943200621 snapshot, including native velocity and locomotion state.
     */
    private static void enableExtendedMovementPackets(Object player)
            throws ReflectiveOperationException {
        if (!Boolean.parseBoolean(System.getProperty(
                "standard.jvm.extendedMovement", "true"))) return;
        Object movementOptions = componentAt(player, 42094);
        if (movementOptions == null) return;
        Object deadline = reflectedField(movementOptions, "char");
        if (deadline == null) return;
        invokeReflected(deadline, "break", java.time.Instant.MAX);
        if (EXTENDED_MOVEMENT_ENABLED.compareAndSet(false, true)) {
            System.err.println("Extended multiplayer movement packets enabled (runtime ID 1978)");
        }
    }

    private static void ensureWorldRenderer(Object game) throws ReflectiveOperationException {
        Object world = reflectedField(game, "g");
        if (world == null) return;
        Class<?> managerClass = Class.forName("680154985", true,
                StandardJvmBootstrap.class.getClassLoader());
        java.lang.reflect.Field managerField = managerClass.getDeclaredField("this");
        managerField.setAccessible(true);
        Object manager = managerField.get(null);
        if (manager == null) return;
        if (invokeReflected(manager, "default") == null) {
            invokeReflected(manager, "for", world);
        }
    }

    private static void ensurePlayerExtension(Object player, String key, String className)
            throws ReflectiveOperationException {
        Object existing = invokeReflected(player, "float", key);
        if (existing != null) return;
        Class<?> extensionClass = Class.forName(className, true,
                StandardJvmBootstrap.class.getClassLoader());
        java.lang.reflect.Constructor<?> constructor = null;
        for (java.lang.reflect.Constructor<?> candidate : extensionClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) throw new NoSuchMethodException(className + "(player)");
        constructor.setAccessible(true);
        Object extension = constructor.newInstance(player);
        invokeReflected(player, "throws", key, extension);
    }

    private static void ensureStaticSingleton(String className, String fieldName)
            throws ReflectiveOperationException {
        Class<?> singletonClass = Class.forName(className, true,
                StandardJvmBootstrap.class.getClassLoader());
        java.lang.reflect.Field singletonField = singletonClass.getDeclaredField(fieldName);
        singletonField.setAccessible(true);
        if (singletonField.get(null) != null) return;
        java.lang.reflect.Constructor<?> constructor = singletonField.getType().getDeclaredConstructor();
        constructor.setAccessible(true);
        singletonField.set(null, constructor.newInstance());
    }

    private static String componentKey(int index) throws ReflectiveOperationException {
        String cached = COMPONENT_KEYS.get(index);
        if (cached != null) return cached;
        Class<?> stringsClass = Class.forName("1281655698", true,
                StandardJvmBootstrap.class.getClassLoader());
        java.lang.reflect.Field stringsField = stringsClass.getDeclaredField("if");
        stringsField.setAccessible(true);
        String key = ((String[]) stringsField.get(null))[index];
        String existing = COMPONENT_KEYS.putIfAbsent(index, key);
        return existing == null ? key : existing;
    }

    private static Object componentAt(Object player, int keyIndex) {
        if (player == null) return null;
        try {
            Object registry = invokeReflected(player, "byte");
            if (registry == null) return null;
            return invokeReflected(registry, "package", componentKey(keyIndex));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureComponent(Object registry, String key, String className)
            throws ReflectiveOperationException {
        Object existing = invokeReflected(registry, "package", key);
        if (existing != null) return;
        Object component = takePendingComponent(registry, key);
        if (component == null) component = createComponent(registry, className);
        invokeReflected(registry, "final", key, component);
    }

    private static Object createComponent(Object registry, String className)
            throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName(className, true,
                StandardJvmBootstrap.class.getClassLoader());
        java.lang.reflect.Constructor<?> constructor = null;
        for (java.lang.reflect.Constructor<?> candidate : componentClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(registry)) {
                constructor = candidate;
                break;
            }
        }
        Object component;
        if (constructor != null) {
            constructor.setAccessible(true);
            component = constructor.newInstance(registry);
        } else if (className.equals("1579323378")) {
            // Kotlin default constructor: (registry, model type, mask, marker).
            constructor = componentClass.getDeclaredConstructor(
                    registry.getClass(), Class.forName("947953390"), int.class,
                    Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"));
            constructor.setAccessible(true);
            component = constructor.newInstance(registry, null, 2, null);
        } else {
            throw new NoSuchMethodException(className + "(358035993)");
        }
        return component;
    }

    private static Object takePendingComponent(Object registry, String key) {
        synchronized (PENDING_COMPONENTS) {
            java.util.Map<String, Object> pending = PENDING_COMPONENTS.get(registry);
            if (pending == null) return null;
            Object component = pending.remove(key);
            if (pending.isEmpty()) PENDING_COMPONENTS.remove(registry);
            return component;
        }
    }

    private static Object pendingComponent(Object registry, String key, String className)
            throws ReflectiveOperationException {
        synchronized (PENDING_COMPONENTS) {
            java.util.Map<String, Object> pending = PENDING_COMPONENTS.computeIfAbsent(
                    registry, ignored -> new java.util.HashMap<>());
            Object component = pending.get(key);
            if (component != null) return component;
            component = createComponent(registry, className);
            pending.put(key, component);
            return component;
        }
    }

    public static Object ensureRequiredEntityComponent(Object existing, Object entity,
            int keyIndex, String className) {
        if (existing != null || entity == null) return existing;
        try {
            Object registry = invokeReflected(entity, "byte");
            if (registry == null || !registry.getClass().getName().equals("358035993")) {
                return null;
            }
            String key = componentKey(keyIndex);
            // This path can run from inside the registry's own update loop.
            // Return a stable component now and let the frame bootstrap install
            // it before the next ECS iteration to avoid mutating HashMap while
            // its callbacks are being traversed.
            return pendingComponent(registry, key, className);
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastError = "lazy component " + className + ": "
                    + root.getClass().getName() + ": " + root.getMessage();
            return null;
        }
    }

    public static Object ensureRequiredPlayerExtension(Object existing, Object player,
            int keyIndex, String className) {
        if (existing != null || player == null) return existing;
        try {
            String key = componentKey(keyIndex);
            ensurePlayerExtension(player, key, className);
            return invokeReflected(player, "float", key);
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastError = "lazy extension " + className + ": "
                    + root.getClass().getName() + ": " + root.getMessage();
            return null;
        }
    }

    public static Object ensureRequiredWorldRenderer(Object existing, Object manager) {
        if (existing != null || manager == null) return existing;
        try {
            Object game = controlGame;
            if (game == null) {
                Class<?> gameClass = Class.forName("1402131154", true,
                        StandardJvmBootstrap.class.getClassLoader());
                java.lang.reflect.Method current = gameClass.getDeclaredMethod("try");
                current.setAccessible(true);
                game = current.invoke(null);
            }
            Object world = reflectedField(game, "g");
            if (world == null) return null;
            Class<?> rendererRegistry = Class.forName("1408947079", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field rendererTypesField =
                    rendererRegistry.getDeclaredField("char");
            rendererTypesField.setAccessible(true);
            Object rendererTypes = rendererTypesField.get(null);
            if (rendererTypes instanceof java.util.Map<?, ?>
                    && ((java.util.Map<?, ?>) rendererTypes).isEmpty()) {
                java.lang.reflect.Method registerTypes = rendererRegistry.getDeclaredMethod("for");
                registerTypes.setAccessible(true);
                registerTypes.invoke(null);
            }
            java.lang.reflect.Method registerFactories =
                    rendererRegistry.getDeclaredMethod("while");
            registerFactories.setAccessible(true);
            registerFactories.invoke(null);
            Class<?> worldContextClass = Class.forName("969130755", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Method getWorldContext = null;
            for (java.lang.reflect.Method candidate : worldContextClass.getDeclaredMethods()) {
                if (candidate.getName().equals("do") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(world)) {
                    getWorldContext = candidate;
                    break;
                }
            }
            if (getWorldContext == null) throw new NoSuchMethodException("969130755.do(world)");
            getWorldContext.setAccessible(true);
            Object worldContext = getWorldContext.invoke(null, world);
            if (worldContext != null && invokeReflected(worldContext, "instanceof") == null) {
                java.lang.reflect.Method defaultRendererType =
                        rendererRegistry.getDeclaredMethod("throws");
                defaultRendererType.setAccessible(true);
                Object rendererType = defaultRendererType.invoke(null);
                invokeReflected(worldContext, "else", rendererType);
            }
            invokeReflected(manager, "for", world);
            return invokeReflected(manager, "default");
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            controlLastError = "lazy world renderer: " + root.getClass().getName()
                    + ": " + root.getMessage();
            if (!controlLastError.equals(componentBootstrapError)) {
                componentBootstrapError = controlLastError;
                System.err.println(controlLastError);
                root.printStackTrace(System.err);
            }
            return null;
        }
    }

    private static Object invokeReflected(Object owner, String name, Object... arguments) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (!reflectionArgumentCompatible(parameters[index], arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner, arguments);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            }
        }
        throw new IllegalStateException("method_not_found:" + owner.getClass().getName() + "." + name);
    }

    private static void captureControlScreenshot() {
        long window = requireControlWindow();
        int[] width = {0};
        int[] height = {0};
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            throw new IllegalStateException("framebuffer_unavailable");
        }
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(
                width[0] * height[0] * 4);
        org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_FRONT);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, width[0], height[0],
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                width[0], height[0], java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height[0]; y++) {
            for (int x = 0; x < width[0]; x++) {
                int offset = ((height[0] - 1 - y) * width[0] + x) * 4;
                int rgba = ((pixels.get(offset) & 255) << 16)
                        | ((pixels.get(offset + 1) & 255) << 8)
                        | (pixels.get(offset + 2) & 255)
                        | (255 << 24);
                image.setRGB(x, y, rgba);
            }
        }
        java.io.File target = new java.io.File(
                System.getProperty("java.io.tmpdir"), "sz-map-probe.png");
        try {
            javax.imageio.ImageIO.write(image, "png", target);
        } catch (java.io.IOException failure) {
            throw new RuntimeException(failure);
        }
    }

    private static Object invokeReflectedWithReturnType(
            Object owner, String name, Class<?> returnType, Object... arguments) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != arguments.length
                        || !returnType.isAssignableFrom(method.getReturnType())) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (!reflectionArgumentCompatible(parameters[index], arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner, arguments);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            }
        }
        throw new IllegalStateException("method_not_found:" + owner.getClass().getName() + "." + name);
    }

    private static Object invokeStaticReflected(
            String ownerName, String name, Object... arguments) {
        try {
            Class<?> owner = Class.forName(ownerName, true,
                    StandardJvmBootstrap.class.getClassLoader());
            for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || !method.getName().equals(name)
                        || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (!reflectionArgumentCompatible(parameters[index], arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) continue;
                method.setAccessible(true);
                return method.invoke(null, arguments);
            }
            throw new NoSuchMethodException(ownerName + "." + name);
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            controlLastError = "static diagnostic " + ownerName + "." + name
                    + ": " + root.getClass().getName() + ": " + root.getMessage();
            return null;
        }
    }

    private static Object gameInputManager(Object game) {
        if (game == null) return null;
        for (java.lang.reflect.Method method : game.getClass().getDeclaredMethods()) {
            if (!method.getName().equals("transient")
                    || method.getParameterCount() != 0
                    || !method.getReturnType().getName().equals("1855282131")) continue;
            try {
                method.setAccessible(true);
                return method.invoke(game);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("input_manager_getter_unavailable", failure);
            }
        }
        return null;
    }

    private static Object gameAuxiliaryScreen(Object game) {
        if (game == null) return null;
        for (java.lang.reflect.Method method : game.getClass().getDeclaredMethods()) {
            if (!method.getName().equals("float")
                    || method.getParameterCount() != 0
                    || !method.getReturnType().getName().equals("622415611")) continue;
            try {
                method.setAccessible(true);
                return method.invoke(game);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("auxiliary_screen_getter_unavailable", failure);
            }
        }
        return null;
    }

    private static String buildControlState(Object game, long frame) {
        Object player = reflectedField(game, "e");
        Object world = reflectedField(game, "g");
        Object primaryScreen = reflectedField(game, "implements");
        Object overlayScreen = reflectedField(game, "volatile");
        Object auxiliaryScreen = gameAuxiliaryScreen(game);
        java.util.Collection<?> toolScreens = adminToolScreens();
        boolean adminToolsOpen = collectionClassInstance(toolScreens, "473922574") != null;
        Object sceneEditor = collectionClassInstance(toolScreens, "1540543037");
        boolean sceneEditorOpen = sceneEditor != null;
        Object session = world == null ? null : reflectedField(world, "byte");
        Object sessionState = session == null ? null : reflectedField(session, "transient");
        Object movementComponent = componentAt(player, 21233);
        Object hudComponent = componentAt(player, 27759);
        Object modelComponent = componentAt(player, 47930);
        Object identityComponent = componentAt(player, 9355);
        Object stateComponent = componentAt(player, 30326);
        Object viewComponent = componentAt(player, 45122);
        Object menuComponent = componentAt(player, 1600);
        Object actorInputComponent = componentAt(player, 8828);
        Object administratorProxy = reflectedField(stateComponent, "throw");
        Object administratorAttribute = reflectedField(administratorProxy, "do");
        Object inventoryController = reflectedField(stateComponent, "default");
        Object personalStorageAvailable = inventoryController == null ? null
                : invokeBooleanNoArg(inventoryController, "continue");
        Object personalStorageInteraction = inventoryController == null ? null
                : invokeBooleanNoArg(inventoryController, "default");
        Object storageButtonAvailable = primaryScreen == null ? null
                : invokeBooleanNoArg(primaryScreen, "int");
        Object storageButtonVisible = primaryScreen == null ? null
                : invokeBooleanNoArg(primaryScreen, "const");
        Object storageButtonEnabled = primaryScreen == null ? null
                : invokeBooleanNoArg(primaryScreen, "else");
        Object inventorySet = reflectedField(inventoryController, "if");
        Object inventoryMap = inventorySet == null ? null
                : invokeReflectedWithReturnType(inventorySet, "throws", java.util.Map.class);
        Object inventoryIndexes = inventoryMap instanceof java.util.Map<?, ?>
                ? ((java.util.Map<?, ?>) inventoryMap).keySet() : null;
        Object storageManager = reflectedField(inventoryController, "assert");
        Object storageManagerMap = storageManager == null ? null
                : invokeReflectedWithReturnType(storageManager, "throws", java.util.Map.class);
        Object storageManagerIndex = storageManager == null ? null
                : invokeReflectedWithReturnType(storageManager, "public", int.class);
        Object storageContainerKeys = storageManagerMap instanceof java.util.Map<?, ?>
                ? ((java.util.Map<?, ?>) storageManagerMap).keySet() : null;
        Object storagePageRegistry = invokeReflected(staticField("929397021", "strictfp"), "char");
        Object storagePageDefinitions = invokeReflected(storagePageRegistry, "protected");
        java.util.List<Object> storagePageIds = new java.util.ArrayList<>();
        if (storagePageDefinitions instanceof java.util.List<?>) {
            for (Object page : (java.util.List<?>) storagePageDefinitions) {
                storagePageIds.add(invokeReflected(page, "byte"));
            }
        }
        Object toolsAccessAtLeastOne = player == null ? null : invokeStaticReflected(
                "353451775", "boolean", player);
        Object toolsAccessAtLeastTwo = player == null ? null : invokeStaticReflected(
                "353451775", "assert", player);
        Object viewController = reflectedField(viewComponent, "throws");
        Object cameraNode = viewController == null ? null
                : invokeReflected(viewController, "volatile");
        Object cameraTransform = reflectedField(cameraNode, "default");
        Object inputManager = gameInputManager(game);
        Object mouseInput = reflectedField(inputManager, "if");
        Object playerYaw = invokeFloatNoArg(player, "catch");
        Object playerPitch = invokeFloatNoArg(player, "volatile");
        Object playerRenderYaw = invokeFloatNoArg(player, "throws");
        Object playerRenderPitch = invokeFloatNoArg(player, "class");
        Object inputCapture = staticField("807672567", "synchronized");
        Object gameplayInputEnabled = invokeBooleanNoArg(game, "this");
        Object playerInputContext = player == null ? null : invokeReflected(player, "final");
        Object playerInputBlocked = invokeBooleanNoArg(playerInputContext, "import");
        Object inputCaptureBlocked = invokeBooleanNoArg(inputCapture, "package");
        Object componentRegistry = player == null ? null : invokeReflected(player, "byte");
        Object componentMap = reflectedField(componentRegistry, "void");
        int componentCount = componentMap instanceof java.util.Map<?, ?>
                ? ((java.util.Map<?, ?>) componentMap).size() : 0;
        String worldEntityState = worldEntityState(world, player);
        Object eventBus = staticField("185710526", "do");
        Object registeredListenerMap = reflectedField(eventBus, "double");
        boolean playerListenerRegistered = mapContainsClass(
                registeredListenerMap, "1674813597");
        boolean actorListenerRegistered = mapContainsClass(
                registeredListenerMap, "812639286");
        Object moduleList = staticField("1852924122", "float");
        int moduleCount = moduleList instanceof java.util.Collection<?>
                ? ((java.util.Collection<?>) moduleList).size() : 0;
        boolean playerModuleLoaded = collectionContainsClass(moduleList, "125604962");
        boolean actorModuleLoaded = collectionContainsClass(moduleList, "812639286");
        int[] windowWidth = new int[] {0};
        int[] windowHeight = new int[] {0};
        if (controlWindow != 0L) {
            org.lwjgl.glfw.GLFW.glfwGetWindowSize(controlWindow, windowWidth, windowHeight);
        }
        int cursorMode = controlWindow == 0L ? -1
                : org.lwjgl.glfw.GLFW.glfwGetInputMode(
                        controlWindow, org.lwjgl.glfw.GLFW.GLFW_CURSOR);
        int windowFocused = controlWindow == 0L ? -1
                : org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
                        controlWindow, org.lwjgl.glfw.GLFW.GLFW_FOCUSED);
        return "{\"ready\":true,\"frame\":" + frame
                + ",\"window\":" + controlWindow
                + ",\"windowWidth\":" + windowWidth[0]
                + ",\"windowHeight\":" + windowHeight[0]
                + ",\"hidden\":" + controlWindowHidden
                + ",\"cursorMode\":" + cursorMode
                + ",\"windowFocused\":" + windowFocused
                + ",\"callbacks\":{\"key\":\""
                + jsonEscape(controlKeyCallbackDescription)
                + "\",\"mouse\":\"" + jsonEscape(controlMouseCallbackDescription)
                + "\",\"cursor\":\"" + jsonEscape(controlCursorCallbackDescription) + "\"}"
                + ",\"game\":\"" + jsonEscape(className(game)) + "\""
                + ",\"primaryScreen\":\"" + jsonEscape(className(primaryScreen)) + "\""
                + ",\"primaryScreenState\":" + reflectedPrimitiveFields(primaryScreen)
                + ",\"overlayScreen\":\"" + jsonEscape(className(overlayScreen)) + "\""
                + ",\"auxiliaryScreen\":\"" + jsonEscape(className(auxiliaryScreen)) + "\""
                + ",\"toolScreens\":" + classNamesJson(toolScreens)
                + ",\"adminToolsOpen\":" + adminToolsOpen
                + ",\"sceneEditorOpen\":" + sceneEditorOpen
                + ",\"sceneEditorState\":" + reflectedPrimitiveFields(sceneEditor)
                + ",\"world\":\"" + jsonEscape(className(world)) + "\""
                + ",\"worldEntities\":" + worldEntityState
                + ",\"player\":\"" + jsonEscape(className(player)) + "\""
                + ",\"movementComponent\":\"" + jsonEscape(className(movementComponent)) + "\""
                + ",\"hudComponent\":\"" + jsonEscape(className(hudComponent)) + "\""
                + ",\"modelComponent\":\"" + jsonEscape(className(modelComponent)) + "\""
                + ",\"identityComponent\":\"" + jsonEscape(className(identityComponent)) + "\""
                + ",\"stateComponent\":\"" + jsonEscape(className(stateComponent)) + "\""
                + ",\"viewComponent\":\"" + jsonEscape(className(viewComponent)) + "\""
                + ",\"menuComponent\":\"" + jsonEscape(className(menuComponent)) + "\""
                + ",\"actorInputComponent\":\"" + jsonEscape(className(actorInputComponent)) + "\""
                + ",\"componentCount\":" + componentCount
                + ",\"playerListenerRegistered\":" + playerListenerRegistered
                + ",\"actorListenerRegistered\":" + actorListenerRegistered
                + ",\"moduleCount\":" + moduleCount
                + ",\"playerModuleLoaded\":" + playerModuleLoaded
                + ",\"actorModuleLoaded\":" + actorModuleLoaded
                + ",\"entityDoubles\":" + reflectedDoubleState(player)
                + ",\"movementState\":" + reflectedPrimitiveFields(movementComponent)
                + ",\"playerState\":" + reflectedPrimitiveFields(stateComponent)
                + ",\"permissions\":{\"administratorAttribute\":"
                + jsonScalar(administratorAttribute)
                + ",\"personalStorageAvailable\":" + jsonScalar(personalStorageAvailable)
                + ",\"personalStorageInteraction\":" + jsonScalar(personalStorageInteraction)
                + ",\"storageButtonAvailable\":" + jsonScalar(storageButtonAvailable)
                + ",\"storageButtonVisible\":" + jsonScalar(storageButtonVisible)
                + ",\"storageButtonEnabled\":" + jsonScalar(storageButtonEnabled)
                + ",\"inventoryIndexes\":" + jsonScalar(String.valueOf(inventoryIndexes))
                + ",\"storageManagerIndex\":" + jsonScalar(storageManagerIndex)
                + ",\"storageContainerKeys\":" + jsonScalar(String.valueOf(storageContainerKeys))
                + ",\"storagePageIds\":" + jsonScalar(String.valueOf(storagePageIds))
                + ",\"toolsAccessAtLeastOne\":" + jsonScalar(toolsAccessAtLeastOne)
                + ",\"toolsAccessAtLeastTwo\":" + jsonScalar(toolsAccessAtLeastTwo)
                + "}"
                + ",\"viewState\":" + reflectedPrimitiveFields(viewComponent)
                + ",\"cameraNodeState\":" + reflectedPrimitiveFields(cameraNode)
                + ",\"cameraTransform\":" + reflectedFloatArrays(cameraTransform)
                + ",\"actorInputState\":" + reflectedPrimitiveFields(actorInputComponent)
                + ",\"inputManagerState\":" + reflectedPrimitiveFields(inputManager)
                + ",\"mouseInputState\":" + reflectedPrimitiveFields(mouseInput)
                + ",\"inputCaptureState\":" + reflectedPrimitiveFields(inputCapture)
                + ",\"nativeMouse\":{\"polls\":" + nativeMousePolls
                + ",\"events\":" + nativeMouseEvents
                + ",\"deltaX\":" + nativeMouseDeltaX
                + ",\"deltaY\":" + nativeMouseDeltaY
                + ",\"lastX\":" + nativeMouseLastX
                + ",\"lastY\":" + nativeMouseLastY + "}"
                + ",\"gameplayInputEnabled\":" + jsonScalar(gameplayInputEnabled)
                + ",\"inputBlocking\":{\"playerContext\":"
                + jsonScalar(playerInputBlocked) + ",\"capture\":"
                + jsonScalar(inputCaptureBlocked) + "}"
                + ",\"playerView\":{\"yaw\":" + jsonScalar(playerYaw)
                + ",\"pitch\":" + jsonScalar(playerPitch)
                + ",\"renderYaw\":" + jsonScalar(playerRenderYaw)
                + ",\"renderPitch\":" + jsonScalar(playerRenderPitch) + "}"
                + ",\"wKeyState\":" + inputKeyState(inputManager, 87)
                + ",\"wInputActions\":" + inputActionsForKey(inputManager, 87)
                + ",\"session\":\"" + jsonEscape(className(session)) + "\""
                + ",\"sessionState\":\"" + jsonEscape(String.valueOf(sessionState)) + "\""
                + ",\"sessionFields\":" + reflectedPrimitiveFields(session)
                + ",\"worldClock\":" + worldClockState()
                + ",\"worldWeather\":{\"desired\":\""
                + jsonEscape(desiredWeatherCode == 2
                    ? "thunder" : desiredWeatherCode == 1 ? "rain" : "clear")
                + "\",\"applied\":\"" + jsonEscape(visualWeather)
                + "\",\"error\":\"" + jsonEscape(visualWeatherError) + "\"}"
                + ",\"lastBlockDelta\":"
                + (controlLastBlockDelta.isEmpty() ? "null" : controlLastBlockDelta)
                + ",\"lastBuilderClick\":"
                + (controlLastBuilderClick.isEmpty() ? "null" : controlLastBuilderClick)
                + ",\"blockDeltaTransport\":{\"queued\":"
                + CLIENT_BLOCK_DELTAS_QUEUED.get() + ",\"appendCalls\":"
                + CLIENT_BLOCK_APPEND_CALLS.get() + ",\"appendedBatches\":"
                + CLIENT_BLOCK_APPEND_BATCHES.get() + ",\"pending\":"
                + CLIENT_BLOCK_DELTAS.size() + ",\"lastEncoded\":"
                + (controlLastEncodedBlockDelta.isEmpty()
                    ? "null" : controlLastEncodedBlockDelta) + "}"
                + ",\"lastError\":\"" + jsonEscape(controlLastError) + "\"}";
    }

    /** Expose the authoritative shared clock used by world/environment rendering. */
    private static String worldClockState() {
        try {
            Class<?> clock = Class.forName("1553688601", true,
                    StandardJvmBootstrap.class.getClassLoader());
            Object localTime = null;
            Object epochMillis = null;
            for (java.lang.reflect.Method method : clock.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) continue;
                if (method.getReturnType() == java.time.LocalTime.class) {
                    method.setAccessible(true);
                    localTime = method.invoke(null);
                } else if (method.getName().equals("byte")
                        && method.getReturnType() == long.class) {
                    method.setAccessible(true);
                    epochMillis = method.invoke(null);
                }
            }
            return "{\"localTime\":" + jsonScalar(localTime)
                    + ",\"epochMillis\":" + jsonScalar(epochMillis)
                    + ",\"speed\":" + jsonScalar(staticField("1553688601", "throw"))
                    + ",\"epochOffset\":" + jsonScalar(staticField("1553688601", "default"))
                    + ",\"synchronizedAt\":" + jsonScalar(staticField("1553688601", "finally"))
                    + ",\"zone\":" + jsonScalar(staticField("1553688601", "case"))
                    + ",\"visualWorldTime\":"
                    + (visualWorldTime == Long.MIN_VALUE ? "null" : visualWorldTime)
                    + ",\"visualDayTime\":"
                    + (visualWorldTime == Long.MIN_VALUE
                        ? "null" : Math.floorMod(visualWorldTime, 24_000L))
                    + ",\"visualError\":\"" + jsonEscape(visualWorldClockError) + "\""
                    + "}";
        } catch (Throwable failure) {
            return "{\"error\":\"" + jsonEscape(
                    failure.getClass().getName() + ": " + failure.getMessage()) + "\"}";
        }
    }

    private static String worldEntityState(Object world, Object localPlayer) {
        if (world == null) return "{\"count\":0,\"playerCount\":0,\"players\":[]}";
        try {
            Class<?> entityClass = Class.forName("1217489680", true,
                    StandardJvmBootstrap.class.getClassLoader());
            Class<?> playerClass = Class.forName("200241649", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.util.Set<Object> entities = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            java.util.TreeMap<String, Integer> sources = new java.util.TreeMap<>();
            for (Class<?> type = world.getClass(); type != null; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.util.Collection.class.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(world);
                        collectWorldEntities(value, entityClass, entities);
                        if (value instanceof java.util.Collection<?>) {
                            sources.put("field:" + type.getName() + "." + field.getName(),
                                    ((java.util.Collection<?>) value).size());
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // One diagnostic source must not hide the remaining ECS state.
                    }
                }
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0
                            || !java.util.Collection.class.isAssignableFrom(
                                    method.getReturnType())) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(world);
                        collectWorldEntities(value, entityClass, entities);
                        if (value instanceof java.util.Collection<?>) {
                            sources.put("method:" + type.getName() + "." + method.getName(),
                                    ((java.util.Collection<?>) value).size());
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // Getter may be temporarily unavailable during world transitions.
                    }
                }
            }
            int playerCount = 0;
            StringBuilder players = new StringBuilder("[");
            for (Object entity : entities) {
                if (!playerClass.isInstance(entity)) continue;
                if (playerCount++ != 0) players.append(',');
                Object registry = invokeReflected(entity, "byte");
                Object components = reflectedField(registry, "void");
                int componentsCount = components instanceof java.util.Map<?, ?>
                        ? ((java.util.Map<?, ?>) components).size() : 0;
                Object equipmentManager = invokeStaticReflected(
                        "276406255", "implements", entity);
                Object playerModel = componentAt(entity, 47930);
                Object appearance = null;
                try {
                    appearance = invokeNoArgReturning(entity, "const", "658914576");
                } catch (ReflectiveOperationException ignored) {
                    // Some player specializations expose no appearance getter.
                }
                players.append("{\"identity\":")
                        .append(System.identityHashCode(entity))
                        .append(",\"local\":").append(entity == localPlayer)
                        .append(",\"class\":\"")
                        .append(jsonEscape(className(entity))).append("\"")
                        .append(",\"faction\":\"").append(jsonEscape(String.valueOf(invokeReflected(invokeStaticReflected("656672376", "char", entity), "return")))).append("\"")
                        .append(",\"hitGeometry\":").append(entityHitGeometry(entity))
                        .append(",\"componentCount\":").append(componentsCount)
                        .append(",\"replicatedComponents\":")
                        .append(replicatedComponentsJson(entity))
                        .append(",\"equipmentAttributes\":")
                        .append(entityEquipmentAttributesJson(entity))
                        .append(",\"equipmentAggregate\":")
                        .append(inventoryAggregateState(
                                reflectedField(equipmentManager, "class")))
                        .append(",\"equipmentBindings\":")
                        .append(equipmentBindingsJson(equipmentManager))
                        .append(",\"modelComponent\":\"")
                        .append(jsonEscape(className(playerModel))).append("\"")
                        .append(",\"modelState\":")
                        .append(reflectedNestedPrimitiveFields(playerModel))
                        .append(",\"appearanceClass\":\"")
                        .append(jsonEscape(className(appearance))).append("\"")
                        .append(",\"appearanceState\":")
                        .append(reflectedNestedPrimitiveFields(appearance))
                        .append(",\"entityDoubles\":")
                        .append(reflectedDoubleState(entity))
                        .append(",\"movementState\":")
                        .append(reflectedPrimitiveFields(componentAt(entity, 21233)))
                        .append(",\"animationGraph\":")
                        .append(animationGraphJson(entity))
                        .append('}');
            }
            return "{\"count\":" + entities.size() + ",\"playerCount\":" + playerCount
                    + ",\"players\":" + players.append(']')
                    + ",\"sources\":" + integerMapJson(sources) + "}";
        } catch (Throwable failure) {
            return "{\"count\":0,\"playerCount\":0,\"players\":[],\"error\":\""
                    + jsonEscape(failure.getClass().getName() + ": " + failure.getMessage())
                    + "\"}";
        }
    }

    private static void collectWorldEntities(
            Object candidate, Class<?> entityClass, java.util.Set<Object> entities) {
        if (!(candidate instanceof Iterable<?>)) return;
        for (Object value : (Iterable<?>) candidate) {
            if (value != null && entityClass.isInstance(value)) entities.add(value);
        }
    }

    /** Read the live replicated equipment attributes (wire IDs 55..66). */
    private static String entityEquipmentAttributesJson(Object entity) {
        if (entity == null) return "[]";
        try {
            Object attributes = invokeNoArgReturning(
                    entity, "float", "1536302723");
            Object registrations = reflectedField(attributes, "catch");
            if (!(registrations instanceof Iterable<?>)) return "[]";
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object attribute : (Iterable<?>) registrations) {
                Object rawId = reflectedField(attribute, "throw");
                if (!(rawId instanceof Number)) continue;
                int id = ((Number) rawId).intValue();
                if (id < 55 || id > 66) continue;
                // 1798539107 contains two zero-argument methods named
                // continue() in bytecode: the void overload resets the value,
                // while the 1160390453 overload returns it. Generic reflection
                // by name alone therefore corrupts the very state being
                // inspected on some JVM reflection orders.
                Object networkStack = reflectedField(attribute, "do");
                Object bytes = reflectedField(networkStack, "assert");
                Object stack = reflectedField(networkStack, "switch");
                Object itemId = invokeIntNoArg(stack, "continue");
                Object version = reflectedField(attribute, "instanceof");
                Object declaredType = reflectedField(attribute, "return");
                if (!first) json.append(',');
                first = false;
                json.append("{\"id\":").append(id)
                        .append(",\"version\":").append(jsonScalar(version))
                        .append(",\"declaredType\":\"")
                        .append(jsonEscape(declaredType instanceof Class<?>
                                ? ((Class<?>) declaredType).getName()
                                : className(declaredType))).append("\"")
                        .append(",\"networkStack\":\"")
                        .append(jsonEscape(className(networkStack))).append("\"")
                        .append(",\"itemId\":").append(jsonScalar(itemId))
                        .append(",\"hex\":\"")
                        .append(bytes instanceof byte[]
                                ? bytesHex((byte[]) bytes) : "")
                        .append("\"}");
            }
            return json.append(']').toString();
        } catch (Throwable failure) {
            return "{\"error\":\"" + jsonEscape(
                    failure.getClass().getName() + ": " + failure.getMessage())
                    + "\"}";
        }
    }

    private static String equipmentBindingsJson(Object equipmentManager) {
        Object bindings = reflectedField(equipmentManager, "protected");
        if (!(bindings instanceof Iterable<?>)) return "[]";
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Object binding : (Iterable<?>) bindings) {
            Object slot = reflectedField(binding, "assert");
            Object attribute = reflectedField(binding, "int");
            Object attributeId = reflectedField(attribute, "throw");
            Object current = reflectedField(attribute, "do");
            Object stack = reflectedField(current, "switch");
            Object itemId = invokeIntNoArg(stack, "continue");
            Object attributeManager = reflectedField(attribute, "interface");
            Object callbacks = reflectedField(attributeManager, "native");
            Object callback = callbacks instanceof java.util.Map<?, ?>
                    ? ((java.util.Map<?, ?>) callbacks).get(attribute) : null;
            if (!first) json.append(',');
            first = false;
            json.append("{\"slot\":").append(jsonScalar(slot))
                    .append(",\"attributeId\":").append(jsonScalar(attributeId))
                    .append(",\"itemId\":").append(jsonScalar(itemId))
                    .append(",\"callbackClass\":\"")
                    .append(jsonEscape(className(callback))).append("\"")
                    .append(",\"callbackIdentity\":")
                    .append(callback == null ? 0 : System.identityHashCode(callback))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static Object invokeNoArgReturning(
            Object owner, String name, String returnClassName)
            throws ReflectiveOperationException {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null;
                type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != 0
                        || !method.getReturnType().getName().equals(returnClassName)) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(owner);
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name
                + "():" + returnClassName);
    }

    private static String replicatedComponentsJson(Object entity) {
        if (entity == null) return "[]";
        try {
            Class<?> replicatedType = Class.forName("1229269370", true,
                    StandardJvmBootstrap.class.getClassLoader());
            Object allComponents = reflectedField(entity, "private");
            if (allComponents instanceof java.util.Collection<?>) {
                StringBuilder json = new StringBuilder("[");
                int index = 0;
                for (Object component : (java.util.Collection<?>) allComponents) {
                    if (!replicatedType.isInstance(component)) continue;
                    if (index != 0) json.append(',');
                    json.append("{\"index\":").append(index++)
                            .append(",\"class\":\"")
                            .append(jsonEscape(className(component))).append("\"")
                            .append(",\"state\":")
                            .append(reflectedPrimitiveFields(component))
                            .append(",\"nestedState\":")
                            .append(reflectedNestedPrimitiveFields(component)).append('}');
                }
                if (index != 0) return json.append(']').toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the replicated-list getter.
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("static") || method.getParameterCount() != 0
                        || !java.util.Collection.class.isAssignableFrom(
                                method.getReturnType())) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(entity);
                    if (!(value instanceof java.util.Collection<?>)
                            || ((java.util.Collection<?>) value).isEmpty()) continue;
                    StringBuilder json = new StringBuilder("[");
                    int index = 0;
                    for (Object component : (java.util.Collection<?>) value) {
                        if (index != 0) json.append(',');
                        json.append("{\"index\":").append(index++)
                                .append(",\"class\":\"")
                                .append(jsonEscape(className(component))).append("\"")
                                .append(",\"state\":")
                                .append(reflectedPrimitiveFields(component))
                                .append(",\"nestedState\":")
                                .append(reflectedNestedPrimitiveFields(component)).append('}');
                    }
                    return json.append(']').toString();
                } catch (ReflectiveOperationException ignored) {
                    // Continue through the hierarchy to the concrete list getter.
                }
            }
        }
        return "[]";
    }

    private static String animationGraphJson(Object entity) {
        try {
            Class<?> providerType = Class.forName("1581759465", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.util.ArrayList<Object> candidates = new java.util.ArrayList<>();
            Object model = componentAt(entity, 47930);
            if (model != null) candidates.add(model);
            Object registry = invokeReflected(entity, "byte");
            Object components = reflectedField(registry, "void");
            if (components instanceof java.util.Map<?, ?>) {
                candidates.addAll(((java.util.Map<?, ?>) components).values());
            }
            for (Object candidate : candidates) {
                String graph = animationGraphFromCandidate(candidate, providerType);
                if (graph != null) return graph;
            }
        } catch (ReflectiveOperationException ignored) {
            // Animation provider is unavailable during model transitions.
        }
        return "{}";
    }

    private static String animationGraphFromCandidate(Object candidate, Class<?> providerType) {
        if (candidate == null) return null;
        if (providerType.isInstance(candidate)) {
            Object graph = invokeReflected(candidate, "package");
            if (graph != null && className(graph).equals("578131936")) {
                return reflectedPrimitiveFields(graph);
            }
        }
        for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object provider = field.get(candidate);
                    if (!providerType.isInstance(provider)) continue;
                    Object graph = invokeReflected(provider, "package");
                    if (graph != null && className(graph).equals("578131936")) {
                        return reflectedPrimitiveFields(graph);
                    }
                } catch (Throwable ignored) {
                    // Continue through alternate component/provider fields.
                }
            }
        }
        return null;
    }

    private static String reflectedNestedPrimitiveFields(Object owner) {
        if (owner == null) return "{}";
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(
                        field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    String nestedClass = className(value);
                    if (!nestedClass.equals("140709081")
                            && !nestedClass.equals("1585574065")
                            && !nestedClass.equals("1591614415")
                            && !nestedClass.equals("703715006")
                            && !nestedClass.equals("1205530968")) continue;
                    if (!first) json.append(',');
                    first = false;
                    json.append('\"').append(jsonEscape(field.getName())).append("\":{")
                            .append("\"class\":\"").append(nestedClass).append("\",")
                            .append("\"state\":").append(reflectedPrimitiveFields(value))
                            .append('}');
                } catch (Throwable ignored) {
                    // Optional replicated payloads may change during this snapshot.
                }
            }
        }
        return json.append('}').toString();
    }

    private static String integerMapJson(java.util.Map<String, Integer> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(entry.getKey())).append("\":")
                    .append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static Object invokeFloatNoArg(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0
                        || method.getReturnType() != float.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException failure) {
                    controlLastError = "float diagnostic " + type.getName() + "." + name
                            + ": " + failure.getMessage();
                    return null;
                }
            }
        }
        return null;
    }

    private static Object invokeDoubleNoArg(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0
                        || method.getReturnType() != double.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            }
        }
        throw new IllegalStateException("double_method_not_found:"
                + owner.getClass().getName() + "." + name);
    }

    /** Resolve return-type-only overloads produced by the protected obfuscator. */
    private static Object invokeIntNoArg(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0
                        || method.getReturnType() != int.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            }
        }
        throw new IllegalStateException("int_method_not_found:"
                + owner.getClass().getName() + "." + name);
    }

    private static Object invokeLongNoArg(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0
                        || method.getReturnType() != long.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException ignored) {
                    // Continue through overloaded/delegating methods.
                }
            }
        }
        return null;
    }

    private static Object invokeBooleanNoArg(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0
                        || method.getReturnType() != boolean.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException failure) {
                    controlLastError = "boolean diagnostic " + type.getName() + "." + name
                            + ": " + failure.getMessage();
                    return null;
                }
            }
        }
        return null;
    }

    private static String inputKeyState(Object inputManager, int key) {
        Object keyboard = reflectedField(inputManager, "long");
        if (keyboard == null) return "{}";
        java.util.TreeMap<String, Boolean> values = new java.util.TreeMap<>();
        for (java.lang.reflect.Method method : keyboard.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != int.class
                    || method.getReturnType() != boolean.class) continue;
            try {
                method.setAccessible(true);
                values.put(method.getName(), (Boolean) method.invoke(keyboard, key));
            } catch (ReflectiveOperationException ignored) {
                // Diagnostics must not affect input processing.
            }
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(entry.getKey())).append("\":")
                    .append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static String inputActionsForKey(Object inputManager, int key) {
        Object actionManager = reflectedField(inputManager, "double");
        Object candidate = reflectedField(actionManager, "const");
        if (!(candidate instanceof java.util.Map<?, ?>)) return "[]";
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> entry
                : ((java.util.Map<?, ?>) candidate).entrySet()) {
            Object action = entry.getKey();
            Object primaryKeys = reflectedField(action, "catch");
            Object alternateKeys = reflectedField(action, "throws");
            boolean matches = primaryKeys instanceof java.util.Set<?>
                    && ((java.util.Set<?>) primaryKeys).contains(key);
            matches |= alternateKeys instanceof java.util.Set<?>
                    && ((java.util.Set<?>) alternateKeys).contains(key);
            if (!matches) continue;
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":\"")
                    .append(jsonEscape(String.valueOf(reflectedField(action, "for"))))
                    .append("\",\"type\":\"")
                    .append(jsonEscape(className(reflectedField(action, "final"))))
                    .append("\",\"blocked\":")
                    .append(jsonScalar(invokeStaticReflected("1822220185", "char", action)))
                    .append(",\"flags\":")
                    .append(reflectedPrimitiveFields(action))
                    .append(",\"state\":")
                    .append(reflectedPrimitiveFields(entry.getValue()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String gameObjectRegistryEntry(int id) {
        if (id < Short.MIN_VALUE || id > Short.MAX_VALUE) {
            return "{\"error\":\"game_object_id_out_of_range\"}";
        }
        try {
            Class<?> type = Class.forName("1814125595", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Method lookup = type.getDeclaredMethod("public", short.class);
            lookup.setAccessible(true);
            Object definition = lookup.invoke(null, (short) id);
            return "{\"id\":" + id + ",\"name\":\""
                    + jsonEscape(String.valueOf(reflectedField(definition, "while")))
                    + "\",\"definitionClass\":\"" + jsonEscape(className(definition)) + "\"}";
        } catch (Throwable failure) {
            return "{\"id\":" + id + ",\"error\":\""
                    + jsonEscape(failure.getClass().getName() + ": " + failure.getMessage()) + "\"}";
        }
    }

    private static String gameObjectRegistry(int maximum) {
        int limit = Math.max(0, Math.min(maximum, Short.MAX_VALUE + 1));
        StringBuilder json = new StringBuilder("{\"entries\":[");
        boolean first = true;
        int consecutiveMissing = 0;
        for (int id = 0; id < limit && consecutiveMissing < 64; id++) {
            String entry = gameObjectRegistryEntry(id);
            if (entry.contains("\"error\"")) {
                consecutiveMissing++;
                continue;
            }
            consecutiveMissing = 0;
            if (!first) json.append(',');
            first = false;
            json.append(entry);
        }
        return json.append("]}").toString();
    }

    /** Expose the live build-local packet table without an Instrumentation agent. */
    private static String packetRegistry(int maximum) {
        int limit = Math.max(0, Math.min(maximum, 65535));
        try {
            Class<?> registryType = Class.forName("594335374", true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field byIdField = registryType.getDeclaredField("protected");
            byIdField.setAccessible(true);
            Object value = byIdField.get(null);
            if (!(value instanceof java.util.Map<?, ?>)) {
                return "{\"ready\":false,\"error\":\"packet_registry_unavailable\"}";
            }
            java.util.List<java.util.Map.Entry<?, ?>> entries = new java.util.ArrayList<>(
                    ((java.util.Map<?, ?>) value).entrySet());
            entries.sort(java.util.Comparator.comparingInt(entry ->
                    ((Number) entry.getKey()).intValue()));
            StringBuilder json = new StringBuilder("{\"ready\":true,\"entries\":[");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> entry : entries) {
                int id = ((Number) entry.getKey()).intValue();
                if (id < 0 || id > limit) continue;
                if (!first) json.append(',');
                first = false;
                Object packetType = entry.getValue();
                String packetClass = packetType instanceof Class<?>
                        ? ((Class<?>) packetType).getName() : className(packetType);
                json.append("{\"id\":").append(id).append(",\"class\":\"")
                        .append(jsonEscape(packetClass)).append("\"}");
            }
            return json.append("]}").toString();
        } catch (Throwable failure) {
            return "{\"ready\":false,\"error\":\""
                    + jsonEscape(failure.getClass().getName() + ": " + failure.getMessage()) + "\"}";
        }
    }

    /** Resolve the hand through the gameplay accessor, not an inventory view. */
    private static String entityHitGeometry(Object entity) {
        try {
            Object box=invokeNoArgReturning(entity,"static","242639986");
            return "{\"id\":"+invokeNoArgReturning(entity,"for","int")
                +",\"x\":"+invokeNoArgReturning(box,"implements","double")
                +",\"y\":"+invokeNoArgReturning(box,"switch","double")
                +",\"z\":"+invokeNoArgReturning(box,"goto","double")
                +",\"alive\":"+invokeNoArgReturning(entity,"super","boolean")
                +",\"collidable\":"+invokeNoArgReturning(entity,"boolean","boolean")
                +",\"hidden\":"+invokeNoArgReturning(entity,"new","boolean")+"}";
        } catch(Exception e) { return "{\"error\":\""+jsonEscape(e.toString())+"\"}"; }
    }

    private static volatile String controlHitProbe="";

    /** Bounded, read-only diagnostics; never call lazy getters or mutate poses. */
    private static String fpDiagnosticObject(Object owner,int depth,
            java.util.IdentityHashMap<Object,Boolean> seen,int[] budget) {
        if(owner==null)return "null";
        String name=className(owner);
        if(owner instanceof Number || owner instanceof Boolean)return jsonScalar(owner);
        if(owner instanceof Enum<?>)return "\""+jsonEscape(((Enum<?>)owner).name())+"\"";
        // Do not expose strings, session data, assets, callbacks or arbitrary
        // collections; inspect only the selected animation/native object graph.
        if(depth<0 || budget[0]--<=0 || seen.put(owner,Boolean.TRUE)!=null)
            return "{\"class\":\""+jsonEscape(name)+"\",\"identity\":"+System.identityHashCode(owner)+"}";
        StringBuilder out=new StringBuilder("{\"class\":\"").append(jsonEscape(name))
                .append("\",\"identity\":").append(System.identityHashCode(owner)).append(",\"fields\":{");
        boolean first=true;
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(java.lang.reflect.Field field:type.getDeclaredFields()){
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            try {
                field.setAccessible(true);Object value=field.get(owner);
                if(!first)out.append(',');first=false;
                out.append('"').append(jsonEscape(type.getName()+"."+field.getName())).append("\":");
                if(value==null)out.append("null");
                else if(field.getType().isPrimitive() || value instanceof Enum<?>)out.append(fpDiagnosticObject(value,0,seen,budget));
                else if(field.getType().isArray())out.append("{\"arrayLength\":").append(java.lang.reflect.Array.getLength(value)).append('}');
                else if(value instanceof java.util.Map<?,?>)out.append("{\"mapSize\":").append(((java.util.Map<?,?>)value).size()).append('}');
                else if(value instanceof java.util.Collection<?>)out.append("{\"collectionSize\":").append(((java.util.Collection<?>)value).size()).append('}');
                else if(className(value).matches("[0-9]+") || className(value).startsWith("sz.animation.fp."))out.append(fpDiagnosticObject(value,depth-1,seen,budget));
                else out.append("{\"class\":\"").append(jsonEscape(className(value))).append("\"}");
            }catch(Throwable failure){out.append("null");}
        }
        return out.append("}}").toString();
    }

    private static String currentHeldItemState() {
        try {
            Object player = reflectedField(controlGame, "e");
            Object held = invokeStaticReflected("764970879", "short", player);
            Object factory = invokeReflected(held, "interface");
            return "{\"ready\":" + (player != null)
                    + ",\"playerClass\":\"" + jsonEscape(className(player))
                    + "\",\"stackClass\":\"" + jsonEscape(className(held))
                    + "\",\"factoryClass\":\"" + jsonEscape(className(factory))
                    + "\"}";
        } catch (Throwable failure) {
            return "{\"ready\":false,\"error\":\""
                    + jsonEscape(failure.getClass().getName() + ": "
                            + failure.getMessage()) + "\"}";
        }
    }

    private static Object staticField(String owner, String name) {
        try {
            Class<?> type = Class.forName(owner, true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable failure) {
            controlLastError = failure.getClass().getName() + ": " + failure.getMessage();
            return null;
        }
    }

    private static void setStaticFloat(String owner, String name, float value) {
        try {
            Class<?> type = Class.forName(owner, true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field field = type.getDeclaredField(name);
            if (field.getType() != float.class) {
                throw new IllegalStateException("not_float_field:" + owner + "." + name);
            }
            field.setAccessible(true);
            field.setFloat(null, value);
        } catch (Throwable failure) {
            throw new IllegalStateException("static_float_write_failed:"
                    + owner + "." + name, failure);
        }
    }

    private static void setStaticBoolean(String owner, String name, boolean value) {
        try {
            Class<?> type = Class.forName(owner, true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field field = type.getDeclaredField(name);
            if (field.getType() != boolean.class) {
                throw new IllegalStateException("not_boolean_field:" + owner + "." + name);
            }
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable failure) {
            throw new IllegalStateException("static_boolean_write_failed:"
                    + owner + "." + name, failure);
        }
    }

    private static void setStaticObject(String owner, String name, Object value) {
        try {
            Class<?> type = Class.forName(owner, true,
                    StandardJvmBootstrap.class.getClassLoader());
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable failure) {
            throw new IllegalStateException("static_object_write_failed:"
                    + owner + "." + name, failure);
        }
    }

    private static boolean mapContainsClass(Object candidate, String className) {
        if (!(candidate instanceof java.util.Map<?, ?>)) return false;
        try {
            Class<?> type = Class.forName(className, false,
                    StandardJvmBootstrap.class.getClassLoader());
            return ((java.util.Map<?, ?>) candidate).containsKey(type);
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static boolean collectionContainsClass(Object candidate, String className) {
        return collectionClassInstance(candidate, className) != null;
    }

    private static Object collectionClassInstance(Object candidate, String className) {
        if (!(candidate instanceof java.util.Collection<?>)) return null;
        for (Object value : (java.util.Collection<?>) candidate) {
            if (value != null && value.getClass().getName().equals(className)) return value;
        }
        return null;
    }

    private static String reflectedDoubleState(Object owner) {
        if (owner == null) return "{}";
        java.util.TreeMap<String, Double> values = new java.util.TreeMap<>();
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() != double.class) continue;
                String key = type.getName() + "." + method.getName();
                if (values.containsKey(key)) continue;
                try {
                    method.setAccessible(true);
                    values.put(key, (Double) method.invoke(owner));
                } catch (Throwable ignored) {
                    // A diagnostic field must never disturb the game frame.
                }
            }
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Double> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(entry.getKey())).append("\":")
                    .append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static String reflectedPrimitiveFields(Object owner) {
        if (owner == null) return "{}";
        java.util.TreeMap<String, String> values = new java.util.TreeMap<>();
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                boolean enumValue = fieldType.isEnum();
                if (!(fieldType == boolean.class || fieldType == byte.class
                        || fieldType == short.class || fieldType == int.class
                        || fieldType == long.class || fieldType == float.class
                        || fieldType == double.class || enumValue)) continue;
                String key = type.getName() + "." + field.getName();
                if (values.containsKey(key)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    values.put(key, enumValue
                            ? "\"" + jsonEscape(String.valueOf(value)) + "\""
                            : String.valueOf(value));
                } catch (Throwable ignored) {
                    // Diagnostics must not affect a game frame.
                }
            }
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(entry.getKey())).append("\":")
                    .append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static String reflectedFloatArrays(Object owner) {
        if (owner == null) return "{}";
        java.util.TreeMap<String, String> values = new java.util.TreeMap<>();
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType() != float[].class) continue;
                try {
                    field.setAccessible(true);
                    float[] array = (float[]) field.get(owner);
                    values.put(type.getName() + "." + field.getName(),
                            array == null ? "null" : java.util.Arrays.toString(array));
                } catch (Throwable ignored) {
                    // Diagnostics must not affect a game frame.
                }
            }
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(entry.getKey())).append("\":\"")
                    .append(jsonEscape(entry.getValue())).append('\"');
        }
        return json.append('}').toString();
    }

    private static Object reflectedField(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (Throwable failure) {
                controlLastError = failure.getClass().getName() + ": " + failure.getMessage();
                return null;
            }
        }
        return null;
    }

    private static boolean reflectionArgumentCompatible(Class<?> parameter, Object argument) {
        if (argument == null) return !parameter.isPrimitive();
        if (parameter.isInstance(argument)) return true;
        if (!parameter.isPrimitive()) return false;
        return (parameter == boolean.class && argument instanceof Boolean)
                || (parameter == byte.class && argument instanceof Byte)
                || (parameter == short.class && argument instanceof Short)
                || (parameter == char.class && argument instanceof Character)
                || (parameter == int.class && argument instanceof Integer)
                || (parameter == long.class && argument instanceof Long)
                || (parameter == float.class && argument instanceof Float)
                || (parameter == double.class && argument instanceof Double);
    }

    private static String className(Object value) {
        return value == null ? "" : value.getClass().getName();
    }

    private static String jsonScalar(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return "\"" + jsonEscape(String.valueOf(value)) + "\"";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void configureNativeOverrides() {
        String directory = System.getProperty("stalcraft.native.dir");
        if (directory == null || directory.isBlank()) return;
        java.nio.file.Path fmod = java.nio.file.Path.of(directory, "fmod", "x64", "fmod.dll").toAbsolutePath();
        java.nio.file.Path studio = java.nio.file.Path.of(directory, "fmod", "x64", "fmodstudio.dll").toAbsolutePath();
        if (java.nio.file.Files.isRegularFile(fmod)) {
            System.setProperty("org.lwjgl.fmod.libname", fmod.toString());
        }
        if (java.nio.file.Files.isRegularFile(studio)) {
            System.setProperty("org.lwjgl.fmod.studio.libname", studio.toString());
        }
    }

    private static void loadBundledNatives() {
        String directory = System.getProperty("stalcraft.native.dir");
        if (directory == null || directory.isBlank()) return;
        for (String name : new String[] {"lwjgl.dll", "lwjgl_opengl.dll", "lwjgl_nanovg.dll"}) {
            java.nio.file.Path path = java.nio.file.Path.of(directory, name).toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(path)) continue;
            try {
                System.load(path.toString());
            } catch (UnsatisfiedLinkError failure) {
                System.err.println("Unable to preload " + path + ": " + failure.getMessage());
            }
        }
        java.nio.file.Path effekseer = java.nio.file.Path.of(
                directory, "stalcraft-effekseer-jni64.dll").toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(effekseer)) {
            throw new IllegalStateException("Required Effekseer JNI library is missing: " + effekseer);
        }
        System.load(effekseer.toString());
        System.err.println("Effekseer JNI loaded: " + effekseer);
    }

    private static void preflight() {
        String[] classes = {
                "1424781030",
                "1190881385",
                "exbo.log.tinylog.TinylogLoggerHook",
                "org.lwjgl.glfw.GLFW",
                "org.lwjgl.opengl.GL",
                "imgui.ImGui",
                "exbo.stalcraft.engine.efk.client.Effekseer",
                "com.github.luben.zstd.Zstd"
        };
        for (String name : classes) {
            try {
                Class<?> loaded = Class.forName(name, true, StandardJvmBootstrap.class.getClassLoader());
                if (name.equals("exbo.log.tinylog.TinylogLoggerHook")) {
                    loaded.getDeclaredConstructor().newInstance();
                } else if (name.equals("1190881385")) {
                    loaded.getDeclaredConstructor().newInstance();
                } else if (name.equals("com.github.luben.zstd.Zstd")) {
                    loaded.getMethod("compressBound", long.class).invoke(null, 1L);
                } else if (name.equals("exbo.stalcraft.engine.efk.client.Effekseer")) {
                    long manager = exbo.stalcraft.engine.efk.client.Effekseer
                            ._Effekseer_Manager_Create(16, true);
                    if (manager == 0L) throw new IllegalStateException(
                            "Effekseer manager smoke test returned a null handle");
                    exbo.stalcraft.engine.efk.client.Effekseer
                            .Effekseer_Manager_GetTotalInstanceCount(manager);
                    exbo.stalcraft.engine.efk.client.Effekseer.J_ReleaseManager(manager);
                }
                System.out.println("PREFLIGHT OK: " + name);
            } catch (Throwable failure) {
                System.err.println("PREFLIGHT FAILED: " + name);
                print(failure, "  ");
            }
        }
    }

    private static void print(Throwable failure, String prefix) {
        if (failure == null) return;
        System.err.println(prefix + failure.getClass().getName() + ": " + failure.getMessage());
        for (StackTraceElement frame : failure.getStackTrace()) {
            System.err.println(prefix + "  at " + frame);
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            System.err.println(prefix + "Suppressed:");
            print(suppressed, prefix + "  ");
        }
        if (failure.getCause() != null && failure.getCause() != failure) {
            System.err.println(prefix + "Caused by:");
            print(failure.getCause(), prefix + "  ");
        }
    }
}
