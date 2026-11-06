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

package baritone.behavior;

import adris.altoclef.AltoClef;
import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import net.minecraft.client.Minecraft;

public final class ActivityBehavior extends Behavior {

    public ActivityBehavior(Baritone baritone) {
        super(baritone);
    }

    // vanilla drops you to 30 fps after a minute with no keyboard or mouse. we drive the player through the input
    // override and rotations so vanilla never sees any input and thinks you went to make a sandwich
    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || !Baritone.settings().keepFpsWhileBotting.value) {
            return;
        }
        if (botIsBusy()) {
            // only the activity timestamp, the user's options.txt never hears about this
            Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
        }
    }

    private boolean botIsBusy() {
        // post tick, so mostRecentInControl is whoever just drove this tick (temporary ones like combat pause count too)
        if (baritone.getPathingBehavior().isPathing() || baritone.getPathingControlManager().mostRecentInControl().isPresent()) {
            return true;
        }
        // the idle task is the bot waiting around for something to do, which is exactly when afk should be allowed
        AltoClef alto = AltoClef.getInstance();
        return alto != null && alto.getUserTaskChain() != null
                && alto.getUserTaskChain().isActive() && !alto.getUserTaskChain().isRunningIdleTask();
    }
}
