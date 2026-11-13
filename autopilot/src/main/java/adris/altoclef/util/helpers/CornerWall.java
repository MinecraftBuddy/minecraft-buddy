package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// (the buddy's fork, Tuning unarmed_no_corner_fight, a fix of 13 Nov 2026) the rules of the wall an unarmed run puts up when it
// has no room (CombatCommit.wallInstead), no minecraft in them: CornerWallTask asks the world and places or digs.
// 26.3's melee mob swings only with a line from its eyes to ours (MeleeAttackGoal.canPerformAttack) and only at a box that
// overlaps its own in height (Mob.isWithinMeleeAttackRange inflates sideways only): a block at our head's height between us
// takes the line away, and two cells down where we stand our head is under the feet of a zombie at the rim
public final class CornerWall {
    private CornerWall() {
    }

    // a mob this far off the main axis toward it (blocks) gets the cell on the other axis too
    public static final double SIDE = 0.5;
    // how deep the dig goes: two puts the buddy's box under a zombie's feet at the rim, a third when the cell over the head has
    // nothing to put the top against (flat open ground)
    public static final int DEPTH = 2;
    public static final int MAX_DEPTH = 3;

    // the cells between the buddy and a mob dx/dz off (mob minus buddy), as {x, y, z} offsets from the buddy's feet cell, in the
    // order they go up: the head's height first, then the feet's, on the axis the mob is most along; then the same on the other
    // axis when the mob stands SIDE or more off it
    public static List<int[]> between(double dx, double dz) {
        List<int[]> out = new ArrayList<>();
        boolean xMain = Math.abs(dx) >= Math.abs(dz);
        int sx = dx >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;
        int[] main = xMain ? new int[]{sx, 0} : new int[]{0, sz};
        int[] other = xMain ? new int[]{0, sz} : new int[]{sx, 0};
        out.add(new int[]{main[0], 1, main[1]});
        out.add(new int[]{main[0], 0, main[1]});
        if ((xMain ? Math.abs(dz) : Math.abs(dx)) >= SIDE) {
            out.add(new int[]{other[0], 1, other[1]});
            out.add(new int[]{other[0], 0, other[1]});
        }
        return out;
    }

    // which way the wall goes up
    public enum Way {
        // shut in already where the buddy stands (a hole of an earlier round, the dead end of a tunnel): nothing to build
        BUILT,
        // a block in a free cell between the buddy and the mob, with a face beside it to click and a block in the bag
        BLOCK,
        // two down where the buddy stands, the top shut when there is a block and something to put it against
        DIG,
        // none of these: the fight, the last resort
        NONE
    }

    // shut: the buddy's two cells walled and roofed; cellFree: a cell between that a block from the bag can go into; diggable:
    // the two cells under the feet come out with the tools at hand and the floor under them holds, with no lava or water by
    public static Way pick(boolean shut, boolean cellFree, boolean diggable) {
        if (shut) return Way.BUILT;
        if (cellFree) return Way.BLOCK;
        if (diggable) return Way.DIG;
        return Way.NONE;
    }

    // kinds that are full, stay put and are still kept: what the route builds its stations, portal and trades from
    private static final Set<String> KEPT = Set.of("crafting_table", "obsidian", "crying_obsidian", "tnt", "pumpkin", "carved_pumpkin",
            "jack_o_lantern", "melon", "hay_block", "slime_block", "honey_block", "magma_block", "ice", "packed_ice", "blue_ice",
            "bookshelf", "sponge", "wet_sponge", "target", "glowstone", "sea_lantern", "redstone_lamp", "note_block", "jukebox",
            "beehive", "bee_nest");

    // a block from the bag that may go into the wall besides the throwaway list's: full, stays where it is put (no sand,
    // gravel or concrete powder), no block entity (chest, furnace, bed) and no leaves, and not a kind the route keeps (an ore,
    // the storage block of an ingot or a gem, a station). logs, planks, any stone and dirt are in
    public static boolean spare(String id, boolean falling, boolean entityBlock, boolean leaves, boolean fullCube) {
        if (id == null || falling || entityBlock || leaves || !fullCube) return false;
        if (KEPT.contains(id) || id.endsWith("_ore") || id.equals("ancient_debris")) return false;
        return !id.endsWith("_block") || id.equals("moss_block") || id.equals("snow_block");
    }
}
