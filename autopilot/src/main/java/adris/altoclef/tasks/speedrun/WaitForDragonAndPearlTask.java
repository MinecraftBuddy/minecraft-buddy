package adris.altoclef.tasks.speedrun;

import adris.altoclef.tasks.speedrun.gamer.end.CrystalBlast;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.gamer.end.DragonWaitRules;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// TODO:
// The 10 Portal pillars form a 43 block radius, but the angle offset/cycle is random.
// Have an internal "cycle" value or something to keep track of where that cycle is
// Detect that value by scrolling around the 43 block radius in search of obsidian and finding
// the "midpoint" between two spots of obsidian and anything else
// Then, when pillaring, make sure we move to one of those areas (so we can move further out without
// risking hitting an obsidian tower)
public class WaitForDragonAndPearlTask extends Task implements IDragonWaiter {

    // How far to travel away from the portal, in XZ
    private static final double XZ_RADIUS = 30;
    private static final double XZ_RADIUS_TOO_FAR = 38;
    // How high to pillar
    private static final int HEIGHT = 42; //Increase height because this too low

    private static final int CLOSE_ENOUGH_DISTANCE = 15;

    // this was a "Y_COORDINATE = 75" fed in as the Z of the pillar goal, which is a pillar in the void. we pillar at XZ_RADIUS
    // from the fountain instead: the obsidian towers sit around 42 out, the dragon dives at the middle
    private BlockPos _pillarSpot;
    private BlockPos _pillarGoal;
    private static final double PILLAR_SPOT_SLACK = 6;

    private static final double DRAGON_FIREBALL_TOO_CLOSE_RANGE = 40;
    private final Task _buildingMaterialsTask = new GetBuildingMaterialsTask(HEIGHT + 10);
    private Task _heightPillarTask;
    private Task _throwPearlTask;
    private BlockPos _targetToPearl;
    // the way out of the breath we are walking, kept until we get there
    private DragonWaitRules.Spot _dodgeSpot;
    private Task _dodgeTask;
    private boolean _dragonIsPerching;
    // To avoid dragons breath
    private Task _pillarUpFurther;

    private boolean _hasPillar = false;
    // the bed strategy never pillars: from up there the bot could not get back down in time for a landing
    private final boolean _groundOnly;

    public WaitForDragonAndPearlTask() {
        this(false);
    }

    public WaitForDragonAndPearlTask(boolean groundOnly) {
        _groundOnly = groundOnly;
    }

    @Override
    public void setExitPortalTop(BlockPos top) {
        BlockPos actualTarget = top.below();
        if (!actualTarget.equals(_targetToPearl)) {
            _targetToPearl = actualTarget;
            _throwPearlTask = new ThrowEnderPearlSimpleProjectileTask(actualTarget);
        }
    }

    @Override
    public void setPerchState(boolean perching) {
        _dragonIsPerching = perching;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // a death sends us back through here with the same task object, nothing from the last attempt carries over
        _hasPillar = false;
        _pillarSpot = null;
        _pillarGoal = null;
        _heightPillarTask = null;
        _pillarUpFurther = null;
        _dodgeSpot = null;
        _dodgeTask = null;
        _throwPearlTask = _targetToPearl == null ? null : new ThrowEnderPearlSimpleProjectileTask(_targetToPearl);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // everything below leans on the pearl target, which only exists after setExitPortalTop
        if (_targetToPearl == null) {
            setDebugState("Waiting for the exit portal to show up.", "Waiting for the dragon");
            return null;
        }
        if (_throwPearlTask != null && _throwPearlTask.isActive() && !_throwPearlTask.isFinished(mod)) {
            setDebugState("Throwing pearl!");
            return _throwPearlTask;
        }

        // pearls decide it every tick: a pearl in the bag is the pillar and the throw, none is the ground. nothing is
        // remembered, so a pearl thrown (or never crafted) does not leave us climbing or hunting for another
        if (_groundOnly || DragonWaitRules.mode(mod.getItemStorage().hasItem(Items.ENDER_PEARL) ? 1 : 0) == DragonWaitRules.Mode.GROUND) {
            return groundTick(mod);
        }

        if (_pillarUpFurther != null && _pillarUpFurther.isActive() && !_pillarUpFurther.isFinished(mod) && (mod.getEntityTracker().getClosestEntity(AreaEffectCloud.class).isPresent())) {

            Optional<Entity> cloud = mod.getEntityTracker().getClosestEntity(AreaEffectCloud.class);

            if (cloud.isPresent() && cloud.get().closerThan(mod.getPlayer(), 4)) {
                setDebugState("PILLAR UP FURTHER to avoid dragon's breath");
                return _pillarUpFurther;
            }

            Optional<Entity> fireball = mod.getEntityTracker().getClosestEntity(DragonFireball.class);

            if (isFireballDangerous(mod, fireball)) {
                setDebugState("PILLAR UP FURTHER to avoid dragon's breath");
                return _pillarUpFurther;
            }
        }

        int minHeight = _targetToPearl.getY() + HEIGHT - 3;

        int deltaY = minHeight - mod.getPlayer().blockPosition().getY();
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(deltaY - 10, HEIGHT - 5) || _buildingMaterialsTask.isActive() && !_buildingMaterialsTask.isFinished(mod)) {
            setDebugState("Collecting building materials...");
            return _buildingMaterialsTask;
        }

        // Our trigger to throw is that the dragon starts perching. We can be an arbitrary distance and we'll still do it lol
        if (_dragonIsPerching && LookHelper.cleanLineOfSight(mod.getPlayer(), _targetToPearl.above(), 300)) {
            Debug.logMessage("THROWING PEARL!!");
            return _throwPearlTask;
        }
        if (mod.getPlayer().blockPosition().getY() < minHeight) {
            if (mod.getEntityTracker().entityFound(entity ->
                    mod.getPlayer().position().closerThan(entity.position(), 4), AreaEffectCloud.class)) {
                if (mod.getEntityTracker().getClosestEntity(EnderDragon.class).isPresent() &&
                        !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                    LookHelper.lookAt(mod, mod.getEntityTracker().getClosestEntity(EnderDragon.class).get().getEyePosition());
                }
                return null;
            }
            if (_heightPillarTask != null && _heightPillarTask.isActive() && !_heightPillarTask.isFinished(mod)) {
                setDebugState("Pillaring up!");
                if (mod.getEntityTracker().entityFound(EndCrystal.class)) {
                    return new DoToClosestEntityTask(
                            (toDestroy) -> {
                                if (toDestroy.closerThan(mod.getPlayer(), 7) && CrystalBlast.safeToSwing(CrystalStep.blastDamage(mod, mod.getPlayer(), toDestroy))) {
                                    mod.getControllerExtras().attack(toDestroy);
                                }
                                if (mod.getPlayer().blockPosition().getY() < minHeight) {
                                    return _heightPillarTask;
                                } else {
                                    if (mod.getEntityTracker().getClosestEntity(EnderDragon.class).isPresent() &&
                                            !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                                        LookHelper.lookAt(mod, mod.getEntityTracker().getClosestEntity(EnderDragon.class).get().getEyePosition());
                                    }
                                    return null;
                                }
                            },
                            EndCrystal.class
                    );
                }
                return _heightPillarTask;
            }
        } else {
            setDebugState("We're high enough.");
            // If a fireball is too close, run UP
            Optional<Entity> dragonFireball = mod.getEntityTracker().getClosestEntity(DragonFireball.class);
            if (dragonFireball.isPresent() && dragonFireball.get().closerThan(mod.getPlayer(), DRAGON_FIREBALL_TOO_CLOSE_RANGE) && LookHelper.cleanLineOfSight(mod.getPlayer(), dragonFireball.get().position(), DRAGON_FIREBALL_TOO_CLOSE_RANGE)) {
                _pillarUpFurther = new GetToYTask(mod.getPlayer().getBlockY() + 5);
                Debug.logMessage("HOLDUP");
                return _pillarUpFurther;
            }
            if (mod.getEntityTracker().entityFound(EndCrystal.class)) {
                return new DoToClosestEntityTask(
                        (toDestroy) -> {
                            if (toDestroy.closerThan(mod.getPlayer(), 7) && CrystalBlast.safeToSwing(CrystalStep.blastDamage(mod, mod.getPlayer(), toDestroy))) {
                                mod.getControllerExtras().attack(toDestroy);
                            }
                            if (mod.getPlayer().blockPosition().getY() < minHeight) {
                                return _heightPillarTask;
                            } else {
                                if (mod.getEntityTracker().getClosestEntity(EnderDragon.class).isPresent() &&
                                        !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                                    LookHelper.lookAt(mod, mod.getEntityTracker().getClosestEntity(EnderDragon.class).get().getEyePosition());
                                }
                                return null;
                            }
                        },
                        EndCrystal.class
                );
            }
            if (mod.getEntityTracker().getClosestEntity(EnderDragon.class).isPresent() &&
                    !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                LookHelper.lookAt(mod, mod.getEntityTracker().getClosestEntity(EnderDragon.class).get().getEyePosition());
            }
            return null;
        }
        if (_pillarSpot == null) {
            _pillarSpot = pickPillarSpot(mod);
        }
        if (!WorldHelper.inRangeXZ(mod.getPlayer(), _pillarSpot, PILLAR_SPOT_SLACK) && mod.getPlayer().position().y() < minHeight && !_hasPillar) {
            if (mod.getEntityTracker().entityFound(entity ->
                    mod.getPlayer().position().closerThan(entity.position(), 4), AreaEffectCloud.class)) {
                if (mod.getEntityTracker().getClosestEntity(EnderDragon.class).isPresent() &&
                        !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                    LookHelper.lookAt(mod, mod.getEntityTracker().getClosestEntity(EnderDragon.class).get().getEyePosition());
                }
                return null;
            }
            setDebugState("Moving to the pillar spot", "Getting to the pillar spot");
            return new GetToXZTask(_pillarSpot.getX(), _pillarSpot.getZ());
        }
        // We're far enough, pillar up!
        if (!_hasPillar) {
            _hasPillar = true;
        }
        BlockPos pillarGoal = new BlockPos(_pillarSpot.getX(), minHeight, _pillarSpot.getZ());
        if (_heightPillarTask == null || !pillarGoal.equals(_pillarGoal)) {
            _pillarGoal = pillarGoal;
            _heightPillarTask = new GetToBlockTask(pillarGoal);
        }
        return _heightPillarTask;
    }

    // no pearl to get down with: stand on the ground ring around the fountain (8 to 12, the bed fight's 13.5 to 16 outside the
    // wings) and let the bed task walk up to its stand once the perched dragon's yaw is frozen. no pillar, no hunting, an enderman is not worth leaving the fight for
    private Task groundTick(AltoClef mod) {
        double px = mod.getPlayer().getX();
        double pz = mod.getPlayer().getZ();
        double fx = _targetToPearl.getX() + 0.5;
        double fz = _targetToPearl.getZ() + 0.5;

        // out of the breath first: the cloud under us or a fireball coming at us. the way out is kept until we are there, the
        // spot is worked out from where we stood when it started, not again every block of the walk
        Optional<Entity> cloud = mod.getEntityTracker().getClosestEntity(AreaEffectCloud.class);
        Optional<Entity> fireball = mod.getEntityTracker().getClosestEntity(DragonFireball.class);
        if (_dodgeSpot != null && ((cloud.isEmpty() && fireball.isEmpty())
                || WorldHelper.inRangeXZ(mod.getPlayer(), new BlockPos((int) Math.round(_dodgeSpot.x()), 0, (int) Math.round(_dodgeSpot.z())), 2))) {
            _dodgeSpot = null;
            _dodgeTask = null;
        }
        if (_dodgeSpot == null) {
            if (cloud.isPresent() && cloud.get() instanceof AreaEffectCloud c) {
                _dodgeSpot = DragonWaitRules.clearOfCloud(px, pz, c.getX(), c.getZ(), c.getRadius());
            }
            if (_dodgeSpot == null && fireball.isPresent()) {
                Vec3 v = fireball.get().getDeltaMovement();
                _dodgeSpot = DragonWaitRules.dodgeFireball(px, pz, fireball.get().getX(), fireball.get().getZ(), v.x, v.z);
            }
            if (_dodgeSpot != null) {
                _dodgeTask = new GetToXZTask((int) Math.round(_dodgeSpot.x()), (int) Math.round(_dodgeSpot.z()));
            }
        }
        if (_dodgeTask != null) {
            setDebugState("Stepping out of the dragon's breath", "Dodging the breath");
            return _dodgeTask;
        }

        DragonWaitRules.Spot spot = _groundOnly ? DragonWaitRules.bedSpot(px, pz, fx, fz) : DragonWaitRules.groundSpot(px, pz, fx, fz);
        boolean settled = _groundOnly ? DragonWaitRules.inBedRing(px, pz, fx, fz) : DragonWaitRules.inGroundRing(px, pz, fx, fz);
        // walking back to the ring through a cloud that is still there is how we would stand in it again
        boolean cloudAtSpot = cloud.isPresent() && cloud.get() instanceof AreaEffectCloud c
                && DragonWaitRules.clearOfCloud(spot.x(), spot.z(), c.getX(), c.getZ(), c.getRadius()) != null;
        if (!settled && !cloudAtSpot) {
            setDebugState("Going to the ground wait spot", "Waiting for the dragon");
            return new GetToXZTask((int) Math.round(spot.x()), (int) Math.round(spot.z()));
        }
        setDebugState("No pearl, waiting for the dragon on the ground", "Waiting for the dragon");
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        if (dragon.isPresent() && !mod.getClientBaritone().getPathingBehavior().isPathing()) {
            LookHelper.lookAt(mod, dragon.get().getEyePosition());
        }
        return null;
    }

    // XZ_RADIUS out from the fountain on the side we are already on (east, where the arrival platform is, when we are in the middle)
    private BlockPos pickPillarSpot(AltoClef mod) {
        double dx = mod.getPlayer().getX() - _targetToPearl.getX();
        double dz = mod.getPlayer().getZ() - _targetToPearl.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new BlockPos((int) Math.round(_targetToPearl.getX() + dx / len * XZ_RADIUS), 0,
                (int) Math.round(_targetToPearl.getZ() + dz / len * XZ_RADIUS));
    }

    private boolean isFireballDangerous(AltoClef mod, Optional<Entity> fireball) {
        if (!fireball.isPresent())
            return false;

        boolean fireballTooClose = fireball.get().closerThan(mod.getPlayer(), DRAGON_FIREBALL_TOO_CLOSE_RANGE);
        boolean fireballInSight = LookHelper.cleanLineOfSight(mod.getPlayer(), fireball.get().position(), DRAGON_FIREBALL_TOO_CLOSE_RANGE);

        return fireballTooClose && fireballInSight;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof WaitForDragonAndPearlTask task && task._groundOnly == _groundOnly;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _dragonIsPerching
                && ((_throwPearlTask == null || (_throwPearlTask.isActive() && _throwPearlTask.isFinished(mod)))
                || (_targetToPearl != null && WorldHelper.inRangeXZ(mod.getPlayer(), _targetToPearl, CLOSE_ENOUGH_DISTANCE)));
    }

    @Override
    protected String toHudString() {
        return "Waiting for the dragon to perch";
    }

    @Override
    protected String toDebugString() {
        return "Waiting for Dragon Perch + Pearling";
    }
}