package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import baritone.pathing.movement.Movement;
import baritone.pathing.path.PathExecutor;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.player.LocalPlayer;
import java.util.Arrays;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

// the world half of LavaShore. standing still with a drop behind us we lean toward the foe (a knock into held keys goes a
// third as far). a knock that came while we were walking a path throws the path away on landing if it left us off it near
// lava, so the executor does not walk the old dest across a cell that is now lava. (no leaning while a path runs: the
// movement clears the forced keys every tick)
final class LavaKnockGuard {
    private static final Input[] KEYS = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT};
    // a hit that never got us off the ground is not coming back down
    private static final int ARM_TICKS = 30;

    private final boolean[] _held = new boolean[4];
    private int _lastHurt;
    private boolean _armed;
    private boolean _wasAirborne;
    private int _armedFor;

    // forget everything, the world is gone
    void reset(AltoClef mod) {
        // (the forced keys outlive the world, they have to come up here)
        if (mod.getClientBaritone() != null) release(mod);
        Arrays.fill(_held, false);
        _armed = false;
        _lastHurt = 0;
    }

    // no world, or in the lava itself
    static boolean standsDown(boolean noPlayer, boolean inLava) {
        return noPlayer || inLava;
    }

    void tick(AltoClef mod, CombatBrain brain) {
        LocalPlayer player = mod.getPlayer();
        // swimming in lava is the escape task's, its keys and ours would fight over the wheel. every dimension: a
        // skeleton knocking us into a cave lava pool kills just as dead as a magma cube at a nether shore. merely burning stays
        // armed, a blaze fight is burning the whole time and that is when a knock off the shore matters most
        if (standsDown(player == null, player != null && player.isInLava())) {
            release(mod);
            _armed = false;
            _lastHurt = player == null ? 0 : player.hurtTime;
            return;
        }
        PathingBehavior pathing = mod.getClientBaritone().getPathingBehavior();
        boolean pathLive = pathing.isPathing();
        boolean onGround = player.onGround();
        // a mob did it: fire, lava and falls also tick hurtTime
        boolean mobHit = player.getLastDamageSource() != null && player.getLastDamageSource().getEntity() instanceof Mob;
        boolean freshHit = player.hurtTime > 0 && mobHit;
        // only the tick it starts, hurtTime stays up for ten
        boolean risingHit = freshHit && player.hurtTime > _lastHurt;
        _lastHurt = player.hurtTime;
        Cells cells = CliffAvoidance.of(mod.getClientBaritone().bsi);

        if (risingHit && pathLive && !_armed) {
            _armed = true;
            _wasAirborne = false;
            _armedFor = 0;
        }
        if (_armed) {
            _armedFor++;
            _wasAirborne |= !onGround;
            if (!pathLive || _armedFor > ARM_TICKS) {
                _armed = false;
            } else if (_wasAirborne && onGround) {
                _armed = false;
                boolean valid = feetInValid(pathing, player);
                if (LavaShore.cancelOnLanding(true, true, true, valid, LavaShore.lavaClose(cells, player.getX(), player.getY(), player.getZ()))) {
                    pathing.forceCancel();
                    pathLive = false;
                }
            }
        }

        Entity foe = brain.lavaFoe(mod);
        if (foe == null) {
            release(mod);
            return;
        }
        double ux = player.getX(), uy = player.getY(), uz = player.getZ();
        double dx = foe.getX() - ux, dz = foe.getZ() - uz;
        double len = Math.hypot(dx, dz);
        boolean back = LavaShore.backUnsafe(cells, ux, uy, uz, foe.getX(), foe.getZ());
        // one block toward the foe, or the foe's own spot when it is closer than that
        double step = Math.min(1.0, len);
        boolean ahead = len >= 0.05 && LavaShore.spotUnsafe(cells, ux + dx / len * step, uy, uz + dz / len * step);
        if (!LavaShore.hold(true, onGround, freshHit, pathLive, back, ahead)) {
            release(mod);
            return;
        }
        LavaShore.Keys keys = LavaShore.keysToward(player.getYRot(), dx, dz);
        if (!keys.any()) {
            release(mod);
            return;
        }
        // a task that presses its own movement keys (no path, but not idle either) keeps them, we only lean when nobody else is
        // driving
        for (int i = 0; i < KEYS.length; i++) {
            if (!_held[i] && (mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(KEYS[i])
                    || mod.getInputControls().isHeldDown(KEYS[i]))) {
                release(mod);
                return;
            }
        }
        boolean[] want = {keys.forward(), keys.back(), keys.left(), keys.right()};
        for (int i = 0; i < KEYS.length; i++) {
            if (want[i] || _held[i]) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(KEYS[i], want[i]);
            }
            _held[i] = want[i];
        }
    }

    // only the keys this guard pressed
    void release(AltoClef mod) {
        for (int i = 0; i < KEYS.length; i++) {
            if (_held[i]) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(KEYS[i], false);
                _held[i] = false;
            }
        }
    }

    private static boolean feetInValid(PathingBehavior pathing, LocalPlayer player) {
        PathExecutor current = pathing.getCurrent();
        if (current == null || current.getPath() == null) {
            return false;
        }
        int at = current.getPosition();
        var movements = current.getPath().movements();
        BetterBlockPos feet = new BetterBlockPos(player.blockPosition());
        // the one it was on and the next, a knock can carry us a step along as easily as off to the side
        for (int i = at; i <= at + 1 && i < movements.size(); i++) {
            if (i >= 0 && movements.get(i) instanceof Movement m && m.getValidPositions().contains(feet)) {
                return true;
            }
        }
        return false;
    }
}
