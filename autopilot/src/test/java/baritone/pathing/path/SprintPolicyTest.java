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
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class SprintPolicyTest {

    // a world made of three piles of cells, nothing else. air is whatever is in none of them
    private static final class Grid implements SprintPolicy.Terrain {
        final Set<List<Integer>> solid = new HashSet<>();
        final Set<List<Integer>> lava = new HashSet<>();
        final Set<List<Integer>> hazards = new HashSet<>();

        static List<Integer> key(int x, int y, int z) {
            return Arrays.asList(x, y, z);
        }

        // floor at y -1 from x 0 to 9, a lane of z 0 only
        Grid floor() {
            for (int x = 0; x <= 9; x++) {
                solid.add(key(x, -1, 0));
            }
            return this;
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            return lava.contains(key(x, y, z)) || hazards.contains(key(x, y, z));
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return lava.contains(key(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return !solid.contains(key(x, y, z));
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }
    }

    // landing at 2,0,0 heading +x, the one block drop everything here is planned around
    private static boolean overshootSafe(Grid grid) {
        return SprintPolicy.overshootSafe(grid, 2, 0, 0, 1, 0, 1);
    }

    @Test
    public void descendWithASafeOvershootSprints() {
        Grid grid = new Grid().floor();
        assertTrue(overshootSafe(grid));
        assertTrue(SprintPolicy.descendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 1, 0), overshootSafe(grid)));
        // 45 degrees (descend then diagonal) is still the same way
        assertTrue(SprintPolicy.descendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 1, 1), true));
    }

    @Test
    public void descendIntoLavaOvershootDoesNot() {
        Grid grid = new Grid().floor();
        grid.solid.remove(Grid.key(3, -1, 0));
        grid.lava.add(Grid.key(3, -1, 0));
        assertFalse(overshootSafe(grid));

        Grid farther = new Grid().floor();
        farther.lava.add(Grid.key(4, 0, 0)); // two cells past the landing, standing in it
        assertFalse(overshootSafe(farther));
        assertFalse(SprintPolicy.descendKeepsSprint(false, 0, overshootSafe(farther)));
    }

    @Test
    public void descendStopsForTheOtherHazardsToo() {
        for (int x : new int[]{3, 4}) {
            Grid fire = new Grid().floor();
            fire.hazards.add(Grid.key(x, 0, 0)); // fire, cactus, berries, water, powder snow all arrive as hazards
            assertFalse(overshootSafe(fire));

            Grid magma = new Grid().floor();
            magma.hazards.add(Grid.key(x, -1, 0)); // magma is a floor you can stand on, which is the problem
            assertFalse(overshootSafe(magma));
        }
        Grid landingInWater = new Grid().floor();
        landingInWater.hazards.add(Grid.key(2, 0, 0));
        assertFalse(overshootSafe(landingInWater));
    }

    @Test
    public void descendStopsAtADropDeeperThanPlanned() {
        Grid cliff = new Grid().floor();
        cliff.solid.remove(Grid.key(4, -1, 0));
        assertFalse(overshootSafe(cliff)); // nothing at all under it, as far as the eye can see (the void)

        Grid step = new Grid().floor();
        step.solid.remove(Grid.key(4, -1, 0));
        step.solid.add(Grid.key(4, -2, 0));
        assertTrue(overshootSafe(step)); // one down is the descend we already signed up for
        assertFalse(SprintPolicy.overshootSafe(step, 2, 0, 0, 1, 0, 0)); // but not on a path that planned a flat landing

        Grid deep = new Grid().floor();
        deep.solid.remove(Grid.key(3, -1, 0));
        deep.solid.add(Grid.key(3, -4, 0));
        assertFalse(overshootSafe(deep));
        assertTrue(SprintPolicy.overshootSafe(deep, 2, 0, 0, 1, 0, 3)); // a planned 3 block fall is allowed to carry on
    }

    @Test
    public void aWallInTheWayIsNotADanger() {
        Grid grid = new Grid().floor();
        grid.solid.add(Grid.key(3, 0, 0));
        grid.lava.add(Grid.key(4, -1, 0)); // behind the wall, nobody is getting there
        assertTrue(overshootSafe(grid));
    }

    @Test
    public void descendThatTurnsHardKeepsTodaysBehavior() {
        assertFalse(SprintPolicy.descendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 0, 1), true));
        assertFalse(SprintPolicy.descendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, -1, 0), true));
        assertFalse(SprintPolicy.descendKeepsSprint(true, 0, true));
    }

    @Test
    public void ascendKeepsSprintGoingStraightOnAndDropsItOnABend() {
        assertTrue(SprintPolicy.ascendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 1, 0))); // ascend into ascend
        assertTrue(SprintPolicy.ascendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 1, 1))); // 45 is still a descend's worth
        // the hop has to be entered in line too: a corner into it keeps the old heading's momentum
        assertTrue(SprintPolicy.turnDegrees(1, 0, 1, 0) <= SprintPolicy.STRAIGHT_ON);
        assertTrue(SprintPolicy.turnDegrees(1, 0, 3, 0) <= SprintPolicy.STRAIGHT_ON);
        assertFalse(SprintPolicy.turnDegrees(1, 0, 0, 1) <= SprintPolicy.STRAIGHT_ON);
        assertFalse(SprintPolicy.turnDegrees(1, 0, 1, 1) <= SprintPolicy.STRAIGHT_ON);
        assertFalse(SprintPolicy.ascendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, 0, 1)));
        assertFalse(SprintPolicy.ascendKeepsSprint(false, SprintPolicy.turnDegrees(1, 0, -1, 0)));
        assertFalse(SprintPolicy.ascendKeepsSprint(true, 0)); // careful beats straight
    }

    @Test
    public void aHopSprintsIntoADiagonalWhenItsCornersAreOpen() {
        double fortyFive = SprintPolicy.turnDegrees(1, 0, 1, 1);
        // the diagonal with open corners is a movement that sprints, and the 45 off the hop is a descend's worth of bend
        assertTrue(SprintPolicy.ascendSprintsInto(false, true, false, fortyFive));
        // a corner column in the way and the diagonal does not sprint itself, so there is nothing to carry the hop's sprint into
        assertFalse(SprintPolicy.ascendSprintsInto(false, false, false, fortyFive));
        // the hop into a diagonal that heads off at a right angle or back is a bend like any other
        assertFalse(SprintPolicy.ascendSprintsInto(false, true, false, SprintPolicy.turnDegrees(1, 0, 0, 1)));
        assertFalse(SprintPolicy.ascendSprintsInto(false, true, false, SprintPolicy.turnDegrees(1, 0, -1, 1)));
        assertFalse(SprintPolicy.ascendSprintsInto(false, true, true, fortyFive)); // careful beats everything
        // the movements that already sprinted still do, with no corners to ask about
        assertTrue(SprintPolicy.ascendSprintsInto(true, false, false, 0));
        assertFalse(SprintPolicy.ascendSprintsInto(true, false, false, SprintPolicy.turnDegrees(1, 0, 0, 1)));
    }

    @Test
    public void aHopStartsFromWithinTheLaneAndSkipNowAgreesWithSprintJump() {
        assertTrue(SprintPolicy.centered(0.15, true)); // skipNow used to say no past 0.1, SprintJump.plan said yes up to 0.2
        assertTrue(SprintPolicy.centered(0.2, true));
        assertFalse(SprintPolicy.centered(0.21, true));
    }

    @Test
    public void aFortyFiveDegreeCornerKeepsSprint() {
        double turn = SprintPolicy.turnDegrees(1, 0, 1, 1);
        assertEquals(45, turn, 0.001);
        assertTrue(SprintPolicy.cornerKeepsSprint(false, turn, false, false));
        assertTrue(SprintPolicy.cornerKeepsSprint(false, turn, true, false)); // hazards only count for the sharp ones
        assertTrue(SprintPolicy.cornerKeepsSprint(false, 60, false, false));
    }

    @Test
    public void aHundredAndTwentyDegreeCornerNearLavaDrops() {
        double turn = SprintPolicy.turnDegrees(2, 0, -1, 2);
        assertTrue(turn > 110 && turn < 130);
        Grid grid = new Grid().floor();
        grid.lava.add(Grid.key(3, -1, 1));
        boolean careful = SprintPolicy.careful(false, SprintPolicy.lavaNear(grid, Arrays.asList(new BlockPos(1, 0, 0))), 20, false);
        assertTrue(careful);
        assertFalse(SprintPolicy.cornerKeepsSprint(careful, turn, true, false));
        // other hazards do it without needing the careful predicate
        assertFalse(SprintPolicy.cornerKeepsSprint(false, turn, true, false));
        // and a sharp turn with nothing around is fine, a hairpin never is
        assertTrue(SprintPolicy.cornerKeepsSprint(false, 90, false, false));
        assertFalse(SprintPolicy.cornerKeepsSprint(false, 180, false, false));
    }

    @Test
    public void aTightCorridorKeepsItsOwnSteering() {
        // 1 wide corridor with a corner: the inside of the bend is a wall, so cutting it is walking into it
        BlockPos src = new BlockPos(0, 0, 0);
        BlockPos dest = new BlockPos(1, 0, 0);
        BlockPos next = new BlockPos(1, 0, 1);
        assertTrue(SprintPolicy.cornerBoxClear(src, dest, next, pos -> true));
        assertFalse(SprintPolicy.cornerBoxClear(src, dest, next, pos -> !pos.equals(new BlockPos(0, 0, 1))));
        assertFalse(SprintPolicy.cornerKeepsSprint(false, 90, false, true));
    }

    @Test
    public void carefulIsTheNetherLavaLowHealthOrBusyAndNothingElse() {
        assertFalse(SprintPolicy.careful(false, false, 20, false));
        assertTrue(SprintPolicy.careful(true, false, 20, false));
        assertTrue(SprintPolicy.careful(false, true, 20, false));
        assertTrue(SprintPolicy.careful(false, false, 6, false));
        assertFalse(SprintPolicy.careful(false, false, 6.5f, false));
        assertTrue(SprintPolicy.careful(false, false, 20, true));
        // and it vetoes both rules
        assertFalse(SprintPolicy.descendKeepsSprint(SprintPolicy.careful(true, false, 20, false), 0, true));
        assertFalse(SprintPolicy.cornerKeepsSprint(SprintPolicy.careful(true, false, 20, false), 45, false, false));
    }

    @Test
    public void lavaNearMeansThreeBlocksAndFourIsFine() {
        List<BlockPos> centers = Arrays.asList(new BlockPos(0, 0, 0));
        Grid close = new Grid();
        close.lava.add(Grid.key(3, 0, -3));
        assertTrue(SprintPolicy.lavaNear(close, centers));
        Grid below = new Grid();
        below.lava.add(Grid.key(0, -3, 0));
        assertTrue(SprintPolicy.lavaNear(below, centers));
        Grid far = new Grid();
        far.lava.add(Grid.key(4, 0, 0));
        assertFalse(SprintPolicy.lavaNear(far, centers));
        assertFalse(SprintPolicy.lavaNear(new Grid(), centers));
    }

    @Test
    public void turnDegreesIsTheUnsignedYawChange() {
        assertEquals(0, SprintPolicy.turnDegrees(1, 0, 1, 0), 0.001);
        assertEquals(90, SprintPolicy.turnDegrees(1, 0, 0, -1), 0.001);
        assertEquals(135, SprintPolicy.turnDegrees(1, 1, -1, 0), 0.001);
        assertEquals(180, SprintPolicy.turnDegrees(0, 1, 0, -1), 0.001);
        assertEquals(180, SprintPolicy.turnDegrees(0, 0, 1, 0), 0.001); // a pillar has no heading to continue
    }

    @Test
    public void earlyTurnSlidesTheAimTowardTheNextBlock() {
        BlockPos src = new BlockPos(0, 0, 0);
        BlockPos dest = new BlockPos(1, 0, 0);
        BlockPos next = new BlockPos(1, 0, 1);
        // a block and a half out, still aiming at our own dest, which is the movement's job
        assertNull(SprintPolicy.cornerAim(-0.4, 0.5, src, dest, next));
        // just inside the lead: still pointed at the middle of the dest block
        double[] far = SprintPolicy.cornerAim(0.5, 0.5, src, dest, next);
        assertEquals(1.5, far[0], 0.001);
        assertEquals(0.5, far[1], 0.001);
        // at the edge of the dest block, halfway to the next one
        double[] near = SprintPolicy.cornerAim(1.0, 0.5, src, dest, next);
        assertEquals(1.5, near[0], 0.001);
        assertEquals(1.0, near[1], 0.001);
        // pushed off the lane (knockback, ice, whatever), the movement can sort it out
        assertNull(SprintPolicy.cornerAim(0.8, 0.95, src, dest, next));
        assertTrue(SprintPolicy.preTurnable(90));
        assertFalse(SprintPolicy.preTurnable(5));
        assertFalse(SprintPolicy.preTurnable(135));
    }

    @Test
    public void onlyInstantBreaksAreTrivialAndOnlyAFewOfThem() {
        assertTrue(SprintPolicy.trivialBreaks(new double[2], 0));
        assertTrue(SprintPolicy.trivialBreaks(new double[]{1.0, 2.5}, 2));
        assertFalse(SprintPolicy.trivialBreaks(new double[]{1.0, 0.99}, 2)); // two ticks is still a wait
        assertFalse(SprintPolicy.trivialBreaks(new double[]{0, 5}, 2)); // can't be mined at all
        assertFalse(SprintPolicy.trivialBreaks(new double[]{-1}, 1));
        assertFalse(SprintPolicy.trivialBreaks(new double[]{Double.NaN}, 1));
        assertFalse(SprintPolicy.trivialBreaks(new double[]{1, 1, 1}, 3)); // a tunnel
    }

    @Test
    public void theColumnBesideTheLaneIsOnTheSideWeLeanTo() {
        assertArrayEquals(new int[]{0, 1}, SprintPolicy.sideStep(1, 0, 0.05, 0.2));
        assertArrayEquals(new int[]{0, -1}, SprintPolicy.sideStep(-1, 0, 0.3, -0.1));
        assertArrayEquals(new int[]{-1, 0}, SprintPolicy.sideStep(0, 1, -0.2, 0.4));
        assertArrayEquals(new int[]{1, 0}, SprintPolicy.sideStep(0, -1, 0.2, -0.4));
        assertArrayEquals(new int[]{0, 0}, SprintPolicy.sideStep(1, 0, 0.3, 0.0)); // dead centre across the lane
        assertArrayEquals(new int[]{0, 0}, SprintPolicy.sideStep(1, 1, 0.3, 0.3)); // a diagonal has no side
    }

    @Test
    public void openSideKeepsTheOldLaneWidth() {
        assertTrue(SprintPolicy.centered(0.2, true));
        assertFalse(SprintPolicy.centered(0.213, true));
    }

    @Test
    public void aWallBesideTheLaneTakesTheClearanceBack() {
        // 0.2 off plus the 0.3 half width is flush with the wall, which is the graze that cost the sprint
        assertFalse(SprintPolicy.centered(0.2, false));
        assertFalse(SprintPolicy.centered(0.213, false));
        assertTrue(SprintPolicy.centered(0.15, false));
        assertTrue(SprintPolicy.centered(0.0, false));
    }
}
