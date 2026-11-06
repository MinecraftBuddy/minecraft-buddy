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

package baritone.utils;

import baritone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

public class PlayerMovementInput extends ClientInput {

    private final InputOverrideHandler handler;

    PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    @Override
    public void tick() {
        float leftImpulse = 0.0F;
        float forwardImpulse = 0.0F;
        boolean jumping = handler.isInputForcedDown(Input.JUMP); // oppa gangnam style

        boolean up = handler.isInputForcedDown(Input.MOVE_FORWARD);
        if (up) {
            forwardImpulse++;
        }

        boolean down = handler.isInputForcedDown(Input.MOVE_BACK);
        if (down) {
            forwardImpulse--;
        }

        boolean left = handler.isInputForcedDown(Input.MOVE_LEFT);
        if (left) {
            leftImpulse++;
        }

        boolean right = handler.isInputForcedDown(Input.MOVE_RIGHT);
        if (right) {
            leftImpulse--;
        }

        boolean sneaking = handler.isInputForcedDown(Input.SNEAK);
        boolean sprinting = handler.isInputForcedDown(Input.SPRINT);
        if (handler.settleActive()) {
            // the edge settle owns this tick whatever got forced, here and not in the force map so no clearAllKeys eats it
            EdgeSettle.Keys keys = handler.settleKeys();
            up = keys.forward();
            down = keys.back();
            left = keys.left();
            right = keys.right();
            forwardImpulse = (up ? 1 : 0) - (down ? 1 : 0);
            leftImpulse = (left ? 1 : 0) - (right ? 1 : 0);
            jumping = false;
            sneaking = true;
            sprinting = false;
        }
        // no sneak scaling here, LocalPlayer.modifyInput does the 0.98 and the sneaking speed attribute (scaling it here
        // too made a sneak 0.09 instead of 0.294). and normalized like KeyboardInput, or a diagonal sneak is 1.4x too fast
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();

        this.keyPresses = new net.minecraft.world.entity.player.Input(up, down, left, right, jumping, sneaking, sprinting);
    }
}
