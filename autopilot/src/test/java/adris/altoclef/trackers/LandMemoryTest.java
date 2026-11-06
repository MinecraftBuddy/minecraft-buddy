package adris.altoclef.trackers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

public class LandMemoryTest {

    private final LandMemory memory = new LandMemory(null);

    @Test
    public void aFrontierIsUnseenAndNextToLand() {
        memory.note(0, 0, 0, 9);
        assertTrue(memory.frontier(1, 0));
        // seen is explored, not a goal
        assertFalse(memory.frontier(0, 0));
        // diagonal isn't next to
        assertFalse(memory.frontier(1, 1));
    }

    @Test
    public void noLandMeansNoReach() {
        memory.note(0, 0, 9, 9);
        assertEquals(-1, memory.reach(0, 0));
        assertFalse(memory.frontier(1, 0));
    }

    @Test
    public void reachCoversTheFarthestCornerOfTheLand() {
        memory.note(3, 0, 0, 9);
        memory.note(-2, 4, 0, 9);
        assertEquals(3 + 4, memory.reach(0, 0));
    }

    @Test
    public void oldChunkMakesAFrontierAndMovesTheReach() {
        memory.note(0, 0, 0, 9);
        memory.explored(1, 0);
        assertTrue(memory.frontier(2, 0));
        assertEquals(1, memory.reach(0, 0));
        // it is not a goal itself until it turns out otherwise, it just never had a look
        memory.note(1, 0, 9, 9);
        assertFalse(memory.frontier(1, 0));
        assertFalse(memory.frontier(2, 0));
    }

    @Test
    public void banSticksUntilTheWorldGoes() {
        memory.note(0, 0, 0, 9);
        memory.ban(new ChunkPos(1, 0));
        assertFalse(memory.frontier(1, 0));
        memory.reset();
        memory.note(0, 0, 0, 9);
        assertTrue(memory.frontier(1, 0));
    }

    @Test
    public void resetForgetsTheLandAndTheCentroid() {
        memory.note(10, 0, 0, 9);
        memory.reset();
        assertEquals(-1, memory.reach(0, 0));
        assertEquals(new BlockPos(1024, 0, 0), memory.startCrossing(0, 0));
    }

    @Test
    public void crossingHeadsAwayFromTheLandWeKnow() {
        // land to the west of us, chunk -10 is around block -152
        memory.note(-10, 0, 0, 9);
        BlockPos target = memory.startCrossing(100, 8);
        assertEquals(100 + 1024, target.getX());
        assertEquals(8, target.getZ());
    }

    @Test
    public void aCrossingOutlivesTheAimAgainAndEndsOnlyWhenTold() {
        assertFalse(memory.crossing());
        memory.startCrossing(5, 6);
        assertTrue(memory.crossing());
        // aiming again from farther out, the shore we left stays the start
        memory.startCrossing(900, 6);
        assertEquals(new BlockPos(5, 0, 6), memory.crossingStart());
        memory.endCrossing();
        assertFalse(memory.crossing());
        memory.startCrossing(900, 6);
        assertEquals(new BlockPos(900, 0, 6), memory.crossingStart());
    }

    @Test
    public void aCrossingGoesWithTheWorld() {
        memory.startCrossing(5, 6);
        memory.reset();
        assertFalse(memory.crossing());
    }

    @Test
    public void landSeenTwiceCountsOnce() {
        memory.note(0, 0, 0, 9);
        memory.note(0, 0, 0, 9);
        memory.note(-10, 0, 0, 9);
        // centroid in the middle of the two, one of them counted twice would pull it
        BlockPos target = memory.startCrossing(-72, 8);
        assertEquals(new BlockPos(-72 + 1024, 0, 8), target);
    }
}
