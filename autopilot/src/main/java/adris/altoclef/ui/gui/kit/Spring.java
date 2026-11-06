package adris.altoclef.ui.gui.kit;

// a critically damped spring. retargeting carries the velocity too, so a reversal does not hit an invisible wall
public final class Spring {

    private double from;
    private double to;
    private double velocity;
    private double omega;
    private double tolerance;
    private long startNanos;

    public Spring(float initial) {
        snap(initial);
    }

    public void set(float target, long nowNanos, int responseMs) {
        if (target == to) {
            return;
        }
        double currentVelocity = velocity(nowNanos);
        double current = value(nowNanos);
        if (!Anim.enabled || responseMs <= 0) {
            snap(target);
            return;
        }
        from = current;
        to = target;
        velocity = currentVelocity;
        startNanos = nowNanos;
        // ten time constants gets within a tenth of a percent at the requested response
        omega = 10_000.0 / responseMs;
        tolerance = 0.001 * Math.max(1.0, Math.abs(to - from));
    }

    public void snap(float value) {
        from = value;
        to = value;
        velocity = 0.0;
        omega = 0.0;
    }

    private double seconds(long nowNanos) {
        return Math.max(0L, nowNanos - startNanos) / 1_000_000_000.0;
    }

    private double displacement(long nowNanos) {
        double t = seconds(nowNanos);
        double d = from - to;
        return (d + (velocity + omega * d) * t) * Math.exp(-omega * t);
    }

    private double rawVelocity(long nowNanos) {
        if (omega == 0.0 || !Anim.enabled) {
            return 0f;
        }
        double t = seconds(nowNanos);
        double b = velocity + omega * (from - to);
        return (velocity - omega * b * t) * Math.exp(-omega * t);
    }

    public float velocity(long nowNanos) {
        return running(nowNanos) ? (float) rawVelocity(nowNanos) : 0f;
    }

    public float value(long nowNanos) {
        return running(nowNanos) ? (float) (to + displacement(nowNanos)) : (float) to;
    }

    public boolean running(long nowNanos) {
        return Anim.enabled && omega > 0.0
                && Math.abs(displacement(nowNanos)) + Math.abs(rawVelocity(nowNanos)) / omega > tolerance;
    }

    public float target() {
        return (float) to;
    }
}
