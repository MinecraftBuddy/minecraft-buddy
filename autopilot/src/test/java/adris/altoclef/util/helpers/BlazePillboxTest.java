package adris.altoclef.util.helpers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

// the pillbox's plan (BlazePillbox): where a fortress has no corner, the fewest blocks that hide a cell by the spawner from
// every spawn eye and leave a look out beside it in sight
public class BlazePillboxTest {

    // a floor of solid at y 63 out to 30 each way, open above it, the spawner a solid block at (0, 64, 0); walls where walls says
    private static Cells floor(Set<Long> walls) {
        return (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (x == 0 && z == 0 && y == 64) return Cell.SOLID;
            if (walls.contains(BlazeCover.key(x, y, z))) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
    }

    @Test
    public void anOpenFloorGetsAPillboxOfSevenAtMost() {
        Cells cells = floor(Set.of());
        BlazePillbox.Plan p = BlazePillbox.plan(cells, new int[]{2, 64, 0}, 0, 64, 0, BlazeCover.spawnEyes(0, 64, 0));
        assertNotNull(p);
        assertTrue("blocks " + p.blocks().size(), p.blocks().size() <= BlazePillbox.MOST);
        Set<Long> placed = new HashSet<>();
        for (int[] b : p.blocks()) placed.add(BlazeCover.key(b[0], b[1], b[2]));
        Cells after = (x, y, z) -> placed.contains(BlazeCover.key(x, y, z)) ? Cell.SOLID : cells.at(x, y, z);
        assertTrue(BlazePillbox.hidden(after, BlazeCover.spawnEyes(0, 64, 0), p.cell()[0], p.cell()[1], p.cell()[2]));
        assertTrue(!BlazePillbox.hidden(after, BlazeCover.spawnEyes(0, 64, 0), p.lookout()[0], p.lookout()[1], p.lookout()[2]));
        double flat = Math.hypot(p.cell()[0] + 0.5 - 0.5, p.cell()[2] + 0.5 - 0.5);
        assertTrue("flat " + flat, flat >= BlazePillbox.CELL_MIN && flat <= BlazePillbox.CELL_MAX);
    }

    // a wall already there is a block less to place
    @Test
    public void aWallThereSavesBlocks() {
        Set<Long> walls = new HashSet<>();
        for (int z = -6; z <= 6; z++) {
            walls.add(BlazeCover.key(7, 64, z));
            walls.add(BlazeCover.key(7, 65, z));
        }
        BlazePillbox.Plan open = BlazePillbox.plan(floor(Set.of()), new int[]{2, 64, 0}, 0, 64, 0, BlazeCover.spawnEyes(0, 64, 0));
        BlazePillbox.Plan walled = BlazePillbox.plan(floor(walls), new int[]{2, 64, 0}, 0, 64, 0, BlazeCover.spawnEyes(0, 64, 0));
        assertNotNull(walled);
        assertTrue(walled.blocks().size() + " against " + open.blocks().size(), walled.blocks().size() <= open.blocks().size());
    }

    @Test
    public void theRayStopsAtASolidCell() {
        Set<Long> walls = Set.of(BlazeCover.key(3, 65, 0));
        Cells cells = floor(walls);
        assertTrue(!BlazePillbox.clear(cells, new net.minecraft.world.phys.Vec3(0.5, 65.5, 0.5), new net.minecraft.world.phys.Vec3(6.5, 65.5, 0.5)));
        assertTrue(BlazePillbox.clear(cells, new net.minecraft.world.phys.Vec3(0.5, 65.5, 2.5), new net.minecraft.world.phys.Vec3(6.5, 65.5, 2.5)));
    }

    // a platform with nothing round it: no pillbox cell within two of its edge, where the builder fell off the rim
    @Test
    public void noCellByAnEdge() {
        Cells platform = (x, y, z) -> {
            if (Math.abs(x) > 8 || Math.abs(z) > 8) return y <= 40 ? Cell.LAVA : Cell.OPEN;
            if (x == 0 && z == 0 && y == 64) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
        BlazePillbox.Plan p = BlazePillbox.plan(platform, new int[]{2, 64, 0}, 0, 64, 0, BlazeCover.spawnEyes(0, 64, 0));
        if (p != null) {
            assertTrue("cell " + java.util.Arrays.toString(p.cell()), Math.abs(p.cell()[0]) <= 6 && Math.abs(p.cell()[2]) <= 6);
        }
    }
}
