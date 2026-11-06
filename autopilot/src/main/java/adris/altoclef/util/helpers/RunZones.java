package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;

// where a heavy hitter ran us off from, remembered for a few minutes. the sweep's path goes right back through the spot we
// were chased out of otherwise: same hoglins, same corridor, second fight. pure numbers, the combat brain writes and the
// sweep's path predicate reads (from baritone's thread, hence the immutable snapshot)
public final class RunZones {

    // the bastion zones are already 48 wide, a wall much fatter than this is a path that never exists
    public static final double RADIUS = 24;
    // a wither pack is a closet, not a bastion
    public static final double PACK_RADIUS = 12;
    // game ticks. three minutes is long enough for the hoglins to wander off and short enough that the zone is not forever
    public static final long EXPIRY_TICKS = 3 * 60 * 20L;
    // two runs from the same spot are one zone, a new one just refreshes the clock
    private static final double SAME_SPOT = 4;
    private static final int MAX = 16;

    public static final RunZones INSTANCE = new RunZones();

    private record Zone(double x, double z, long at, double radius) {
    }

    private volatile List<Zone> zones = List.of();

    // every zone carries its own radius, the bastion ones are fat and a pack of skeletons is not
    public void add(double x, double z, long now, double radius) {
        List<Zone> next = new ArrayList<>();
        for (Zone zone : zones) {
            // (the clock going backwards is another world, nothing from the old one means anything)
            if (zone.at > now || now - zone.at >= EXPIRY_TICKS) continue;
            if (Math.hypot(zone.x - x, zone.z - z) <= SAME_SPOT) {
                // the same spot again: the fatter of the two radii wins, a pack zone must not shrink a bastion one
                radius = Math.max(radius, zone.radius);
                continue;
            }
            next.add(zone);
        }
        next.add(new Zone(x, z, now, radius));
        while (next.size() > MAX) next.remove(0);
        zones = List.copyOf(next);
    }

    // swaps the whole list in one go, for a source that is rebuilt from scratch every so often (no empty gap for the other
    // thread to see). spots are {x, z}, all stamped now, so they live as long as the source keeps listing them
    public void replaceAll(List<double[]> spots, long now, double radius) {
        List<Zone> next = new ArrayList<>();
        for (double[] spot : spots) {
            next.add(new Zone(spot[0], spot[1], now, radius));
        }
        while (next.size() > MAX) next.remove(0);
        zones = List.copyOf(next);
    }

    public void clear() {
        zones = List.of();
    }

    // how many zones are still alive
    public int active(long now) {
        int n = 0;
        for (Zone zone : zones) {
            if (live(zone, now)) n++;
        }
        return n;
    }

    // is (x, z) inside a live zone that is allowed to be a wall. a zone we stand in is no wall (a hard ring around the player
    // is a path that never exists, same rule as the bastion zones) and neither is one the goal is in: the sweep has to get there,
    // and it is a three minute detour at worst, not a reason to give up. NaN goal = no goal known
    public boolean blocks(int x, int z, long now, double playerX, double playerZ, double goalX, double goalZ) {
        for (Zone zone : zones) {
            if (!live(zone, now) || !inside(zone, x, z)) continue;
            if (inside(zone, playerX, playerZ) || inside(zone, goalX, goalZ)) continue;
            return true;
        }
        return false;
    }

    // is (x, z) inside any live zone, no exemptions. this is for picking blazes, not paths: a blaze over the closet is the walk
    // into the closet whether we stand in the zone or not. every zone counts (two runs from one spot merge into the fatter one,
    // and in a fortress the fat ones are wither skeleton runs anyway)
    public boolean covers(double x, double z, long now) {
        for (Zone zone : zones) {
            if (live(zone, now) && inside(zone, x, z)) return true;
        }
        return false;
    }

    private static boolean live(Zone zone, long now) {
        return zone.at <= now && now - zone.at < EXPIRY_TICKS;
    }

    private static boolean inside(Zone zone, double x, double z) {
        double dx = zone.x - x;
        double dz = zone.z - z;
        // (NaN fails the compare, so a goal nobody knows about is never inside)
        return dx * dx + dz * dz < zone.radius * zone.radius;
    }
}
