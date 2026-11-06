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

package adris.altoclef.chains;

import org.junit.Test;

import static adris.altoclef.chains.RespawnWait.Action.*;
import static org.junit.Assert.assertEquals;

public class RespawnWaitTest {

    @Test
    public void nothingIsSentWhileStillDead() {
        RespawnWait wait = new RespawnWait();
        for (int i = 0; i < 50; i++) {
            assertEquals(WAIT, wait.tick(true, false));
        }
    }

    @Test
    public void sendsAfterTheSettleDelayOnceRespawned() {
        RespawnWait wait = new RespawnWait();
        wait.tick(true, false);
        wait.tick(true, false);
        // new player entity shows up
        for (int i = 1; i < RespawnWait.SETTLE_TICKS; i++) {
            assertEquals("tick " + i + " after respawn", WAIT, wait.tick(true, true));
        }
        assertEquals(SEND, wait.tick(true, true));
    }

    @Test
    public void notSentTheSameTickWeRespawn() {
        // the old code ran the command right after asking, this is the bug
        assertEquals(WAIT, new RespawnWait().tick(true, true));
    }

    @Test
    public void givesUpIfTheRespawnNeverHappens() {
        RespawnWait wait = new RespawnWait();
        for (int i = 0; i < RespawnWait.TIMEOUT_TICKS; i++) {
            assertEquals(WAIT, wait.tick(true, false));
        }
        assertEquals(DROP, wait.tick(true, false));
    }

    @Test
    public void givesUpWhenDisconnected() {
        RespawnWait wait = new RespawnWait();
        wait.tick(true, false);
        assertEquals(DROP, wait.tick(false, false));
    }

    @Test
    public void aLateRespawnStillGetsItsCommand() {
        // the timeout is for a respawn that never comes, once it has come we just finish settling
        RespawnWait wait = new RespawnWait();
        for (int i = 0; i < RespawnWait.TIMEOUT_TICKS - 2; i++) {
            wait.tick(true, false);
        }
        for (int i = 1; i < RespawnWait.SETTLE_TICKS; i++) {
            assertEquals(WAIT, wait.tick(true, true));
        }
        assertEquals(SEND, wait.tick(true, true));
    }
}
