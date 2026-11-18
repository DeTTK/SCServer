package replay;

/** Reviewed bridge releases; packet codecs and recorded resource evidence stay pinned. */
final class ReplayClientCompatibility {
    private ReplayClientCompatibility() {}

    static boolean accepts(String name,String recorded,String installed) {
        // The October 26 bridge retains the environment clock fields and the
        // native loading, sky clock and cursor methods intercepted by ReplayAgent.
        // UI/settings and live-server fixes do not change the recording codec.
        // Bind both releases explicitly rather than accepting arbitrary rebuilds.
        return name.equals("StandardJvmBootstrap")
            && recorded.equals("a18ee4b8cf5ad7347fe6aa49a1a6aa09d45915a70d069604120089a406e2fe76")
            && installed.equals("7d8012d269a2106a21327b8a0b6bc6862728cb562df8faedf89010e5c50cdad0");
    }
}
