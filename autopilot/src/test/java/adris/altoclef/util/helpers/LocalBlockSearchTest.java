package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalBlockSearchTest {
    private static final BlockPos FEET = new BlockPos(10, 64, -20);

    private static int shell(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    @Test
    public void theNearerOfTwoWinsWhicheverWayRoundTheyAre() {
        BlockPos near = FEET.offset(2, 0, 0);
        BlockPos far = FEET.offset(-3, 0, 0);
        assertEquals(Optional.of(near), LocalBlockSearch.nearest(FEET, 8, p -> p.equals(near) || p.equals(far)));
        BlockPos nearOtherSide = FEET.offset(-2, 0, 0);
        BlockPos farOtherSide = FEET.offset(3, 0, 0);
        assertEquals(Optional.of(nearOtherSide), LocalBlockSearch.nearest(FEET, 8, p -> p.equals(nearOtherSide) || p.equals(farOtherSide)));
    }

    // a drop we are walking to asks every tick, no need for the whole cube every tick
    @Test
    public void anEmptyLookStandsWhileChasingSomethingElse() {
        assertTrue(LocalBlockSearch.emptyStands(true, 100, 100));
        assertTrue(LocalBlockSearch.emptyStands(true, 100 + LocalBlockSearch.EMPTY_STANDS_TICKS - 1, 100));
        assertFalse(LocalBlockSearch.emptyStands(true, 100 + LocalBlockSearch.EMPTY_STANDS_TICKS, 100));
    }

    // the drop got picked up one block closer to the netherrack: nothing to chase, and this answer is about to call it wandering
    @Test
    public void anEmptyLookNeverStandsWithNothingToChase() {
        assertFalse(LocalBlockSearch.emptyStands(false, 100, 100));
        assertFalse(LocalBlockSearch.emptyStands(false, 101, 100));
    }

    @Test
    public void noLookYetOrANewClockIsNotAnEmptyLook() {
        assertFalse(LocalBlockSearch.emptyStands(true, 100, Integer.MIN_VALUE / 2));
        assertFalse(LocalBlockSearch.emptyStands(true, 5, 100));
    }

    @Test
    public void everySpotIsAskedOnceAndNoShellComesBeforeANearerOne() {
        List<BlockPos> asked = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        assertTrue(LocalBlockSearch.nearest(FEET, 8, p -> {
            asked.add(p.immutable());
            assertTrue("asked twice: " + p, seen.add(p.immutable()));
            return false;
        }).isEmpty());
        assertEquals(17 * 17 * 17, asked.size());
        int last = 0;
        for (BlockPos p : asked) {
            int now = shell(FEET, p);
            assertTrue(now >= last);
            last = now;
        }
    }

    @Test
    public void thePredicateDecides() {
        BlockPos refused = FEET.offset(1, 0, 0);
        BlockPos taken = FEET.offset(5, 0, 0);
        Set<BlockPos> blocks = Set.of(refused, taken);
        assertEquals(Optional.of(taken), LocalBlockSearch.nearest(FEET, 8, p -> blocks.contains(p) && !p.equals(refused)));
    }

    @Test
    public void nothingPassingIsEmpty() {
        assertFalse(LocalBlockSearch.nearest(FEET, 8, p -> false).isPresent());
    }

    @Test
    public void theRadiusIsTheLastShell() {
        BlockPos edge = FEET.offset(8, -8, 8);
        BlockPos past = FEET.offset(9, 0, 0);
        assertEquals(Optional.of(edge), LocalBlockSearch.nearest(FEET, 8, p -> p.equals(edge)));
        assertFalse(LocalBlockSearch.nearest(FEET, 8, p -> p.equals(past)).isPresent());
    }

    @Test
    public void radiusZeroIsTheSpotItself() {
        assertEquals(Optional.of(FEET), LocalBlockSearch.nearest(FEET, 0, p -> true));
    }

    // same shell: a wall beats the floor, or the dig goes straight down
    @Test
    public void ourOwnLevelBeatsAboveAndAboveBeatsBelow() {
        BlockPos level = FEET.offset(1, 0, 1);
        BlockPos above = FEET.offset(1, 1, 0);
        BlockPos below = FEET.offset(0, -1, -1);
        assertEquals(Optional.of(level), LocalBlockSearch.nearest(FEET, 1, p -> p.equals(below) || p.equals(above) || p.equals(level)));
        assertEquals(Optional.of(above), LocalBlockSearch.nearest(FEET, 1, p -> p.equals(below) || p.equals(above)));
    }

    // the cursor moves on, what comes back has to stay put
    @Test
    public void theAnswerIsNotTheCursor() {
        BlockPos hit = LocalBlockSearch.nearest(FEET, 2, p -> p.getY() == FEET.getY() + 1).orElseThrow();
        assertFalse(hit instanceof BlockPos.MutableBlockPos);
    }
}
