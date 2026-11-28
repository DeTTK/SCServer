package playerscale;

/** Checks the scale hooks against the packaged game with the perspective agent active. */
public final class CameraAgentChainCheck {
    public static void main(String[] args) throws Exception {
        for (String name : new String[]{"1389510037", "873944585", "576005245", "1775200393"}) {
            Class.forName(name, false, ClassLoader.getSystemClassLoader()).getDeclaredMethods();
            if (!ScaleAgent.healthy || !ScaleAgent.transformed.contains(name)) {
                throw new AssertionError("Character scaling adapter did not load: " + name);
            }
        }
        System.out.println("PASS packaged character scaling camera, animation, firing and jump adapters");
    }
}
