package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.DangerMeter.Level;
import java.util.List;

// the buddy's fork: the forced drills' switch (steering 116, the danger meter's study (b)). while the shadow meter is not calm
// the buddy fights or flees by force, whatever the mob defense and the blaze fight would do, so a drill can measure how the
// meter's risk at the start and at its peak predicts who dies under each. tuning key danger_force: 0 (the default) off,
// nothing changes; 1 fight: the meter's top threat (the mob adding the most to T) attacked where it is, no cover, no hiding,
// no run; 2 flee: away from what the meter counts (each by what it adds to T and push) until the meter has been calm
// CALM_TICKS, then back to the order. pure: DangerForceChain hands in the meter's level and its threats each tick
public final class DangerForce {
    public static final int OFF = 0;
    public static final int FIGHT = 1;
    public static final int FLEE = 2;
    // a flee ends once the meter has been calm this long (2 s)
    public static final long CALM_TICKS = 40;
    // another mob takes the fight over only when it adds this many times the target's T (a fight that hops between two
    // equal foes kills neither)
    public static final double SWITCH = 1.5;

    private DangerForce() {
    }

    public enum Act {
        NONE, FIGHT, FLEE
    }

    // one mob the meter counts: its entity id and kind, its feet, what it adds to T and to push (hp per 3 s)
    public record Threat(int id, String kind, double x, double y, double z, double t, double push) {
        double weight() {
            return t + push;
        }
    }

    // what to do this tick: the fight's target (its id, -1 else), or the crowd to flee from and its weighted centre
    public record Choice(Act act, Threat target, List<Threat> crowd, double cx, double cy, double cz) {
        public static final Choice NONE = new Choice(Act.NONE, null, List.of(), 0, 0, 0);
    }

    // the tuned value as one of the three
    public static int mode(double tuned) {
        long m = Math.round(tuned);
        return m == FIGHT ? FIGHT : m == FLEE ? FLEE : OFF;
    }

    // the fight's target: the mob adding the most to T, the current one kept unless another adds SWITCH times its T. a body
    // that only pushes (a calm zombified piglin by the lava) is no target: hitting it would make it a foe
    static Threat top(List<Threat> threats, int current) {
        Threat best = null;
        Threat kept = null;
        for (Threat th : threats) {
            if (th.t() <= 0) continue;
            if (best == null || th.t() > best.t()) best = th;
            if (th.id() == current) kept = th;
        }
        if (kept != null && best != null && best.t() < SWITCH * kept.t()) return kept;
        return best;
    }

    // the centre of the crowd, each mob weighted by what it adds to T and push (equal weights when none adds anything)
    static double[] centre(List<Threat> crowd) {
        double w = 0;
        double x = 0;
        double y = 0;
        double z = 0;
        for (Threat th : crowd) {
            w += th.weight();
            x += th.weight() * th.x();
            y += th.weight() * th.y();
            z += th.weight() * th.z();
        }
        if (w <= 0) {
            for (Threat th : crowd) {
                x += th.x();
                y += th.y();
                z += th.z();
            }
            w = crowd.size();
        }
        return w <= 0 ? new double[]{0, 0, 0} : new double[]{x / w, y / w, z / w};
    }

    // the choice over time
    public static final class Rule {
        private int _target = -1;
        private boolean _fleeing;
        private long _calmSince = Long.MIN_VALUE;
        private List<Threat> _crowd = List.of();

        public Choice step(int force, Level level, long tick, List<Threat> threats) {
            boolean calm = level == Level.CALM;
            if (force == FIGHT) {
                _fleeing = false;
                _crowd = List.of();
                Threat top = calm ? null : top(threats, _target);
                _target = top == null ? -1 : top.id();
                return top == null ? Choice.NONE : new Choice(Act.FIGHT, top, List.of(), top.x(), top.y(), top.z());
            }
            if (force != FLEE) {
                reset();
                return Choice.NONE;
            }
            _target = -1;
            if (!calm) {
                _fleeing = true;
                _calmSince = Long.MIN_VALUE;
                // (the last crowd stays while the meter is not calm and names nobody this sample: a mob out of sight a moment)
                if (!threats.isEmpty()) _crowd = List.copyOf(threats);
            } else if (_fleeing) {
                if (_calmSince == Long.MIN_VALUE) _calmSince = tick;
                if (tick - _calmSince >= CALM_TICKS) {
                    _fleeing = false;
                    _crowd = List.of();
                }
            }
            if (!_fleeing || _crowd.isEmpty()) return Choice.NONE;
            double[] c = centre(_crowd);
            return new Choice(Act.FLEE, null, _crowd, c[0], c[1], c[2]);
        }

        public void reset() {
            _target = -1;
            _fleeing = false;
            _calmSince = Long.MIN_VALUE;
            _crowd = List.of();
        }
    }
}
