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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// pure ticks and distances, no minecraft. a zombie biting us mid mine should get hit back, and nothing else should
public class SelfDefenseRulesTest {

    private static final double REACH = 3;

    @Test
    public void aFreshBiteInReachStartsAFight() {
        assertTrue(SelfDefenseRules.engage(10, true));
        assertTrue(SelfDefenseRules.engage(0, true));
        assertTrue(SelfDefenseRules.engage(SelfDefenseRules.FRESH_HIT, true));
    }

    @Test
    public void anOldBiteDoesNot() {
        assertFalse(SelfDefenseRules.engage(41, true));
        // and a fresh one that is not here is not our problem either, we don't chase
        assertFalse(SelfDefenseRules.engage(10, false));
        // a clock that went backwards is a new world
        assertFalse(SelfDefenseRules.engage(-5, true));
    }

    @Test
    public void outOfReachHoldsUntilThirtyTicks() {
        assertFalse(SelfDefenseRules.release(false, REACH + 1, 29, 0));
        assertTrue(SelfDefenseRules.release(false, REACH + 1, 30, 0));
    }

    @Test
    public void walkingOffPastEightIsAnInstantRelease() {
        assertFalse(SelfDefenseRules.release(false, 8, 1, 0));
        assertTrue(SelfDefenseRules.release(false, 8.01, 1, 0));
    }

    @Test
    public void noSwingableLineForFortyTicksReleases() {
        assertFalse(SelfDefenseRules.release(false, 2, 0, 39));
        assertTrue(SelfDefenseRules.release(false, 2, 0, 40));
        // and we leave that one alone for a bit after
        long length = SelfDefenseRules.shrugFor(false);
        assertEquals(SelfDefenseRules.SHRUG_TICKS, length);
        assertTrue(SelfDefenseRules.shrugged(0, length));
        assertTrue(SelfDefenseRules.shrugged(length - 1, length));
        assertFalse(SelfDefenseRules.shrugged(length, length));
        assertFalse(SelfDefenseRules.shrugged(-1, length));
    }

    @Test
    public void oneThatWontGetHurtIsGivenUpOn() {
        assertFalse(SelfDefenseRules.noProgress(SelfDefenseRules.NO_PROGRESS_TICKS - 1));
        assertTrue(SelfDefenseRules.noProgress(SelfDefenseRules.NO_PROGRESS_TICKS));
        // and it's left alone a lot longer than one we just couldn't see
        assertTrue(SelfDefenseRules.shrugFor(true) > SelfDefenseRules.shrugFor(false));
        assertTrue(SelfDefenseRules.shrugged(SelfDefenseRules.SHRUG_TICKS, SelfDefenseRules.shrugFor(true)));
    }

    @Test
    public void deadIsDone() {
        assertTrue(SelfDefenseRules.release(true, 1, 0, 0));
        assertFalse(SelfDefenseRules.release(false, 1, 0, 0));
    }

    @Test
    public void excludedKindsNeverGoInTheBook() {
        for (String type : new String[]{"creeper", "warden", "wither", "ender_dragon", "creaking", "phantom", "vex", "ghast",
                "blaze"}) {
            assertFalse(type, SelfDefenseRules.noteable(true, true, type));
        }
        assertTrue(SelfDefenseRules.noteable(true, true, "zombie"));
        assertTrue(SelfDefenseRules.noteable(true, true, "skeleton"));
        assertTrue(SelfDefenseRules.noteable(true, true, "zombified_piglin"));
        // a wolf is a mob but no enemy, a player is neither
        assertFalse(SelfDefenseRules.noteable(true, false, "wolf"));
        assertFalse(SelfDefenseRules.noteable(false, false, "player"));
    }

    @Test
    public void theBookForgetsOldDeadAndTimeTravel() {
        assertFalse(SelfDefenseRules.forget(SelfDefenseRules.MEMORY, true));
        assertTrue(SelfDefenseRules.forget(SelfDefenseRules.MEMORY + 1, true));
        assertTrue(SelfDefenseRules.forget(5, false));
        assertTrue(SelfDefenseRules.forget(-1, true));
    }

    @Test
    public void altoRunningMeansAltoFights() {
        assertTrue(SelfDefenseRules.canRun(true, true, false, false, false, false, true));
        assertFalse(SelfDefenseRules.canRun(true, true, false, false, false, true, true));
    }

    @Test
    public void noJobNoGuard() {
        assertFalse(SelfDefenseRules.canRun(true, true, false, false, false, false, false));
    }

    @Test
    public void waterLavaElytraAndTheSettingAllStandItDown() {
        assertFalse(SelfDefenseRules.canRun(true, true, false, true, false, false, true));
        assertFalse(SelfDefenseRules.canRun(true, true, false, false, true, false, true));
        assertFalse(SelfDefenseRules.canRun(true, true, true, false, false, false, true));
        assertFalse(SelfDefenseRules.canRun(false, true, false, false, false, false, true));
        assertFalse(SelfDefenseRules.canRun(true, false, false, false, false, false, true));
    }

    @Test
    public void swingsOnlyWhenChargedAndOnTarget() {
        // on target is reach, a clear line and the ray actually hitting it, all in one (see CombatAimMathTest for the ray)
        assertTrue(SelfDefenseRules.swing(1f, true));
        assertTrue(SelfDefenseRules.swing(SelfDefenseRules.SWING_CHARGE, true));
        assertFalse(SelfDefenseRules.swing(0.5f, true));
        assertFalse(SelfDefenseRules.swing(1f, false));
    }

    @Test
    public void swordThenAxeThenWhateverHitsHardest() {
        // pickaxe in hand (slot 0), axe in 3, sword in 5
        int[] kind = {0, 0, 0, 1, 0, 2, 0, 0, 0};
        float[] dmg = {5, 1, 1, 9, 1, 6, 1, 1, 1};
        assertEquals(5, SelfDefenseRules.weaponSlot(kind, dmg, 0, 1));
        kind[5] = 0;
        dmg[5] = 1;
        assertEquals(3, SelfDefenseRules.weaponSlot(kind, dmg, 0, 1));
        kind[3] = 0;
        dmg[3] = 1;
        // only the pickaxe hits harder than a fist, and it's already in hand
        assertEquals(0, SelfDefenseRules.weaponSlot(kind, dmg, 0, 1));
    }

    @Test
    public void twoEqualSwordsNeverSwap() {
        int[] kind = {2, 0, 2, 0, 0, 0, 0, 0, 0};
        float[] dmg = {6, 1, 6, 1, 1, 1, 1, 1, 1};
        assertEquals(2, SelfDefenseRules.weaponSlot(kind, dmg, 2, 1));
    }

    @Test
    public void nothingBetterThanFistsKeepsTheHand() {
        float[] dmg = {1, 1, 1, 1, 1, 1, 1, 1, 1};
        assertEquals(-1, SelfDefenseRules.weaponSlot(new int[9], dmg, 4, 1));
    }
}
