package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.DangerMeter.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

// the buddy's fork: the forced drills' switch (steering 116, the danger meter's study (b) and its count ladder (b2)). tuning
// key danger_force: 0 (the default) off, nothing changes; 1 fight, 2 flee. the arm locks the first time the shadow meter is
// not calm after the switch went on, and from then on keeps the wheel until the switch goes off (the rig takes it away at
// the end of its window), calm or not: the review of (b2) found arms that let go whenever the meter turned calm a moment,
// so the defaults took over and a flee walked back into its foes. locked, 1 fights the meter's top threat where it is (no
// cover, no hiding, no run), and with nobody counted now the strongest one remembered that is still alive; 2 runs from the
// weighted centre of every threat remembered since the lock that is still alive. with nobody left to fight or flee from, the
// arm stands where it is, still holding the wheel. pure: DangerForceChain hands in the meter's level, its threats and which
// remembered mobs still live
public final class DangerForce {
    public static final int OFF = 0;
    public static final int FIGHT = 1;
    public static final int FLEE = 2;
    // another mob takes the fight over only when it adds this many times the target's T (a fight that hops between two
    // equal foes kills neither)
    public static final double SWITCH = 1.5;

    private DangerForce() {
    }

    public enum Act {
        NONE, FIGHT, FLEE, STAND
    }

    // one mob the meter counts: its entity id and kind, its feet, what it adds to T and to push (hp per 3 s)
    public record Threat(int id, String kind, double x, double y, double z, double t, double push) {
        double weight() {
            return t + push;
        }
    }

    // what to do this tick: the fight's target (null else), or the crowd to flee from and its weighted centre. STAND: locked
    // with nobody to act on, the wheel held standing
    public record Choice(Act act, Threat target, List<Threat> crowd, double cx, double cy, double cz) {
        public static final Choice NONE = new Choice(Act.NONE, null, List.of(), 0, 0, 0);
        public static final Choice STAND = new Choice(Act.STAND, null, List.of(), 0, 0, 0);
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
    public static double[] centre(List<Threat> crowd) {
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
        private int _force = OFF;
        private boolean _locked;
        private long _lockedAt = Long.MIN_VALUE;
        private int _target = -1;
        // every mob the meter counted since the lock, by id, as last counted (its weight then, where it was then)
        private final Map<Integer, Threat> _seen = new LinkedHashMap<>();

        // force: the switch now; alive: whether a remembered mob still lives (and is in the loaded world)
        public Choice step(int force, Level level, long tick, List<Threat> threats, IntPredicate alive) {
            if (force != _force) {
                // off, or the other arm: from scratch (the next lock waits for the meter again)
                reset();
                _force = force;
            }
            if (force != FIGHT && force != FLEE) return Choice.NONE;
            if (!_locked) {
                if (level == Level.CALM) return Choice.NONE;
                _locked = true;
                _lockedAt = tick;
            }
            for (Threat th : threats) _seen.put(th.id(), th);
            _seen.values().removeIf(th -> !alive.test(th.id()));
            if (force == FIGHT) {
                Threat top = top(threats, _target);
                if (top == null) {
                    // nobody counted now (out of sight, out of range, the meter calm): the target if it lives, else the
                    // remembered one adding the most
                    top = _seen.get(_target);
                    if (top == null) top = top(new ArrayList<>(_seen.values()), -1);
                }
                _target = top == null ? -1 : top.id();
                return top == null ? Choice.STAND : new Choice(Act.FIGHT, top, List.of(), top.x(), top.y(), top.z());
            }
            if (_seen.isEmpty()) return Choice.STAND;
            List<Threat> crowd = List.copyOf(_seen.values());
            double[] c = centre(crowd);
            return new Choice(Act.FLEE, null, crowd, c[0], c[1], c[2]);
        }

        // locked: the meter was not calm once since the switch went on
        public boolean locked() {
            return _locked;
        }

        public long lockedAt() {
            return _lockedAt;
        }

        public void reset() {
            _force = OFF;
            _locked = false;
            _lockedAt = Long.MIN_VALUE;
            _target = -1;
            _seen.clear();
        }
    }
}
