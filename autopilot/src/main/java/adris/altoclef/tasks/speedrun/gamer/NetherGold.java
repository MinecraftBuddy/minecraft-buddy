package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.world.phys.AABB;

// pure: the nether fallback for a bot that came through the portal with no gold piece. piglins are only calm with one on, so
// dig nuggets from nether gold ore, fold them into ingots and into the piece PiglinGold picks. every craft here is from the bag
// and a table (the overworld gate packs one, see PiglinGold.tableForNether), nothing goes and fetches anything, because the
// nether has nothing to fetch it with
public final class NetherGold {
    // a visit gets one try this long, under the 120 s stall timer
    public static final double CAP_SECONDS = 90;
    // only ore the tracker already knows about, and only this far from where the try began
    public static final double ORE_BLOCKS = 48;
    // and about our own height. nether gold sits in the walls and up by the roof too, and a dig to ore 40 up the cliff is a
    // pillar and a path along a ledge over the lava sea: one of those walked us off the edge. there is always more ore at our level
    public static final int ORE_DY = 6;
    // PiglinAi.angerNearbyPiglins: breaking a block in #guarded_by_piglins angers every piglin in the player's box inflated by
    // this, no line of sight asked
    public static final double ANGER_BLOCKS = 16;
    public static final int NUGGETS_PER_INGOT = 9;
    public static final int PLANKS_PER_TABLE = 4;
    public static final int PLANKS_PER_LOG = 4;

    private NetherGold() {
    }

    // what the bag holds, folded the way the crafts see it
    public record Bag(int nuggets, int ingots, int planks, int logs, boolean table) {
        int woodPlanks() {
            return planks + PLANKS_PER_LOG * logs;
        }
    }

    public enum Stage {
        // the bag cannot make the piece (no table and no wood for one)
        ABORT,
        PLANKS,
        TABLE,
        INGOTS,
        PIECE,
        MINE
    }

    // the box vanilla angers piglins in, around the player's own box
    // an ore this try may go for: inside the radius sideways and within ORE_DY up or down of where it began
    public static boolean oreInReach(int ox, int oy, int oz, int fx, int fy, int fz) {
        double dx = ox - fx, dz = oz - fz;
        return dx * dx + dz * dz <= ORE_BLOCKS * ORE_BLOCKS && Math.abs(oy - fy) <= ORE_DY;
    }

    public static AABB angerBox(AABB player) {
        return player.inflate(ANGER_BLOCKS);
    }

    // a table is there, or the wood for one is
    public static boolean canTable(Bag b) {
        return b.table() || b.woodPlanks() >= PLANKS_PER_TABLE;
    }

    // the ingots we would have if every set of nine nuggets were folded
    public static int ingotsAfterCraft(Bag b) {
        return b.ingots() + b.nuggets() / NUGGETS_PER_INGOT;
    }

    // nuggets we still need to dig for `need` ingots in total
    public static int nuggetsToMine(Bag b, int need) {
        return Math.max(0, NUGGETS_PER_INGOT * (need - b.ingots()) - b.nuggets());
    }

    // the one place that says what the next step is. a bag with no way to a table is ABORT even before the digging, there is
    // no point in nuggets that can never be folded
    public static Stage stage(Bag b, int need) {
        if (!canTable(b)) {
            return Stage.ABORT;
        }
        if (ingotsAfterCraft(b) < need) {
            return Stage.MINE;
        }
        if (!b.table()) {
            return b.logs() > 0 && b.planks() < PLANKS_PER_TABLE ? Stage.PLANKS : Stage.TABLE;
        }
        return b.ingots() >= need ? Stage.PIECE : Stage.INGOTS;
    }

    // may a try begin: a stage it can run, and ore it already knows about when that stage is the digging
    public static boolean mayStart(Bag b, int need, boolean oreKnown) {
        Stage s = stage(b, need);
        return s != Stage.ABORT && (s != Stage.MINE || oreKnown);
    }

    // the try is over when the clock says so, whatever the bag looks like
    public static boolean overTime(double secondsSinceStart) {
        return secondsSinceStart >= CAP_SECONDS;
    }

    // a latched off visit gets another go this long after it latched, so a wander or a dry anchor is not the end of it
    public static final double RETRY_SECONDS = 60;
    // tries a visit, the first one included
    public static final int MAX_TRIES = 3;

    // whether a latched off try may be re-armed: long enough ago, tries left, and ore the tracker knows about from here
    public static boolean mayRetry(double secondsSinceOver, int triesUsed, boolean oreKnownHere) {
        return secondsSinceOver >= RETRY_SECONDS && triesUsed < MAX_TRIES && oreKnownHere;
    }

    // a new player instance after a death: the visit's tries start over
    public static boolean resetOnDeath(int deathsSeen, int deathsNow) {
        return deathsNow != deathsSeen;
    }
}
