package adris.altoclef.util.helpers;

// the buddy's fork: the forced fight's step out of the fire (the danger meter's count ladder: the fight arm stood where it
// was while the blazes' fire burned under it). a player in that fight steps off a burning block and fights on: from the
// feet, the nearest cell within RADIUS blocks across and one up or down where the feet and the head are clear and not
// burning and the floor is solid and not hot, a cell beside no fire before one beside fire. never a run, never cover: the
// fight goes on from there. pure: the game hands in its cells
public final class FireStep {
    // how far the step may go, across
    public static final int RADIUS = 3;
    // what a cell beside fire costs, in blocks: a fireball's fire spreads to the next cell
    static final double BY_FIRE = 2;
    // what a step up or down costs, in blocks
    static final double CLIMB = 0.5;

    private FireStep() {
    }

    public enum Cell {
        // nothing to stand in or on, and nothing that burns (air, a plant, a torch)
        OPEN,
        // a block to stand on that does not burn
        SOLID,
        // fire, lava, a magma block, a campfire: never stepped into or onto
        HOT,
        // not loaded on the client
        UNLOADED
    }

    public interface Cells {
        Cell at(int x, int y, int z);
    }

    // where to stand: its feet cell, the blocks across from the feet, and whether fire is beside it
    public record Spot(int x, int y, int z, double blocks, boolean byFire) {
    }

    // from the feet (fx, fy, fz): the spot, or null when no cell within RADIUS is out of the fire
    public static Spot find(Cells cells, int fx, int fy, int fz) {
        Spot best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    double flat = Math.sqrt(dx * dx + dz * dz);
                    if (flat > RADIUS) continue;
                    int x = fx + dx;
                    int y = fy + dy;
                    int z = fz + dz;
                    if (!standable(cells, x, y, z)) continue;
                    boolean byFire = cells.at(x + 1, y, z) == Cell.HOT || cells.at(x - 1, y, z) == Cell.HOT
                            || cells.at(x, y, z + 1) == Cell.HOT || cells.at(x, y, z - 1) == Cell.HOT;
                    double cost = flat + Math.abs(dy) * CLIMB + (byFire ? BY_FIRE : 0);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new Spot(x, y, z, flat, byFire);
                    }
                }
            }
        }
        return best;
    }

    // feet and head clear and not burning, on a floor that holds and does not burn
    static boolean standable(Cells cells, int x, int y, int z) {
        return cells.at(x, y - 1, z) == Cell.SOLID && cells.at(x, y, z) == Cell.OPEN && cells.at(x, y + 1, z) == Cell.OPEN;
    }
}
