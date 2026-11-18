package szserver.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Workers {
    private Workers() {}
    public static ThreadPoolExecutor pool(String name, int threads, int backlog) {
        AtomicInteger ids = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(backlog), task -> {
                Thread t = new Thread(task, name+"-"+ids.incrementAndGet()); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
    public static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) throw new IllegalStateException("worker_shutdown_timeout");
        }
    }
}
