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

package baritone.pathing.sim;

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static baritone.pathing.sim.AscendHop.Verdict.JUMP;
import static baritone.pathing.sim.AscendHop.Verdict.UNKNOWN;
import static baritone.pathing.sim.AscendHop.Verdict.WAIT;
import static org.junit.Assert.assertEquals;

// the step is the one in PlayerSimTest: floor top at y = 64, a one block step with its face at z = 12, running +z.
// for a sprint hop the clean window is z = 9.3 to 11.2 at the start of the jump tick, with a tenth to spare in 9.4 to 11.1
public class AscendHopTest {

    private static final BetterBlockPos DEST = new BetterBlockPos(8, 65, 12);

    private static ArrayWorld world() {
        ArrayWorld world = new ArrayWorld(16, 80, 64);
        world.fill(0, 60, 0, 15, 63, 63, ArrayWorld.STONE);
        world.fill(0, 64, 12, 15, 64, 63, ArrayWorld.STONE);
        return world;
    }

    private static PlayerSim sprintingAt(double z) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world());
        sim.x = 8.5;
        sim.y = 64;
        sim.z = z;
        sim.vy = -0.0784;
        sim.vz = 0.1532; // sprint pace, 0.28 a tick
        sim.onGround = true;
        sim.sprinting = true;
        return sim;
    }

    private static AscendHop.Verdict ask(double z) {
        return AscendHop.decide(sprintingAt(z), 0, 1, DEST, 0F, 0F, true);
    }

    @Test
    public void insideTheWindowJumpsNow() {
        assertEquals(JUMP, ask(10.0));
        assertEquals(JUMP, ask(9.6));
        assertEquals(JUMP, ask(10.9));
    }

    @Test
    public void justBeforeTheWindowHoldsOnForAnotherTick() {
        assertEquals(WAIT, ask(9.0)); // the hop would come down against the face, a tick on it does not
        assertEquals(WAIT, ask(8.8));
    }

    @Test
    public void rightAtTheNearEdgeItJumpsBecauseItNeverGetsBetter() {
        // clean but only just: a tenth of a block closer is a clip, and so is the next tick
        assertEquals(JUMP, ask(11.15));
    }

    @Test
    public void pastTheWindowThereIsNothingToDoAboutIt() {
        assertEquals(UNKNOWN, ask(11.5));
    }

    @Test
    public void tooFarBackToReachTheWindowInTimeIsNotHeldStillFor() {
        assertEquals(UNKNOWN, ask(6.0));
    }

    @Test
    public void aWalkingHopHasItsOwnWindowAndTheSimKnowsIt() {
        // a walking jump is shorter in every way: it needs to be within about a block of the face to come down on top of it
        // (1.2 blocks from the middle of the step, which is where the movement has always jumped)
        assertEquals(AscendHop.Verdict.UNKNOWN, askWalking(9.0));
        assertEquals(WAIT, askWalking(10.0));
        assertEquals(JUMP, askWalking(11.0));
    }

    private static AscendHop.Verdict askWalking(double z) {
        PlayerSim sim = sprintingAt(z);
        sim.sprinting = false;
        sim.vz = 0.098 / 0.454 * 0.546;
        return AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, false);
    }

    @Test
    public void iceUnderneathMeansWeDontKnow() {
        ArrayWorld world = world();
        world.fill(0, 63, 0, 15, 63, 63, ArrayWorld.ICE);
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = 8.5;
        sim.y = 64;
        sim.z = 10;
        sim.vy = -0.0784;
        sim.onGround = true;
        assertEquals(UNKNOWN, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true));
    }
}
