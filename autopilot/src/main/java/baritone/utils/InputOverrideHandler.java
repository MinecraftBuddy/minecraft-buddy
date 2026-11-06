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

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;

import baritone.api.pathing.movement.IMovement;
import baritone.api.process.IBaritoneProcess;
import baritone.pathing.path.PathExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    // holds sneak and walks us back on when a path lets go while we hang off a ledge, see EdgeSettle
    private final EdgeSettle settle = new EdgeSettle();
    // the executor the last tick ran, a new one means the old one stopped steering
    private PathExecutor lastExec;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
        this.blockPlaceHelper.setWant(pos -> baritone.getBuilderProcess().placeAt(pos.getX(), pos.getY(), pos.getZ(), baritone.bsi.get0(pos)));
        this.blockBreakHelper.setWant(pos -> baritone.getBuilderProcess().placeAt(pos.getX(), pos.getY(), pos.getZ(), baritone.bsi.get0(pos)));
        this.blockBreakHelper.setBreakListener((pos, before, who) -> baritone.getBuilderProcess().broke(pos, before, who));
        this.blockPlaceHelper.setGeneration(() -> baritone.getBuilderProcess().ledgerGeneration());
        this.blockPlaceHelper.setTracedPlaceListener((pos, state, who, generation) -> baritone.getBuilderProcess().placed(pos, state, who, generation));
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            settle.reset();
            lastExec = null;
            return;
        }
        tickSettle();
        if (AltoClefSettings.getInstance().isInteractionPaused() || settle.active()) {
            // this is the only place a click turns into a real one, so every process and movement gets muzzled at once
            setInputForceState(Input.CLICK_LEFT, false);
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        if (isInputForcedDown(Input.CLICK_RIGHT) && baritone.getInventoryBehavior().handWaitingOnSwap()) {
            // the block we selected is still on its way to the hotbar and the click would hit the fallback in BlockPlaceHelper
            // (useItem), so whatever is in that slot gets used instead. the movement asks again next tick, the swap has landed by then
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        // only while building, everything else has its own trace lines and these would drown them
        String who = baritone.getBuilderProcess().isActive() && (isInputForcedDown(Input.CLICK_LEFT) || isInputForcedDown(Input.CLICK_RIGHT)) ? clicker() : null;
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT), who);
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT), who);

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    // judged here because the keys are final: processes ran in preTick, the path ran, and the soft cancel after us only sets
    // a flag. the last consumed input is the one the player moved with last tick
    private void tickSettle() {
        LocalPlayer player = ctx.player();
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        boolean changed = exec != lastExec;
        lastExec = exec;
        if (player == null) {
            settle.tick(null, null, null, false, false, true);
            return;
        }
        boolean shiftLastTick = player.input instanceof PlayerMovementInput p && p.keyPresses.shift();
        boolean moveKey = false;
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.JUMP}) {
            moveKey |= isInputForcedDown(input);
        }
        // a pause that landed or a path that ended or got swapped is a path not steering. the same executor running a tick
        // with no keys is a look wait, not a let go
        boolean idle = exec == null || baritone.getPathingBehavior().pausedLastTick() || changed;
        settle.tick(player, ctx.world(), ctx.minecraft().options, shiftLastTick, isInputForcedDown(Input.SNEAK), moveKey || !idle);
    }

    // PlayerMovementInput lays these over the forced keys: sneak down, no jump, no sprint
    public boolean settleActive() {
        return settle.active();
    }

    public EdgeSettle.Keys settleKeys() {
        return settle.keys(baritone.getLookBehavior().pendingMoveYaw());
    }

    // whose click this is for the trace: the builder's own, else the movement running, else whoever has the wheel
    private String clicker() {
        if (baritone.getBuilderProcess().clickedByBuilder()) {
            return "builder";
        }
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec != null) {
            List<IMovement> moves = exec.getPath().movements();
            int at = exec.getPosition();
            if (at >= 0 && at < moves.size()) {
                return moves.get(at).getClass().getSimpleName();
            }
        }
        return baritone.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName).orElse("nobody");
    }

    private boolean inControl() {
        // or the swap to KeyboardInput lets go of sneak right here
        if (settle.active()) {
            return true;
        }
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    // game time of the last ask to keep sprint off, see lockSprintOff
    private long sprintLockedAt = Long.MIN_VALUE;

    // keep sprint off for this tick, whoever wants it on: the toggle, a held ctrl, us. the crit hop asks every tick from the
    // jump to the swing, a sprinting hit is no crit. nobody has to remember to let go, a lock nobody refreshed is gone a tick
    // later (a swing, a give up, a landing, the task stopping, a new world). vanilla's double tap W sprint doesn't go
    // through the sprint input and isn't covered, the bot never double taps so that one only matters if you do it yourself
    public void lockSprintOff() {
        if (ctx.world() != null) {
            sprintLockedAt = ctx.world().getGameTime();
        }
    }

    // read in aiStep. the ask comes from the alto tick, which is the same game tick or (if the level clock moved in
    // between) the one before
    public boolean sprintLocked() {
        if (ctx.world() == null) {
            return false;
        }
        long since = ctx.world().getGameTime() - sprintLockedAt;
        return since >= 0 && since <= 1;
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        sprintLockedAt = Long.MIN_VALUE;
        settle.reset();
        lastExec = null;
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }

    public BlockPlaceHelper getBlockPlaceHelper() {
        return blockPlaceHelper;
    }
}
