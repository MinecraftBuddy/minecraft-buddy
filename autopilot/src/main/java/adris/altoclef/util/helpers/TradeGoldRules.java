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
    // no gold ore known within reach this long, the search for it walking meanwhile, and the step is over (game seconds). (the
    // first smoke of 19 Nov 2026 gave up after 60 s with the tracker's first scan of the nether still to come, twice: no gold,
    // so no trade at all)
    public static final double NO_ORE_S = 300;
    // the search for ore keeps this far from a known spawner, and ore this close to one is never taken: the fortress's blazes,
    // and a trade that does not begin by the fortress (FireResTradeRules.FORTRESS_NEAR, and as much again)
    public static final double SPAWNER_KEEP = 2 * FireResTradeRules.FORTRESS_NEAR;
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

    // (Tuning gold_rounds) whether the gold step digs another round before the blazes without a potion: rounds allowed in all
    // (1 or less: off), the rounds so far, the ingots handed in all, the target, whether this round's step began, is still
    // running, and ended at a dead end (the pickaxes kept, the cap)
    public static boolean anotherRound(int rounds, int roundsSoFar, int handed, int target, boolean begun, boolean running,
                                       boolean deadEnd) {
        return rounds > 1 && roundsSoFar < rounds && handed < target && begun && !running && !deadEnd;
    }

    // (Tuning gold_stone_dig) what the gold step does with the pickaxes of iron or better at keep uses left in all or fewer:
    // DIG on, MAKE a stone pickaxe from the bag first, or END (the pickaxes kept for the way on). nether gold ore and
    // netherrack take a stone pickaxe, and the wear chain (ToolPick.chainOrder) swings a stone one before iron and diamond, so
    // with a usable stone pickaxe in the bag the kit's are spared. a craft never with danger near: the dig goes on with the
    // kit's down to floor uses meanwhile, then ends. a craft that failed once is not tried again
    public enum PickWay { DIG, MAKE, END }

    public static PickWay pickWay(boolean on, int ironUses, int keep, int floor, boolean stonePick, boolean canMake,
                                  boolean makeFailed, boolean danger) {
        if (ironUses >= keep) return PickWay.DIG;
        if (!on) return PickWay.END;
        if (stonePick) return PickWay.DIG;
        if (!canMake || makeFailed) return PickWay.END;
        if (danger) return ironUses >= floor ? PickWay.DIG : PickWay.END;
        return PickWay.MAKE;
    }

    // (Tuning gold_keep_stand_stone) the cobblestone a stone pickaxe may take: the bag's, less what the brewing stand keeps of
    // all its stone (cobblestone, blackstone, cobbled deepslate together; kept is 0 with a stand in the bag)
    public static int cobbleFree(int cobble, int standStone, int kept) {
        return Math.max(0, Math.min(cobble, standStone - kept));
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

    // an ore or a search point keeps SPAWNER_KEEP from the spawner (null: none known)
    public static boolean awayFromSpawner(int x, int z, BlockPos spawner) {
        return spawner == null || Math.hypot(x - spawner.getX(), z - spawner.getZ()) > SPAWNER_KEEP;
    }

    // the bearing the search's rings start on: from the spawner through the centre, away from the fortress; with none known, the
    // way the buddy faces (any)
    public static double searchFrom(double cx, double cz, BlockPos spawner, double fallback) {
        if (spawner == null || Math.hypot(cx - spawner.getX(), cz - spawner.getZ()) < 1) return fallback;
        return Math.atan2(cz - spawner.getZ(), cx - spawner.getX());
    }

    // the ore's band round the step's start height: BAND_DY up or down, and never lower than the lava sea's top and SEA_MARGIN
    // (the nether's sea level is the lava sea's top). the start's height, not the feet's: a band that went with the feet took
    // the buddy up a cliff and down under the lava sea in one smoke
    public static final int BAND_DY = 8;
    public static final int SEA_MARGIN = 4;
    // lava this near an ore keeps the pick off it
    public static final int LAVA_NEAR = 2;
    // this many cells or more with nothing to stand on right under an ore, and it is left: a break from on top drops the buddy
    public static final int DROP_MAX = 4;

    public static boolean dropTooDeep(int openUnder) {
        return openUnder >= DROP_MAX;
    }

    // (Tuning gold_sea_margin, a fix of 19 Nov 2026, 10 by default, 4 before) an ore 6 blocks over the lava sea in a basalt delta
    // was broken, the next step had air under it and the fall ended in the sea (heel-55-mc3, live watch): his rule, never
    // where a knock or a fall ends in lava
    public static int seaMargin() {
        return (int) Tuning.get("gold_sea_margin", SEA_MARGIN);
    }

    public static int bandLow(int startY, int seaLevel) {
        return Math.max(startY - BAND_DY, seaLevel + seaMargin());
    }

    public static int bandHigh(int startY) {
        return startY + BAND_DY;
    }

    // (Tuning gold_way_open, a fix of 19 Nov 2026, 2 by default, 0 off) the most columns of the straight way from the feet to an
    // ore that may be open under it: no floor within WAY_FLOOR_DOWN under the way's height there, or lava first. the step picked
    // ore by straight distance alone: across the lava sea on a bridge of its own, along basalt delta rims, and a ghast's ball or
    // a magma cube knocked the buddy off, 4 of 5 deaths in the step read on 19 Nov (heel-59, -60, -64)
    public static final int WAY_FLOOR_DOWN = 6;

    // (Tuning gold_search_fall, a fix of 22 Nov 2026, on) for the search's ring points: a floor more than this (and
    // WAY_FLOOR_DOWN) under the way at the feet's height is no floor, a fall from there kills (TradeGold, look 217)
    public static final int SEARCH_FALL = 16;

    public static boolean searchFallOn() {
        return Tuning.get("gold_search_fall", 0) > 0;
    }

    // (Tuning gold_search_recentre, a fix of 22 Nov 2026, on) the search's points all walked or left: the rings again round the
    // feet, at most RECENTRE_MAX times a step and only RECENTRE_AWAY or more from the last centre; with none of that the step
    // holds where it is till its no-ore clock ends it. before, the step had nothing to do and its parent walked to the blaze
    // spawner in each gap, 27 times in 27 minutes, a third of the step's time, on ways no rule checked (look 227, heel-203-mc2 r1);
    // in 24 h of heel 8 of the 21 deaths in this step were blazes and skeletons of the fortress
    public static final int RECENTRE_MAX = 4;
    public static final double RECENTRE_AWAY = 24;

    public static boolean searchRecentreOn() {
        return Tuning.get("gold_search_recentre", 0) > 0;
    }

    public static boolean recentreDue(boolean on, int done, double fromCentre) {
        return on && done < RECENTRE_MAX && fromCentre >= RECENTRE_AWAY;
    }

    public static int wayOpenMax() {
        return (int) Tuning.get("gold_way_open", 0);
    }

    // (Tuning gold_search_way, a fix of 21 Nov 2026, on) the search for ore with none known leaves a ring point whose straight way
    // from the feet crosses the lava sea in more than wayOpenMax columns (nothing to stand on from the feet's height down to the
    // sea, lava first): the ore trips never crossed it, but the search walk bridged over the sea in 5 of 63 segments (look 192)
    public static boolean searchWayOn() {
        return Tuning.get("gold_search_way", 0) > 0;
    }

    // whether the column at (x, z) is open under the way's height y
    public interface OpenColumn {
        boolean open(int x, int y, int z);
    }

    // the open columns of the straight way from the feet to the ore, one sample a block sideways, the height going straight
    // from the feet's to the ore's; the first and last WAY_ENDS samples are left out (the feet's own ground, and the ore's
    // face, which overADrop and the stand judge). stops counting past stopAt
    public static final int WAY_ENDS = 2;

    public static int openColumns(int fx, int fy, int fz, int ox, int oy, int oz, OpenColumn column, int stopAt) {
        int dx = ox - fx, dz = oz - fz;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int open = 0;
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int i = WAY_ENDS; i <= steps - WAY_ENDS; i++) {
            double t = i / (double) steps;
            int x = (int) Math.floor(fx + 0.5 + dx * t);
            int z = (int) Math.floor(fz + 0.5 + dz * t);
            if (x == lastX && z == lastZ) continue;
            lastX = x;
            lastZ = z;
            int y = (int) Math.round(fy + (oy - fy) * t);
            if (column.open(x, y, z) && ++open > stopAt) return open;
        }
        return open;
    }

    // an ore may be the next anchor: within ANCHOR_BLOCKS sideways of the feet and ANCHOR_DY up or down
    public static boolean anchorOk(int ox, int oy, int oz, int fx, int fy, int fz) {
        double dx = ox - fx, dz = oz - fz;
        return dx * dx + dz * dz <= ANCHOR_BLOCKS * ANCHOR_BLOCKS && Math.abs(oy - fy) <= ANCHOR_DY;
    }
}
