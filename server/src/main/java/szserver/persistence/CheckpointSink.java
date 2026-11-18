package szserver.persistence;

import com.google.gson.JsonObject;
import java.io.IOException;

/** Checkpoint row transport; durable files and resident save pages share the same state reducers. */
public interface CheckpointSink {
    void begin(String section)throws IOException;
    void row(JsonObject row)throws IOException;
    void end()throws IOException;
    interface Logical extends CheckpointSink {
        /** Already checked against the canonical checkpoint row size/parser budget. */
        void logicalRow(JsonObject row,byte[] encoded)throws IOException;
    }
}
