/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderStallTest {

    private static final BetterBlockPos CELL = new BetterBlockPos(10, 70, 10);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // placed against the wall to the west, the same level allowed (something above the cell)
    private static BuilderProcess.GoalAdjacent adjacent(boolean solid) {
        return new BuilderProcess.GoalAdjacent(CELL, CELL.west(), true, solid);
    }

    @Test
    public void aBreakNeverWaitsOnThePlanner() {
        // replanPending says yes to every planless cell that doesn't want a full cube, a break included
        assertFalse(BuilderProcess.heldForPlan(false, false, () -> true));
        assertTrue(BuilderProcess.heldForPlan(true, false, () -> true));
        // once the wait ran out a place cell goes like any other
        assertFalse(BuilderProcess.heldForPlan(true, true, () -> true));
        assertFalse(BuilderProcess.heldForPlan(true, false, () -> false));
    }

    @Test
    public void aSolidCellDoesntTakeFeetRightUnderIt() {
        assertFalse(adjacent(true).isInGoal(CELL.below()));
        // a torch doesn't care where our head is
        assertTrue(adjacent(false).isInGoal(CELL.below()));
    }

    @Test
    public void theRestOfAdjacentStillCounts() {
        BuilderProcess.GoalAdjacent goal = adjacent(true);
        assertFalse(goal.isInGoal(CELL));
        assertFalse(goal.isInGoal(CELL.west())); // the block we click on
        assertTrue(goal.isInGoal(CELL.east()));
        assertTrue(goal.isInGoal(CELL.north()));
        assertTrue(goal.isInGoal(CELL.east().below()));
        assertTrue(goal.isInGoal(CELL.above()));
        assertFalse(goal.isInGoal(CELL.below(2)));
    }

    @Test
    public void solidIsPartOfWhichGoalItIs() {
        // a flip between the two would cancel the path like any other goal change
        assertFalse(adjacent(true).equals(adjacent(false)));
        assertEquals(adjacent(true), adjacent(true));
        assertEquals(adjacent(true).hashCode(), adjacent(true).hashCode());
    }

    @Test
    public void nearIsWithinTheTrimDistance() {
        BetterBlockPos player = new BetterBlockPos(0, 64, 0);
        BetterBlockPos close = new BetterBlockPos(10, 64, 10); // 200, right on the edge
        BetterBlockPos far = new BetterBlockPos(11, 64, 10);
        List<BetterBlockPos> near = BuilderProcess.nearCells(List.of(close, far, player), player, BuilderProcess.NEAR_DIST_SQ);
        assertTrue(near.contains(close));
        assertTrue(near.contains(player));
        assertFalse(near.contains(far));
    }

    @Test
    public void theFarFallbackIsTheNearestFewPastTheEdge() {
        BlockPos player = new BlockPos(0, 64, 0);
        List<BetterBlockPos> all = new ArrayList<>();
        all.add(new BetterBlockPos(1, 64, 0)); // near, not a fallback
        for (int x = 100; x > 15; x--) {
            all.add(new BetterBlockPos(x, 64, 0));
        }
        List<BetterBlockPos> far = BuilderProcess.nearestFar(all, player, BuilderProcess.NEAR_DIST_SQ, 5);
        assertEquals(5, far.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(16 + i, far.get(i).x); // nearest first
        }
        // fewer far ones than k is all of them
        assertEquals(1, BuilderProcess.nearestFar(List.of(new BetterBlockPos(50, 64, 0), new BetterBlockPos(1, 64, 0)), player, BuilderProcess.NEAR_DIST_SQ, 48).size());
    }

    @Test
    public void theWatchdogGivesUpWhatThePathWasHeadedFor() {
        BetterBlockPos a = new BetterBlockPos(0, 64, 0);
        BetterBlockPos b = new BetterBlockPos(20, 64, 0);
        List<Pair<BetterBlockPos, Goal>> owners = List.of(new Pair<>(a, new GoalBlock(a.above())), new Pair<>(b, new GoalBlock(b.above())));
        BetterBlockPos feet = new BetterBlockPos(5, 64, 0);
        assertEquals(List.of(b), BuilderProcess.watchdogOwners(owners, b.above(), feet, 8));
        // no path: the nearest to our feet by heuristic
        assertEquals(List.of(a), BuilderProcess.watchdogOwners(owners, null, feet, 1));
        // a partial path that stopped short of b: nearest to where it stopped, not to us
        assertEquals(List.of(b), BuilderProcess.watchdogOwners(owners, new BetterBlockPos(17, 64, 0), feet, 1));
    }

    @Test
    public void thePlannerTakesTheGoallessOnesBeforeTheStaleOnes() {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        BetterBlockPos feet = new BetterBlockPos(0, 64, 0);
        BuilderProcess.CellPlan stale = new BuilderProcess.CellPlan(stair, List.of(new PlacementPlanner.Feet(new BetterBlockPos(3, 64, 3), null)));
        BuilderProcess.CellPlan parked = new BuilderProcess.CellPlan(stair, List.of(new PlacementPlanner.Feet(feet, null)));
        BuilderProcess.CellPlan nowhere = new BuilderProcess.CellPlan(stair, List.of());
        assertEquals(0, BuilderProcess.planOrder(parked, stair, feet));
        assertEquals(1, BuilderProcess.planOrder(null, stair, feet));
        assertEquals(1, BuilderProcess.planOrder(nowhere, stair, feet));
        // planned for something else, it's got nothing to stand on for this one
        assertEquals(1, BuilderProcess.planOrder(stale, Blocks.STONE_STAIRS.defaultBlockState(), feet));
        assertEquals(2, BuilderProcess.planOrder(stale, stair, feet));
    }

    @Test
    public void theWatchdogOnlyCountsGettingFurther() {
        WatchdogProgress p = new WatchdogProgress();
        BetterBlockPos dest = new BetterBlockPos(20, 64, 0);
        // the first look is always a new best
        assertTrue(p.progressed(100, dest, new BetterBlockPos(0, 64, 0)));
        // standing still, or bobbing back and forth, is nothing
        assertFalse(p.progressed(100, dest, new BetterBlockPos(0, 64, 0)));
        assertFalse(p.progressed(100, dest, new BetterBlockPos(-1, 64, 0)));
        assertFalse(p.progressed(100, dest, new BetterBlockPos(0, 64, 0)));
        // closer than ever is
        assertTrue(p.progressed(100, dest, new BetterBlockPos(1, 64, 0)));
        // a block going in, then out again, then in again: only the first one is new
        assertTrue(p.progressed(101, dest, new BetterBlockPos(1, 64, 0)));
        assertFalse(p.progressed(100, dest, new BetterBlockPos(1, 64, 0)));
        assertFalse(p.progressed(101, dest, new BetterBlockPos(1, 64, 0)));
        assertFalse(p.progressed(101, null, new BetterBlockPos(1, 64, 0)));
    }

    @Test
    public void twoGoalsTakingTurnsArentNewEveryTime() {
        WatchdogProgress p = new WatchdogProgress();
        BetterBlockPos a = new BetterBlockPos(20, 64, 0);
        BetterBlockPos b = new BetterBlockPos(-20, 64, 0);
        BetterBlockPos feet = new BetterBlockPos(0, 64, 0);
        assertTrue(p.progressed(0, a, feet));
        assertTrue(p.progressed(0, b, feet));
        // the place, break, place loop flips the path end between the same two spots
        assertFalse(p.progressed(0, a, feet));
        assertFalse(p.progressed(0, b, feet));
        p.reset();
        assertTrue(p.progressed(0, a, feet));
    }

    @Test
    public void besideIsUnderTheFeetOrTouchingTheBody() {
        BlockPos feet = new BlockPos(0, 64, 0);
        assertTrue(BuilderProcess.besideFeet(feet, 0, 63, 0)); // standing on it
        assertTrue(BuilderProcess.besideFeet(feet, 1, 63, 0));
        assertTrue(BuilderProcess.besideFeet(feet, 0, 64, -1));
        assertTrue(BuilderProcess.besideFeet(feet, -1, 65, 0)); // head level
        assertFalse(BuilderProcess.besideFeet(feet, 1, 64, 1)); // the corner isn't touching
        assertFalse(BuilderProcess.besideFeet(feet, 2, 64, 0));
        assertFalse(BuilderProcess.besideFeet(feet, 0, 62, 0));
        assertFalse(BuilderProcess.besideFeet(feet, 0, 66, 0));
    }

    @Test
    public void breakingAsideNeverStandsOnItOrNextToIt() {
        BuilderProcess.GoalBreakAside goal = new BuilderProcess.GoalBreakAside(CELL);
        assertFalse(goal.isInGoal(CELL.above()));
        assertFalse(goal.isInGoal(CELL.east()));
        assertFalse(goal.isInGoal(CELL.east().below()));
        assertFalse(goal.isInGoal(CELL.below()));
        assertTrue(goal.isInGoal(CELL.east(2)));
        assertTrue(goal.isInGoal(CELL.east().north()));
        assertTrue(goal.isInGoal(CELL.east(2).above()));
        // under it with the head two short, a break straight up
        assertTrue(goal.isInGoal(CELL.below(2)));
        assertFalse(goal.isInGoal(CELL.east(3)));
        assertFalse(goal.isInGoal(CELL.east(2).above(2)));
        assertEquals(goal, new BuilderProcess.GoalBreakAside(CELL));
        assertEquals(goal.hashCode(), new BuilderProcess.GoalBreakAside(CELL).hashCode());
    }

    @Test
    public void scaffoldIsStillOursTillSomethingElseIsThere() {
        assertTrue(BuilderProcess.stillOurs(Blocks.COBBLESTONE, Blocks.COBBLESTONE.defaultBlockState()));
        // a dirt pillar top in the light goes grassy
        assertTrue(BuilderProcess.stillOurs(Blocks.DIRT, Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(BuilderProcess.stillOurs(Blocks.COBBLESTONE, Blocks.GRASS_BLOCK.defaultBlockState()));
        // the night's bed in a cell our pillar used to be in
        assertFalse(BuilderProcess.stillOurs(Blocks.COBBLESTONE, Blocks.CRAFTING_TABLE.defaultBlockState()));
        // nothing on record, nothing to argue with
        assertTrue(BuilderProcess.stillOurs(null, Blocks.STONE.defaultBlockState()));
    }
}
