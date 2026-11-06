package adris.altoclef.tasks.movement;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

// every call the land aware wander makes, with no world in sight so a test can make them too. LandMemory and
// TimeoutWanderTask only do the asking of minecraft
public final class LandFrontier {

    // 7 of 9 columns, not most of them: a river running through a chunk leaves it on the land side, the far bank has
    // to stay something we can walk to
    private static final int WATER_COLS = 7;
    private static final int WATER_OF = 9;

    // a river is 2 or 3 s of swimming, so this is about distance and never about time
    static final double OPEN_WATER_BLOCKS = 32;
    // a crossing aims this far out, past that it just aims again
    static final int CROSSING_BLOCKS = 1024;
    static final double CROSSING_ARRIVED_BLOCKS = 64;

    public record Spot(int x, int z) {
    }

    private LandFrontier() {
    }

    public static boolean isWater(int waterCols, int total) {
        return total > 0 && waterCols * WATER_OF >= WATER_COLS * total;
    }

    // only ever next to land we actually saw. "next to anything that isn't water" is how you end up on unknown chunks all
    // the way out to sea. a chunk cached by an earlier session has no memory entry, it counts as land unless water is
    // right there too
    public static boolean frontierOk(boolean landNbr, boolean waterNbr, boolean oldNbr) {
        return landNbr || (oldNbr && !waterNbr);
    }

    // a shore in range means this is the far bank of something and we should keep going
    public static boolean openWater(boolean inWater, boolean onGround, double distToDry, boolean shoreFound) {
        return inWater && !onGround && distToDry >= OPEN_WATER_BLOCKS && !shoreFound;
    }

    // a wander of a few blocks is someone getting unstuck. it has no business swimming a minute back to land, and
    // GetToBlockTask hangs one of those under itself so it would turn back inside its own turn back
    public static boolean turnsBack(float distanceToWander) {
        return distanceToWander >= OPEN_WATER_BLOCKS;
    }

    // straight away from everything we know, that side has nothing to offer anymore. standing on the centroid there is no
    // away, so pick one
    public static Spot crossingTarget(int meX, int meZ, double centroidX, double centroidZ) {
        double dx = meX - centroidX;
        double dz = meZ - centroidZ;
        double len = Math.hypot(dx, dz);
        if (len < 1) {
            return new Spot(meX + CROSSING_BLOCKS, meZ);
        }
        return new Spot(meX + (int) Math.round(dx / len * CROSSING_BLOCKS), meZ + (int) Math.round(dz / len * CROSSING_BLOCKS));
    }

    // a real frontier is the whole test, no distance. the beach we left had none or we wouldn't have left, and a crossing
    // left over from a wander that ended has to let go right where it stands, inland is right there
    public static boolean reanchor(boolean dryLand, boolean frontierExists) {
        return dryLand && frontierExists;
    }

    public static boolean crossingArrived(double distToTarget) {
        return distToTarget < CROSSING_ARRIVED_BLOCKS;
    }

    // the chunks the swim was heading for: everything on the far side of dry land, seen from us. banning the whole list
    // would take the inland chunks at the same ring with it and send a coastal start out to sea. nothing ahead (we swam
    // sideways) still costs the closest one, or the next swim is this swim
    public static List<ChunkPos> toBan(int dryX, int dryZ, int meX, int meZ, List<ChunkPos> frontier) {
        List<ChunkPos> ahead = new ArrayList<>();
        ChunkPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (ChunkPos chunk : frontier) {
            int x = (chunk.x() << 4) + 8;
            int z = (chunk.z() << 4) + 8;
            if ((double) (x - dryX) * (meX - dryX) + (double) (z - dryZ) * (meZ - dryZ) > 0) {
                ahead.add(chunk);
            }
            double dist = Math.hypot(x - meX, z - meZ);
            if (dist < closestDist) {
                closestDist = dist;
                closest = chunk;
            }
        }
        if (ahead.isEmpty() && closest != null) {
            ahead.add(closest);
        }
        return ahead;
    }
}
