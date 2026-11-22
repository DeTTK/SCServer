package szserver.client;
/** Loaded in each isolated adapter loader, so System.loadLibrary must use that loader's resolver. */
public final class NativeLibraryProbe {
    public static boolean roundTrip()throws Exception{
        System.loadLibrary("zstd-jni-1.5.0-5");Class<?> zstd=Class.forName("com.github.luben.zstd.Zstd");
        byte[] original="native NBT compression".repeat(128).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed=(byte[])zstd.getMethod("compress",byte[].class).invoke(null,(Object)original);
        byte[] restored=(byte[])zstd.getMethod("decompress",byte[].class,int.class).invoke(null,compressed,original.length);
        return java.util.Arrays.equals(original,restored);
    }
}
