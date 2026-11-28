package fpv;

/** Virtual right stick driven by the game's captured cursor. */
final class MouseStick {
    private long window;
    private double previousX, previousY;
    private double roll, pitch;

    void sample(long currentWindow, double x, double y, boolean center, double travel) {
        if (window != currentWindow || center) {
            roll = pitch = 0;
        } else {
            roll = clamp(roll + (x - previousX) / travel);
            pitch = clamp(pitch - (y - previousY) / travel);
        }
        window = currentWindow;
        previousX = x;
        previousY = y;
    }

    void reset() {
        window = 0;
        roll = pitch = 0;
    }

    double roll() { return roll; }
    double pitch() { return pitch; }

    private static double clamp(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
