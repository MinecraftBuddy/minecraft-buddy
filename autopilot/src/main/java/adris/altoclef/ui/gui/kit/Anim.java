package adris.altoclef.ui.gui.kit;

// an eased float. retargeting mid flight starts from wherever it currently is, so hovering on and off a button
// never jumps. time is whatever the frame says it is, never the clock
public final class Anim {

    // reduced motion: flip off and every duration is zero, values just land
    public static boolean enabled = true;

    private float from;
    private float to;
    private long startNanos;
    private long durNanos;

    public Anim(float initial) {
        from = initial;
        to = initial;
    }

    public static long ms(long millis) {
        return millis * 1_000_000L;
    }

    // asking for the target we are already heading to is a no-op, so draw can call this every frame
    public void set(float target, long nowNanos, int durationMs) {
        if (target == to) {
            return;
        }
        from = value(nowNanos);
        to = target;
        startNanos = nowNanos;
        durNanos = enabled ? ms(durationMs) : 0L;
    }

    public void snap(float v) {
        from = v;
        to = v;
        durNanos = 0L;
    }

    public float value(long nowNanos) {
        if (!enabled || durNanos <= 0L || nowNanos - startNanos >= durNanos) {
            return to;
        }
        float t = (nowNanos - startNanos) / (float) durNanos;
        return from + (to - from) * Easing.outCubic(t);
    }

    public boolean running(long nowNanos) {
        return enabled && durNanos > 0L && nowNanos - startNanos < durNanos;
    }

    public float target() {
        return to;
    }
}
