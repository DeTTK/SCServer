package playerscale;

/** Canonical 1943200621.const/do values, checked against all 13 native posture enums. */
public final class NativeDimensions {
    private NativeDimensions(){}
    private static final float[] HEIGHT={1.8f,1.5f,1.8f,.6f,.6f,1.8f,1.8f,.6f,1.5f,.6f,1.8f,1.8f,1.8f};
    private static final float[] EYE={1.71f,1.25f,1.538f,1.71f,.45f,1.71f,1.71f,.45f,1.71f,.45f,1.71f,1.71f,1.71f};
    private static void valid(int pose){if(pose<0||pose>=HEIGHT.length)throw new IllegalArgumentException("character_scale_posture");}
    public static float height(int pose){valid(pose);return HEIGHT[pose];}
    public static float eye(int pose){valid(pose);return EYE[pose];}
}
