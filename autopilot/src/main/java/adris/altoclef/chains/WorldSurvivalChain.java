package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InputControls;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasks.movement.StepIntoPortalTask;
import adris.altoclef.tasks.movement.StepOffTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ArrivalRules;
import adris.altoclef.util.helpers.BreathDodge;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.DunkRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.EndermanWater;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Dimension;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame _wasInLavaTimer = new TimerGame(1);
    // the lava escape keeps the wheel after the first dry tick until we stand clear of the lava (EscapeFromLavaTask.clearOfLava),
    // ten game seconds after the last lava at most, so an escape that finds no safe ground still lets go
    static final int ESCAPE_HOLD_TICKS = 200;
    private long _lastLavaTick = Long.MIN_VALUE / 2;
    private boolean _wasAvoidingDrowning;
    private TimerGame _portalStuckTimer = new TimerGame(5);

    // the first seconds after a dimension change (see ArrivalRules): the level we last saw, when it changed, and whether the
    // arrival line went out. a step back into the portal latches so it can wait there for the teleport
    private net.minecraft.world.level.Level _arrivalWorld;
    private Dimension _arrivalDim;
    private int _sinceArrival;
    private boolean _arrivalLogged;
    private boolean _stepBackLatched;
    private long _lastStepBackMs;
    // ticks since the step back started, whether we ever stood in a portal block since, the cell it locked onto (a new cell
    // would be a new task and reset its wait), and whether it quit so it doesn't relatch while we are still in lava
    private int _sinceStepBack;
    private boolean _stepBackInside;
    private BlockPos _stepBackPortal;
    private boolean _stepBackGaveUp;
    // step backs per portal, so a portal that keeps dropping us by lava can't lap us between dimensions forever
    private BlockPos _lastStepBackPortal;
    private int _stepBackCount;

    // our name on the sneak key, so letting go never lifts the shield's hold and the fix chain knows to leave it be
    static final Object MAGMA_SNEAK = new Object();
    private InputControls _magmaKeys;

    private BlockPos _extinguishWaterPosition;
    // the block and face the dunk clicked, the pickup clicks the same ones
    private BlockPos _extinguishHit;
    private Direction _extinguishFace = Direction.UP;
    private int _dunkAimTicks;
    private boolean _dunkGaveUp;
    // where the dunk gave up and whether we have been off the ground since, see DunkRules.retry
    private BlockPos _dunkGaveUpAt;
    private boolean _dunkLeftGround;
    private boolean _trackingWater;
    // ticks spent fetching the dunk water back, the End gives up on it fast and everywhere else on a slow one
    private int _pickupTicks;

    // our own tracker (FoodChain has one too, each is cheap and caches per tick). one task per kind of dodge, dropped once
    // out of the cloud: the run away goal freezes on the first cloud it saw
    private final DragonBreathTracker _breath = new DragonBreathTracker();
    private Task _breathTask;
    private BreathDodge.Move _breathMove = BreathDodge.Move.NONE;
    private BreathDodge.Move _breathEscalated = BreathDodge.Move.NONE;
    private int _breathClearTicks;
    private Vec3 _breathRunFrom;
    private int _breathRunTicks;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority(AltoClef mod) {
        baritone.pathing.movement.MovementHelper.IN_LAVA.set(mod.getPlayer() != null && mod.getPlayer().isInLava());
        if (!AltoClef.inGame()) {
            letGoOfMagmaSneak();
            return Float.NEGATIVE_INFINITY;
        }

        // Drowning
        handleDrowning(mod);
        // (the buddy's fork: low on air in water under a solid block, ice over a frozen ocean: swimming up only bumps the
        // head on it. the block is broken instead. attempts 194 and 197 drowned under packed ice, 4 Nov 2026)
        Task upThrough = blockOverhead(mod);
        if (upThrough != null) {
            setTask(upThrough);
            return 100;
        }
        // (and with water over the head, to the nearest cell where the head is in air: the mob defense stood idle behind
        // its shield at y -10 in a cave's water and the buddy drowned with no block over its head, attempt 213)
        Task toAir = toAir(mod);
        if (toAir != null) {
            setTask(toAir);
            return 100;
        }

        // magma only burns what is not sneaking, so a bot stuck standing on it keeps its shift down
        handleMagmaSneak(mod);

        trackArrival(mod);
        // before anything returns: the arrival line must go out even when a lava escape takes the wheel
        boolean holding = arrivalHold(mod);

        // up here so the lava return can't skip it, burning in a fire on the way out of lava is still burning in a fire
        breakFireWeTouch(mod);

        // Lava Escape
        if (mod.getPlayer().isInLava()) {
            _lastLavaTick = mod.getWorld().getGameTime();
        }
        boolean lava = isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava();
        // a portal that dropped us by lava is the safest way out, the other side is home
        Task stepBack = portalStepBack(mod, lava);
        if (stepBack != null) {
            setTask(stepBack);
            return 100;
        }
        if (lava) {
            setTask(new EscapeFromLavaTask());
            return 100;
        }
        if (_mainTask instanceof EscapeFromLavaTask escape && isCurrentlyRunning(mod)
                && holdEscape(escape.clearOfLava(mod), mod.getWorld().getGameTime() - _lastLavaTick)) {
            return 100;
        }

        // Sand or gravel landed on us. it is 1 damage every half second for as long as our eyes are in it, so it goes
        // before anything polite. the task knows to dig rather than step aside when the block is the one we stand in
        // not canBreak: that one says no for anything ever marked unreachable, and being unreachable is not a thing for
        // the block our head is in. only the hard nos (bedrock-hard, the never-break list) stop this
        BlockPos buried = WorldHelper.buriedInFallenBlock(mod);
        if (buried != null && mod.getWorld().getBlockState(buried).getDestroySpeed(mod.getWorld(), buried) >= 0
                && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(buried)) {
            setTask(new DestroyBlockTask(buried));
            return 100;
        }

        // standing in dragon breath burns through whatever else holds the wheel (below the lava and the falls, above the pickup
        // and the mob chains). the breath ticks damage every half second, a long walk to a bucket is not worth it
        Task dodge = breathDodge(mod);
        if (dodge != null) {
            setTask(dodge);
            return 85;
        }

        // no chunks yet after a dimension change means the planner sees air where the lava is, so the user task waits until they
        // land. under the fights (mob defense) and the lava above, over everything the user task does
        if (holding) {
            setTask(new IdleTask());
            return 70;
        }

        // an angry enderman at the bottom of the health bar: water at our feet (see EndermanWater)
        double angryEnderman = nearestAngryEnderman(mod);
        boolean endermanDunk = EndermanWater.dunkNow(mod.getPlayer().getHealth(), angryEnderman,
                mod.getItemStorage().hasItem(Items.WATER_BUCKET), WorldHelper.waterEvaporates(mod.getWorld()), mod.getPlayer().isInWater());

        // Extinguish with water
        // (endBurn would hand the dunk a fresh aim budget every tick, and it is the budget that stops a look that never lands)
        if (!mod.getPlayer().isOnFire() && !endermanDunk) {
            endBurn(mod);
        }
        if (Baritone.settings().altoExtinguishSelfWithWater.value) {
            if (!(_mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !adris.altoclef.util.helpers.FireRes.has(mod.getPlayer()) && !WorldHelper.waterEvaporates(mod.getWorld())) {
                // Extinguish ourselves
                trackWaterWhileBurning(mod);
                float dunk = dunkOrWalk(mod, true);
                if (dunk != Float.NEGATIVE_INFINITY) {
                    return dunk;
                }
            } else if (EndermanWater.keepWater(angryEnderman) && _extinguishWaterPosition != null) {
                // the water stays up while an angry enderman is about, it is the thing it is afraid of. the pickup clock
                // starts when it leaves, or a long enderman fight used up the whole pickup window before it ever opened
                _pickupTicks = 0;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER) && !pickupGaveUp(mod)) {
                // Pick up the water
                InteractWithBlockTask pickup = new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), _extinguishFace, _extinguishHit, true);
                // in the End the water is only worth a click from here, never a walk. unless it was the last bucket: that
                // one is the fall bucket for every fling the dragon has left in it
                setTask(WorldHelper.getCurrentDimension() == Dimension.END && !lastBucket(mod) ? pickup.stayPut() : pickup);
                return 60;
            } else {
                _extinguishWaterPosition = null;
                _pickupTicks = 0;
            }
        }

        if (endermanDunk) {
            float dunk = dunkOrWalk(mod, false);
            if (dunk != Float.NEGATIVE_INFINITY) {
                return dunk;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal(mod)) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            _portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (_portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        return Float.NEGATIVE_INFINITY;
    }

    // pure so it can be tested: on magma and nobody walking us anywhere. onGround has no say: sneaking on a ladder cancels the
    // downward motion, so resting right on the magma there is no collision and it reads false. letting go for that means the
    // next tick lands with shift up, and that's the burn. and shift in the air over magma costs nothing
    static boolean magmaSneakNow(boolean onMagma, boolean pathing) {
        return onMagma && !pathing;
    }

    private void handleMagmaSneak(AltoClef mod) {
        var pathing = mod.getClientBaritone().getPathingBehavior();
        boolean onMagma = mod.getWorld().getBlockState(mod.getPlayer().getOnPos()).is(Blocks.MAGMA_BLOCK);
        // (a search still thinking is not a walk: we are standing here until the path actually starts)
        if (magmaSneakNow(onMagma, pathing.isPathing())) {
            _magmaKeys = mod.getInputControls();
            // pinned: mining and placing tasks let go of sneak by hand every tick they run, and one tick up is a burn
            _magmaKeys.pin(Input.SNEAK, MAGMA_SNEAK);
        } else {
            letGoOfMagmaSneak();
        }
    }

    private void letGoOfMagmaSneak() {
        if (_magmaKeys != null) {
            _magmaKeys.unpin(Input.SNEAK, MAGMA_SNEAK);
            _magmaKeys = null;
        }
    }

    // distance to the closest enderman that is angry at us, infinity for none
    private static double nearestAngryEnderman(AltoClef mod) {
        double nearest = Double.POSITIVE_INFINITY;
        try {
            for (Enderman man : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
                if (man.isAlive() && EntityHelper.isAngryAtPlayer(mod, man)) {
                    nearest = Math.min(nearest, mod.getPlayer().distanceTo(man));
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // one tick of stale endermen is fine
        }
        return nearest;
    }

    // the burn is over (or never was): next one gets a fresh aim budget
    private void endBurn(AltoClef mod) {
        _dunkAimTicks = 0;
        _dunkGaveUp = false;
        _dunkGaveUpAt = null;
        _dunkLeftGround = false;
        if (_trackingWater) {
            _trackingWater = false;
            mod.getBlockTracker().stopTracking(Blocks.WATER);
        }
    }

    // the far-water check needs the tracker to know about water before any task is walking there to ask for it
    private void trackWaterWhileBurning(AltoClef mod) {
        if (!_trackingWater) {
            _trackingWater = true;
            mod.getBlockTracker().trackBlock(Blocks.WATER);
        }
    }

    // dunk where we stand if a face puts the water in our cell, and hold still until the look lands (a walk's pathing
    // rewrites the camera every tick, so the old fall-through to a pond never got to click). only when there is no face,
    // or the aim gave up, do we walk, and then only to water that is close. NEGATIVE_INFINITY means nothing to do here
    private float dunkOrWalk(AltoClef mod, boolean walk) {
        BlockPos feet = mod.getPlayer().blockPosition();
        // a click mid jump lands in the cell we were in, not the one we are in by the time it goes through
        boolean airborne = !mod.getPlayer().onGround() && !mod.getPlayer().onClimbable() && !mod.getPlayer().isInWater();
        if (_dunkGaveUp) {
            _dunkLeftGround |= airborne;
            if (DunkRules.retry(_dunkGaveUpAt, feet, _dunkLeftGround, airborne)) {
                _dunkGaveUp = false;
                _dunkAimTicks = 0;
            }
        }
        if (airborne && !_dunkGaveUp) {
            return Float.NEGATIVE_INFINITY;
        }
        if (mod.getItemStorage().hasItem(Items.WATER_BUCKET) && !_dunkGaveUp && WorldHelper.canPlace(mod, feet)) {
            for (DunkRules.Face face : DunkRules.candidates(mod.getWorld(), feet)) {
                Optional<Rotation> reach = LookHelper.getReach(face.hit(), face.face());
                if (reach.isEmpty()) {
                    continue;
                }
                if (!DunkRules.keepAiming(_dunkAimTicks)) {
                    _dunkGaveUp = true;
                    _dunkGaveUpAt = feet;
                    _dunkLeftGround = false;
                    break;
                }
                _dunkAimTicks++;
                setTask(null);
                mod.getClientBaritone().getPathingBehavior().cancelEverything();
                mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                if (aimedAt(mod, face) && mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                    _extinguishWaterPosition = face.lands();
                    _pickupTicks = 0;
                    _extinguishHit = face.hit();
                    _extinguishFace = face.face();
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                }
                return 90;
            }
        }
        Optional<BlockPos> water = mod.getBlockTracker().getNearestTracking(Blocks.WATER);
        // below the mob defense run (80) and fight (65) so a pond walk never keeps them off the wheel
        if (walk && water.isPresent() && DunkRules.walkWorthIt(mod.getPlayer().position().distanceTo(WorldHelper.toVec3d(water.get())))) {
            // the exact block we measured, not whichever one the closest-block task likes better
            setTask(new GetToBlockTask(water.get()));
            return 60;
        }
        return Float.NEGATIVE_INFINITY;
    }

    // the End has no water to refill from, so there the bucket only comes back when the water is a step away
    private boolean pickupGaveUp(AltoClef mod) {
        _pickupTicks++;
        double distance = mod.getPlayer().position().distanceTo(WorldHelper.toVec3d(_extinguishWaterPosition));
        return BreathDodge.pickupExpired(WorldHelper.getCurrentDimension() == Dimension.END, lastBucket(mod), _pickupTicks / 20.0, distance);
    }

    // the water out there is the only water we have
    private static boolean lastBucket(AltoClef mod) {
        return !mod.getItemStorage().hasItem(Items.WATER_BUCKET);
    }

    // null when we are not in a cloud (or in the air, MLG owns that), else the task that gets us out of it
    private Task breathDodge(AltoClef mod) {
        _breath.updateBreath(mod);
        boolean inCloud = false;
        for (BlockPos in : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_breath.isTouchingDragonBreath(in)) {
                inCloud = true;
                break;
            }
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        // the fire dunk (90) comes first when we can do it, a dodge would just hide it behind the 85
        if (mod.getPlayer().isOnFire() && mod.getItemStorage().hasItem(Items.WATER_BUCKET) && !_dunkGaveUp
                && !adris.altoclef.util.helpers.FireRes.has(mod.getPlayer())) {
            return null;
        }
        BreathDodge.Move move = BreathDodge.choose(inCloud, mod.getPlayer().onGround(), floorAround(mod, feet), spareWater(mod));
        move = escalateStuckRun(mod, feet, move);
        _breathClearTicks = move == BreathDodge.Move.NONE ? _breathClearTicks + 1 : 0;
        move = BreathDodge.follow(move, _breathMove, _breath.isNearDragonBreath(feet, BreathDodge.CLEAR_BLOCKS), _breathClearTicks,
                mod.getPlayer().onGround(), mod.getPlayer().getDeltaMovement().y);
        // a drop is committed: sliding half a block along the pillar top would otherwise see the pillar as floor and swap
        // it for a run before we ever fall
        if (_breathMove == BreathDodge.Move.DROP && inCloud && mod.getPlayer().onGround()) {
            move = BreathDodge.Move.DROP;
        }
        if (move != _breathMove) {
            Debug.logMessage(move == BreathDodge.Move.NONE ? "dragon breath: out of the cloud at " + feet.toShortString()
                    : "dragon breath: " + move + " at " + feet.toShortString());
            _breathTask = null;
            _breathMove = move;
        }
        if (move == BreathDodge.Move.NONE) {
            return null;
        }
        // a finished run (a newer cloud over its goal) gets a fresh goal
        if (_breathTask == null || _breathTask.isFinished(mod)) {
            _breathTask = switch (move) {
                case DROP -> new StepOffTask();
                case DIG -> new DestroyBlockTask(feet.below());
                default -> _breath.getRunAwayTask();
            };
        }
        return _breathTask;
    }

    // a run that sits in the cloud for 2 s without getting a block away switches to a drop or a dig, and stays switched
    // until the cloud is gone (the floor check would flip it straight back to a run)
    private BreathDodge.Move escalateStuckRun(AltoClef mod, BlockPos feet, BreathDodge.Move move) {
        if (move == BreathDodge.Move.NONE) {
            _breathEscalated = BreathDodge.Move.NONE;
            _breathRunTicks = 0;
            return move;
        }
        BlockPos below = feet.below();
        float hardness = mod.getWorld().getBlockState(below).getDestroySpeed(mod.getWorld(), below);
        boolean hard = hardness >= BreathDodge.HARD_BLOCK_HARDNESS || hardness < 0;
        // the block under us can change while a dig is latched, obsidian is not something we dig our way out of
        if (_breathEscalated == BreathDodge.Move.DIG && hard) {
            _breathEscalated = BreathDodge.Move.NONE;
        }
        if (_breathEscalated != BreathDodge.Move.NONE) {
            return _breathEscalated;
        }
        if (move == BreathDodge.Move.RUN) {
            // exact position, block coords would reset the clock every time we wobble over a block line
            Vec3 at = mod.getPlayer().position();
            if (_breathRunFrom == null || _breathRunFrom.distanceToSqr(at) >= 1) {
                _breathRunFrom = at;
                _breathRunTicks = 0;
            } else {
                _breathRunTicks++;
            }
        }
        BreathDodge.Move out = BreathDodge.escalate(move, _breathRunTicks >= BreathDodge.STUCK_RUN_TICKS, spareWater(mod),
                mod.getItemStorage().hasItem(Items.WATER_BUCKET), hard);
        if (out != move && out != BreathDodge.Move.RUN) {
            _breathEscalated = out;
        }
        return out;
    }

    // a breath drop never bets the last water bucket (see BreathDodge.spareWater)
    private static boolean spareWater(AltoClef mod) {
        return BreathDodge.spareWater(mod.getItemStorage().getItemCount(Items.WATER_BUCKET));
    }

    // somewhere to run to: floor under any cell in the 5x5 around us. a pillar top has none
    private static boolean floorAround(AltoClef mod, BlockPos feet) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if ((dx != 0 || dz != 0) && WorldHelper.isSolid(mod, feet.offset(dx, -1, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    // the crosshair is on the block and the face we picked, anything else puts the water somewhere else
    private static boolean aimedAt(AltoClef mod, DunkRules.Face face) {
        HitResult hit = mod.getClientBaritone().getPlayerContext().objectMouseOver();
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                && BucketPour.aimedRight(b.getBlockPos(), b.getDirection(), face.hit(), face.face());
    }

    // six seconds of air of fifteen
    private static final int LOW_AIR = 120;

    private Task _upThrough;
    private Task _toAir;
    private BlockPos _toAirCell;
    // (the buddy's fork: a cell the walk to air got no closer to in TO_AIR_STUCK ticks is off the list until the air is back.
    // the pick took the air five blocks straight up a column of falling water, which no swim gets up, and the buddy drowned
    // at the bottom with the jump held, attempt 225, 4 Nov 2026)
    private static final int TO_AIR_STUCK = 30;
    private final java.util.Set<BlockPos> _toAirBanned = new java.util.HashSet<>();
    private long _toAirSince;
    private double _toAirBest;

    // in water, low on air: the nearest cell up to 8 above and 6 aside where the head is in air, or null
    private Task toAir(AltoClef mod) {
        var player = mod.getPlayer();
        if (!player.isInWater() || player.getAirSupply() >= LOW_AIR) {
            _toAir = null;
            _toAirCell = null;
            _toAirBanned.clear();
            return null;
        }
        var level = mod.getWorld();
        BlockPos feet = player.blockPosition();
        long now = level.getGameTime();
        if (_toAirCell != null) {
            double d = player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(_toAirCell));
            if (d < _toAirBest - 0.5) {
                _toAirBest = d;
                _toAirSince = now;
            } else if (now - _toAirSince > TO_AIR_STUCK) {
                Debug.logInternal("to air: no closer to " + _toAirCell.toShortString() + " in " + TO_AIR_STUCK + " ticks, another cell");
                _toAirBanned.add(_toAirCell);
                _toAirCell = null;
                _toAir = null;
            }
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // (the buddy's fork: a boat on the water is a lid. one floated over the buddy after it got out, every air cell picked was
        // within a block of its feet and under the boat, and it drowned two blocks under the surface, attempt 233, 4 Nov 2026)
        java.util.List<net.minecraft.world.phys.AABB> lids = new java.util.ArrayList<>();
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.boat.AbstractBoat.class, player.getBoundingBox().inflate(8, 10, 8))) {
            lids.add(e.getBoundingBox().inflate(0.3, 0, 0.3));
        }
        for (int dy = 0; dy <= 8; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    double d = dx * dx + dz * dz + dy * dy * 0.5;
                    if (d >= bestD) continue;
                    BlockPos c = feet.offset(dx, dy, dz);
                    if (_toAirBanned.contains(c)) continue;
                    var at = level.getBlockState(c);
                    var head = level.getBlockState(c.above());
                    if (!at.getCollisionShape(level, c).isEmpty() || !head.getFluidState().isEmpty()
                            || !head.getCollisionShape(level, c.above()).isEmpty() || at.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                    if (dy > 0 && fallingWaterUnder(level, c, feet.getY())) continue;
                    if (underLid(lids, c, feet.getY())) continue;
                    best = c;
                    bestD = d;
                }
            }
        }
        if (best == null) return null;
        if (!best.equals(_toAirCell)) {
            _toAirCell = best;
            _toAir = new adris.altoclef.tasks.movement.GetToBlockTask(best);
            _toAirSince = now;
            _toAirBest = player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(best));
        }
        return _toAir;
    }

    // a boat over the column from the feet's height up to this cell's head
    private static boolean underLid(java.util.List<net.minecraft.world.phys.AABB> lids, BlockPos cell, int fromY) {
        if (lids.isEmpty()) return false;
        var column = new net.minecraft.world.phys.AABB(cell.getX(), Math.min(fromY, cell.getY()), cell.getZ(), cell.getX() + 1, cell.getY() + 2, cell.getZ() + 1);
        for (var lid : lids) if (lid.intersects(column)) return true;
        return false;
    }

    // falling water in the column under this cell, down to the feet's height: a waterfall pushes a swimmer down
    private static boolean fallingWaterUnder(net.minecraft.world.level.Level level, BlockPos cell, int fromY) {
        for (int y = cell.getY() - 1; y >= fromY; y--) {
            var fluid = level.getFluidState(new BlockPos(cell.getX(), y, cell.getZ()));
            if (fluid.is(net.minecraft.tags.FluidTags.WATER) && !fluid.isSource()
                    && fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return true;
        }
        return false;
    }

    // in water, low on air, and a solid block one or two cells over the feet: that block to break
    private Task blockOverhead(AltoClef mod) {
        var player = mod.getPlayer();
        if (!player.isInWater() || player.getAirSupply() >= LOW_AIR) {
            _upThrough = null;
            return null;
        }
        BlockPos feet = player.blockPosition();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos c = feet.above(dy);
            var state = mod.getWorld().getBlockState(c);
            if (!state.getFluidState().isEmpty()) continue;
            if (state.getCollisionShape(mod.getWorld(), c).isEmpty()) return null;
            if (state.getBlock() == Blocks.BEDROCK) return null;
            if (!(_upThrough instanceof DestroyBlockTask d) || !d.equals(new DestroyBlockTask(c))) {
                _upThrough = new DestroyBlockTask(c);
            }
            return _upThrough;
        }
        return null;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (Baritone.settings().altoAvoidDrowning.value) {
            // (the buddy's fork: with little air left a path does not hold it back. it stood still at y 31 in a cave's water
            // while baritone worked out a path, forty game seconds, and drowned in its armour, the practice runner, 2 Nov 2026,
            // attempt 65)
            boolean lowAir = mod.getPlayer().getAirSupply() < LOW_AIR;
            if (!mod.getClientBaritone().getPathingBehavior().isPathing() || lowAir) {
                if (mod.getPlayer().isInWater() && mod.getPlayer().getAirSupply() < mod.getPlayer().getMaxAirSupply()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    _wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (_wasAvoidingDrowning && !avoidedDrowning) {
            _wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    private void trackArrival(AltoClef mod) {
        Dimension dim = WorldHelper.getCurrentDimension();
        if (mod.getWorld() != _arrivalWorld || dim != _arrivalDim) {
            _arrivalWorld = mod.getWorld();
            _arrivalDim = dim;
            _sinceArrival = 0;
            _arrivalLogged = false;
            _stepBackLatched = false;
            _stepBackGaveUp = false;
            // a fresh arrival gets a fresh five seconds in a portal, the old clock was for the other side
            _portalStuckTimer.reset();
        } else if (_sinceArrival < Integer.MAX_VALUE) {
            _sinceArrival++;
        }
    }

    private Task portalStepBack(AltoClef mod, boolean inLava) {
        if (_arrivalDim != Dimension.NETHER || _stepBackGaveUp) {
            return null;
        }
        java.util.function.Predicate<BlockPos> isPortal = p -> mod.getWorld().getBlockState(p).is(Blocks.NETHER_PORTAL);
        BlockPos portal = _stepBackLatched && _stepBackPortal != null && isPortal.test(_stepBackPortal) ? _stepBackPortal
                : ArrivalRules.nearestPortal(mod.getPlayer().blockPosition(), isPortal);
        int since = _stepBackLatched ? _sinceStepBack : -1;
        if (!ArrivalRules.stepBack(true, _sinceArrival, inLava, since, portal != null)) {
            _stepBackLatched = false;
            return null;
        }
        if (!_stepBackLatched) {
            long now = System.currentTimeMillis();
            if (ArrivalRules.loopCapped(_lastStepBackPortal, _stepBackCount, portal)) {
                return null;
            }
            Debug.logInternal("arrival: lava next to the portal, stepping back in at " + portal.toShortString()
                    + (now - _lastStepBackMs < ArrivalRules.REPEAT_MS ? " (again, the last one was " + (now - _lastStepBackMs) / 1000 + " s ago)" : ""));
            _stepBackCount = _lastStepBackPortal != null && _lastStepBackPortal.distSqr(portal) <= ArrivalRules.SAME_PORTAL_RADIUS * ArrivalRules.SAME_PORTAL_RADIUS
                    ? _stepBackCount + 1 : 1;
            _lastStepBackPortal = portal;
            _lastStepBackMs = now;
            _sinceStepBack = 0;
            _stepBackInside = false;
        } else {
            _sinceStepBack++;
        }
        _stepBackInside |= WorldHelper.isStandingInPortal(mod);
        if (ArrivalRules.gaveUp(_sinceStepBack, _stepBackInside)) {
            // lava or a wall in the way: the lava escape knows more than a straight walk does
            Debug.logInternal("arrival: step back never reached the portal, handing it to the lava escape");
            _stepBackGaveUp = true;
            _stepBackLatched = false;
            return null;
        }
        _stepBackLatched = true;
        _stepBackPortal = portal;
        return new StepIntoPortalTask(portal);
    }

    // true while the ground around us has not arrived, once per arrival it also writes down what the box around us holds
    private boolean arrivalHold(AltoClef mod) {
        if (_arrivalLogged) {
            return false;
        }
        BlockPos at = mod.getPlayer().blockPosition();
        int r = ArrivalRules.LOOK_RADIUS;
        boolean loaded = true;
        for (int dx = -r; dx <= r && loaded; dx += 2 * r) {
            for (int dz = -r; dz <= r && loaded; dz += 2 * r) {
                loaded = mod.getWorld().getChunkSource().hasChunk((at.getX() + dx) >> 4, (at.getZ() + dz) >> 4);
            }
        }
        if (ArrivalRules.hold(_sinceArrival, loaded)) {
            return true;
        }
        _arrivalLogged = true;
        if (_arrivalDim == Dimension.NETHER) {
            int lava = 0;
            int portal = 0;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 1, r))) {
                Block b = mod.getWorld().getBlockState(p).getBlock();
                if (b == Blocks.LAVA) lava++;
                else if (b == Blocks.NETHER_PORTAL) portal++;
            }
            Debug.logInternal("arrival: nether at " + at.toShortString() + ", 5x3x5 has lava " + lava + " portal " + portal
                    + arrivalState(mod) + ", build blocks " + StorageHelper.getBuildingMaterialCount(mod)
                    + (loaded ? "" : " (chunks never all showed up)"));
        } else if (_arrivalDim == Dimension.END) {
            Debug.logInternal("arrival: end at " + at.toShortString() + arrivalState(mod) + ", build blocks " + StorageHelper.getBuildingMaterialCount(mod)
                    + (loaded ? "" : " (chunks never all showed up)"));
        }
        return false;
    }

    static boolean holdEscape(boolean clearOfLava, long ticksSinceLava) {
        return !clearOfLava && ticksSinceLava >= 0 && ticksSinceLava < ESCAPE_HOLD_TICKS;
    }

    // what we walked in with: water at the feet, something in the hand, keys still down from the other side. log only
    private static String arrivalState(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        BlockPos eyes = BlockPos.containing(mod.getPlayer().getEyePosition());
        StringBuilder keys = new StringBuilder();
        StringBuilder forced = new StringBuilder();
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT, Input.JUMP, Input.SNEAK}) {
            if (mod.getInputControls().isHeldDown(input)) {
                keys.append(keys.isEmpty() ? "" : " ").append(input.name().toLowerCase());
            }
            if (mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(input)) {
                forced.append(forced.isEmpty() ? "" : " ").append(input.name().toLowerCase());
            }
        }
        return ", fluid feet " + fluidName(mod.getWorld().getFluidState(feet)) + " eyes " + fluidName(mod.getWorld().getFluidState(eyes))
                + ", holding " + ItemHelper.stripItemName(mod.getPlayer().getMainHandItem().getItem())
                + ", keys [" + keys + "] baritone [" + forced + "]";
    }

    private static String fluidName(FluidState fluid) {
        if (fluid.isEmpty()) return "none";
        return fluid.is(FluidTags.WATER) ? "water" : fluid.is(FluidTags.LAVA) ? "lava" : "other";
    }

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !adris.altoclef.util.helpers.FireRes.has(mod.getPlayer())) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }

    // no task and no wheel: a task at the top of the pile stops the path and the fight while it waits on a click that a
    // raised shield can hold off forever, and the fire keeps biting the whole time. one fire a tick, and only the one the
    // crosshair is on, broken by the face the look came in by (the face toward the eye): a dig on a block the look never
    // reached, by a face the eye can't see, or several digs in one tick is what the anti-cheat's break checks flag.
    // standing still we turn to the nearest one; a walking path keeps the head (movement goes where the yaw points), and
    // the escape or the dunk gets us out of the fire instead
    private void breakFireWeTouch(AltoClef mod) {
        if (!mod.getPlayer().isOnFire() || adris.altoclef.util.helpers.FireRes.has(mod.getPlayer())) return;
        MultiPlayerGameMode controller = mod.getController();
        if (controller == null) return;
        List<BlockPos> fires = allMatches(WorldHelper.getBlocksTouchingPlayer(mod),
                pos -> mod.getWorld().getBlockState(pos).getBlock() instanceof BaseFireBlock);
        if (fires.isEmpty()) return;
        HitResult hit = mod.getClientBaritone().getPlayerContext().objectMouseOver();
        if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && fires.contains(b.getBlockPos())) {
            // startDestroyBlock aborts the old dig to the server but leaves isDestroying up on our side, so stop it properly.
            // a dig that loses its progress starts again, it is a cheap price for not being on fire
            controller.stopDestroyBlock();
            controller.startDestroyBlock(b.getBlockPos(), b.getDirection());
            // (the buddy's fork, upstream bug, PR #6: a dig goes to the server with its punch, or Vulcan sees a dig nobody swung)
            baritone.behavior.LegacyCombat.swing(mod.getPlayer(), net.minecraft.world.InteractionHand.MAIN_HAND);
            Debug.logInternal("fire: broke 1 of " + fires.size() + " touching, using=" + mod.getPlayer().isUsingItem());
            return;
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) return;
        Vec3 eye = mod.getPlayer().getEyePosition();
        fires.sort(java.util.Comparator.comparingDouble(pos -> pos.distToCenterSqr(eye)));
        for (BlockPos fire : fires) {
            Optional<Rotation> reach = LookHelper.getReach(fire);
            if (reach.isPresent()) {
                mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                return;
            }
        }
    }

    // scanRegion may hand back the same mutable pos every step, so the hits are copied
    static List<BlockPos> allMatches(Iterable<BlockPos> positions, java.util.function.Predicate<BlockPos> test) {
        List<BlockPos> hits = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (test.test(pos)) hits.add(pos.immutable());
        }
        return hits;
    }

    private boolean isStuckInNetherPortal(AltoClef mod) {
        if (!WorldHelper.isInNetherPortal(mod) || _stepBackLatched) return false;
        // nothing running means nobody asked us to be anywhere, so a player standing in a portal gets left alone
        Task userTask = mod.getUserTaskChain().getCurrentTask();
        return userTask != null && !userTask.thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public String getHudName() {
        return "Staying safe";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop(AltoClef mod) {
        letGoOfMagmaSneak();
        super.onStop(mod);
    }
}
