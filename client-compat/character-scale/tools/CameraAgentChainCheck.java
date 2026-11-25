package playerscale;

/** Loads the real camera class with both production agents active. */
public final class CameraAgentChainCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("1389510037", false, ClassLoader.getSystemClassLoader()).getDeclaredMethods();
        if (!ScaleAgent.healthy || !ScaleAgent.transformed.contains("1389510037")) {
            throw new AssertionError("Character scaling did not compose with the native F5 policy");
        }
        System.out.println("PASS camera policy and character scaling agent chain");
    }
}
