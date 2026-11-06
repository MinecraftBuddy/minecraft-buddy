package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// kill whoever is closest, forever. the swinging is all AbstractKillEntityTask (same weapon pick, crit hops and reach
// rules the mob fights use), this only decides who
public class HuntTask extends AbstractKillEntityTask {

    // a new player has to be this much closer to steal the target, or two players at the same range swap us every tick
    private static final double SWITCH_MARGIN = 3;

    private Player _target;
    // where the target was when we last had them, for walking after someone who left render distance
    private Vec3 _lastSeen;

    public HuntTask() {
        super(7, 1);
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _target = null;
        _lastSeen = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        pickTarget(mod);
        if (_target == null) {
            // super would wander off here. nobody in sight means go where the last one was, or stand and wait
            mod.getMobDefenseChain().resetForceField();
            if (_lastSeen != null) {
                if (_lastSeen.closerThan(mod.getPlayer().position(), 2)) {
                    _lastSeen = null;
                } else {
                    setDebugState("Walking to where the last player was");
                    return new GetToBlockTask(BlockPos.containing(_lastSeen), false);
                }
            }
            setDebugState("Nobody in sight, waiting");
            return null;
        }
        _lastSeen = _target.position();
        return super.onTick(mod);
    }

    private void pickTarget(AltoClef mod) {
        // a corpse is not worth walking to, but someone who unloaded still has health and is
        if (_target != null && _target.getHealth() <= 0) _lastSeen = null;
        if (_target != null && !valid(mod, _target)) _target = null;

        Player closest = null;
        double closestDist = Double.POSITIVE_INFINITY;
        for (Player player : mod.getWorld().players()) {
            if (!valid(mod, player)) continue;
            double dist = player.distanceTo(mod.getPlayer());
            if (dist < closestDist) {
                closest = player;
                closestDist = dist;
            }
        }
        if (closest == null) return;
        if (_target == null || closestDist < _target.distanceTo(mod.getPlayer()) - SWITCH_MARGIN) {
            _target = closest;
        }
    }

    private static boolean valid(AltoClef mod, Player player) {
        // creative players can't be hurt, so swinging at them is just a long walk
        return player != mod.getPlayer() && player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        return Optional.ofNullable(_target);
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        return other instanceof HuntTask;
    }

    @Override
    protected String toHudString() {
        return _target == null ? "Hunting" : "Hunting " + _target.getName().getString();
    }

    @Override
    protected String toDebugString() {
        return toHudString();
    }
}
