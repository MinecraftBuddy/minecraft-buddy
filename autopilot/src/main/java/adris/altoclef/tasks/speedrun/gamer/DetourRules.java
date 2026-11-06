package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.WalkCost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

// a resource on the way: while the phases mine, a block worth having that is close by gets a short detour, since we would come
// out for it later anyway and out here it is already in sight. coal was the first (a smelt that runs short of fuel sends us out
// for coal anyway), gravel for flint is the second, a diamond ore in sight the third. this is the deciding half (ResourceDetour is the half that touches the world,
// DetourSpec says what is different per resource). one instance holds one detour at a time. it starts on a strict test, stays
// on a looser one, and every way out is a named Step, so it cannot flap between ticks
public final class DetourRules {
    // staying costs more reach than starting did. the walk to the vein and the walk round it to pick up its drops move us a few
    // blocks, and a test that is the same on both sides of the line would drop the job the moment we step back
    public static final double KEEP_FACTOR = 1.5;
    // and the player may be this many BLOCKS (as the crow flies, not WalkCost: a vein 4 down is 16 to walk and we stand under it at
    // 6 down, and a leash that charged for height cut that detour short) times the start budget away from the spot it began at.
    // a vein at the edge of the keep reach has us standing a couple of blocks past it
    public static final double LEASH_FACTOR = 2.0;
    // a few seconds of peace after any coal detour, so it does not trade places with the head task every other second.
    // the default for new DetourRules(), each spec brings its own (DetourSpec.cooldownTicks)
    public static final long COOLDOWN_TICKS = 5 * 20;
    // the ore is gone and its drop is a tick or two from existing. waiting this long is how the last piece gets into the bag
    // instead of onto the floor behind us
    public static final long DROP_GRACE_TICKS = 20;
    // a detour that was not asked about for this long was not ours to run (a trip, a pickup had the wheel), and its clock
    // would bill us for that time
    public static final long GAP_TICKS = 40;
    // a drop this close is still ours to pick up, same reach the mining task picks drops first at
    public static final double DROP_RADIUS = 6;
    // the chat line is for a NEW cluster. a detour that restarts on the same one (a bed or a chest took the tick for a few seconds)
    // stays quiet unless the new start is further than this many blocks from the spot we last said it at, or it has been this
    // long since we did
    public static final double ANNOUNCE_BLOCKS = 8;
    public static final long ANNOUNCE_TICKS = 60 * 20;
    // the offset tables are cubes of this size at the most, a typo'd 500 in the config is not 40 million entries
    static final double MAX_BUDGET = 48;

    public enum Step {
        // nothing going on, nothing started
        IDLE,
        // started this tick
        START,
        // on it, keep mining
        KEEP,
        // the ore is gone and the drop is not up yet: give it a moment before calling it done
        SETTLE,
        // over: nothing we can see is left in the cluster, or we hold what the plan needs
        DONE,
        // over: mined the spec's block cap for one detour and the thing we came for never dropped (gravel's 10, flint is a 10% roll)
        CAPPED,
        // over: out of time, so whatever is still standing gets banned
        TIMEOUT,
        // over: led too far from where it began with ore still standing (the mining task gave up on a block and went for a vein
        // across the map), banned like a timeout
        STRAYED,
        // over: a never rule fired, or somebody else had the wheel
        BLOCKED
    }

    // what the phase knows this tick. the world half builds it, the rules never read the game. dimension = this resource may be
    // dug here at all (coal and diamond: the overworld, gravel: see DetourSpec.gravelPhase), tool = the spec's tool gate, held = what we hold
    // of the item we came for, need = what we stop at, resourceHead = the kit task is already on this exact thing, mined = blocks
    // broken since the detour began (Tally)
    public record Inputs(boolean dimension, boolean tool, int held, int need, boolean cookStation, boolean furnaceDue,
                         boolean loadInFlight, boolean foodLeads, boolean resourceHead, int mined) {
        // most callers have not mined anything yet, coal never counts
        public Inputs(boolean dimension, boolean tool, int held, int need, boolean cookStation, boolean furnaceDue,
                      boolean loadInFlight, boolean foodLeads, boolean resourceHead) {
            this(dimension, tool, held, need, cookStation, furnaceDue, loadInFlight, foodLeads, resourceHead, 0);
        }
    }

    // the numbers one spec runs on, read from the config (DetourSpec.limits). heldCap = the config's own "stop at" on top of the
    // need, maxTicks = the clock, mineCap = blocks per detour (0 = no cap, coal stops on what it holds)
    public record Limits(int heldCap, long maxTicks, int mineCap) {
    }

    // the questions about the world, asked lazily because the first three are block reads: a veined cave wall costs thousands.
    // start = a block worth starting on (in reach, in sight right now (DetourSight), breakable, not banned), keep = any we
    // can still see in the looser reach around where the detour began, drop = an item we came for on the floor close by that fits
    // in the bag, strayed = we are further from where it began than the leash
    public record Ore(BooleanSupplier start, BooleanSupplier keep, BooleanSupplier drop, BooleanSupplier strayed) {
    }

    // one cell of the reach table, relative to the player's feet
    public record Offset(int dx, int dy, int dz) {
    }

    private static final Map<Double, List<Offset>> TABLES = new ConcurrentHashMap<>();

    private final long cooldownTicks;
    private boolean running;
    private long startTick;
    private long lastTick;
    private long cooldownUntil;
    // when the last ore left the reach, -1 while there is some
    private long goneSince = -1;
    // when the block cap was hit, -1 before that (see going)
    private long cappedSince = -1;
    // the last spot and time the chat line went out at, for announce
    private boolean announced;
    private long announcedTick;
    private int announcedX;
    private int announcedY;
    private int announcedZ;

    // coal's cooldown
    public DetourRules() {
        this(COOLDOWN_TICKS);
    }

    public DetourRules(long cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    public boolean running() {
        return running;
    }

    public long startTick() {
        return startTick;
    }

    // the start test and the stay test, in the same unit (WalkCost)
    public static double keepBudget(double budget) {
        return budget * KEEP_FACTOR;
    }

    // how far from where it began the player may get, in blocks. the mining task goes for the nearest block it knows of at any
    // range, so a block it cannot reach sends it off to the next vein, and this is what cuts that walk short
    public static double leash(double budget) {
        return budget * LEASH_FACTOR;
    }

    // the player is this far (dx, dy, dz) from the spot the detour began at: past the leash
    public static boolean strayed(double dx, double dy, double dz, double budget) {
        double leash = leash(budget);
        return dx * dx + dy * dy + dz * dz > leash * leash;
    }

    // the things that are never worth a detour, for starting and for staying alike:
    //  - the wrong place for this resource, or the spec's tool gate says no (coal: stone pick or better, gravel: nothing)
    //  - a cook has picked its station, a furnace load is in flight (a screen open, a smelt task just had one), or a job is
    //    due (the collect trip goes first). a quick smoker stand-by never gets here, it cuts in on a detour
    //    (IronActivity.preempts, PrepSupport.tickStandBy on the old path)
    //  - the food or the cook leads the plan, or this resource is the head need and the kit task is already on it
    public static boolean blocked(Inputs in) {
        return !in.dimension() || !in.tool() || in.cookStation() || in.furnaceDue() || in.loadInFlight() || in.foodLeads()
                || in.resourceHead();
    }

    // what we hold covers the plan (and the config's own cap, if somebody set that lower). a need of 0 is always enough
    public static boolean enough(Inputs in, Limits lim) {
        return in.held() >= Math.min(in.need(), lim.heldCap());
    }

    // dug the spec's share for one detour. gravel stops at 10 blocks with or without flint, or every patch is a strip mine
    public static boolean capped(Inputs in, Limits lim) {
        return lim.mineCap() > 0 && in.mined() >= lim.mineCap();
    }

    // one call per tick the side job is asked about
    public Step tick(long now, Inputs in, Limits lim, Ore ore) {
        if (running && now - lastTick > GAP_TICKS) {
            lastTick = now;
            return end(now, Step.BLOCKED);
        }
        lastTick = now;
        return running ? going(now, in, lim, ore) : idle(now, in, lim, ore);
    }

    private Step idle(long now, Inputs in, Limits lim, Ore ore) {
        // the cheap questions first, the ore question is a world scan
        if (now < cooldownUntil || blocked(in) || enough(in, lim) || !ore.start().getAsBoolean()) {
            return Step.IDLE;
        }
        running = true;
        startTick = now;
        goneSince = -1;
        cappedSince = -1;
        return Step.START;
    }

    private Step going(long now, Inputs in, Limits lim, Ore ore) {
        if (blocked(in)) {
            return end(now, Step.BLOCKED);
        }
        if (enough(in, lim)) {
            return end(now, Step.DONE);
        }
        // after enough on purpose, and only once the last block's drop had its chance: the tally counts a block the tick it goes,
        // the flint shows up a tick or two later and still has to be walked over. a 10th gravel that drops the flint is a success
        if (capped(in, lim)) {
            if (cappedSince < 0) {
                cappedSince = now;
            }
            boolean waited = now - cappedSince >= DROP_GRACE_TICKS;
            // a drop we can't get to before the clock runs out is not worth more than the clock
            if (waited && !ore.drop().getAsBoolean() || now - startTick > lim.maxTicks()) {
                return end(now, Step.CAPPED);
            }
            return Step.SETTLE;
        }
        boolean standing = ore.keep().getAsBoolean();
        if (!standing && !ore.drop().getAsBoolean()) {
            if (goneSince < 0) {
                goneSince = now;
            }
            return now - goneSince >= DROP_GRACE_TICKS ? end(now, Step.DONE) : Step.SETTLE;
        }
        goneSince = -1;
        // after the done checks on purpose: a detour that finished on the last tick of its budget is a success, not a ban. and only
        // with ore still standing, a bot that wandered a bit far for the last drop has nothing to blame
        if (standing && ore.strayed().getAsBoolean()) {
            return end(now, Step.STRAYED);
        }
        return now - startTick > lim.maxTicks() ? end(now, Step.TIMEOUT) : Step.KEEP;
    }

    private Step end(long now, Step why) {
        running = false;
        goneSince = -1;
        cappedSince = -1;
        cooldownUntil = now + cooldownTicks;
        return why;
    }

    // something ranked above us took the tick (PrepSupport). that ends the detour, it is not a pause: the head task has the
    // wheel now, and if the ore is still there after the cooldown it is a fresh detour
    public void preempted(long now) {
        if (running) {
            end(now, Step.BLOCKED);
        }
    }

    // the phase left, nothing carries over
    public void reset() {
        running = false;
        goneSince = -1;
        cappedSince = -1;
        cooldownUntil = 0;
        lastTick = 0;
        announced = false;
    }

    // should the chat line go out for a detour that started at this spot just now. yes for the first one, for a start more than
    // ANNOUNCE_BLOCKS from the last place we said it, and for one more than ANNOUNCE_TICKS after the last time. a start that stays
    // quiet does not move either reference, so a detour that creeps along a long vein still says it every so often
    public boolean announce(long now, int x, int y, int z) {
        if (announced && now >= announcedTick && now - announcedTick <= ANNOUNCE_TICKS) {
            double dx = x - announcedX;
            double dy = y - announcedY;
            double dz = z - announcedZ;
            if (dx * dx + dy * dy + dz * dz <= ANNOUNCE_BLOCKS * ANNOUNCE_BLOCKS) {
                return false;
            }
        }
        announced = true;
        announcedTick = now;
        announcedX = x;
        announcedY = y;
        announcedZ = z;
        return true;
    }

    // every cell within the budget of the player, nearest walk first, so the caller can stop at the first block that counts.
    // ties go to the one nearer our own height, then to a fixed order so two ticks never disagree about which came first
    public static List<Offset> offsets(double budget) {
        return TABLES.computeIfAbsent(Math.min(Math.max(budget, 0), MAX_BUDGET), DetourRules::build);
    }

    private static List<Offset> build(double budget) {
        int flat = (int) Math.floor(budget);
        int tall = (int) Math.floor(budget / WalkCost.VERTICAL_WEIGHT);
        List<Offset> cells = new ArrayList<>();
        for (int dy = -tall; dy <= tall; dy++) {
            for (int dx = -flat; dx <= flat; dx++) {
                for (int dz = -flat; dz <= flat; dz++) {
                    if (WalkCost.within(dx, dy, dz, budget)) {
                        cells.add(new Offset(dx, dy, dz));
                    }
                }
            }
        }
        cells.sort(Comparator.comparingDouble((Offset o) -> WalkCost.estimate(o.dx(), o.dy(), o.dz()))
                .thenComparingInt(o -> Math.abs(o.dy())).thenComparingInt(Offset::dy).thenComparingInt(Offset::dx)
                .thenComparingInt(Offset::dz));
        return List.copyOf(cells);
    }

    // counts the blocks of ours we broke, for the per detour cap. there is no "you broke a block" event that fires (BlockBrokenEvent
    // has no poster), so we watch the block being broken and count it once it stops being ours. the falling column refills that
    // cell, but only a few ticks later: a 1 block drop is two ticks of waiting plus the fall, and we look every tick
    public static final class Tally {
        private boolean watching;
        private long watched;
        private int count;

        // breaking = a break is going on at pos right now, ours = is that cell still one of the blocks we are after
        public void tick(boolean breaking, long pos, LongPredicate ours) {
            if (watching && !ours.test(watched)) {
                count++;
                watching = false;
            }
            if (breaking && ours.test(pos)) {
                watching = true;
                watched = pos;
            } else if (!breaking) {
                // gave up halfway (the stale flag fell), a later fall of that block would not be our break
                watching = false;
            }
        }

        public int count() {
            return count;
        }

        public void reset() {
            watching = false;
            count = 0;
        }
    }
}
