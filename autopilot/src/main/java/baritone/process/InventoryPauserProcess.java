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

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;

public class InventoryPauserProcess extends BaritoneProcessHelper {

    boolean pauseRequestedLastTick;
    boolean safeToCancelLastTick;
    int ticksOfStationary;
    // (inv_pause_cap, a fix of 14 Nov 2026, on) a run of pauses held to PauseCap's ticks, then the pathing runs a while
    private final PauseCap pauseCap = new PauseCap();
    private boolean capSaid;

    public InventoryPauserProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        return true;
    }

    private double motion() {
        return ctx.player().getDeltaMovement().multiply(1, 0, 1).length();
    }

    private boolean stationaryNow() {
        return motion() < 0.00001;
    }

    public boolean stationaryForInventoryMove() {
        pauseRequestedLastTick = true;
        return safeToCancelLastTick && ticksOfStationary > 1;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        //logDebug(pauseRequestedLastTick + " " + safeToCancelLastTick + " " + ticksOfStationary);
        safeToCancelLastTick = isSafeToCancel;
        // (inv_pause_cap) at the gold mine a swap loop asked for a pause four times a second for 18 minutes and no path search
        // ran: a run of pauses holds the pathing about 2 s at most, then 3 s with no pause (and so no move that waits for one)
        if (adris.altoclef.util.helpers.Tuning.get("inv_pause_cap", 0) > 0) {
            boolean requested = pauseRequestedLastTick;
            pauseRequestedLastTick = false;
            if (!pauseCap.tick(requested)) {
                if (pauseCap.freeing() && !capSaid) {
                    capSaid = true;
                    logDebug("inventory pauser: pauses held the pathing " + PauseCap.CAP_TICKS + " ticks, the pathing runs " + PauseCap.FREE_TICKS + " ticks first (inv_pause_cap)");
                }
                if (!pauseCap.freeing()) {
                    capSaid = false;
                }
                ticksOfStationary = 0;
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            if (stationaryNow()) {
                ticksOfStationary++;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (pauseRequestedLastTick) {
            pauseRequestedLastTick = false;
            if (stationaryNow()) {
                ticksOfStationary++;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ticksOfStationary = 0;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    @Override
    public void onLostControl() {

    }

    @Override
    public String displayName0() {
        return "inventory pauser";
    }

    @Override
    public double priority() {
        return 5.1; // slightly higher than backfill
    }

    @Override
    public boolean isTemporary() {
        return true;
    }
}
