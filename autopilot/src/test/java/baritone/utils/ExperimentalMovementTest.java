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

package baritone.utils;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.FALL_N_BLOCKS_COST;
import static baritone.api.pathing.movement.ActionCosts.WALK_OFF_BLOCK_COST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExperimentalMovementTest {

    @Test
    public void fallDamageIsWholeBlocksPastSafe() {
        // landing from three blocks is free, every block past that is a half heart
        assertEquals(0, ExperimentalMovement.fallDamage(0));
        assertEquals(0, ExperimentalMovement.fallDamage(3));
        assertEquals(1, ExperimentalMovement.fallDamage(4));
        assertEquals(4, ExperimentalMovement.fallDamage(7));
        assertEquals(20, ExperimentalMovement.fallDamage(23)); // the sanity cap is exactly a full health bar
    }

    @After
    public void clearStamp() {
        // the stamp lives in a singleton, a failed assertion must not leave it up for the next test
        baritone.altoclef.AltoClefSettings.getInstance().resetAll();
    }

    @Test
    public void jumpsStayOffOnlyWhileTheStampIsAhead() {
        long until = 102;
        // fastMode on and the setting off is the nether bug: the stamp has to beat fastMode
        assertFalse(ExperimentalMovement.jumpMoveAllowed(false, true, false, until, 100));
        assertFalse(ExperimentalMovement.jumpMoveAllowed(false, true, true, until, 102)); // the last tick still counts
        // nobody refreshed it, so it lapses and fastMode gets its jumps back
        assertTrue(ExperimentalMovement.jumpMoveAllowed(false, true, false, until, 103));
        assertTrue(ExperimentalMovement.jumpMoveAllowed(false, false, true, until, 500));
        // never stamped
        assertTrue(ExperimentalMovement.jumpMoveAllowed(false, true, false, Long.MIN_VALUE, 0));
        // a stamp from a world with a longer clock is junk, not a lifetime ban
        assertTrue(ExperimentalMovement.jumpMoveAllowed(false, true, false, 9_000_000L, 10));
        // and with neither fastMode nor the setting there were never any jumps to take away
        assertFalse(ExperimentalMovement.jumpMoveAllowed(false, false, false, Long.MIN_VALUE, 0));
    }

    @Test
    public void netherTurnsEveryJumpOffWhateverElseSaysYes() {
        for (boolean fast : new boolean[]{false, true}) {
            for (boolean setting : new boolean[]{false, true}) {
                assertFalse(ExperimentalMovement.jumpMoveAllowed(true, fast, setting, Long.MIN_VALUE, 0));
                // and the overworld still follows fastMode || setting
                assertEquals(fast || setting, ExperimentalMovement.jumpMoveAllowed(false, fast, setting, Long.MIN_VALUE, 0));
            }
        }
    }

    @Test
    public void hurtingFallsNeedFastModeAndNoNether() {
        assertTrue(ExperimentalMovement.hurtingFallsOk(false, true));
        assertFalse(ExperimentalMovement.hurtingFallsOk(true, true));
        assertFalse(ExperimentalMovement.hurtingFallsOk(false, false));
        assertFalse(ExperimentalMovement.hurtingFallsOk(true, false));
        // no world is a test, not the nether
        assertFalse(ExperimentalMovement.inNether(null));
    }

    @Test
    public void stampFromTheSettingsHoldsJumpsOffUntilReset() {
        baritone.altoclef.AltoClefSettings s = baritone.altoclef.AltoClefSettings.getInstance();
        s.noJumpsFor(100);
        assertFalse(ExperimentalMovement.jumpMoveAllowed(false, true, false, s.jumpsOffUntilTick(), 102));
        s.resetAll();
        assertTrue(ExperimentalMovement.jumpMoveAllowed(false, true, false, s.jumpsOffUntilTick(), 102));
    }

    @Test
    public void healthGateKeepsTheMinimum() {
        assertTrue(ExperimentalMovement.canAffordFall(20, 4, 12));
        assertTrue(ExperimentalMovement.canAffordFall(20, 8, 12)); // ending exactly on the minimum is fine
        assertFalse(ExperimentalMovement.canAffordFall(20, 9, 12));
        assertFalse(ExperimentalMovement.canAffordFall(12, 1, 12));
        assertTrue(ExperimentalMovement.canAffordFall(12, 0, 12));
        assertTrue(ExperimentalMovement.canAffordFall(14.5, 2, 12)); // absorption makes for fractional hp
    }

    @Test
    public void tallestAffordableFallAtFullHealth() {
        int best = 0;
        for (int blocks = 0; blocks <= ExperimentalMovement.MAX_HURT_FALL; blocks++) {
            if (ExperimentalMovement.canAffordFall(20, ExperimentalMovement.fallDamage(blocks), 12)) {
                best = blocks;
            }
        }
        assertEquals(11, best); // 3 free + 8 hp
    }

    @Test
    public void sevenBlockDropCost() {
        // same arithmetic as MovementDescend.hurtingFall, written out so a change to either one trips this
        int blocks = 7;
        double cost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[blocks] + ExperimentalMovement.fallDamage(blocks) * 20;
        // 3.7 to step off + ~14 of falling + 4 hp at 20 ticks each is ~99 ticks, which is what ~21 blocks of walking costs
        assertEquals(4, ExperimentalMovement.fallDamage(blocks));
        assertTrue(cost > 95 && cost < 105);
    }

    private static Settings settingsOrSkip() {
        ItemFixture.boot();
        try {
            return BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
            return null;
        }
    }

    @Test
    public void ladderClutchFollowsThePreset() {
        Settings settings = settingsOrSkip();
        boolean fast = settings.fastMode.value;
        boolean clutch = settings.allowLadderClutch.value;
        try {
            settings.fastMode.value = false;
            settings.allowLadderClutch.value = false;
            assertFalse(ExperimentalMovement.allowLadderClutch());
            settings.allowLadderClutch.value = true;
            assertTrue(ExperimentalMovement.allowLadderClutch()); // gamer forces the setting, that has to keep working
            settings.allowLadderClutch.value = false;
            settings.fastMode.value = true;
            assertTrue(ExperimentalMovement.allowLadderClutch());
            assertFalse(settings.allowLadderClutch.value);
        } finally {
            settings.fastMode.value = fast;
            settings.allowLadderClutch.value = clutch;
        }
    }

    @Test
    public void parkourPlaceFollowsThePreset() {
        Settings settings = settingsOrSkip();
        boolean fast = settings.fastMode.value;
        boolean place = settings.allowParkourPlace.value;
        try {
            settings.fastMode.value = false;
            settings.allowParkourPlace.value = false;
            assertFalse(ExperimentalMovement.allowParkourPlace());
            settings.allowParkourPlace.value = true;
            assertTrue(ExperimentalMovement.allowParkourPlace()); // the user's own choice still counts with the preset off
            settings.allowParkourPlace.value = false;
            settings.fastMode.value = true;
            assertTrue(ExperimentalMovement.allowParkourPlace());
            assertFalse(settings.allowParkourPlace.value); // the preset never rewrites the setting
        } finally {
            settings.fastMode.value = fast;
            settings.allowParkourPlace.value = place;
        }
    }
}
