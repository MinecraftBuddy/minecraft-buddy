package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.DangerMeter;
import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.SafeStand;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.pathing.goals.Goal;
import baritone.pathing.movement.CliffAvoidance.Cells;
import baritone.pathing.movement.RiskCost;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.Locale;
import java.util.function.Supplier;

// the buddy's fork (S137 step 3, risk in the path search): the flee search's goal. under Tuning risk_flee every run (the combat
// commitment's, and RunAwayFromHostilesTask's for the blaze fight, the item recovery and the forced drills) goes to the
// nearest cell, by the way's cost, where the live mob field (RiskField) prices standing at under the calm bound
// (risk_flee_calm, hp a second; the meter's calm, 1 hp in its 3 s horizon) and from which no knock ends in lava or a fall that
// hurts (SafeStand). the way there costs its ticks plus lambda times the hp the mobs are expected to take on it, lambda
// risk_flee_lambda times the travel search's (RiskCost.start): the danger on the way out is priced, whatever risk_mobs says.
// a cell's rate is the mobs' as they can be there within the field's horizon (a melee mob's reach by then, a shooter's sight):
// where the run ends the buddy stays, so arriving early does not make a cell calm.
// the estimate is that rate times lambda, the price of a second standing there. not 0: with 0 every node's estimate ties the
// start's, and a search that runs out of time before a calm cell keeps no best node; with it, the best so far is the cheapest
// way to the least danger.
// one goal object is asked on two threads: a search asks the judge it was bound to at its start (RiskCost.start, the search's
// own field and chunk copy), the game thread the one refresh made from the newest field and the live world
public final class GoalFlee implements Goal, SnapshotGoal {

    // the meter's calm, T under 1 hp in its horizon, as hp a second
    public static final double CALM = DangerMeter.CALM_T / DangerMeter.HORIZON;
    // the flee search's lambda over the travel search's: the hp on the way out weighs more than the time
    public static final double LAMBDA_FACTOR = 2;
    // a cell's rate is what a second standing there costs (the field's exposure is in ticks)
    static final double SECOND = 20;

    public static boolean on() {
        return Tuning.get("risk_flee", 0) >= 1;
    }

    public static double calm() {
        return Tuning.get("risk_flee_calm", CALM);
    }

    public static double lambdaFactor() {
        return Tuning.get("risk_flee_lambda", LAMBDA_FACTOR);
    }

    // the game thread's view of the world, and the world the field must be of (the field of another world has no mobs here)
    private final Supplier<Cells> _cells;
    private final Supplier<Object> _world;
    private volatile Judge _main;
    private final ThreadLocal<Judge> _bound = new ThreadLocal<>();

    public GoalFlee(Supplier<Cells> cells, Supplier<Object> world) {
        _cells = cells;
        _world = world;
    }

    // the game thread, once a tick: a judge of the newest field when there is a new one
    @Override
    public void refresh() {
        RiskField.Version field = RiskField.current();
        Judge judge = _main;
        if (judge != null && judge.version == field && judge.owner == Thread.currentThread()) return;
        Cells cells = _cells.get();
        RiskField.Pricer mobs = field.world() == _world.get() && !field.sources().isEmpty() ? pricer(field, cells, 0) : null;
        _main = new Judge(field, mobs, cells, calm(), 0, Thread.currentThread());
    }

    // a search's judge, on the search's thread before it makes its first node
    public void bind(Judge judge) {
        _bound.set(judge);
    }

    // the judge for this thread: the search's, else the game thread's own; none for a thread neither belongs to
    private Judge judge() {
        Judge judge = _bound.get();
        if (judge != null) return judge;
        judge = _main;
        return judge != null && judge.owner == Thread.currentThread() ? judge : null;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        Judge judge = judge();
        return judge != null && judge.calm(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        Judge judge = judge();
        return judge == null ? 0 : judge.heuristic(x, y, z);
    }

    // the mob field priced at the horizon's reach, all mobs counted (a flee has no mob it goes to)
    public static RiskField.Pricer pricer(RiskField.Version field, Cells cells, double offset) {
        RiskField.Pricer mobs = new RiskField.Pricer(field, cells, offset, Tuning.get("risk_mob_horizon", RiskCost.MOB_HORIZON) * 20,
                (int) Tuning.get("risk_mob_rays", RiskCost.MOB_RAYS), s -> false);
        return mobs.isEmpty() ? null : mobs;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GoalFlee{calm %.2f hp/s}", calm());
    }

    // one thread's look at the cells: the rate the mobs take there a second (memoised per cell) and whether a knock from it is
    // safe. mobs null: no mob counts, every cell is calm and only the knock is judged
    public static final class Judge {
        final RiskField.Version version;
        final RiskField.Pricer mobs;
        final Cells cells;
        final double calm;
        final double lambda;
        final Thread owner;
        private final Long2DoubleOpenHashMap _rates = new Long2DoubleOpenHashMap();
        private final Long2ByteOpenHashMap _safe = new Long2ByteOpenHashMap();

        public Judge(RiskField.Version version, RiskField.Pricer mobs, Cells cells, double calm, double lambda, Thread owner) {
            this.version = version;
            this.mobs = mobs;
            this.cells = cells;
            this.calm = calm;
            this.lambda = lambda;
            this.owner = owner;
            _rates.defaultReturnValue(-1);
        }

        // hp a second the mobs take from us standing with our feet in (x, y, z), as they can be there within the horizon
        public double rate(int x, int y, int z) {
            if (mobs == null) return 0;
            long key = pack(x, y, z);
            double rate = _rates.get(key);
            if (rate < 0) {
                rate = mobs.hp(x, y, z, Double.POSITIVE_INFINITY, SECOND, null);
                _rates.put(key, rate);
            }
            return rate;
        }

        public boolean calm(int x, int y, int z) {
            if (rate(x, y, z) >= calm) return false;
            long key = pack(x, y, z);
            byte safe = _safe.get(key);
            if (safe == 0) {
                safe = SafeStand.unsafe(cells, x, y, z) ? (byte) 2 : (byte) 1;
                _safe.put(key, safe);
            }
            return safe == 1;
        }

        public double heuristic(int x, int y, int z) {
            return lambda * rate(x, y, z);
        }
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }
}
