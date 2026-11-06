package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

// pure: when does the sweep stop walking to dig itself some blocks? the bridges over lava eat throwaways and nothing in the
// nether hands them back, so a bag that ran dry mid-walk turns every path after it into "0 blocks closer"
public final class NetherRestock {
    // under this many it is time to dig. the bar is high on purpose: with no spare block a pocket sealed by lava has nothing
    // to bridge out with (breaking next to lava is off and placing costs infinity), so the dig has to start while there is
    // still something to path with
    static final int LOW = 32;
    // after a waypoint was given up on the bag is the first suspect, so the bar is at least the walking one
    static final int GIVE_UP_LOW = 32;
    // a bag this thin digs again right away after a dry end, whatever the cooldown says (the cooldown is for a bag that is
    // merely getting low, and LOW is far too big a number to mean "empty")
    static final int EMPTY = 8;
    public static final int TARGET = 64;
    // what the first dig of a phase entry fills to. we walk in with netherEntryBlocks and the rest comes off the nether's own
    // walls. a number of its own: raising LOW and TARGET for this would hand the fallback dig to healthy bags and double the
    // death trip's dig
    public static final int ARRIVAL_TARGET = 128;
    // netherrack is everywhere but the digging can still go nowhere (lava all around): stop trying after this
    static final double MAX_SECONDS = 75;
    static final double COOLDOWN_SECONDS = 120;
    // one empty tracker snapshot is not the end of the blocks, same grace as the gold dig
    static final double EXHAUSTED_SECONDS = 4;

    private boolean active;
    private int activeStopAt = TARGET;
    private double startedAt;
    private int heldAtStart;
    private double exhaustedSince = Double.NaN;
    private double coolUntil = Double.NEGATIVE_INFINITY;
    // the last dig ended on the clock with a few blocks but not the target: the same spot would just eat another MAX_SECONDS,
    // so even an empty bag waits. (a clock end with nothing at all is a dry end, see update)
    private boolean clockEnd;
    // restocks in a row that ended because the dig had nothing left to go to (not the clock, not a full bag). the phase reads it to
    // tell "short" from "short and the band is empty"
    private int exhaustedEnds;
    private boolean arrivalAsked;
    // log only, what ended the last dig
    private String endWhy = "";

    public int exhaustedEnds() {
        return exhaustedEnds;
    }

    public void clearExhaustedEnds() {
        exhaustedEnds = 0;
    }

    public boolean isActive() {
        return active;
    }

    // true once in this restock's life. the phase makes a new NetherBlockRestock per phase entry and not per arrival, a reset on
    // arrival would have every food trip dig the whole stack again. same idea as portalRestockAsked in NetherRecoverTask
    public boolean takeArrivalAsk() {
        boolean first = !arrivalAsked;
        arrivalAsked = true;
        return first;
    }

    // the tracker lines, one when a dig starts and one when it ends
    public String startLine() {
        return "restock: dig start, " + heldAtStart + " blocks, filling to " + activeStopAt
                + (exhaustedEnds == 0 ? "" : ", arm's reach only");
    }

    public String endLine(int held, double now) {
        return "restock: dig end, " + heldAtStart + " -> " + held + " blocks in " + Math.round(now - startedAt) + " s, " + endWhy;
    }

    // the dig stays in a box around where it started, a few blocks either way in height and not far off sideways. on a ledge
    // everything level with the feet can be a cliff face a long way up or across, so the box reaches down a bit too: the
    // netherrack below the ledge is nearer than any of it. a bridge over lava is near lava, and that reject (nearLava) is what
    // keeps us from digging out the span we stand on. the one block under the feet is never fair game, it is the floor
    static final int BAND_RADIUS = 48;
    static final int BAND_BELOW = 6;
    static final int BAND_ABOVE = 4;

    public static boolean inRestockBand(BlockPos origin, BlockPos pos) {
        int dy = pos.getY() - origin.getY();
        if (dy < -BAND_BELOW || dy > BAND_ABOVE) {
            return false;
        }
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        if (dx == 0 && dz == 0 && dy == -1) {
            return false;
        }
        return dx * dx + dz * dz <= BAND_RADIUS * BAND_RADIUS;
    }

    // the band is only a search cap. what we walk on is judged live: level with the feet or above is a wall, below the feet
    // only a block with something solid under it, so a dug floor never opens onto a drop and its item lands where we can
    // reach it (a one wide path over nothing, dug ahead and behind, was the whole floor going)
    public static boolean keepsFooting(int feetY, BlockPos pos, boolean solidBelow) {
        return pos.getY() >= feetY || solidBelow;
    }

    // what the fallback dig may touch: blocks we can break from where we stand, no walk to them (survival reach is 4.5 from
    // the eyes, a little under it so the dig never needs the step it can't take)
    static final double DIG_REACH = 4;

    public static boolean inReach(Vec3 eye, BlockPos pos) {
        return eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= DIG_REACH * DIG_REACH;
    }

    // lava a couple of blocks off, or a few below, means the block is a shore or a pillar standing in a lava sea. only
    // checking the faces of the block itself misses that: digging toward a block walks us down onto the ones around it, and
    // those are what is holding us over the lava. netherrack away from lava is everywhere
    static final int LAVA_CLEAR_SIDE = 2;
    static final int LAVA_CLEAR_BELOW = 3;
    static final int LAVA_CLEAR_ABOVE = 1;

    public static boolean nearLava(BlockPos pos, Predicate<BlockPos> lava) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        // nearest rows first, a hit there ends it early
        for (int dy = 0; dy >= -LAVA_CLEAR_BELOW; dy--) {
            if (lavaInRow(pos, dy, lava, at)) return true;
        }
        for (int dy = 1; dy <= LAVA_CLEAR_ABOVE; dy++) {
            if (lavaInRow(pos, dy, lava, at)) return true;
        }
        return false;
    }

    private static boolean lavaInRow(BlockPos pos, int dy, Predicate<BlockPos> lava, BlockPos.MutableBlockPos at) {
        for (int dx = -LAVA_CLEAR_SIDE; dx <= LAVA_CLEAR_SIDE; dx++) {
            for (int dz = -LAVA_CLEAR_SIDE; dz <= LAVA_CLEAR_SIDE; dz++) {
                if (lava.test(at.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) return true;
            }
        }
        return false;
    }

    // held = every placeable throwaway in the bag. exhausted = the dig has nothing left to go to. true = dig (until the
    // target) instead of walking
    public boolean update(int held, boolean gaveUp, boolean hasPick, boolean exhausted, double now) {
        return update(held, gaveUp ? GIVE_UP_LOW : LOW, hasPick, exhausted, now);
    }

    // startBelow = the bag size that starts a dig. the sweep goes by LOW / GIVE_UP_LOW, a caller that knows a long walk is
    // coming can ask for a full top up
    public boolean update(int held, int startBelow, boolean hasPick, boolean exhausted, double now) {
        return update(held, startBelow, TARGET, hasPick, exhausted, now);
    }

    // stopAt = the bag size that ends the dig, for a caller that only wants a short top up
    public boolean update(int held, int startBelow, int stopAt, boolean hasPick, boolean exhausted, double now) {
        if (!hasPick) {
            active = false;
            endWhy = "no pick";
            return false;
        }
        if (active) {
            // every way out cools down, a finished restock re-triggering a few blocks later is how it ran 8 times a visit
            if (!exhausted) {
                exhaustedSince = Double.NaN;
            } else if (Double.isNaN(exhaustedSince)) {
                exhaustedSince = now;
            }
            boolean dry = !Double.isNaN(exhaustedSince) && now - exhaustedSince >= EXHAUSTED_SECONDS;
            if (held >= activeStopAt || dry || now - startedAt >= MAX_SECONDS) {
                // the whole clock without one block in the bag is a dry dig too: every pick was a path it couldn't make
                boolean empty = dry || held <= heldAtStart;
                exhaustedEnds = held < activeStopAt && empty ? exhaustedEnds + 1 : 0;
                // only the arrival dig asks for this much. it was a bonus: coming up dry on it with a healthy bag says nothing about
                // the bag, so it can't unlock the fallback dig or count toward goHomeForBlocks
                if (activeStopAt == ARRIVAL_TARGET && held >= LOW) {
                    exhaustedEnds = 0;
                }
                endWhy = held >= activeStopAt ? "full" : dry ? "dry" : "clock";
                clockEnd = held < activeStopAt && !empty;
                active = false;
                exhaustedSince = Double.NaN;
                coolUntil = now + COOLDOWN_SECONDS;
            }
            return active;
        }
        // an empty bag digs again right away, the cooldown is for a bag that is merely getting low. a dig that ran out the clock
        // is the exception, the same spot would just eat another MAX_SECONDS
        if (now < coolUntil && (held >= EMPTY || clockEnd)) {
            return false;
        }
        if (held < startBelow) {
            active = true;
            activeStopAt = stopAt;
            startedAt = now;
            heldAtStart = held;
            exhaustedSince = Double.NaN;
        }
        return active;
    }
}
