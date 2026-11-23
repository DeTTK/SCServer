/** Synthetic fixture: no game classes or assets. */
public final class StandardJvmBootstrap {
    public static String probe(Object game) { return buildControlState(game, 1); }
    private static String buildControlState(Object game, long frame) {
        throw new AssertionError("Mutating observer must not run in isolated QA");
    }
}
