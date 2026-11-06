package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// the pure half of "can this mob actually get to us". no world, no entities, just numbers and a Terrain, so it can be
// tested without booting minecraft. MobReachability feeds it real data.
public final class MobReachRules {

    // a mob is 0.6 wide and walks up 0.6 high steps. anything lower than that is a speed bump, not a wall
    public static final double STEP_HEIGHT = 0.6;

    // "more than 3 blocks up or down while basically on top of us". past that a zombie is not getting there by walking
    public static final double VERTICAL_GAP = 3;
    public static final double VERTICAL_GAP_HORIZONTAL_RANGE = 4;

    // no closer by a block over two seconds means it is stuck on something
    public static final double STALL_PROGRESS = 1;
    public static final long STALL_TICKS = 40;
    // a mob this close is arriving, not stuck. the tracker only watches the gap, and our own dodging and backing off keep
    // the gap from closing, so the zombie right behind us used to read as stalled and get dropped from the threat count
    public static final double STALL_EXEMPT_RANGE = CombatRules.CONTACT_RANGE;
    // and while we are running (the low hp latch, or a danger run installed) anything this close is the thing we are running
    // from: a mob that keeps pace with us never gets a block closer, which is all a stall is
    public static final double FLEEING_STALL_RANGE = CombatRules.LOW_HP_RANGE;

    private MobReachRules() {
    }

    public enum Verdict {
        REACHABLE, ENCLOSED, VERTICAL_GAP, STALLED;

        public boolean reachable() {
            return this == REACHABLE;
        }
    }

    public interface Terrain {
        // collision tall enough that a mob at this height has to go around (taller than a step). doors, trapdoors, fence
        // gates and fluids say false here, zombies break doors and swim
        boolean wall(int x, int y, int z);

        // any collision at all, same exclusions. what a mob's head and body bonk into
        boolean solid(int x, int y, int z);
    }

    // standing at feet block (x, y, z): ceiling over the head and all four sides closed. a mob needs 2 blocks of clearance
    // so a side counts as closed if either the feet cell or the head cell is blocked, a 1 high gap lets nobody through
    public static boolean isEnclosed(Terrain t, int x, int y, int z) {
        if (!t.solid(x, y + 2, z)) return false;
        return sideClosed(t, x + 1, y, z) && sideClosed(t, x - 1, y, z)
                && sideClosed(t, x, y, z + 1) && sideClosed(t, x, y, z - 1);
    }

    private static boolean sideClosed(Terrain t, int x, int y, int z) {
        return t.wall(x, y, z) || t.solid(x, y + 1, z);
    }

    // a creeper inside the defuse step's trigger is never "stuck": it is the one mob that kills from standing still
    public static final double CREEPER_EXEMPT_RANGE = CreeperStep.TRIGGER;

    // how close a mob has to be to never count as stuck. running widens it, outside it the old forty ticks stand
    public static double stallExemptRange(boolean fleeing) {
        return stallExemptRange(fleeing, false);
    }

    public static double stallExemptRange(boolean fleeing, boolean creeper) {
        double range = fleeing ? Math.max(STALL_EXEMPT_RANGE, FLEEING_STALL_RANGE) : STALL_EXEMPT_RANGE;
        return creeper ? Math.max(range, CREEPER_EXEMPT_RANGE) : range;
    }

    // a verdict that was cached while the mob was further out stops holding once it is in the exempt range. only the
    // stalled one: a wall is still a wall at two blocks
    public static boolean verdictHolds(Verdict cached, double distance) {
        return verdictHolds(cached, distance, false);
    }

    public static boolean verdictHolds(Verdict cached, double distance, boolean fleeing) {
        return verdictHolds(cached, distance, fleeing, false);
    }

    public static boolean verdictHolds(Verdict cached, double distance, boolean fleeing, boolean creeper) {
        return cached != Verdict.STALLED || distance > stallExemptRange(fleeing, creeper);
    }

    // directly above or below us, further than a mob can climb or safely drop. spiders skip this one, they climb
    public static boolean isVerticallyOut(double dx, double dy, double dz) {
        if (Math.abs(dy) <= VERTICAL_GAP) return false;
        return dx * dx + dz * dz <= VERTICAL_GAP_HORIZONTAL_RANGE * VERTICAL_GAP_HORIZONTAL_RANGE;
    }

    // watches how close each mob gets over time. a mob that is trying to reach us and is still no closer two seconds later
    // is stuck on a fence or a moat or a bad idea, and we stop caring about it
    public static final class StallTracker {
        // gaps in watching this long mean the clock is meaningless (we were busy eating, the mob was out of range, ...)
        private static final long FORGET_AFTER = 20;

        private static final class Entry {
            double best;
            long since;
            long lastSeen;
        }

        private final Map<Integer, Entry> entries = new HashMap<>();

        // record a sample, returns true if this mob has been stuck for STALL_TICKS
        public boolean update(int id, double distance, long now) {
            return update(id, distance, now, false);
        }

        // fleeing: we are running from something, see FLEEING_STALL_RANGE
        public boolean update(int id, double distance, long now, boolean fleeing) {
            return update(id, distance, now, fleeing, false);
        }

        public boolean update(int id, double distance, long now, boolean fleeing, boolean creeper) {
            Entry e = entries.get(id);
            if (e == null || now - e.lastSeen > FORGET_AFTER) {
                e = new Entry();
                e.best = distance;
                e.since = now;
                entries.put(id, e);
            }
            e.lastSeen = now;
            if (distance <= stallExemptRange(fleeing, creeper)) {
                // on top of us (or on our heels while we run), so the clock starts over from here: backing off from a zombie
                // that follows is not it being stuck
                e.best = distance;
                e.since = now;
                return false;
            }
            if (distance <= e.best - STALL_PROGRESS) {
                // got closer by enough, restart the clock from here
                e.best = distance;
                e.since = now;
            } else if (distance >= e.best + STALL_PROGRESS) {
                // walking away is not stuck, it is just leaving. start over from where it is
                e.best = distance;
                e.since = now;
            }
            return now - e.since >= STALL_TICKS;
        }

        // drop entries nobody asked about lately, so ids of dead mobs do not pile up forever
        public void prune(long now) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastSeen > FORGET_AFTER * 5) it.remove();
            }
        }

        public void clear() {
            entries.clear();
        }

        public int size() {
            return entries.size();
        }
    }
}
