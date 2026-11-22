package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;

// (Tuning slime_post, off: test slijmpost, 22 Nov 2026; the critic's B of 20 Nov 09:43, changed on 22 Nov 09:58) the slime
// hunt's post by a slime chunk already known. A slime seen below y 40 outside a swamp marks a slime chunk (38 of 40 such
// sightings lay in one by the seed formula, 22 Nov), and in 35 of the 49 runs with no ball after the slime phase's search the
// buddy had seen one earlier in the run. The game spawns nothing within 24 blocks of a player (NaturalSpawner) and a slime
// within 32 never despawns (Mob.checkDespawn), so the post stands 24 to 32 blocks from the sighting at its height: the
// chunk's slimes spawn out of the buddy's way and stay till the chase (48 across) takes them. The buddy (its Sightings)
// notes the slimes it sees; KillAndLootTask goes to the post when no slime is in chase range, stands there at most
// STAND_TICKS, and digs as before after that, or when no sighting is within REACH, or when the post is not reached in
// WALK_TICKS.
public final class SlimePost {
    // how far across from the sighting the post stands, on the line from the sighting to the buddy
    public static final double POST_FROM = 28;
    // the farthest sighting used, across from the buddy when the hunt begins
    public static final double REACH = 128;
    // the slimes taken for a sighting: below this height, outside a swamp
    public static final int BELOW_Y = 40;
    // two sightings this close across are one
    static final double SAME = 8;
    static final int KEPT = 32;
    public static final int STAND_TICKS = 15 * 60 * 20;
    public static final int WALK_TICKS = 6 * 60 * 20;

    private static final List<int[]> SEEN = new ArrayList<>();

    private SlimePost() {
    }

    public static boolean on() {
        return Tuning.get("slime_post", 0) > 0;
    }

    // a slime seen at (x, y, z), below BELOW_Y and outside a swamp (the caller checks the biome)
    public static synchronized void note(int x, int y, int z) {
        if (y >= BELOW_Y) return;
        for (int[] s : SEEN) {
            if (Math.hypot(s[0] - x, s[2] - z) <= SAME) return;
        }
        if (SEEN.size() >= KEPT) SEEN.remove(0);
        SEEN.add(new int[]{x, y, z});
    }

    // a new run remembers nothing
    public static synchronized void forget() {
        SEEN.clear();
    }

    public static synchronized int[] nearest(double px, double pz) {
        return nearest(SEEN, px, pz);
    }

    // the sighting nearest across within REACH, or null
    static int[] nearest(List<int[]> seen, double px, double pz) {
        int[] best = null;
        double bestD = REACH;
        for (int[] s : seen) {
            double d = Math.hypot(s[0] - px, s[2] - pz);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    // the post for a sighting: POST_FROM across from it toward the buddy, at the sighting's height; straight east of it when
    // the buddy stands on top of it
    public static int[] post(int[] seen, double px, double pz) {
        double dx = px - seen[0], dz = pz - seen[2];
        double d = Math.hypot(dx, dz);
        if (d < 1) {
            dx = 1;
            dz = 0;
            d = 1;
        }
        return new int[]{(int) Math.round(seen[0] + dx / d * POST_FROM), seen[1], (int) Math.round(seen[2] + dz / d * POST_FROM)};
    }
}
