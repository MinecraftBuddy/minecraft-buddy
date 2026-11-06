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

package baritone.pathing.movement;

import baritone.pathing.movement.SwimEscape.Step;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwimEscapeTest {

    private static final int FAILING = SwimEscape.MIN_EXITS;

    private static Step floating(SwimEscape e) {
        return e.tick(FAILING, false, false, false);
    }

    @Test
    public void twoFailingExitsAreNotEnough() {
        SwimEscape e = new SwimEscape();
        for (int i = 0; i < 100; i++) {
            assertEquals(Step.NONE, e.tick(2, false, false, false));
        }
    }

    @Test
    public void sinksForTheWholeBudgetThenHandsThePathBack() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.SINK, floating(e));
        assertTrue(e.changed());
        for (int i = 1; i < SwimEscape.SINK_TICKS; i++) {
            assertEquals(Step.SINK, floating(e));
            assertFalse(e.changed());
        }
        // not back to swimming, that floats us right back to where we started
        assertEquals(Step.GIVE_BACK, floating(e));
        assertTrue(e.changed());
    }

    @Test
    public void triggerCanWaitForTheThirdExit() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.NONE, e.tick(FAILING - 1, false, false, false));
        assertEquals(Step.SINK, floating(e));
        assertTrue(e.changed());
    }

    @Test
    public void landingStopsTheSinkAndWalks() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.SINK, floating(e));
        assertEquals(Step.WALK, e.tick(FAILING, false, true, false));
        assertTrue(e.changed());
    }

    @Test
    public void alreadyOnTheFloorSkipsTheSink() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.WALK, e.tick(FAILING, false, true, false));
        assertTrue(e.changed());
    }

    @Test
    public void walkingSurvivesLeavingTheGround() {
        // a step up or the bob on a climb, the sink must not take forward away again mid move
        SwimEscape e = new SwimEscape();
        assertEquals(Step.WALK, e.tick(FAILING, false, true, false));
        for (int i = 0; i < SwimEscape.SINK_TICKS * 2; i++) {
            assertEquals(Step.WALK, floating(e));
        }
    }

    @Test
    public void sinkingUnderTheMoveHandsThePathBackRightAway() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.SINK, floating(e));
        assertEquals(Step.GIVE_BACK, e.tick(FAILING, false, false, true));
    }

    @Test
    public void landingUnderTheMoveAlsoHandsThePathBack() {
        // walking is for the cell the move starts in, down here a traverse would just press jump to get back up
        SwimEscape e = new SwimEscape();
        assertEquals(Step.GIVE_BACK, e.tick(FAILING, false, true, true));
    }

    @Test
    public void airWinsAtEveryStage() {
        SwimEscape before = new SwimEscape();
        assertEquals(Step.NONE, before.tick(FAILING, true, false, false));

        SwimEscape sinking = new SwimEscape();
        assertEquals(Step.SINK, floating(sinking));
        assertEquals(Step.NONE, sinking.tick(FAILING, true, false, false));

        SwimEscape walking = new SwimEscape();
        assertEquals(Step.WALK, walking.tick(FAILING, false, true, false));
        assertEquals(Step.NONE, walking.tick(FAILING, true, true, false));

        SwimEscape under = new SwimEscape();
        assertEquals(Step.NONE, under.tick(FAILING, true, false, true));
    }

    @Test
    public void aBreathDoesNotBuyMoreSinkTicks() {
        SwimEscape e = new SwimEscape();
        for (int i = 0; i < SwimEscape.SINK_TICKS / 2; i++) {
            assertEquals(Step.SINK, floating(e));
        }
        assertEquals(Step.NONE, e.tick(FAILING, true, false, false));
        for (int i = 0; i < SwimEscape.SINK_TICKS / 2; i++) {
            assertEquals(Step.SINK, floating(e));
        }
        assertEquals(Step.GIVE_BACK, floating(e));
    }

    @Test
    public void forgottenFailuresEndIt() {
        SwimEscape e = new SwimEscape();
        assertEquals(Step.SINK, floating(e));
        assertEquals(Step.NONE, e.tick(0, false, false, false));
    }
}
