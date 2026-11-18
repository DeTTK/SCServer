package szserver.util;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** A stop request is acknowledged before the main thread begins ordered resource close. */
public final class ServerLifecycle {
    private final CountDownLatch stop;
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final UUID boot=UUID.randomUUID();
    private final long started=System.nanoTime();
    public ServerLifecycle(CountDownLatch stop){this.stop=Objects.requireNonNull(stop);}
    public boolean request(){return stopping.compareAndSet(false,true);}
    public void signal(){stopping.set(true);stop.countDown();}
    public boolean stopping(){return stopping.get();}
    public Map<String,Object> status(){return Map.of("bootId",boot.toString(),"pid",ProcessHandle.current().pid(),"status",stopping()?"stopping":"running","uptimeSeconds",(System.nanoTime()-started)/1_000_000_000L);}
}
