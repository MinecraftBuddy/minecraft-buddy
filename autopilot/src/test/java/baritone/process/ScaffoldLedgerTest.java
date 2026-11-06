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

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScaffoldLedgerTest {

    private static final Vec3i ORIGIN = new Vec3i(100, 64, 100);
    private static final long A = BetterBlockPos.serializeToLong(101, 65, 101);
    private static final long B = BetterBlockPos.serializeToLong(102, 65, 101);

    private static ScaffoldLedger owned() {
        ScaffoldLedger l = new ScaffoldLedger();
        assertFalse(l.own("house", ORIGIN));
        return l;
    }

    @Test
    public void theSameBuildKeepsItsScaffold() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        // a stop and start, a resume, a repeat: same name, the origin build() got
        assertTrue(l.own("house", ORIGIN));
        assertTrue(l.isScaffold(A));
    }

    @Test
    public void aDifferentBuildStartsOver() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        l.detour(B);
        assertFalse(l.own("house", new Vec3i(0, 64, 0)));
        assertFalse(l.isScaffold(A));
        assertEquals(0, l.detours(B));
        l.scaffold(A, "cobble");
        assertFalse(l.own("barn", new Vec3i(0, 64, 0)));
        assertFalse(l.isScaffold(A));
    }

    @Test
    public void anotherDimensionStartsOverButAReconnectDoesnt() {
        ScaffoldLedger l = owned();
        l.world("overworld");
        l.scaffold(A, "cobble");
        l.world("overworld");
        assertTrue(l.isScaffold(A));
        l.world("nether");
        assertFalse(l.isScaffold(A));
    }

    @Test
    public void goneScaffoldLeavesAndCounts() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        l.gone(A, true);
        assertFalse(l.isScaffold(A));
        assertEquals(1, l.cleared());
        // twice is still one block
        l.gone(A, true);
        assertEquals(1, l.cleared());
        // water moving in takes it off the books but isn't us getting anywhere
        l.scaffold(A, "cobble");
        l.gone(A, false);
        assertFalse(l.isScaffold(A));
        assertEquals(1, l.cleared());
    }

    @Test
    public void aSolidCellIsKnownButNeverKept() {
        ScaffoldLedger l = owned();
        l.solid(A, "cobble");
        assertTrue(l.isSolid(A));
        assertFalse(l.isScaffold(A));
        assertTrue(l.view(null, false).kept().isEmpty());
        l.gone(A, true);
        assertFalse(l.isSolid(A));
    }

    @Test
    public void detoursGetTwoGoesThenTheBoot() {
        ScaffoldLedger l = owned();
        assertEquals(ScaffoldLedger.Detour.PASSING, l.detour(A));
        assertTrue(l.passing().contains(A));
        // put back, the count stays
        l.filled(A);
        assertFalse(l.passing().contains(A));
        assertEquals(ScaffoldLedger.Detour.LATE, l.detour(A));
        assertFalse(l.passing().contains(A));
        assertTrue(l.hasLate());
        assertEquals(ScaffoldLedger.Detour.GIVE_UP, l.detour(A));
        assertFalse(l.hasLate());
        assertEquals(ScaffoldLedger.Detour.GIVE_UP, l.detour(A));
    }

    @Test
    public void aPassingOneRejoinsOnceItsOffThePathAndOutOfReach() {
        ScaffoldLedger l = owned();
        l.detour(A);
        LongOpenHashSet path = new LongOpenHashSet(new long[]{A});
        boolean[] reach = {true};
        // on the path and in reach
        assertTrue(l.updatePassing(path::contains, c -> reach[0]).isEmpty());
        // walked off but the path still goes through it
        reach[0] = false;
        assertTrue(l.updatePassing(path::contains, c -> reach[0]).isEmpty());
        // coming back into reach doesn't count once we left
        reach[0] = true;
        path.clear();
        LongArrayList rejoined = l.updatePassing(path::contains, c -> reach[0]);
        assertEquals(1, rejoined.size());
        assertEquals(A, rejoined.getLong(0));
        assertFalse(l.passing().contains(A));
        // and once out it stays out, even with a path right through it
        path.add(A);
        assertTrue(l.updatePassing(path::contains, c -> true).isEmpty());
        assertFalse(l.view(null, false).deferred().contains(A));
    }

    @Test
    public void theViewKeepsWhatTheCleanupIsntOn() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        l.scaffold(B, "cobble");
        l.detour(BetterBlockPos.serializeToLong(0, 0, 0));
        long late = BetterBlockPos.serializeToLong(1, 0, 0);
        l.detour(late);
        l.detour(late);
        ScaffoldLedger.View building = l.view(null, false);
        assertTrue(building.kept().contains(A) && building.kept().contains(B));
        assertTrue(building.deferred().contains(late));
        // this round takes A down, B went up after it started
        ScaffoldLedger.View cleaning = l.view(new LongOpenHashSet(new long[]{A}), false);
        assertFalse(cleaning.kept().contains(A));
        assertTrue(cleaning.kept().contains(B));
        // and last, the late ones are work
        assertFalse(l.view(new LongOpenHashSet(new long[]{A}), true).deferred().contains(late));
        // the passing one is never work while it's passing
        assertTrue(l.view(null, true).deferred().contains(BetterBlockPos.serializeToLong(0, 0, 0)));
    }

    @Test
    public void outOfRoundsTheRestStaysUpButKept() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        assertEquals(1, l.abandon());
        assertFalse(l.hasScaffold());
        assertTrue(l.isScaffold(A));
        assertTrue(l.view(null, false).kept().contains(A));
        // a fresh pillar in the same spot is cleanup work again
        l.scaffold(A, "cobble");
        assertTrue(l.hasScaffold());
    }

    @Test
    public void itRemembersWhatWentInTillItsGone() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        l.solid(B, "dirt");
        assertEquals("cobble", l.what(A));
        assertEquals("dirt", l.what(B));
        l.gone(A, true);
        l.gone(B, true);
        assertEquals(null, l.what(A));
        assertEquals(null, l.what(B));
    }

    @Test
    public void aFreshStartGetsAnotherGoAtTheLeftovers() {
        ScaffoldLedger l = owned();
        l.scaffold(A, "cobble");
        l.abandon();
        l.unabandon();
        assertTrue(l.hasScaffold());
        assertTrue(l.view(null, false).kept().contains(A));
    }

    @Test
    public void theMarginIsEightAllRound() {
        int m = ScaffoldLedger.MARGIN;
        assertEquals(8, m);
        assertTrue(ScaffoldLedger.nearBox(-m, 0, 0, 0, 0, 0, 2, 2, 2));
        assertTrue(ScaffoldLedger.nearBox(2 + m, 2 + m, 2 + m, 0, 0, 0, 2, 2, 2));
        assertFalse(ScaffoldLedger.nearBox(-m - 1, 0, 0, 0, 0, 0, 2, 2, 2));
        assertFalse(ScaffoldLedger.nearBox(0, 2 + m + 1, 0, 0, 0, 0, 2, 2, 2));
        assertFalse(ScaffoldLedger.nearBox(0, 0, -m - 1, 0, 0, 0, 2, 2, 2));
    }

    @Test
    public void cleanupGoesNearestColumnFirstTopDown() {
        List<BetterBlockPos> cells = List.of(
                new BetterBlockPos(-1, 0, 0), new BetterBlockPos(-1, 2, 0), new BetterBlockPos(-1, 1, 0),
                new BetterBlockPos(1, 0, 1), new BetterBlockPos(2, 2, 2));
        List<BetterBlockPos> order = ScaffoldLedger.cleanupOrder(cells, new BlockPos(-2, 5, 0));
        assertEquals(List.of(new BetterBlockPos(-1, 2, 0), new BetterBlockPos(-1, 1, 0), new BetterBlockPos(-1, 0, 0),
                new BetterBlockPos(1, 0, 1), new BetterBlockPos(2, 2, 2)), order);
    }

    @Test
    public void theSameBuildKeepsItsGenerationAndADifferentOneDoesnt() {
        ScaffoldLedger l = owned();
        int stamp = l.generation();
        // a resume or a repeat: confirms from before it still count
        assertTrue(l.own("house", ORIGIN));
        assertTrue(l.current(stamp));
        // the next portal cell, one block over: whatever the last cell clicked is history
        assertFalse(l.own("house", new Vec3i(101, 64, 100)));
        assertFalse(l.current(stamp));
        assertTrue(l.current(l.generation()));
    }

    @Test
    public void aNewDimensionIsANewGenerationButAReconnectIsnt() {
        ScaffoldLedger l = owned();
        l.world("overworld");
        int stamp = l.generation();
        l.world("overworld");
        assertTrue(l.current(stamp));
        l.world("nether");
        assertFalse(l.current(stamp));
    }

    @Test
    public void aFreshLedgerNeverReusesAnOldStamp() {
        ScaffoldLedger a = owned();
        ScaffoldLedger b = new ScaffoldLedger();
        assertFalse(b.current(a.generation()));
        assertFalse(b.current(0));
    }
}
