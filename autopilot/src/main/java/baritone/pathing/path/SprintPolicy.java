/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Predicate;

// every "should we keep sprinting here" answer that is more than straight on, with no world in sight so it can be tested.
// PathExecutor asks the world questions through Terrain and hands the answers to these
public final class SprintPolicy {

    // vanilla baritone stops sprinting for every descend because the landing is the one place sprint momentum can carry
    // you somewhere you did not plan to be. these are the numbers for "how far, how sideways, how scared"
    static final float CAREFUL_HEALTH = 6;
    static final int LAVA_RADIUS = 3;
    static final double DESCEND_MAX_TURN = 45;
    // how far off the lane a hop can start and still go straight. SprintJump.plan, skipNow and MovementAscend's side check
    // all go through centered() so they agree, when they didn't the bot sprinted up to a hop and then dropped it for being 0.15 off
    public static final double MAX_SIDE_OFFSET = 0.2;
    // two headings that are the same one, give or take acos
    static final double STRAIGHT_ON = 1;
    static final double CORNER_SHARP_TURN = 90;
    static final double CORNER_HAIRPIN_TURN = 135;
    // below this it is a straight line with a wobble, smoothSteering's problem and not ours
    static final double CORNER_MIN_TURN = 20;
    // past this the early turn would be cutting across half the room
    static final double CORNER_MAX_PRETURN = 100;
    // sprint momentum carries about this many cells past a landing (a third of a block of coast on top of the fall)
    static final int OVERSHOOT_CELLS = 2;
    // start turning this far from the middle of the dest block. a movement is done at the near edge, half a block out
    static final double CORNER_LEAD = 1.0;
    static final double CORNER_EDGE = 0.5;
    static final double CORNER_MAX_LATERAL = 0.3;
    // acos gives 45.00000000000001 for a perfectly good 45
    private static final double TURN_SLACK = 0.5;

    private SprintPolicy() {}

    // what the executor knows about the blocks, in cells. hazard is anything you do not want to coast into: lava, fire,
    // magma, powder snow, cactus, berry bushes, and any fluid at all (a water edge changes the plan)
    interface Terrain {
        boolean hazard(int x, int y, int z);

        boolean lava(int x, int y, int z);

        boolean passable(int x, int y, int z);

        boolean standable(int x, int y, int z);
    }

    // degrees of yaw between two flat headings. no heading at all (a pillar, a straight drop) counts as a turn all the way around
    static double turnDegrees(int ax, int az, int bx, int bz) {
        double la = Math.hypot(ax, az);
        double lb = Math.hypot(bx, bz);
        if (la == 0 || lb == 0) {
            return 180;
        }
        double cos = (ax * bx + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static boolean within(double turn, double limit) {
        return turn <= limit + TURN_SLACK;
    }

    // the one predicate for "do it the old careful way". the nether, lava near the path, health at or under 6, or somebody
    // nearby is placing / breaking something (bridging, pillaring, parkour places) and needs the player where it put them
    static boolean careful(boolean nether, boolean lavaNear, float health, boolean busy) {
        return nether || lavaNear || health <= CAREFUL_HEALTH || busy;
    }

    // destroy progress per tick of 1 or more is a block that is gone the tick the click lands (that is vanilla's own rule for
    // instant mining), so there is nothing to wait for and nothing to stop sprinting over. an instant break goes through
    // clickBlock with no delay (BlockBreakHelper only sits out blockBreakSpeed - 1 ticks after a multi tick one), but every
    // one still costs a tick of aiming at the block, so a handful of them in a row is a tunnel, not a trivial break
    static final double INSTANT_BREAK = 1.0;
    static final int MAX_INSTANT_BREAKS = 2;

    // progress is each block's destroy progress per tick with the tool we'd use. no breaks at all is trivially trivial, and
    // anything slow, unbreakable (zero or negative) or unknown (NaN) is not
    static boolean trivialBreaks(double[] progress, int count) {
        if (count > MAX_INSTANT_BREAKS) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!(progress[i] >= INSTANT_BREAK)) { // NaN fails this too, which is what we want
                return false;
            }
        }
        return true;
    }

    static boolean lavaNear(Terrain terrain, List<BlockPos> centers) {
        for (BlockPos center : centers) {
            for (int x = -LAVA_RADIUS; x <= LAVA_RADIUS; x++) {
                for (int z = -LAVA_RADIUS; z <= LAVA_RADIUS; z++) {
                    // lava below us is the one that gets you at the bottom of a descend, so it gets the long end
                    for (int y = -LAVA_RADIUS; y <= LAVA_RADIUS - 1; y++) {
                        if (terrain.lava(center.getX() + x, center.getY() + y, center.getZ() + z)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // the landing and the OVERSHOOT_CELLS cells past it in the direction we are travelling. a wall is fine (we bonk it and
    // that is all), open air has to have a floor no further down than the fall the path already planned
    static boolean overshootSafe(Terrain terrain, int x, int y, int z, int dirX, int dirZ, int plannedDrop) {
        if (terrain.hazard(x, y, z) || terrain.hazard(x, y + 1, z) || terrain.hazard(x, y - 1, z)) {
            return false; // we are not even landing somewhere nice
        }
        for (int k = 1; k <= OVERSHOOT_CELLS; k++) {
            int cx = x + dirX * k;
            int cz = z + dirZ * k;
            if (terrain.hazard(cx, y, cz) || terrain.hazard(cx, y + 1, cz)) {
                return false;
            }
            if (!terrain.passable(cx, y, cz) || !terrain.passable(cx, y + 1, cz)) {
                return true; // nothing gets past this, so nothing further along matters
            }
            int floor = y - 1;
            int drop = 0;
            while (!terrain.standable(cx, floor, cz)) {
                // no floor at all (void, unloaded), a fluid or something solid we cannot stand on, or a deeper drop than planned
                if (terrain.hazard(cx, floor, cz) || !terrain.passable(cx, floor, cz) || ++drop > plannedDrop) {
                    return false;
                }
                floor--;
            }
            if (terrain.hazard(cx, floor, cz)) {
                return false; // magma, water. canWalkOn will happily say yes to both
            }
        }
        return true;
    }

    static boolean descendKeepsSprint(boolean careful, double turn, boolean overshootSafe) {
        return !careful && overshootSafe && within(turn, DESCEND_MAX_TURN);
    }

    // a hop up a block has nothing to coast over on the far side, the landing is a floor the path already checked. so it is
    // only the bend that matters, and a descend's worth of bend is the most we put up with
    static boolean ascendKeepsSprint(boolean careful, double turn) {
        return descendKeepsSprint(careful, turn, true);
    }

    // the hop's sprint goes on into the next movement when that one sprints itself and the bend is a gentle one. a diagonal is the
    // second kind: it has no say unless its corner columns are open, which is its own rule for sprinting and ours to borrow
    static boolean ascendSprintsInto(boolean nextSprints, boolean diagonalCornersOpen, boolean careful, double turn) {
        return (nextSprints || diagonalCornersOpen) && ascendKeepsSprint(careful, turn);
    }

    // 0.2 off the middle puts the side of the box (0.3 out from its centre) exactly on the cell edge, which with a wall on that
    // side is zero clearance, and a hair more is a graze that costs the sprint. with a wall there we want a little room
    public static final double MAX_SIDE_OFFSET_BESIDE_WALL = 0.15;

    // which column beside a straight lane the player is leaning towards, as {dx, dz}. {0, 0} when dead centre or on a diagonal
    public static int[] sideStep(int dirX, int dirZ, double leanX, double leanZ) {
        if (dirX != 0 && dirZ != 0) {
            return new int[]{0, 0};
        }
        return dirX != 0 ? new int[]{0, (int) Math.signum(leanZ)} : new int[]{(int) Math.signum(leanX), 0};
    }

    // sideOpen: nothing solid in the column next to the lane, on the side we are leaning to, at feet and head height
    public static boolean centered(double sideOffset, boolean sideOpen) {
        return sideOffset <= (sideOpen ? MAX_SIDE_OFFSET : MAX_SIDE_OFFSET_BESIDE_WALL);
    }

    // true means we keep sprint and steer the corner early. false just means nothing clever happens here, the movements
    // steer the way they always did (tight corridors especially, an early turn there is a wall)
    static boolean cornerKeepsSprint(boolean careful, double turn, boolean hazardNear, boolean tightCorridor) {
        if (careful || tightCorridor || turn > CORNER_HAIRPIN_TURN) {
            return false;
        }
        if (turn < CORNER_SHARP_TURN) {
            return true; // under 90 is a bend, hazards only start to matter once it is a proper turn
        }
        return !hazardNear;
    }

    // every cell the early turn could sweep: the box around where we are, where this movement ends and where the next one does
    static boolean cornerBoxClear(BlockPos src, BlockPos dest, BlockPos next, Predicate<BlockPos> clear) {
        int minX = Math.min(src.getX(), Math.min(dest.getX(), next.getX()));
        int maxX = Math.max(src.getX(), Math.max(dest.getX(), next.getX()));
        int minZ = Math.min(src.getZ(), Math.min(dest.getZ(), next.getZ()));
        int maxZ = Math.max(src.getZ(), Math.max(dest.getZ(), next.getZ()));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!clear.test(new BlockPos(x, src.getY(), z))) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean hazardAround(Terrain terrain, BlockPos center) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    if (terrain.hazard(center.getX() + x, center.getY() + y, center.getZ() + z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // where to look while we are inside the last CORNER_LEAD of a movement: the middle of the dest block, slid toward the
    // middle of the next one as we close in (halfway by the time the movement would finish). null is "keep your own aim",
    // which it is for anything that is not a clean approach down the lane
    static double[] cornerAim(double px, double pz, BlockPos src, BlockPos dest, BlockPos next) {
        double ax = dest.getX() - src.getX();
        double az = dest.getZ() - src.getZ();
        double len = Math.hypot(ax, az);
        if (len == 0) {
            return null;
        }
        double offX = px - (src.getX() + 0.5);
        double offZ = pz - (src.getZ() + 0.5);
        double lateral = Math.abs(offX * az - offZ * ax) / len;
        double left = len - (offX * ax + offZ * az) / len;
        if (lateral > CORNER_MAX_LATERAL || left > CORNER_LEAD || left < -CORNER_EDGE) {
            return null; // not close yet, or knocked off the lane and the movement can sort itself out
        }
        double slide = Math.max(0, Math.min(1, (CORNER_LEAD - left) / (CORNER_LEAD - CORNER_EDGE))) * 0.5;
        return new double[]{
                dest.getX() + 0.5 + (next.getX() - dest.getX()) * slide,
                dest.getZ() + 0.5 + (next.getZ() - dest.getZ()) * slide
        };
    }

    static boolean preTurnable(double turn) {
        return turn >= CORNER_MIN_TURN && within(turn, CORNER_MAX_PRETURN);
    }
}
