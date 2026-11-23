package exbo.stalcraft.engine.model.lktk;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.rctcwyvrn.blake3.Blake3;

/** Pure-Java replacement for native methods embedded in the protected VM. */
public final class JNI {
    private static final class State {
        volatile Blake3 hasher = Blake3.newInstance();
    }

    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();

    private JNI() {
    }

    public static long create_hasher() {
        long handle = NEXT_HANDLE.getAndIncrement();
        STATES.put(handle, new State());
        return handle;
    }

    public static void destroy_hasher(long handle) {
        STATES.remove(handle);
    }

    public static void blake3_hasher_init(long handle) {
        state(handle).hasher = Blake3.newInstance();
    }

    public static void blake3_hasher_init_keyed(long handle, ByteBuffer key) {
        state(handle).hasher = Blake3.newKeyedHasher(readFromStart(key, 32));
    }

    public static void blake3_hasher_init_derive_key(long handle, String context) {
        state(handle).hasher = Blake3.newKeyDerivationHasher(context);
    }

    public static void blake3_hasher_update(long handle, ByteBuffer input, int length) {
        state(handle).hasher.update(readFromStart(input, length));
    }

    public static void blake3_hasher_finalize(long handle, ByteBuffer output, int length) {
        byte[] digest = state(handle).hasher.digest(length);
        ByteBuffer target = output.duplicate();
        target.clear();
        target.put(digest);
    }

    private static State state(long handle) {
        State state = STATES.get(handle);
        if (state == null) throw new IllegalStateException("Invalid BLAKE3 handle: " + handle);
        return state;
    }

    private static byte[] readFromStart(ByteBuffer source, int length) {
        if (length < 0 || length > source.capacity()) {
            throw new IllegalArgumentException("Invalid buffer length: " + length);
        }
        ByteBuffer view = source.duplicate();
        view.clear();
        view.limit(length);
        byte[] bytes = new byte[length];
        view.get(bytes);
        return bytes;
    }
}
