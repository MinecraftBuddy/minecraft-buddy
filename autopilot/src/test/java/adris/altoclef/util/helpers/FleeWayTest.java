package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.FleeWay.Cell;
import adris.altoclef.util.helpers.FleeWay.End;
import org.junit.Test;

// the way out on foot from a crowd: a walk on the ground away from its centre, ended by lava, a drop, a wall or the loaded
// world's edge; the longest of five headings
public class FleeWayTest {
    // flat netherrack at y 63 (feet at 64) everywhere, unless the lambda says otherwise
    private static Cell flat(int x, int y, int z) {
        return y <= 63 ? Cell.SOLID : Cell.OPEN;
    }

    @Test
    public void openGroundIsTheWholeWay() {
        FleeWay.Way way = FleeWay.find(FleeWayTest::flat, 0.5, 64, 0.5, -10, 0.5);
        assertEquals(FleeWay.MAX, way.best().blocks());
        assertEquals(End.OPEN, way.best().end());
    }

    @Test
    public void lavaEastEndsTheStraightWay() {
        // the crowd to the west: straight away is east; lava from x 6 on along z 0 only
        FleeWay.Cells cells = (x, y, z) -> x >= 6 && z == 0 && y == 63 ? Cell.LAVA : flat(x, y, z);
        FleeWay.Walk straight = FleeWay.walk(cells, 0.5, 64, 0.5, 1, 0, 0);
        assertEquals(5, straight.blocks());
        assertEquals(End.LAVA, straight.end());
    }

    @Test
    public void aCliffIsADropAndAStepUpIsWalked() {
        // a step up of one at x 3, then a pit with no floor from x 8
        FleeWay.Cells cells = (x, y, z) -> {
            if (x >= 8) return Cell.OPEN;
            if (x >= 3) return y <= 64 ? Cell.SOLID : Cell.OPEN;
            return flat(x, y, z);
        };
        FleeWay.Walk w = FleeWay.walk(cells, 0.5, 64, 0.5, 1, 0, 0);
        assertEquals(7, w.blocks());
        assertEquals(End.DROP, w.end());
    }

    @Test
    public void aStepDownOfThreeIsWalkedAndFourIsADrop() {
        FleeWay.Cells three = (x, y, z) -> x >= 4 ? (y <= 60 ? Cell.SOLID : Cell.OPEN) : flat(x, y, z);
        assertEquals(End.OPEN, FleeWay.walk(three, 0.5, 64, 0.5, 1, 0, 0).end());
        FleeWay.Cells four = (x, y, z) -> x >= 4 ? (y <= 59 ? Cell.SOLID : Cell.OPEN) : flat(x, y, z);
        FleeWay.Walk w = FleeWay.walk(four, 0.5, 64, 0.5, 1, 0, 0);
        assertEquals(End.DROP, w.end());
        assertEquals(3, w.blocks());
    }

    @Test
    public void aWallTwoHighAndTheLoadedEdge() {
        FleeWay.Cells wall = (x, y, z) -> x == 5 && y <= 66 ? Cell.SOLID : flat(x, y, z);
        FleeWay.Walk w = FleeWay.walk(wall, 0.5, 64, 0.5, 1, 0, 0);
        assertEquals(End.WALL, w.end());
        assertEquals(4, w.blocks());
        FleeWay.Cells edge = (x, y, z) -> x >= 20 ? Cell.UNLOADED : flat(x, y, z);
        assertEquals(End.UNLOADED, FleeWay.walk(edge, 0.5, 64, 0.5, 1, 0, 0).end());
    }

    @Test
    public void theBestOfTheFiveHeadings() {
        // lava straight east from x 3 within z -1..1; a heading 30 or 60 degrees off goes round it
        FleeWay.Cells cells = (x, y, z) -> x >= 3 && Math.abs(z) <= 1 && y == 63 ? Cell.LAVA : flat(x, y, z);
        FleeWay.Way way = FleeWay.find(cells, 0.5, 64, 0.5, -10, 0.5);
        assertEquals(End.LAVA, way.walks()[0].end());
        assertEquals(FleeWay.MAX, way.best().blocks());
        assertEquals(true, way.best().turn() != 0);
    }
}
