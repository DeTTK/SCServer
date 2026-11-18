package szserver.net;

import java.util.Properties;

/** Legacy defaults, with independent bounded catalogue IO and demand windows. */
public record ChunkStreamingSettings(int distance,int batchSize) {
    public ChunkStreamingSettings {
        if(distance<1||distance>ChunkWindow.MAX_RADIUS)throw new IllegalArgumentException("chunk_distance_out_of_bounds");
        if(batchSize<1||batchSize>128)throw new IllegalArgumentException("chunk_manifest_batch_limit");
    }
    public static ChunkStreamingSettings defaults(){return new ChunkStreamingSettings(20,1);}
    public static ChunkStreamingSettings configured(Properties p){return new ChunkStreamingSettings(Integer.parseInt(p.getProperty("game.chunkDistance","20")),Integer.parseInt(p.getProperty("game.chunkBatchSize","1")));}
    public int outstandingLimit(){return Math.max(64,Math.min(256,batchSize*4));}
}
