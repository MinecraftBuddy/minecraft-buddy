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

package baritone.behavior.look;

import org.junit.Test;

import static org.junit.Assert.*;

public class CombatWantsTest {

    // the order CombatAim.Priority uses: self defense, aura, golem, kill task
    private static final int SELF_DEFENSE = 0;
    private static final int AURA = 1;
    private static final int KILL_TASK = 3;

    @Test
    public void theHighestPriorityAskWinsWhateverOrderTheyCameIn() {
        CombatWants<String, String> w = new CombatWants<>();
        w.want("zombie", AURA, null);
        w.want("skeleton", KILL_TASK, "head");
        w.want("spider", SELF_DEFENSE, null);
        assertEquals("skeleton", w.who());
        assertEquals("head", w.point());
    }

    @Test
    public void aTieGoesToWhoeverAskedFirst() {
        CombatWants<String, String> w = new CombatWants<>();
        w.want("zombie", AURA, null);
        w.want("husk", AURA, null);
        assertEquals("zombie", w.who());
    }

    @Test
    public void anAskIsGoodForOneTick() {
        CombatWants<String, String> w = new CombatWants<>();
        w.want("zombie", KILL_TASK, null);
        w.clear();
        assertNull(w.who());
        // and a low priority ask after the clear is not beaten by the stale high one
        w.want("spider", SELF_DEFENSE, null);
        assertEquals("spider", w.who());
    }

    @Test
    public void theRampOnlyStartsOverForANewMob() {
        CombatWants<String, String> w = new CombatWants<>();
        assertTrue(w.engage("zombie"));
        // the same mob asked for every tick is one engagement
        for (int tick = 0; tick < 50; tick++) {
            assertFalse(w.engage("zombie"));
        }
        assertTrue(w.engage("skeleton"));
        assertTrue(w.engage("zombie"));
    }

    @Test
    public void aShortBreakKeepsTheEngagementALongOneEndsIt() {
        CombatWants<String, String> w = new CombatWants<>();
        w.engage("zombie");
        for (int tick = 0; tick < CombatWants.IDLE_RESET; tick++) {
            w.idle();
        }
        assertFalse(w.engage("zombie"));
        for (int tick = 0; tick <= CombatWants.IDLE_RESET; tick++) {
            w.idle();
        }
        assertTrue(w.engage("zombie"));
    }

    @Test
    public void aResetForgetsEverything() {
        CombatWants<String, String> w = new CombatWants<>();
        w.engage("zombie");
        w.want("zombie", KILL_TASK, null);
        w.reset();
        assertNull(w.who());
        assertTrue(w.engage("zombie"));
    }
}
