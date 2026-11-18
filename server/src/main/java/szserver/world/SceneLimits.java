package szserver.world;

/** Transaction admission is charged by bytes and existing live-state budgets, not a128-object batch. */
public final class SceneLimits {
    public static final int IDENTITIES=100_000,PLACEMENTS=200_000,RAW_BYTES=16*1024*1024,COMPRESSED_BYTES=RAW_BYTES;
    public static final int PARTS=(COMPRESSED_BYTES*4/3+8192)/8192;
    private SceneLimits(){}
}
