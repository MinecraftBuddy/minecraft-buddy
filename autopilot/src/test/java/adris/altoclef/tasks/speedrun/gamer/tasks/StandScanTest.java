package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Look;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Spot;
import adris.altoclef.tasks.speedrun.gamer.tasks.StandScan.Block;
import adris.altoclef.tasks.speedrun.gamer.tasks.StandScan.Column;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StandScanTest {
    // a lava sea: lava up to y 31, open air to the roof, bedrock from 123 up
    private static Block sea(int y) {
        if (y <= 31) {
            return Block.LAVA;
        }
        return y >= 123 ? Block.SOLID : Block.AIR;
    }

    // the sea with a flat island (solid at y 40) over the given box
    private static Column island(int x0, int x1, int z0, int z1) {
        return (x, y, z) -> y == 40 && x >= x0 && x <= x1 && z >= z0 && z <= z1 ? Block.SOLID : sea(y);
    }

    @Test
    public void openLavaSeaHasNowhereToStand() {
        Spot s = StandScan.look((x, y, z) -> sea(y), 0, 0, 12);
        assertEquals(Look.NOWHERE, s.look());
    }

    @Test
    public void aWaypointOnAnIslandIsFine() {
        assertEquals(Look.FINE, StandScan.look(island(-3, 3, -3, 3), 0, 0, 12).look());
    }

    @Test
    public void aSeaColumnIsShiftedToTheNearestIslandColumn() {
        Spot s = StandScan.look(island(5, 9, -3, 3), 0, 0, 12);
        assertEquals(Look.SHIFTED, s.look());
        assertEquals(5, s.x());
        assertEquals(0, s.z());
    }

    @Test
    public void anIslandPastTheRingDoesNotCount() {
        assertEquals(Look.NOWHERE, StandScan.look(island(14, 20, -3, 3), 0, 0, 12).look());
    }

    @Test
    public void solidNetherrackWithNoPocketIsATunnelNotAProblem() {
        Column rock = (x, y, z) -> y >= 1 ? Block.SOLID : Block.LAVA;
        assertEquals(Look.FINE, StandScan.look(rock, 0, 0, 12).look());
    }

    @Test
    public void aBridgeOverTheSeaIsStandable() {
        // three wide along x, a fortress bridge
        Column bridge = (x, y, z) -> y == 60 && z >= -1 && z <= 1 ? Block.SOLID : sea(y);
        assertTrue(StandScan.standableColumn(bridge, 7, 0));
        assertEquals(Look.FINE, StandScan.look(bridge, 7, 0, 12).look());
    }

    @Test
    public void aLonePillarIsNotAPlaceToWalkTo() {
        assertFalse(StandScan.standableColumn(island(0, 0, 0, 0), 0, 0));
    }

    @Test
    public void lavaBesideTheFeetRulesALedgeOut() {
        Column ledge = (x, y, z) -> {
            if (y == 40 && x >= 0 && x <= 4 && z >= 0 && z <= 4) {
                return Block.SOLID;
            }
            // lava lapping at the feet on the x=-1 side of the island, over the 40 floor
            if (y == 41 && x == -1 && z >= 0 && z <= 4) {
                return Block.LAVA;
            }
            return sea(y);
        };
        assertFalse(StandScan.standable(ledge, 0, 40, 2));
        assertTrue(StandScan.standable(ledge, 2, 40, 2));
    }

    @Test
    public void magmaIsNotAFloorAndFireIsNotAir() {
        Column magma = (x, y, z) -> y == 40 ? Block.HARM : sea(y);
        assertFalse(StandScan.standableColumn(magma, 0, 0));
        Column fire = (x, y, z) -> y == 40 ? Block.SOLID : y == 41 ? Block.HARM : sea(y);
        assertFalse(StandScan.standableColumn(fire, 0, 0));
    }

    @Test
    public void aLowCeilingIsNotStandable() {
        Column crawl = (x, y, z) -> y == 40 || y >= 42 ? Block.SOLID : y == 41 ? Block.AIR : Block.LAVA;
        assertFalse(StandScan.standableColumn(crawl, 0, 0));
    }
}
