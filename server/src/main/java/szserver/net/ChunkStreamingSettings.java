package szserver.net;

import java.util.Properties;

/** Legacy defaults, with independent bounded catalogue IO and demand windows. */
public record ChunkStreamingSettings(int distance,int batchSize) {
    private static final int DEFAULT_BATCH_SIZE=16;
    public ChunkStreamingSettings {
        if(distance<1||distance>ChunkWindow.MAX_RADIUS)throw new IllegalArgumentException("chunk_distance_out_of_bounds");
        if(batchSize<1||batchSize>128)throw new IllegalArgumentException("chunk_manifest_batch_limit");
    }
    public static ChunkStreamingSettings defaults(){return new ChunkStreamingSettings(20,DEFAULT_BATCH_SIZE);}
    public static ChunkStreamingSettings configured(Properties p){return new ChunkStreamingSettings(Integer.parseInt(p.getProperty("game.chunkDistance","20")),Integer.parseInt(p.getProperty("game.chunkBatchSize",Integer.toString(DEFAULT_BATCH_SIZE))));}
    public int outstandingLimit(){return Math.max(64,Math.min(256,batchSize*4));}
}
