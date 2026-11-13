package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;

// (Tuning trade_blaze_off, a fix of 13 Nov 2026, on.) the ground of the blazes the trade walked off from with no fire
// resistance, a wall for the trade's walks for a while. the route's phase 2 handed the tick to the blaze camp whenever a blaze
// came within the fight's reach (the trade's YIELD), and with no potion that camp is a pillbox or a hiding pocket by the
// spawner: 4 of the 17 phase-2 losses of route-60 and route-61 (13 Nov 2026), each a death by the spawner 5 to 9 minutes on,
// on starts whose way to the wastes ran past a fortress. now the trade walks off the blazes instead, and the search's walks
// go round where they were. pure numbers: the trade writes, the path search reads (from baritone's thread, hence the
// immutable snapshot)
public final class TradeBlazeZones {

    // a blaze's sight (BlazeFightRules.SIGHT_RANGE): a walk that keeps this far off is not seen
    public static final double RADIUS = BlazeFightRules.SIGHT_RANGE;
    // the walk off the blazes ends this far from where they were, outside the wall and the trade's yield line (BLAZE_KEEP)
    public static final double RUN = 28;
    // game ticks a zone lives after its last renewal: long enough for the search to walk round, short enough that a way
    // only through it opens again
    public static final long LIFE_TICKS = 3 * 60 * 20L;
    // a blaze within this of a zone renews that one (blazes hover round their spawner)
    public static final double SAME = 12;
    public static final int MAX = 16;

    public static final TradeBlazeZones INSTANCE = new TradeBlazeZones();

    private record Zone(double x, double z, long until) {
    }

    private volatile List<Zone> zones = List.of();

    // a blaze (or its spawner) at (x, z) walked off from at game tick now
    public synchronized void note(double x, double z, long now) {
        List<Zone> next = new ArrayList<>();
        double nx = x, nz = z;
        for (Zone zone : zones) {
            if (zone.until < now || zone.until - LIFE_TICKS > now) continue;
            if (Math.hypot(zone.x - x, zone.z - z) <= SAME) continue;
            next.add(zone);
        }
        next.add(new Zone(nx, nz, now + LIFE_TICKS));
        while (next.size() > MAX) next.remove(0);
        zones = List.copyOf(next);
    }

    // whether (x, z) is a wall for a walk from (px, pz) at game tick now: within RADIUS of a live zone, and the walker not
    // (the walk off and the way out of the zone the buddy stands in stay open). called by the path search for its nodes
    public boolean wall(double x, double z, long now, double px, double pz) {
        List<Zone> list = zones;
        if (list.isEmpty()) return false;
        double r2 = RADIUS * RADIUS;
        for (Zone zone : list) {
            if (zone.until < now || zone.until - LIFE_TICKS > now) continue;
            double dx = zone.x - x, dz = zone.z - z;
            if (dx * dx + dz * dz > r2) continue;
            double ex = zone.x - px, ez = zone.z - pz;
            if (ex * ex + ez * ez <= r2) continue;
            return true;
        }
        return false;
    }

    public int live(long now) {
        int n = 0;
        for (Zone zone : zones) if (zone.until >= now && zone.until - LIFE_TICKS <= now) n++;
        return n;
    }

    public void clear() {
        zones = List.of();
    }
}
