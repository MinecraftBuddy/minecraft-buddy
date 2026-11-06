package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.SafeStand.Push;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.List;
import org.junit.Test;

// the one test of a cell to stand on: no knock from it ends in lava or in a fall that hurts
public class SafeStandTest {

    // feet at y 0 on a floor of stone (y < 0). west of x = 0 the floor ends: open for `open` cells below the feet, then
    // `bottom`. a cell at x = n - 1 is n cells in from the edge, its centre n - 0.5 from it
    private static Cells edge(int open, Cell bottom) {
        return (x, y, z) -> {
            if (x >= 0) return y < 0 ? Cell.SOLID : Cell.OPEN;
            if (y >= -open) return Cell.OPEN;
            return y == -open - 1 ? bottom : Cell.SOLID;
        };
    }

    private static Cells with(Cells world, int wx, int wy, int wz, Cell cell) {
        return (x, y, z) -> x == wx && y == wy && z == wz ? cell : world.at(x, y, z);
    }

    @Test
    public void anOpenFloorIsSafe() {
        Cells floor = (x, y, z) -> y < 0 ? Cell.SOLID : Cell.OPEN;
        assertFalse(SafeStand.unsafe(floor, 0, 0, 0));
        assertFalse(SafeStand.unsafe(floor, 0, 0, 0, List.of(Push.round(SafeStand.HOGLIN))));
    }

    @Test
    public void lavaOneBelowTwoCellsOffIsUnsafeThreeOffSafe() {
        Cells w = edge(0, Cell.LAVA);
        assertTrue(SafeStand.unsafe(w, 1, 0, 0));
        assertFalse(SafeStand.unsafe(w, 2, 0, 0));
    }

    @Test
    public void aDeepDropCountsWhereverItEnds() {
        // forty down onto lava, or the void: unsafe two in from the edge, safe three in
        assertTrue(SafeStand.unsafe(edge(39, Cell.LAVA), 1, 0, 0));
        assertFalse(SafeStand.unsafe(edge(39, Cell.LAVA), 2, 0, 0));
        assertTrue(SafeStand.unsafe(edge(1000, Cell.SOLID), 1, 0, 0));
    }

    @Test
    public void aFallOfFourHurtsAShorterOneDoesNot() {
        assertFalse(SafeStand.unsafe(edge(2, Cell.SOLID), 1, 0, 0));
        assertFalse(SafeStand.unsafe(edge(3, Cell.SOLID), 1, 0, 0));
        assertTrue(SafeStand.unsafe(edge(4, Cell.SOLID), 1, 0, 0));
        assertTrue(SafeStand.unsafe(edge(5, Cell.SOLID), 1, 0, 0));
        // water takes the fall at any depth
        assertFalse(SafeStand.unsafe(edge(30, Cell.WATER), 1, 0, 0));
    }

    @Test
    public void lavaOnTheWayDownCounts() {
        // a fall of two with lava half way down the column
        assertTrue(SafeStand.unsafe(with(edge(2, Cell.SOLID), -1, -2, 0, Cell.LAVA), 1, 0, 0));
    }

    @Test
    public void aWallBetweenTheCellAndTheEdgeMakesItSafe() {
        Cells wall = (x, y, z) -> x == 0 && (y == 0 || y == 1) ? Cell.SOLID : edge(39, Cell.LAVA).at(x, y, z);
        assertFalse(SafeStand.unsafe(wall, 1, 0, 0));
        // a block at head height only stops the push as well
        Cells head = (x, y, z) -> x == 0 && y == 1 ? Cell.SOLID : edge(39, Cell.LAVA).at(x, y, z);
        assertFalse(SafeStand.unsafe(head, 1, 0, 0));
    }

    @Test
    public void lavaAtFootHeightCountsWithinTheReachAndTheBox() {
        // its face 2.5 from the centre: the box touches it at the push's end
        Cells floor = (x, y, z) -> y < 0 ? Cell.SOLID : Cell.OPEN;
        assertTrue(SafeStand.unsafe(with(floor, 3, 0, 0, Cell.LAVA), 0, 0, 0));
        assertFalse(SafeStand.unsafe(with(floor, 4, 0, 0, Cell.LAVA), 0, 0, 0));
    }

    @Test
    public void thePushGoesOnAfterAShortFall() {
        // a ledge one lower from x -1 to -2, then lava at its feet height: a knock from the rim drops onto the ledge and on
        Cells w = (x, y, z) -> {
            if (x >= 0) return y < 0 ? Cell.SOLID : Cell.OPEN;
            if (x >= -2) return y < -1 ? Cell.SOLID : Cell.OPEN;
            return y == -1 ? Cell.LAVA : y < -1 ? Cell.SOLID : Cell.OPEN;
        };
        assertTrue(SafeStand.unsafe(w, 0, 0, 0));
        assertFalse(SafeStand.unsafe(w, 1, 0, 0));
    }

    @Test
    public void aFoeOnTheEdgesSidePushesAwayFromIt() {
        Cells w = edge(39, Cell.LAVA);
        // the foe between us and the edge throws us inland, one on the far side throws us over it
        assertFalse(SafeStand.unsafe(w, 1, 0, 0, List.of(Push.away(0.5, 0.5, SafeStand.HIT))));
        assertTrue(SafeStand.unsafe(w, 1, 0, 0, List.of(Push.away(3.5, 0.5, SafeStand.HIT))));
        // a foe off to the side still has the edge within its cone
        assertTrue(SafeStand.unsafe(w, 1, 0, 0, List.of(Push.away(2.5, -0.5, SafeStand.HIT))));
    }

    @Test
    public void aHoglinReachesSixFromTheEdge() {
        Cells w = edge(39, Cell.LAVA);
        assertFalse(SafeStand.unsafe(w, 5, 0, 0));
        assertTrue(SafeStand.unsafe(w, 5, 0, 0, List.of(SafeStand.push(true, false, false, 9.5, 0.5, 4))));
    }

    @Test
    public void aGhastsBallCarriesFurtherAlongItsFlight() {
        Cells w = edge(39, Cell.LAVA);
        // four in from the edge (3.5): out of an ordinary hit's reach, not of a ghast's
        assertFalse(SafeStand.unsafe(w, 3, 0, 0, List.of(Push.away(20.5, 0.5, SafeStand.HIT))));
        assertTrue(SafeStand.unsafe(w, 3, 0, 0, List.of(SafeStand.push(false, true, true, 20.5, 0.5, 17))));
    }

    @Test
    public void aFarFoeOnlyCountsWhenItShoots() {
        assertNull(SafeStand.push(false, false, false, 30, 0, 30));
        Push skeleton = SafeStand.push(false, false, true, 30, 0, 30);
        assertEquals(SafeStand.HIT, skeleton.reach(), 1e-9);
        assertFalse(skeleton.allRound());
        // no foe at all: only the cell itself, which has a floor
        assertFalse(SafeStand.unsafe(edge(39, Cell.LAVA), 0, 0, 0, List.of()));
    }

    @Test
    public void aCellWithNoFloorFalls() {
        assertTrue(SafeStand.unsafe(edge(39, Cell.LAVA), -1, 0, 0, List.of()));
    }
}
