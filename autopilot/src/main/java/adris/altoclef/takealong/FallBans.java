package adris.altoclef.takealong;

import java.util.ArrayList;
import java.util.List;

// (take_along_falling_ban, a fix of 14 Nov 2026) the take-along's bans for a falling kind (gravel, sand) by area: the x,z
// columns of a detour's box, whatever height. a gravel column at a pond's edge kept refilling from above, each fallen block
// landed on a position the block bans had not named (or just outside the box), and the next detour began at the same place;
// the flint rolled into the water each time. pure, so the rules can be tested without a world
public final class FallBans {
    // one area: the dimension's name, the box's columns (both ends counted) and the game tick it lasts until
    record Area(String dim, int loX, int loZ, int hiX, int hiZ, long until) {
        boolean holds(String d, int x, int z, long now) {
            return now < until && dim.equals(d) && x >= loX && x <= hiX && z >= loZ && z <= hiZ;
        }
    }

    private final List<Area> areas = new ArrayList<>();

    // the columns of the box from (loX, loZ) to (hiX, hiZ), both ends counted, banned until the game tick {@code until}; the
    // number of columns
    public int add(String dim, int loX, int loZ, int hiX, int hiZ, long until) {
        areas.add(new Area(dim, Math.min(loX, hiX), Math.min(loZ, hiZ), Math.max(loX, hiX), Math.max(loZ, hiZ), until));
        return (Math.abs(hiX - loX) + 1) * (Math.abs(hiZ - loZ) + 1);
    }

    // a block of a falling kind at this column is under an area ban
    public boolean banned(String dim, int x, int z, long now) {
        areas.removeIf(a -> now >= a.until());
        for (Area a : areas) {
            if (a.holds(dim, x, z, now)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return areas.size();
    }

    // the end of a detour that bans its box as an area: the key on, a falling kind among its targets, and out of time, led too
    // far, or capped with nothing of the kind got (three detours for flint under the ground each "capped, flint got 0")
    public static boolean areaBan(boolean on, boolean falling, String end, int got) {
        if (!on || !falling) {
            return false;
        }
        return "timeout".equals(end) || "strayed".equals(end) || "capped".equals(end) && got <= 0;
    }

    public interface Water {
        boolean at(int x, int y, int z);
    }

    // a block of a falling kind whose drop would land in water: water in one of the six cells round it, or beside the cell
    // under it, where the drop falls when the column has gone. the pickup leaves a drop in water alone, so such a block gives
    // nothing
    public static boolean dropWet(Water water, int x, int y, int z) {
        if (water.at(x + 1, y, z) || water.at(x - 1, y, z) || water.at(x, y + 1, z) || water.at(x, y - 1, z)
                || water.at(x, y, z + 1) || water.at(x, y, z - 1)) {
            return true;
        }
        int u = y - 1;
        return water.at(x + 1, u, z) || water.at(x - 1, u, z) || water.at(x, u, z + 1) || water.at(x, u, z - 1);
    }
}
