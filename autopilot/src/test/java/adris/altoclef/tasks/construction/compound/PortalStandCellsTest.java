package adris.altoclef.tasks.construction.compound;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// where the buddy stands to click a block into the portal frame (ConstructNetherPortalObsidianTask.standCells)
public class PortalStandCellsTest {

    private static final double EYES = 1.62;
    private static final double REACH = 4.5;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean isIn(Vec3i cell, Vec3i[] cells) {
        for (Vec3i c : cells) if (c.equals(cell)) return true;
        return false;
    }

    // a top face: the eyes over it, in reach of its centre, never in the cell being filled or in the frame
    private static void topFaceFrom(List<Vec3i> stands, Vec3i placed) {
        assertFalse(placed + ": somewhere to stand", stands.isEmpty());
        for (Vec3i s : stands) {
            double eye = s.getY() + EYES;
            assertTrue(placed + " from " + s + ": the eyes over the face", eye > placed.getY());
            double dx = s.getX() - placed.getX(), dy = eye - placed.getY(), dz = s.getZ() - placed.getZ();
            assertTrue(placed + " from " + s + ": in reach", Math.sqrt(dx * dx + dy * dy + dz * dz) <= REACH);
            assertFalse(placed + " from " + s + ": not in the cell", s.equals(placed));
            assertFalse(placed + " from " + s + ": not in the frame", isIn(s, ConstructNetherPortalObsidianTask.PORTAL_FRAME));
        }
    }

    @Test
    public void aColumnsThirdBlockIsClickedFromOneBlockUp() {
        for (Vec3i top : new Vec3i[]{new Vec3i(0, 2, -1), new Vec3i(0, 2, 2)}) {
            List<Vec3i> stands = ConstructNetherPortalObsidianTask.standCells(top, Direction.UP, true);
            topFaceFrom(stands, top);
            for (Vec3i s : stands) assertTrue(top + " from " + s, s.getY() == 1);
        }
    }

    @Test
    public void aCornerIsClickedFromTwoUp() {
        for (Vec3i corner : new Vec3i[]{new Vec3i(0, 3, -1), new Vec3i(0, 3, 2)}) {
            List<Vec3i> stands = ConstructNetherPortalObsidianTask.standCells(corner, Direction.UP, true);
            topFaceFrom(stands, corner);
            for (Vec3i s : stands) assertTrue(corner + " from " + s, s.getY() == 2);
        }
    }

    @Test
    public void theLowBlocksAreClickedFromTheGround() {
        for (Vec3i low : new Vec3i[]{new Vec3i(0, 0, -1), new Vec3i(0, 1, -1), new Vec3i(0, 0, 2), new Vec3i(0, 1, 2)}) {
            List<Vec3i> stands = ConstructNetherPortalObsidianTask.standCells(low, Direction.UP, true);
            topFaceFrom(stands, low);
            for (Vec3i s : stands) assertTrue(low + " from " + s, s.getY() == 0);
        }
    }

    @Test
    public void noPillarStandsInThePortalsOwnSpace() {
        for (Vec3i high : new Vec3i[]{new Vec3i(0, 2, -1), new Vec3i(0, 2, 2), new Vec3i(0, 3, -1), new Vec3i(0, 3, 2)}) {
            for (Vec3i s : ConstructNetherPortalObsidianTask.standCells(high, Direction.UP, true)) {
                for (int y = 0; y <= s.getY(); y++) {
                    Vec3i p = new Vec3i(s.getX(), y, s.getZ());
                    assertFalse(high + " from " + s + ": the pillar's " + p, isIn(p, ConstructNetherPortalObsidianTask.PORTAL_INTERIOR));
                    assertFalse(high + " from " + s + ": the pillar's " + p, isIn(p, ConstructNetherPortalObsidianTask.PORTAL_FRAME));
                }
            }
        }
    }

    @Test
    public void aTopCellsSideFaceIsClickedFromTheGroundOnItsSide() {
        // the first top cell against the north corner's south face: the face's plane is the cell's north edge
        Vec3i first = new Vec3i(0, 3, 0);
        List<Vec3i> stands = ConstructNetherPortalObsidianTask.standCells(first, Direction.SOUTH, true);
        assertFalse(stands.isEmpty());
        for (Vec3i s : stands) {
            assertTrue(s.toString(), (s.getY() == 0 || s.getY() == 2) && s.getZ() + 0.5 > first.getZ() && s.getX() != 0);
        }
        // the second against the first's south face, or the south corner's north face
        Vec3i second = new Vec3i(0, 3, 1);
        for (Vec3i s : ConstructNetherPortalObsidianTask.standCells(second, Direction.SOUTH, true)) {
            assertTrue(s.toString(), s.getZ() + 0.5 > second.getZ());
        }
        for (Vec3i s : ConstructNetherPortalObsidianTask.standCells(second, Direction.NORTH, true)) {
            assertTrue(s.toString(), s.getZ() + 0.5 < second.getZ() + 1);
        }
    }

    @Test
    public void withTheInteriorNotClearATopCellIsClickedFromAPillarOutsideIt() {
        for (Vec3i top : new Vec3i[]{new Vec3i(0, 3, 0), new Vec3i(0, 3, 1)}) {
            List<Vec3i> stands = ConstructNetherPortalObsidianTask.standCells(top, Direction.SOUTH, false);
            assertFalse(top + ": somewhere to stand", stands.isEmpty());
            for (Vec3i s : stands) {
                assertTrue(top + " from " + s, s.getY() == 2 && s.getZ() + 0.5 > top.getZ());
                double dx = s.getX() + 0.5 - (top.getX() + 0.5), dy = s.getY() + EYES - (top.getY() + 0.5), dz = s.getZ() + 0.5 - top.getZ();
                assertTrue(top + " from " + s + ": in reach", Math.sqrt(dx * dx + dy * dy + dz * dz) <= REACH);
                for (int y = 0; y <= s.getY(); y++) {
                    Vec3i p = new Vec3i(s.getX(), y, s.getZ());
                    assertFalse(top + " from " + s + ": the pillar's " + p, isIn(p, ConstructNetherPortalObsidianTask.PORTAL_INTERIOR));
                }
            }
        }
    }
}
