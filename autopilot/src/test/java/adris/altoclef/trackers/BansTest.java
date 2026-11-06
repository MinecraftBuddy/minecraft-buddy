package adris.altoclef.trackers;

import adris.altoclef.trackers.Bans.Key;
import adris.altoclef.trackers.Bans.Until;
import baritone.api.utils.Dimension;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BansTest {
    private final List<String> lines = new ArrayList<>();
    private final Bans bans = new Bans(lines::add);

    private static Key block(int x, int y, int z) {
        return Key.block(Dimension.OVERWORLD, x, y, z);
    }

    @Test
    public void aBanIsBannedAndOnlyThatKey() {
        bans.tick(100);
        assertTrue(bans.ban(block(1, 2, 3), "test", 40));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 1, 3, 3));
        assertFalse(bans.entityBanned(1));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("ban: + block 1 2 3 (overworld), test, 2 s"));
    }

    @Test
    public void theDimensionIsPartOfTheKey() {
        bans.ban(block(5, 64, 5), "test", Bans.RUN);
        assertFalse(bans.blockBanned(Dimension.NETHER, 5, 64, 5));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 5, 64, 5));
    }

    @Test
    public void entitiesAndBlocksNeverCollide() {
        bans.ban(Key.entity(7), "test", Bans.RUN);
        assertTrue(bans.entityBanned(7));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 7, 0, 0));
    }

    @Test
    public void theClockEndsItWithOneLine() {
        bans.tick(0);
        bans.ban(block(0, 0, 0), "test", 100);
        bans.tick(99);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        bans.tick(100);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertEquals(0, bans.count());
        assertEquals("ban: - block 0 0 0 (overworld), test (expired)", lines.get(1));
        bans.tick(200);
        assertEquals(2, lines.size());
    }

    @Test
    public void aBanPastItsClockIsOverBeforeTheSweep() {
        // the query does not wait for tick() to tidy up
        bans.tick(0);
        bans.ban(Key.entity(3), "test", 10);
        bans.tick(5);
        assertTrue(bans.entityBanned(3));
    }

    @Test
    public void forTheRunNeverRunsOut() {
        bans.ban(block(0, 0, 0), "test", Bans.RUN);
        bans.tick(Long.MAX_VALUE - 1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertTrue(lines.get(0).endsWith("for the run"));
    }

    @Test
    public void theSameReasonTwiceIsOneBanAndOneLine() {
        bans.tick(0);
        assertTrue(bans.ban(block(0, 0, 0), "trap", Bans.RUN));
        assertFalse(bans.ban(block(0, 0, 0), "trap", Bans.RUN));
        assertEquals(1, bans.count());
        assertEquals(1, lines.size());
    }

    @Test
    public void liftTakesOnlyItsOwnReason() {
        bans.ban(block(0, 0, 0), "outpost", Bans.RUN);
        bans.ban(block(0, 0, 0), "unreachable", Bans.RUN);
        bans.ban(block(9, 0, 0), "outpost", Bans.RUN);
        assertEquals(1, bans.lift("outpost", k -> k.x() == 0));
        assertTrue("the other reason still holds", bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 9, 0, 0));
        assertTrue(lines.get(lines.size() - 1).endsWith("outpost (lifted)"));
    }

    @Test
    public void aBetterPickEndsOnlyTheBansMadeBelowIt() {
        bans.toolTier(1);
        bans.ban(block(0, 0, 0), "a", Bans.RUN, Until.BETTER_TOOL);
        bans.ban(block(1, 0, 0), "b", Bans.RUN);
        bans.toolTier(1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        bans.toolTier(2);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertTrue("no BETTER_TOOL on it", bans.blockBanned(Dimension.OVERWORLD, 1, 0, 0));
        // a pick that broke and came back is not better than what the ban saw
        bans.ban(block(2, 0, 0), "c", Bans.RUN, Until.BETTER_TOOL);
        bans.toolTier(1);
        bans.toolTier(2);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 2, 0, 0));
    }

    @Test
    public void aChunkReloadEndsTheBansInThatChunkOnly() {
        bans.ban(block(17, 60, -1), "a", Bans.RUN, Until.CHUNK_RELOAD);
        bans.ban(block(40, 60, -1), "b", Bans.RUN, Until.CHUNK_RELOAD);
        bans.ban(block(18, 60, -2), "c", Bans.RUN);
        bans.chunkUnloaded(1, -1);
        bans.chunkLoaded(Dimension.NETHER, 1, -1);
        assertTrue("a nether load is not this chunk", bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
        bans.chunkLoaded(Dimension.OVERWORLD, 1, -1);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 40, 60, -1));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 18, 60, -2));
    }

    @Test
    public void aHitEndsTheHitUsBans() {
        bans.ban(Key.entity(5), "no path", Bans.RUN, Until.HIT_US);
        bans.ban(Key.entity(6), "drop", Bans.RUN);
        bans.hitBy(6);
        assertTrue(bans.entityBanned(6));
        bans.hitBy(5);
        assertFalse(bans.entityBanned(5));
        assertTrue(lines.get(lines.size() - 1).endsWith("(it hit us)"));
    }

    @Test
    public void clearRunDropsEverythingWithOneLine() {
        bans.ban(block(0, 0, 0), "a", Bans.RUN);
        bans.ban(Key.entity(1), "b", Bans.RUN);
        lines.clear();
        bans.clearRun("world left");
        assertEquals(0, bans.count());
        assertFalse(bans.entityBanned(1));
        assertEquals(List.of("ban: run over (world left), dropped 2"), lines);
        bans.clearRun("again");
        assertEquals(1, lines.size());
    }

    @Test
    public void strikesBanAfterTheAllowanceRunsOut() {
        Key k = block(0, 0, 0);
        assertFalse(bans.strike(k, "far", 2, 100, 50));
        assertFalse(bans.strike(k, "far", 2, 100, 50));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertTrue(bans.strike(k, "far", 2, 100, 50));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertTrue(lines.get(0), lines.get(0).contains("far (3 tries)"));
    }

    @Test
    public void zeroAllowedBansOnTheFirstStrike() {
        assertTrue(bans.strike(block(0, 0, 0), "now", 0, 10, 50));
        assertTrue(lines.get(0).contains("now (1 try)"));
    }

    @Test
    public void aStrikeFromCloserStartsTheCountOver() {
        Key k = block(0, 0, 0);
        bans.strike(k, "r", 1, 100, 50);
        // closer by more than a block squared: a new try
        assertFalse(bans.strike(k, "r", 1, 90, 50));
        // closer by less: same try, second failure, out
        assertTrue(bans.strike(k, "r", 1, 89.5, 50));
    }

    @Test
    public void aStrikeWithABetterPickStartsTheCountOver() {
        Key k = block(0, 0, 0);
        bans.strike(k, "r", 1, 100, 50);
        bans.toolTier(3);
        assertFalse(bans.strike(k, "r", 1, 100, 50));
        assertTrue(bans.strike(k, "r", 1, 100, 50));
    }

    @Test
    public void theCountStartsOverOnceTheBanIsGone() {
        Key k = block(0, 0, 0);
        bans.tick(0);
        bans.strike(k, "r", 1, 100, 50);
        assertTrue(bans.strike(k, "r", 1, 100, 50));
        bans.tick(50);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
        assertFalse("one strike after the ban is a fresh first", bans.strike(k, "r", 1, 100, 50));
    }

    @Test
    public void aLoadWithoutAnUnloadIsNotAReload() {
        // a tracked block changing (a furnace going lit) sends a load for a chunk that never went anywhere
        bans.ban(block(17, 60, -1), "a", Bans.RUN, Until.CHUNK_RELOAD);
        bans.chunkLoaded(Dimension.OVERWORLD, 1, -1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
        bans.chunkUnloaded(1, -1);
        bans.chunkLoaded(Dimension.OVERWORLD, 1, -1);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
    }

    @Test
    public void anUnloadOfAChunkWithNoReloadBanIsNotKept() {
        bans.chunkUnloaded(1, -1);
        // the ban comes after, so the next load is not a reload for it
        bans.ban(block(17, 60, -1), "a", Bans.RUN, Until.CHUNK_RELOAD);
        bans.chunkLoaded(Dimension.OVERWORLD, 1, -1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
    }

    @Test
    public void aShortBanRunningOutDoesNotResetTheStrikes() {
        // the mining task's pair: a short ban of its own plus a strike, every stall
        Key k = block(0, 0, 0);
        bans.tick(0);
        for (int stall = 1; stall <= 3; stall++) {
            bans.ban(k, "mining got nowhere", 20);
            boolean out = bans.strike(k, "mining got nowhere", 2, 100, 1000);
            assertEquals("stall " + stall, stall == 3, out);
            bans.tick(stall * 30L);
        }
        assertTrue("the long one, from the strikes", bans.blockBanned(Dimension.OVERWORLD, 0, 0, 0));
    }

    @Test
    public void strikesOnAKeyThatIsAlreadyOutAreQuiet() {
        Key k = block(0, 0, 0);
        assertTrue(bans.strike(k, "r", 0, 100, 1000));
        int lines0 = lines.size();
        assertFalse(bans.strike(k, "r", 0, 100, 1000));
        assertFalse(bans.strike(k, "r", 0, 100, 1000));
        assertEquals(1, bans.count());
        assertEquals(lines0, lines.size());
    }

    @Test
    public void aPortalRoundTripIsAReload() {
        // no forget packet for the overworld ever reaches us under the overworld's name, leaving the dimension is the unload
        bans.ban(block(17, 60, -1), "a", Bans.RUN, Until.CHUNK_RELOAD);
        bans.dimensionLeft(Dimension.OVERWORLD);
        bans.chunkLoaded(Dimension.NETHER, 1, -1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
        bans.chunkLoaded(Dimension.OVERWORLD, 1, -1);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 17, 60, -1));
    }

    @Test
    public void anOldStrikeCountDecays() {
        Key k = block(0, 0, 0);
        bans.tick(0);
        bans.strike(k, "r", 1, 100, 50);
        bans.tick(50);
        assertFalse("the first one is as old as its ban would have been", bans.strike(k, "r", 1, 100, 50));
        bans.tick(60);
        assertTrue(bans.strike(k, "r", 1, 100, 50));
    }

    @Test
    public void aScopedBanIsOnlyForWhoAsks() {
        bans.banFor("detour", block(1, 2, 3), "gave up", Bans.RUN);
        assertFalse("the trackers ask without a scope", bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
        assertTrue(bans.banned(block(1, 2, 3), "detour"));
        assertFalse(bans.banned(block(1, 2, 3), "someone else"));
        // and a plain ban counts for the scoped asker too
        bans.ban(block(4, 2, 3), "trap", Bans.RUN);
        assertTrue(bans.banned(block(4, 2, 3), "detour"));
    }

    @Test
    public void aScopedAndAPlainBanOfTheSameReasonAreTwoBans() {
        bans.banFor("detour", block(1, 2, 3), "r", Bans.RUN);
        assertTrue(bans.ban(block(1, 2, 3), "r", Bans.RUN));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
    }

    @Test
    public void aBatchIsOneLineAndOnlyCountsTheNewOnes() {
        bans.ban(block(0, 0, 0), "outpost", Bans.RUN);
        lines.clear();
        int n = bans.banAll(List.of(block(0, 0, 0), block(1, 0, 0), block(2, 0, 0)), "outpost", Bans.RUN, "logs near x");
        assertEquals(2, n);
        assertEquals(List.of("ban: + 2 logs near x, for the run"), lines);
        assertEquals(0, bans.banAll(List.of(block(1, 0, 0)), "outpost", Bans.RUN, "logs near x"));
        assertEquals(1, lines.size());
        assertEquals(3, bans.liftAll("outpost", k -> true, "logs near x"));
        assertEquals("ban: - 3 logs near x (lifted)", lines.get(1));
        assertEquals(2, lines.size());
    }
}
