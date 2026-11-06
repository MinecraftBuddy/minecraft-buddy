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

package baritone.pathing.movement.movements;

import baritone.utils.ExperimentalMovement;
import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a ladder clutch at hp 6 that misses is a dead bot. the planner stops offering it below fastModeMinHealth, the
// bucket keeps its place
public class ClutchHealthTest {

    private static final double MIN = 12;
    private static final double CLUTCH = 40;
    private static final double BUCKET = 55;

    @Test
    public void theClutchIsPricedAtFullHealth() {
        assertEquals(CLUTCH, MovementDescend.clutchAtHealth(CLUTCH, 20, MIN), 0);
        // right on the floor still counts, like canAffordFall
        assertEquals(CLUTCH, MovementDescend.clutchAtHealth(CLUTCH, MIN, MIN), 0);
    }

    @Test
    public void theClutchIsOffTheMenuAtLowHealth() {
        assertEquals(COST_INF, MovementDescend.clutchAtHealth(CLUTCH, 6, MIN), 0);
        assertEquals(COST_INF, MovementDescend.clutchAtHealth(CLUTCH, 11.5, MIN), 0);
        // and a clutch that was impossible anyway stays impossible
        assertEquals(COST_INF, MovementDescend.clutchAtHealth(COST_INF, 20, MIN), 0);
    }

    @Test
    public void theBucketStaysWhenTheClutchIsPricedOut() {
        // hp 20: the cheaper clutch wins over the bucket
        assertTrue(MovementDescend.clutchBeatsBucket(MovementDescend.clutchAtHealth(CLUTCH, 20, MIN), true, BUCKET));
        // hp 6: the clutch is gone, the bucket (finite, no health gate on it) is the save
        assertFalse(MovementDescend.clutchBeatsBucket(MovementDescend.clutchAtHealth(CLUTCH, 6, MIN), true, BUCKET));
        assertTrue(BUCKET < COST_INF);
        // no bucket either at hp 6: no save at all, the fall is left to hurtingFall's own health gate
        assertFalse(MovementDescend.clutchBeatsBucket(MovementDescend.clutchAtHealth(CLUTCH, 6, MIN), false, BUCKET));
    }

    @Test
    public void theBucketStillWinsTies() {
        assertFalse(MovementDescend.clutchBeatsBucket(BUCKET, true, BUCKET));
        assertTrue(MovementDescend.clutchBeatsBucket(BUCKET, false, BUCKET));
    }

    @Test
    public void aHurtingFallInsideTheLimitOnlyHasToNotKill() {
        // the user raised maxFallHeightNoWater for these, they used to be free
        assertEquals(ExperimentalMovement.BUCKET_MISS_FLOOR, ExperimentalMovement.hurtFloor(true, MIN), 0);
        // the longer ones fastMode adds keep its floor
        assertEquals(MIN, ExperimentalMovement.hurtFloor(false, MIN), 0);
    }

    @Test
    public void canAffordClutchIsTheSameFloorAsTheHurtGate() {
        assertTrue(ExperimentalMovement.canAffordClutch(20, MIN));
        assertTrue(ExperimentalMovement.canAffordClutch(MIN, MIN));
        assertFalse(ExperimentalMovement.canAffordClutch(6, MIN));
        // absorption counts, same as the context's health
        assertTrue(ExperimentalMovement.canAffordClutch(10 + 4, MIN));
    }
}
