package fpv;

import java.util.ArrayDeque;

/** Game-thread FIFO; terminal events must not be replaced by newer snapshots. */
final class SnapshotInbox {
    private record Entry(Object connection, byte[] payload) {}
    private final ArrayDeque<Entry> packets=new ArrayDeque<>();
    void add(Object connection, byte[] payload) {
        if(packets.size()>=128) throw new IllegalStateException("FPV snapshot backlog exceeded");
        packets.addLast(new Entry(connection,payload.clone()));
    }
    byte[] poll(Object connection) {
        while(!packets.isEmpty()) {
            Entry entry=packets.removeFirst();
            if(entry.connection==connection) return entry.payload;
        }
        return null;
    }
}
