/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.altoclef;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class AltoClefSettingsTest {

    private final AltoClefSettings s = AltoClefSettings.getInstance();

    @After
    public void clean() {
        s.getBreakAvoiders().clear();
        s.getPlaceAvoiders().clear();
        s.getBlocksToAvoidBreaking().clear();
        s.getForceWalkOnPredicates().clear();
        s.getForceAvoidWalkThroughPredicates().clear();
        s.getProtectedItems().clear();
        s.setProtectedReserve(Map.of());
        s.setInteractionPaused(false);
    }

    // there is no registry in a unit test, so no real Item to protect. null is a fine key for a HashSet and a HashMap,
    // and none of the code under test looks inside it
    private static final Item ITEM = null;

    @Test
    public void protectedWithNoNumberIsFullyReserved() {
        assertEquals(0, s.reservedCount(ITEM)); // not protected, all of it is free
        s.getProtectedItems().add(ITEM);
        assertEquals(AltoClefSettings.FULLY_RESERVED, s.reservedCount(ITEM));
    }

    @Test
    public void protectedWithANumberReservesThatMany() {
        s.getProtectedItems().add(ITEM);
        s.setProtectedReserve(reserve(6));
        assertEquals(6, s.reservedCount(ITEM));
        s.setProtectedReserve(Map.of());
        assertEquals(AltoClefSettings.FULLY_RESERVED, s.reservedCount(ITEM)); // number withdrawn, back to all of it
    }

    @Test
    public void aNumberForSomethingNotProtectedMeansNothing() {
        s.setProtectedReserve(reserve(6));
        assertEquals(0, s.reservedCount(ITEM));
    }

    @Test
    public void sameReserveTwiceKeepsTheSnapshot() {
        s.getProtectedItems().add(ITEM);
        s.setProtectedReserve(reserve(6));
        AltoClefSettings.Snapshot before = s.snapshot();
        s.setProtectedReserve(reserve(6)); // what every applyState does
        assertSame(before, s.snapshot());
        s.setProtectedReserve(reserve(7));
        assertNotSame(before, s.snapshot());
        assertEquals(7, s.reservedCount(ITEM));
    }

    @Test
    public void resetAllClearsTheReserve() {
        s.getProtectedItems().add(ITEM);
        s.setProtectedReserve(reserve(6));
        s.resetAll();
        assertNull(s.snapshot().protectedReserve);
        assertEquals(0, s.reservedCount(ITEM));
    }

    private static Map<Item, Integer> reserve(int n) {
        Map<Item, Integer> m = new HashMap<>();
        m.put(ITEM, n);
        return m;
    }

    @Test
    public void idleSnapshotIsEmpty() {
        assertFalse(s.snapshot().hasPathingRules());
        assertFalse(s.shouldAvoidBreaking(1, 2, 3));
        assertFalse(s.shouldAvoidPlacingAt(1, 2, 3));
    }

    @Test
    public void mutatingTheReturnedListsRepublishes() {
        // what BotBehaviour does: clear then addAll on the list it got from the getter
        List<Predicate<BlockPos>> mine = new ArrayList<>();
        mine.add(pos -> pos.getY() == 5);
        s.getBreakAvoiders().clear();
        s.getBreakAvoiders().addAll(mine);
        assertTrue(s.shouldAvoidBreaking(0, 5, 0));
        assertFalse(s.shouldAvoidBreaking(0, 6, 0));
        s.getBreakAvoiders().clear();
        assertFalse(s.shouldAvoidBreaking(0, 5, 0));

        s.getPlaceAvoiders().add(pos -> pos.getX() == 9);
        assertTrue(s.shouldAvoidPlacingAt(new BlockPos(9, 0, 0)));
        s.getPlaceAvoiders().remove(0);
        assertFalse(s.shouldAvoidPlacingAt(new BlockPos(9, 0, 0)));
    }

    @Test
    public void exactPositionsAndSetMutation() {
        s.avoidBlockBreak(new BlockPos(1, 2, 3));
        assertTrue(s.shouldAvoidBreaking(1, 2, 3));
        assertFalse(s.shouldAvoidBreaking(1, 2, 4));
        s.getBlocksToAvoidBreaking().clear();
        assertFalse(s.shouldAvoidBreaking(1, 2, 3));
        s.getBlocksToAvoidBreaking().add(new BlockPos(4, 4, 4));
        assertTrue(s.shouldAvoidBreaking(new BlockPos(4, 4, 4)));
    }

    @Test
    public void snapshotIsImmutableOnceTaken() {
        s.getForceWalkOnPredicates().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        s.getForceWalkOnPredicates().clear();
        assertNotNull(before.forceWalkOn); // the search that already took it keeps its answer
        assertNull(s.snapshot().forceWalkOn);
    }

    // altoclef calls applyState (clear + addAll on everything) from tasks every tick, the pathing thread rebuilds its
    // snapshot every time one of these says it changed. so saying nothing changed has to stay quiet
    @Test
    public void changeThatChangesNothingKeepsTheSnapshot() {
        s.getBreakAvoiders().add(pos -> pos.getY() == 99);
        s.getBlocksToAvoidBreaking().add(new BlockPos(1, 2, 3));
        AltoClefSettings.Snapshot before = s.snapshot();

        s.getPlaceAvoiders().clear();
        s.getPlaceAvoiders().addAll(new ArrayList<>());
        s.getBlocksToAvoidBreaking().add(new BlockPos(1, 2, 3));
        s.getBlocksToAvoidBreaking().addAll(List.of(new BlockPos(1, 2, 3)));
        s.getBlocksToAvoidBreaking().remove(new BlockPos(7, 7, 7));
        s.getBlocksToAvoidBreaking().removeAll(List.of(new BlockPos(7, 7, 7)));
        s.getForceWalkOnPredicates().clear();
        s.getProtectedItems().clear();
        assertSame(before, s.snapshot());

        // and a real change still shows up
        s.getBlocksToAvoidBreaking().add(new BlockPos(4, 5, 6));
        assertNotSame(before, s.snapshot());
        assertTrue(s.shouldAvoidBreaking(4, 5, 6));
        AltoClefSettings.Snapshot mid = s.snapshot();
        s.getBlocksToAvoidBreaking().remove(new BlockPos(4, 5, 6));
        assertNotSame(mid, s.snapshot());
        assertFalse(s.shouldAvoidBreaking(4, 5, 6));
    }

    // stock soprano must behave exactly like stock while altoclef is idle, so the frame rules are a toggle in the table key
    @Test
    public void endPortalFrameRulesAreATableKeyBit() {
        int idle = s.blockToggleBits();
        assertFalse(s.hasEndPortalFrameRules());
        s.endPortalFrameRules(true);
        assertNotEquals(idle, s.blockToggleBits());
        assertTrue((s.blockToggleBits() & AltoClefSettings.TOGGLE_FRAMES) != 0);
        s.endPortalFrameRules(false);
        assertEquals(idle, s.blockToggleBits());
    }

    @Test
    public void resetAllLeavesNothingBehind() {
        s.getBreakAvoiders().add(pos -> true);
        s.getPlaceAvoiders().add(pos -> true);
        s.getForceWalkOnPredicates().add(pos -> true);
        s.getForceAvoidWalkThroughPredicates().add(pos -> true);
        s.getForceUseToolPredicates().add((state, stack) -> true);
        s.avoidBlockBreak(new BlockPos(1, 2, 3));
        s.setInteractionPaused(true);
        s.allowSwimThroughLava(true);
        s.canWalkOnEndPortal(true);
        s.endPortalFrameRules(true);
        s.configurePlaceBucketButDontFall(true);
        s.setFlowingWaterPass(true);
        assertTrue(s.snapshot().hasPathingRules());

        s.resetAll();

        assertFalse(s.snapshot().hasPathingRules());
        assertNull(s.snapshot().forceUseTool);
        assertFalse(s.isInteractionPaused());
        assertFalse(s.canSwimThroughLava());
        assertFalse(s.isCanWalkOnEndPortal());
        assertFalse(s.hasEndPortalFrameRules());
        assertFalse(s.shouldNotPlaceBucketButStillFall());
        assertFalse(s.isFlowingWaterPassAllowed());
        assertEquals(0, s.blockToggleBits());
    }

    @Test
    public void aThrowingRuleSaysNoInsteadOfThrowing() {
        // one throw is well under the bridge's give up limit, and is counted there
        s.getForceWalkOnPredicates().add(pos -> {
            throw new IllegalStateException("task code had a bad day");
        });
        assertFalse(s.canWalkOnForce(1, 2, 3));
    }

    @Test
    public void tableKeyTracksBlockToggles() {
        int idle = s.blockToggleBits();
        s.canWalkOnEndPortal(true);
        assertNotEquals(idle, s.blockToggleBits());
        s.canWalkOnEndPortal(false);
        s.allowSwimThroughLava(true);
        assertNotEquals(idle, s.blockToggleBits());
        s.allowSwimThroughLava(false);
        assertEquals(idle, s.blockToggleBits());
    }
}
