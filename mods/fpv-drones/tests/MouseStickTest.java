package fpv;

public final class MouseStickTest {
    public static void main(String[] args) {
        MouseStick stick = new MouseStick();
        stick.sample(1, 900, 600, false, 100);
        check(stick, 0, 0, "first captured sample");
        stick.sample(1, 950, 575, false, 100);
        check(stick, .5, .25, "virtual stick movement");
        stick.sample(1, 950, 575, false, 100);
        check(stick, .5, .25, "holds deflection without movement");
        stick.sample(1, 1100, 900, false, 100);
        check(stick, 1, -1, "stick limits");
        stick.sample(1, 1090, 890, false, 100);
        check(stick, .9, -.9, "reverses immediately after saturation");
        stick.sample(1, 1080, 880, true, 100);
        check(stick, 0, 0, "center button");
        stick.sample(1, 1090, 870, false, 100);
        check(stick, .1, .1, "no accumulated movement while centering");
        stick.reset();
        stick.sample(1, 50, 50, false, 100);
        check(stick, 0, 0, "no jump after focus is restored");
        stick.sample(1, 100, 50, false, 100);
        stick.sample(2, 500, 500, false, 100);
        check(stick, 0, 0, "no jump when the game window changes");
        System.out.println("PASS FPV mouse stick baseline, axes, limits, centering and recapture");
    }

    private static void check(MouseStick stick, double roll, double pitch, String label) {
        if (Math.abs(stick.roll() - roll) > 1e-9 || Math.abs(stick.pitch() - pitch) > 1e-9)
            throw new AssertionError(label + ": " + stick.roll() + ", " + stick.pitch());
    }
}
