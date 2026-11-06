package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.util.helpers.ReachRules;

// where to hit a crystal from. the old spot was up by it on the pillar top, which is the one place its blast sees all of us.
// it goes off at its feet, right on top of the one bedrock block it sits on, so any ray coming up at it from lower down runs
// into that bedrock first. one block out past the pillar's edge with our feet two under the crystal, all of our box is below
// it and hitting it from there is a flat 1 damage
public final class CrystalStand {
    // obsidian or bedrock at a spot, the only blocks a pillar is made of
    public interface PillarLookup {
        boolean pillar(int x, int y, int z);
    }

    // the fattest pillar is radius 5, so 8 steps out without leaving it means this isn't a pillar we know
    private static final int MAX_STEPS = 8;
    private static final double EYE = 1.62;
    // a crystal's box is 2 wide and 2 tall, sitting on its feet
    private static final double HALF_WIDTH = 1;
    private static final double HEIGHT = 2;
    // pillaring up 40 blocks and walking over fits in this with room. past it something is wrong and the dragon is waiting
    public static final int GIVE_UP_TICKS = 60 * 20;

    private CrystalStand() {
    }

    // the cardinal step from the crystal toward us, so the stand is on our side of the pillar
    public static int[] side(double crystalX, double crystalZ, double ourX, double ourZ) {
        double dx = ourX - crystalX;
        double dz = ourZ - crystalZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[]{dx < 0 ? -1 : 1, 0};
        }
        return new int[]{0, dz < 0 ? -1 : 1};
    }

    // feet cell of the stand, or null. the bedrock under the crystal is a single block, the obsidian stops one lower
    // (crystalY - 2), so that's the layer to walk out along until it isn't pillar any more
    public static int[] stand(int cx, int cy, int cz, int dx, int dz, PillarLookup lookup) {
        // not sitting on a pillar, no idea where the cover would be
        if (!lookup.pillar(cx, cy - 1, cz)) {
            return null;
        }
        for (int k = 1; k <= MAX_STEPS; k++) {
            int x = cx + k * dx;
            int z = cz + k * dz;
            if (!lookup.pillar(x, cy - 2, z)) {
                // the head cell is over the pillar's top, so it's clear on a vanilla one. if it isn't the shape is weird, pass
                return lookup.pillar(x, cy - 1, z) ? null : new int[]{x, cy - 2, z};
            }
        }
        return null;
    }

    // eye to the crystal's box from the middle of the stand cell, the number the server holds a hit to
    public static double standGap(int[] stand, double crystalX, double crystalY, double crystalZ) {
        return ReachRules.gap(stand[0] + 0.5, stand[1] + EYE, stand[2] + 0.5,
                crystalX - HALF_WIDTH, crystalY, crystalZ - HALF_WIDTH, crystalX + HALF_WIDTH, crystalY + HEIGHT, crystalZ + HALF_WIDTH);
    }

    // why we leave this crystal alone, or null to keep at it. a radius 5 pillar puts the stand just past the crystal reach
    public static String skipReason(int[] stand, double gap, int pursuitTicks) {
        if (stand == null) {
            return "no covered spot next to its pillar";
        }
        if (gap > ReachRules.CRYSTAL_REACH) {
            return String.format("the covered spot is %.1f from it", gap);
        }
        if (pursuitTicks > GIVE_UP_TICKS) {
            return "60 s on it and it's still there";
        }
        return null;
    }
}
