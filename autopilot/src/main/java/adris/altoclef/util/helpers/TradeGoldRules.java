package adris.altoclef.util.helpers;

import java.util.List;
import net.minecraft.core.BlockPos;

// the buddy's fork, Tuning nether_gold: the pure rules of the trade's gold dug in the nether (TradeGold; steering 200 of 19 Nov
// 2026). what the step wants, when it folds the nuggets, when it is over, which ore it may take with piglins about
public final class TradeGoldRules {
    private TradeGoldRules() {
    }

    // the trade's gold (Tuning nether_gold_to): what the overworld kit carried before, 96 since 14 Nov 2026
    public static final int TARGET = 96;
    // the whole step at most this long (Tuning nether_gold_cap_s): the question to the maintainer reckoned 96 in one to two game
    // hours, and the overworld mine took five to nine
    public static final double CAP_S = 5400;
    // no new gold this long and the step is over (Tuning nether_gold_dry_s), the trade going on with the bag's
    public static final double DRY_S = 600;
    // no gold ore known within reach this long and the step is over (game seconds)
    public static final double NO_ORE_S = 60;
    // nuggets folded into ingots once the bag holds this many: one stack less one, so the nuggets never take a second slot
    public static final int FOLD_AT = 63;
    public static final int NUGGETS_PER_INGOT = 9;
    // a piglin within this many blocks of an ore on every axis keeps the pick off it: the anger box is the player's own
    // inflated by 16 (PiglinAi.angerNearbyPiglins), the player stands up to 4.5 from the ore it breaks, and a piglin walks a
    // couple of blocks while it breaks
    public static final double PIGLIN_CLEAR = 24;
    // a hostile this close and no craft is begun out in the open (the brief: never craft with danger near)
    public static final double DANGER_BLOCKS = 12;
    // the next anchor: known ore at most this far sideways from the feet, and this far up or down
    public static final double ANCHOR_BLOCKS = 96;
    public static final int ANCHOR_DY = 12;

    // gold in ninths: an ingot nine, a nugget one
    public static int ninths(int ingots, int nuggets) {
        return NUGGETS_PER_INGOT * ingots + nuggets;
    }

    // why the step does not begin, or null: it begins in the nether short of the target, with a table or wood for one, before
    // the trade's potion, the fortress and the first two rods
    public static String whyNot(int ingots, int nuggets, int target, boolean potion, boolean atFortress, int rods, boolean table) {
        if (potion) return "a fire resistance potion already in the bag";
        if (ingots >= target) return ingots + " gold in the bag already";
        if (rods >= FireResTradeRules.ROD_LIMIT) return rods + " rods already";
        if (atFortress) return "at the fortress already";
        if (!table) return "no crafting table and no wood for one";
        return null;
    }

    // why the step is over by a clock, or null: its cap, no new gold for the dry spell, or no ore known for NO_ORE_S (game ticks)
    public static String overWhy(long ran, long sinceRise, long noOre, long cap, long dry) {
        if (ran >= cap) return "the cap of " + cap / 20 + " s";
        if (sinceRise >= dry) return "no new gold for " + dry / 20 + " s";
        if (noOre >= (long) (20 * NO_ORE_S)) return "no gold ore known for " + (long) NO_ORE_S + " s";
        return null;
    }

    // whether the nuggets are folded now: a set of nine at least, and the target reached by folding, a stack full, or the end
    public static boolean fold(int ingots, int nuggets, int target, boolean ending) {
        if (nuggets < NUGGETS_PER_INGOT || ingots >= target) return false;
        return ingots + nuggets / NUGGETS_PER_INGOT >= target || nuggets >= FOLD_AT || ending;
    }

    // the nuggets the mine is to have in the bag: what the target still asks, never fewer than the bag holds
    public static int nuggetsWanted(int ingots, int nuggets, int target) {
        return Math.max(nuggets, NUGGETS_PER_INGOT * Math.max(0, target - ingots));
    }

    // no piglin within PIGLIN_CLEAR of the ore on every axis
    public static boolean clearOfPiglins(BlockPos ore, List<BlockPos> piglins) {
        for (BlockPos p : piglins) {
            if (Math.abs(p.getX() - ore.getX()) <= PIGLIN_CLEAR && Math.abs(p.getY() - ore.getY()) <= PIGLIN_CLEAR
                    && Math.abs(p.getZ() - ore.getZ()) <= PIGLIN_CLEAR) {
                return false;
            }
        }
        return true;
    }

    // an ore may be the next anchor: within ANCHOR_BLOCKS sideways of the feet and ANCHOR_DY up or down
    public static boolean anchorOk(int ox, int oy, int oz, int fx, int fy, int fz) {
        double dx = ox - fx, dz = oz - fz;
        return dx * dx + dz * dz <= ANCHOR_BLOCKS * ANCHOR_BLOCKS && Math.abs(oy - fy) <= ANCHOR_DY;
    }
}
