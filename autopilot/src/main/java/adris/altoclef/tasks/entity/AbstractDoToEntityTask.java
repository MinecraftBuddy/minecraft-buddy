package adris.altoclef.tasks.entity;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.CritTiming;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.tasks.PearlHuntTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.ApproachRules;
import adris.altoclef.util.helpers.EntityHelper;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import adris.altoclef.util.helpers.BackOffRules;
import adris.altoclef.util.helpers.EntityBlockerRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StandCap;
import adris.altoclef.util.helpers.WaitCap;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.MobReachability;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import baritone.behavior.CombatAim;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Interacts with an entity while maintaining distance.
 * <p>
 * The interaction is abstract.
 */
public abstract class AbstractDoToEntityTask extends Task implements ITaskRequiresGrounded {
    protected final MovementProgressChecker _progress = new MovementProgressChecker();
    private final double _maintainDistance;
    private final double _combatGuardLowerRange;
    private final double _combatGuardLowerFieldRadius;

    // ten seconds to chew through one block. a dig that takes longer than this is not going to get better
    private static final int BLOCKER_TICKS = 200;

    // kept while the target and distance stay the same, so its own stuck handling gets to accumulate across ticks
    private GetToEntityTask _approach;
    private Entity _approachEntity;
    private double _approachDistance;
    // the block we are digging out of the way (fence, wall, whatever won the raycast), and how many digs this target has cost
    private DestroyBlockTask _blocker;
    private int _blockerTicks;
    private Entity _blockerTarget;
    private int _blockerDigs;
    private GoalRunAway _backOffGoal;
    // the approach child was in the tree last tick (it is walking, a stop now would cut the path), see ApproachRules
    private boolean _approachLive;
    // the wander we handed back with nothing to target was the child last tick, see ApproachRules.wanderWhenNoTarget
    private boolean _wanderLive;
    // ticks in a row spent standing in reach with the swing gated, see ApproachRules.WAIT_TICKS. the kill tasks use _standCap
    // instead, which counts ticks since the last swing that really went out whatever the plan flickered through
    private int _waitTicks;
    private final StandCap _standCap = new StandCap();
    // the waits StandCap does not count (an angry enderman out of reach, a swing gated in reach), see WaitCap
    private final WaitCap _waitCap = new WaitCap();
    private Entity _waitCapEntity;
    private Entity _waitEntity;
    private long _swingsSeen;
    // the back-off we started has to be getting somewhere, and a stuck one is not started again for a while
    private final BackOffRules.Watch _backOffWatch = new BackOffRules.Watch();
    private long _backOffMutedUntil = Long.MIN_VALUE;

    public AbstractDoToEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        _maintainDistance = maintainDistance;
        _combatGuardLowerRange = combatGuardLowerRange;
        _combatGuardLowerFieldRadius = combatGuardLowerFieldRadius;
    }

    public AbstractDoToEntityTask(double maintainDistance) {
        this(maintainDistance, 0, Double.POSITIVE_INFINITY);
    }

    public AbstractDoToEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        this(-1, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progress.reset();
        _approach = null;
        _approachLive = false;
        _wanderLive = false;
        _waitTicks = 0;
        _rimCell = null;
        _rimStepTicks = 0;
        _rimStandTicks = 0;
        _rimLive = false;
        _groundCell = null;
        _groundTicks = 0;
        _rimFailed.clear();
        _standCap.reset();
        _waitCap.reset();
        _waitCapEntity = null;
        _waitEntity = null;
        _swingsSeen = mod.getControllerExtras().swings();
        _backOffWatch.clear();
        _backOffMutedUntil = Long.MIN_VALUE;
        _blocker = null;
        _blockerTarget = null;
        _blockerDigs = 0;
        _backOffGoal = null;
        _aimStalled = false;
        _unaimedTicks = 0;
        _unaimedEntity = null;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        } // Kinda duct tape but it should be future proof ish
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progress.reset();
        }
        boolean wasWalking = _approachLive;
        _approachLive = false;
        boolean wasWandering = _wanderLive;
        _wanderLive = false;

        Optional<Entity> checkEntity = getEntityTarget(mod);
        if (checkEntity.isEmpty()) _blocker = null;

        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetForceField();
        }
        if (checkEntity.isPresent()) {
            Entity entity = checkEntity.get();

            double playerReach = Baritone.settings().altoEntityReachRange.value;

            // TODO: This is basically useless.
            EntityHitResult result = LookHelper.raycast(mod.getPlayer(), entity, playerReach);

            double sqDist = entity.distanceToSqr(mod.getPlayer());

            if (sqDist < _combatGuardLowerRange * _combatGuardLowerRange) {
                mod.getMobDefenseChain().setForceFieldRange(_combatGuardLowerFieldRadius);
            } else {
                mod.getMobDefenseChain().resetForceField();
            }

            // If we don't specify a maintain distance, default to within 1 block of our reach.
            double maintainDistance = _maintainDistance >= 0 ? _maintainDistance : playerReach - 1;

            long now = mod.getWorld().getGameTime();
            // a kill task with nowhere to step back to (a shaft, a low tunnel), or whose last step back went nowhere, stands
            // and hits: the run-away would only own the keys, and "too close" is not a problem we can fix there
            boolean backOffOk = !isKillTask() || (now >= _backOffMutedUntil && mod.getControllerExtras().roomToBackOff());
            // a kill task only minds being right on top of the mob, see ReachRules for how its distances nest
            double backOffLine = isKillTask() ? ReachRules.BACK_OFF_START : maintainDistance;
            boolean tooClose = backOffOk && sqDist < backOffLine * backOffLine;
            // the wide check (a drop anywhere close) is for stepping and the back-off, the swing timing keeps the knock line only
            boolean rimWide = isKillTask() && rimUnsafe(mod, entity);
            if (isKillTask()) {
                watchBackOff(mod, now);
                // a walk to ground is on: the back-off would grab the goal and walk us wherever it likes
                if (LavaShore.backOffAllowed(rimWide || _groundCell != null)) {
                    killBackOff(mod, entity, tooClose);
                } else {
                    dropBackOff(mod);
                }
            } else if (tooClose && !mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                //setDebugState("Maintaining distance");
                _backOffGoal = new GoalRunAway(maintainDistance, entity.blockPosition());
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_backOffGoal);
            }

            boolean rim = isKillTask() && rimBack(mod, entity);
            boolean ready = LavaShore.swingReady(!mod.getFoodChain().needsToEat() &&
                    !mod.getMLGBucketChain().isFallingOhNo(mod) && mod.getMLGBucketChain().doneMLG() &&
                    !mod.getMLGBucketChain().isChorusFruiting(),
                    // a sword just waits for the charge, no crits, so being knocked airborne is no reason to hold the swing
                    mod.getPlayer().onGround() || mod.getControllerExtras().swordInHand(), mod.getControllerExtras().critInFlight(), LavaShore.rimSwing(rim, rimWide, mod.getPlayer().hurtTime));
            // a child that was walking to some other mob is not the one to keep
            boolean inReach = inReach(mod, entity);
            boolean shot = result != null && result.getType() == HitResult.Type.ENTITY;
            if (isKillTask()) {
                _aimStalled = aimStalled(mod, entity, inReach && shot);
                shot = shot && !_aimStalled;
                countStandingTicks(mod, entity, StandCap.standing(inReach, shot, tooClose));
            }
            boolean gatesOpen = !mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod)
                    && mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting();
            ApproachRules.Policy policy = !isKillTask() ? ApproachRules.Policy.OTHER
                    : gatesOpen ? ApproachRules.Policy.KILL : ApproachRules.Policy.KILL_GATED;
            boolean comesToUs = isKillTask() && entity instanceof Enderman && EntityHelper.isAngryAtPlayer(mod, entity);
            // while we let it come the head is already on it (legs, CombatAim keeps us out of its eyes): aimStalled only aims in
            // reach, and the swing wants onTarget, so the turn used to start the tick it arrived and it got the first hit
            if (comesToUs && !inReach && sqDist < PRE_AIM_RANGE * PRE_AIM_RANGE) {
                mod.getControllerExtras().aimAt(entity, CombatAim.Priority.KILL_TASK);
            }
            if (isKillTask()) {
                logEndermanContact(mod, entity, comesToUs && inReach, now);
            }
            // a foe that is biting us in reach: a step cannot land under the knockback, the swing is the way out
            boolean underFire = LavaShore.underFire(mod.getControllerExtras().ticksSinceHurt(), inReach);
            ApproachRules.Plan plan = ApproachRules.plan(wasWalking && _approachEntity == entity, inReach, shot, ready,
                    mod.getClientBaritone().getPathingBehavior().isSafeToCancel(), tooClose,
                    isKillTask() ? ApproachRules.swingWaited(_standCap.waitedLong(), underFire, rimWide) : _waitTicks >= ApproachRules.WAIT_TICKS, policy,
                    comesToUs);
            if (isKillTask()) {
                if (_waitCapEntity != entity) {
                    _waitCapEntity = entity;
                    _waitCap.reset();
                }
                boolean stuckWaiting = plan == ApproachRules.Plan.WAIT && WaitCap.counts(comesToUs, inReach, shot, tooClose, policy == ApproachRules.Policy.KILL_GATED);
                if (stuckWaiting && _waitCap.approaching()) plan = ApproachRules.Plan.APPROACH;
                WaitCap.Verdict verdict = _waitCap.tick(stuckWaiting);
                if (verdict == WaitCap.Verdict.APPROACH) {
                    plan = ApproachRules.Plan.APPROACH;
                } else if (verdict == WaitCap.Verdict.BAN) {
                    BanPolicy.noPath(mod.getBans(), entity.getId(), EntityTracker.describe(entity));
                    _waitCapEntity = null;
                    return null;
                }
            }
            if (!isKillTask()) {
                _waitTicks = plan == ApproachRules.Plan.WAIT ? _waitTicks + 1 : 0;
            }
            // a rim step already walking is finished, not cut by the plan flipping to a swing the next tick: a step restarted
            // every few ticks never lands
            boolean stepWalking = _rimLive;
            _rimLive = false;
            boolean stepPlan = plan != ApproachRules.Plan.SWING_NOW && plan != ApproachRules.Plan.INTERACT && !underFire;
            boolean shore = isKillTask() && onShore(mod, entity);
            if (isKillTask() && stepWalking && stepPlan
                    && !mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                Task off = rimStep(mod, entity);
                if (off != null) {
                    return off;
                }
            }
            // lava in knock range with no edge close and nothing on the knock line: rimWide doesn't see that, and it's not worth
            // the rim's stand-still either, so only the walk comes of it
            if (shore && !rimWide && stepPlan) {
                Task off = rimStep(mod, entity);
                if (off != null) {
                    return off;
                }
            }
            // a kill task on any edge with a melee foe close gets off it before anything else, the plan only gets the tick when
            // there is nowhere better (rimStep is null then) or the step ran out of time
            if (isKillTask() && rimWide && stepPlan) {
                Task off = rimStep(mod, entity);
                if (off != null) {
                    return off;
                }
                // nowhere better to stand: no walking from here either (an approach or a shuffle along the rim is how we end up
                // off it), the foe comes to us and the swing is what we do. capped, a foe that cannot get to us is not waited on forever
                if ((plan == ApproachRules.Plan.APPROACH || plan == ApproachRules.Plan.WANDER) && ++_rimStandTicks <= RIM_STAND_TICKS) {
                    _progress.reset();
                    setDebugState("On an edge, letting it come");
                    return null;
                }
            } else {
                _rimStandTicks = 0;
            }
            switch (plan) {
                case INTERACT:
                    _progress.reset();
                    _blocker = null;
                    // on a rim the cooldown ticks between swings are for getting off it, the swing itself still comes first
                    if ((rimWide || shore) && !underFire && mod.getPlayer().getAttackStrengthScale(0.5f) < 0.9f) {
                        Task off = rimStep(mod, entity);
                        if (off != null) {
                            return off;
                        }
                    }
                    return onEntityInteract(mod, entity);
                case APPROACH:
                    // lava on the foe, under us or between us (just the foe, for an animal): it comes to us. the walk in is what put us in the lava, and a
                    // foe that never comes just stalls out. returning null also lets go of an approach already walking
                    if (isKillTask() && lavaHold(mod, entity)) {
                        _progress.reset();
                        _blocker = null;
                        Task off = underFire ? null : rimStep(mod, entity);
                        if (off != null) {
                            return off;
                        }
                        if (stareDown(mod, entity)) {
                            return null;
                        }
                        setDebugState("Lava by the foe, letting it come");
                        return null;
                    }
                    // a kill task walks in to hop range, not to the edge of reach: the swing plan kicks in at the kill
                    // reach anyway and the crit walk-up closes the rest, this is for when it doesn't (no clear shot yet)
                    return approach(mod, entity, isKillTask() ? ReachRules.KILL_APPROACH : maintainDistance);
                case KEEP_CHILD:
                    // the approach that is already walking is what walks off a bridge: over lava it ends, the foe comes to us
                    if (isKillTask() && lavaUnderUs(mod, entity)) {
                        _progress.reset();
                        _blocker = null;
                        return null;
                    }
                    // halfway through a step: let it land, the swing goes next tick that is safe. stopping the child here
                    // cut the path mid stride and the next tick started it again
                    _progress.reset();
                    _blocker = null;
                    setDebugState("Finishing the step before the swing");
                    _approachLive = true;
                    return _approach;
                case SWING_NOW:
                    // stood in reach this long with no swing: whatever path is in the way goes, the hit is what is owed
                    _progress.reset();
                    _blocker = null;
                    // the enderman gets this from the first tick, so the recharge ticks are ours to get off a rim, same as INTERACT
                    if (comesToUs && (rimWide || shore) && !underFire && mod.getPlayer().getAttackStrengthScale(0.5f) < 0.9f) {
                        Task off = rimStep(mod, entity);
                        if (off != null) {
                            return off;
                        }
                    }
                    _standCap.forced();
                    dropBackOff(mod);
                    mod.getClientBaritone().getPathingBehavior().cancelEverything();
                    setDebugState("In reach, swinging through the stall");
                    return onStalledSwing(mod, entity);
                case WAIT:
                    _progress.reset();
                    _blocker = null;
                    // the shuffle we started (plan WANDER) puts a path up that makes the next plan WAIT, and null cut it every
                    // other tick. same rule as with no target: a wander that is already our child finishes its step
                    if (wasWandering && !mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                        setDebugState("Finishing the shuffle");
                        _wanderLive = true;
                        return new TimeoutWanderTask();
                    }
                    if (isKillTask() && !underFire) {
                        Task off = rimStep(mod, entity);
                        if (off != null) {
                            return off;
                        }
                    }
                    // the enderman wait lands here out of reach too, and "in reach, no shot" there reads like a broken shot
                    setDebugState(!inReach ? "Out of reach, letting it come"
                            : !shot ? "In reach, waiting: no shot"
                            : !ready ? "In reach, waiting: swing gated"
                            : "In reach, waiting: path mid-step");
                    return null;
                default:
                    // the shuffle is a walk too
                    if (plan == ApproachRules.Plan.WANDER && isKillTask() && lavaHold(mod, entity)) {
                        setDebugState("Lava by the foe, not shuffling");
                        return null;
                    }
                    break;
            }
        }
        _blocker = null;
        if (!ApproachRules.wanderWhenNoTarget(wasWandering, mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            return null;
        }
        // a fresh one is isEqual to the running one, so the running one stays
        _wanderLive = true;
        return new TimeoutWanderTask();
    }

    // waiting on one foe by the lava. an animal is let go of at once (the picker takes the next one), anything that can bite
    // gets LavaShore.WAIT_CAP_TICKS to come to us. a ban takes it out of getClosestEntity, so the next tick has another target.
    // the count is per mob id and static: callers rebuild this task whenever their pick changes, and two foes swapping places
    // as the closest would restart a count that lives in the task forever
    private static final java.util.Map<Integer, int[]> LAVA_WAITS = new java.util.HashMap<>();
    // a gap this long between two holds on one mob is a different wait
    private static final int LAVA_WAIT_GAP = 100;

    // across every mob, for the ones that watch the whole pool and not one foe (the pearl hunt's lava spot)
    private static final LavaHoldClock LAVA_HOLDS = new LavaHoldClock();

    // hold ticks in the last minute, any mob
    public static int lavaHoldTicks(int now) {
        return LAVA_HOLDS.ticks(now);
    }

    public static void clearLavaHolds() {
        LAVA_HOLDS.clear();
    }

    // a calm enderman never walks up on its own, so while we hold by the lava we look at it and make it angry (same bait rules as
    // the nook, EndermanNook.stareWorthwhile). per mob id and static like the waits. a stare nobody answers stops at STARE_TICKS and
    // the hold carries on to the lava shore ban. this all runs inside lavaHold, so it counts toward the lava spot's hold ticks:
    // about four ignored stares and we leave the spot, which is the point
    private static final java.util.Map<Integer, int[]> LAVA_STARES = new java.util.HashMap<>();

    private boolean stareDown(AltoClef mod, Entity entity) {
        if (!(entity instanceof Enderman man)) {
            return false;
        }
        var us = mod.getPlayer();
        int angryNow = 0;
        for (Enderman other : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
            if (other.isAlive() && other.isCreepy()) {
                angryNow++;
            }
        }
        boolean ourLavaClear = PearlHuntTask.exposedLava(mod.getWorld(), us.blockPosition(), EndermanNook.AGGRO_LAVA_GAP).isEmpty();
        boolean between = LavaShore.lineHitsLava(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(),
                man.getX(), man.getY(), man.getZ());
        if (!EndermanNook.stareWorthwhile(us.isOnFire(), us.getHealth(), ourLavaClear, angryNow, man.distanceTo(us), man.isAlive(),
                man.isCreepy(), man.hasLineOfSight(us), between)) {
            return false;
        }
        if (LAVA_STARES.size() > 64) {
            LAVA_STARES.clear();
        }
        if (++LAVA_STARES.computeIfAbsent(man.getId(), k -> new int[1])[0] > EndermanNook.STARE_TICKS) {
            return false;
        }
        setDebugState("Lava by the foe, staring it down");
        LookHelper.lookAt(mod, man.getEyePosition());
        return true;
    }

    private boolean lavaHold(AltoClef mod, Entity entity) {
        if (!lavaInTheWay(mod, entity)) {
            return false;
        }
        int now = mod.getPlayer().tickCount;
        LAVA_HOLDS.note(now);
        if (LAVA_WAITS.size() > 64) {
            LAVA_WAITS.clear();
        }
        int[] wait = LAVA_WAITS.computeIfAbsent(entity.getId(), k -> new int[]{0, now});
        if (now < wait[1] || now - wait[1] > LAVA_WAIT_GAP) {
            wait[0] = 0;
        }
        wait[0]++;
        wait[1] = now;
        if (LavaShore.giveUp(isAnimal(mod, entity), wait[0])) {
            BanPolicy.lavaShore(mod.getBans(), entity.getId(), EntityTracker.describe(entity));
            LAVA_WAITS.remove(entity.getId());
        }
        return true;
    }

    // standing on a rim with a melee foe close: the knock lands on whatever is behind us. same range the lava guard leans at
    private static final double RIM_FOE_RANGE = 3.5;
    // a step that has not gotten us anywhere by now is not going to, and the old stand-and-hit is better than shuffling
    private static final int RIM_STEP_TICKS = 40;
    private BlockPos _rimCell;
    private int _rimStepTicks;
    private boolean _rimLive;
    private static final int RIM_STAND_TICKS = 60;
    private int _rimStandTicks;
    // cells a step already went nowhere to this fight, so the same doomed one is not picked again the next tick
    private final java.util.Set<BlockPos> _rimFailed = new java.util.HashSet<>();
    private double _rimBestDist;
    private int _rimIdleTicks;
    // the cell a walk off a shore is headed for, see LavaShore.groundCell
    private BlockPos _groundCell;
    // walking for the whole fight, not per walk: a foe that never follows us (a calm enderman) would otherwise be a walk
    // up to it and a walk back forever. a six cell walk is about 30 ticks
    private static final int GROUND_WALK_TICKS = 100;
    private int _groundTicks;

    // a blaze in melee contact knocks us off an edge as well as a zombie does, so it counts here (a blaze that close is not
    // shooting, it is hitting). ghasts and the real ranged kinds still do not
    private boolean rimFoe(AltoClef mod, Entity entity) {
        return rimFoe(mod, entity, RIM_FOE_RANGE);
    }

    private boolean rimFoe(AltoClef mod, Entity entity, double range) {
        if (!(entity instanceof Mob mob)) {
            return false;
        }
        return LavaShore.rimFoe(entity instanceof Ghast, MobReachability.isRanged(mob), isAnimal(mod, entity), entity.isAlive(),
                entity.distanceTo(mod.getPlayer()), range);
    }

    // lava in knock range with a melee foe on us. a walk already on doesn't care about the range: outrunning a slow cube is
    // the walk working, not a reason to turn around
    private boolean onShore(AltoClef mod, Entity entity) {
        var us = mod.getPlayer();
        // arrived, or out of walk: a swing tick never reaches groundWalk, and a cell left over here reads as a walk still on
        if (_groundTicks >= GROUND_WALK_TICKS || !LavaShore.lavaDeepNear(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(), LavaShore.GROUND_CLEAR)) {
            _groundCell = null;
            return false;
        }
        return rimFoe(mod, entity, _groundCell != null ? Double.MAX_VALUE : RIM_FOE_RANGE);
    }

    // the walk to real ground. no range on the foe: the holds that call us are waiting on one that isn't here yet, which is the
    // best time to get off the shore. null when we're on ground, the walk ran out or there's nowhere to go, and rimStep carries
    // on with its one cell step
    private Task groundWalk(AltoClef mod, Entity entity) {
        var us = mod.getPlayer();
        Cells cells = CliffAvoidance.of(mod.getClientBaritone().bsi);
        if (_groundTicks >= GROUND_WALK_TICKS || !rimFoe(mod, entity, Double.MAX_VALUE)
                || !LavaShore.lavaDeepNear(cells, us.getX(), us.getY(), us.getZ(), LavaShore.GROUND_CLEAR)) {
            _groundCell = null;
            return null;
        }
        // stays picked while it's fit, same as _rimCell, or the foe shuffling about turns the walk around every tick
        if (_groundCell == null || !LavaShore.groundFit(cells, _groundCell.getX(), _groundCell.getY(), _groundCell.getZ(), entity.getX(), entity.getZ())) {
            // a cell that went nowhere is skipped, not the end of it: the next one along may have a way there
            int[] cell = LavaShore.groundCell(cells, us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getZ(),
                    (x, y, z) -> _rimFailed.contains(new BlockPos(x, y, z)));
            if (cell == null) {
                _groundCell = null;
                return null;
            }
            _groundCell = new BlockPos(cell[0], cell[1], cell[2]);
            _rimBestDist = Math.sqrt(_groundCell.distToCenterSqr(us.position()));
            _rimIdleTicks = 0;
        } else {
            // same idea as the rim step's stall: the swing is worth more than a path that isn't going anywhere
            double best = LavaShore.stepBest(_rimBestDist, Math.sqrt(_groundCell.distToCenterSqr(us.position())));
            _rimIdleTicks = best < _rimBestDist ? 0 : _rimIdleTicks + 1;
            _rimBestDist = best;
            if (LavaShore.walkStalled(_rimIdleTicks)) {
                _rimFailed.add(_groundCell);
                _groundCell = null;
                return null;
            }
        }
        _groundTicks++;
        _rimLive = true;
        setDebugState("Walking off the shore");
        return new GetToBlockTask(_groundCell);
    }

    private boolean rimBack(AltoClef mod, Entity entity) {
        if (!rimFoe(mod, entity)) {
            return false;
        }
        var us = mod.getPlayer();
        return LavaShore.backUnsafe(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getZ());
    }

    private boolean rimUnsafe(AltoClef mod, Entity entity) {
        if (!rimFoe(mod, entity)) {
            return false;
        }
        var us = mod.getPlayer();
        return LavaShore.rimUnsafe(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getZ());
    }

    // real ground if there is any in reach, else one block to a safer cell before the next hit comes. null when there is
    // nothing to do or nowhere better to go
    private Task rimStep(AltoClef mod, Entity entity) {
        Task ground = groundWalk(mod, entity);
        if (ground != null) {
            return ground;
        }
        if (!rimFoe(mod, entity)) {
            _rimCell = null;
            _rimStepTicks = 0;
            return null;
        }
        var us = mod.getPlayer();
        int[] step = LavaShore.safeStep(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getZ());
        if (step == null) {
            _rimCell = null;
            _rimStepTicks = 0;
            return null;
        }
        if (++_rimStepTicks > RIM_STEP_TICKS) {
            return null;
        }
        // the cell we picked stays picked while it is still a step away: the foe shuffling about would otherwise flip it every tick
        BlockPos here = us.blockPosition();
        BlockPos pick = here.offset(step[0], 0, step[1]);
        Cells cells = CliffAvoidance.of(mod.getClientBaritone().bsi);
        if (_rimCell == null || _rimCell.equals(here) || _rimCell.distSqr(here) > 2
                || !LavaShore.stepFit(cells, _rimCell.getX(), _rimCell.getY(), _rimCell.getZ(), entity.getX(), entity.getZ())) {
            // a cell that already went nowhere is not picked again this fight, the plan (the swing) gets the tick instead
            if (_rimFailed.contains(pick)) {
                _rimCell = null;
                _rimStepTicks = 0;
                return null;
            }
            _rimCell = pick;
            _rimStepTicks = 0;
            _rimBestDist = Math.sqrt(pick.distToCenterSqr(us.position()));
            _rimIdleTicks = 0;
        } else {
            // a step that has stopped getting closer (knocked about, a path that cannot be walked) is dropped and remembered:
            // the swing is worth more than holding a doomed path for two seconds
            double dist = Math.sqrt(_rimCell.distToCenterSqr(us.position()));
            double best = LavaShore.stepBest(_rimBestDist, dist);
            _rimIdleTicks = best < _rimBestDist ? 0 : _rimIdleTicks + 1;
            _rimBestDist = best;
            if (LavaShore.stepStalled(_rimIdleTicks)) {
                _rimFailed.add(_rimCell);
                _rimCell = null;
                _rimStepTicks = 0;
                return null;
            }
        }
        _rimLive = true;
        setDebugState("Stepping off the rim");
        return new GetToBlockTask(_rimCell);
    }

    // by type, not by mood: an enderman nobody has angered yet or a day spider reads as not hostile until the first hit, and
    // that hit is what throws us into the pool
    private static boolean isAnimal(AltoClef mod, Entity entity) {
        return !(entity instanceof Enemy) && !(entity instanceof NeutralMob) && !EntityHelper.isGenerallyHostileToPlayer(mod, entity);
    }

    // the same targets lavaInTheWay lets walk (no blazes, shooters or animals), with lava under or beside our own spot
    private static boolean lavaUnderUs(AltoClef mod, Entity entity) {
        if (entity instanceof Blaze || entity instanceof Ghast || (entity instanceof Mob mob && MobReachability.isRanged(mob))
                || isAnimal(mod, entity)) return false;
        var us = mod.getPlayer();
        return LavaShore.lavaDeepNear(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ(), LavaShore.NEAR);
    }

    // the approach walk near lava, see LavaShore. every dimension: a cave lava pool behind a spider is the overworld's version
    private static boolean lavaInTheWay(AltoClef mod, Entity entity) {
        // only the ones that walk up and hit. a blaze hovers over the lava on purpose, and waiting for it to come is no rods
        if (entity instanceof Blaze || entity instanceof Ghast || (entity instanceof Mob mob && MobReachability.isRanged(mob))) return false;
        Cells cells = CliffAvoidance.of(mod.getClientBaritone().bsi);
        var us = mod.getPlayer();
        return LavaShore.blockedFor(isAnimal(mod, entity), cells, us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getY(), entity.getZ());
    }

    // close enough to interact. the feet line for everyone (trading, shearing, frames), the kill tasks go by the eye to box
    // gap inside the swing's reach instead (ReachRules.killReach)
    protected boolean inReach(AltoClef mod, Entity entity) {
        return mod.getControllerExtras().inRange(entity);
    }

    // the kill tasks are committed to the mob in front of them: they stand and hit what is in reach and do not shuffle off it
    protected boolean isKillTask() {
        return false;
    }

    // the swing for a stall the plan gave up waiting out (kill tasks only)
    protected Task onStalledSwing(AltoClef mod, Entity entity) {
        return null;
    }

    // the raycast in onTick goes through blocks and the swing gate doesn't, and the gap is shorter than the aimed ray. so
    // "in reach with a clear shot" can be true for good while the swing never is (a wall, a slab lip, a mob at an awkward
    // angle), and the stall breaker can't help, it swings through the same gate. after this many ticks where the aim had
    // the head and still isn't on the mob, the shot counts as not clear: the plan walks closer, and the approach's own
    // stuck check digs or blacklists if walking can't fix it
    private static final int AIM_STALL_TICKS = 30;
    private int _unaimedTicks;
    // the aim gave up on this mob last time we asked (see aimStalled): a swing that is not coming, so staying close for one is pointless
    private boolean _aimStalled;
    private Entity _unaimedEntity;

    private boolean aimStalled(AltoClef mod, Entity entity, boolean shotInReach) {
        if (_unaimedEntity != entity) {
            _unaimedEntity = entity;
            _unaimedTicks = 0;
        }
        if (!inReach(mod, entity)) {
            _unaimedTicks = 0;
            return false;
        }
        // in reach we want the head on it whatever the plan says: waiting out a gate (eating, a ladder, the water) with the
        // head turned away is a stall of its own, and the stall breaker should start with the head already there. a
        // walking path keeps the head anyway, so asking during a step costs nothing
        mod.getControllerExtras().aimAt(entity, CombatAim.Priority.KILL_TASK);
        if (mod.getControllerExtras().onTarget(entity)) {
            _unaimedTicks = 0;
            return false;
        }
        if (_unaimedTicks >= AIM_STALL_TICKS) {
            return true;
        }
        // only ticks the aim could have used count: a walking path has the head
        if (shotInReach && !mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _unaimedTicks++;
        }
        return false;
    }

    // an enderman further than this teleports a few times before it gets here, turning the head that early is just twitching
    private static final double PRE_AIM_RANGE = 8;

    // first contact with an angry enderman, once per mob per task: where we stood when it got into reach, then how long until our
    // first swing and the first hurt (any source, the nearest thing is usually it). gives up quietly after CONTACT_LOG_TICKS
    private static final int CONTACT_LOG_TICKS = 200;
    private Entity _contactEntity;
    private long _contactStart;
    private long _contactSwings;
    private int _contactHurtTime;
    private boolean _contactSwung;
    private boolean _contactHurt;

    private void logEndermanContact(AltoClef mod, Entity entity, boolean angryInReach, long now) {
        var us = mod.getPlayer();
        var extras = mod.getControllerExtras();
        if (angryInReach && _contactEntity != entity) {
            _contactEntity = entity;
            _contactStart = now;
            _contactSwings = extras.swings();
            _contactHurtTime = us.hurtTime;
            _contactSwung = false;
            _contactHurt = false;
            Vec3 eye = us.getEyePosition();
            var box = entity.getBoundingBox();
            double gap = ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            Debug.logInternal(String.format("enderman contact: gap %.2f, onTarget %b, attackReady %b, safeToCancel %b, pathing %b",
                    gap, extras.onTarget(entity), extras.attackReady(),
                    mod.getClientBaritone().getPathingBehavior().isSafeToCancel(), mod.getClientBaritone().getPathingBehavior().isPathing()));
            // a hit that came in with it is a rise we'll never see from here, hurtTime only goes down after this (10 on a hit)
            if (us.hurtTime >= 9) {
                _contactHurt = true;
                Debug.logInternal("enderman contact: first hurt +0 ticks");
            }
            return;
        }
        if (_contactEntity != entity || (_contactSwung && _contactHurt) || now - _contactStart > CONTACT_LOG_TICKS) {
            return;
        }
        // the swing goes out after this runs, so it's seen a tick late. the controller knows the real tick
        if (!_contactSwung && extras.swings() != _contactSwings) {
            _contactSwung = true;
            Debug.logInternal("enderman contact: first swing +" + (extras.lastSwingTick() - _contactStart) + " ticks");
        }
        // hurtTime jumps back up to 10 on a hit, so a rise is a new one
        if (!_contactHurt && us.hurtTime > _contactHurtTime) {
            _contactHurt = true;
            Debug.logInternal("enderman contact: first hurt +" + (now - _contactStart) + " ticks");
        }
        _contactHurtTime = us.hurtTime;
    }

    // feeds the stand cap: a swing, a new target or the mob leaving reach starts it over
    private void countStandingTicks(AltoClef mod, Entity entity, boolean standingInReach) {
        long swings = mod.getControllerExtras().swings();
        boolean swung = swings != _swingsSeen;
        _swingsSeen = swings;
        if (_waitEntity != entity) {
            _waitEntity = entity;
            _standCap.reset();
        }
        _standCap.tick(standingInReach, swung);
    }

    // the run-away we started has to be moving us, or it is dropped and left alone for a bit
    private void watchBackOff(AltoClef mod, long now) {
        var goalProcess = mod.getClientBaritone().getCustomGoalProcess();
        if (_backOffGoal == null || goalProcess.getGoal() != _backOffGoal || !goalProcess.isActive()) {
            _backOffWatch.clear();
            return;
        }
        if (_backOffWatch.stuck(now, mod.getPlayer().getX(), mod.getPlayer().getZ())) {
            dropBackOff(mod);
            _backOffMutedUntil = now + BackOffRules.MUTE_TICKS;
        }
    }

    // only the kill tasks swing, so only they have a hop for the back-off to get out of the way of. shearing a sheep right
    // after equipping shears also has a recharging cooldown, and that is no reason to skip its back-off
    protected boolean wantsCritHops() {
        return false;
    }

    // the kill tasks' back-off: a step out from a mob that is right on top of us, only while there is nothing better to do
    // with the time. a swing due or a hop planned for this cooldown wants us close, and a path alive then is what vetoes
    // the hop and takes the head off the mob, so the back-off is dropped the moment either shows up. a hop being planned
    // is asked for the whole charge, not just the lead: a back-off that starts mid charge is still walking when the lead
    // comes. (cancelSegmentIfSafe can take a few ticks mid step, that is fine, a ledge is worse)
    private void killBackOff(AltoClef mod, Entity entity, boolean tooClose) {
        if (!tooClose && _backOffGoal == null) {
            return;
        }
        boolean angryEnderman = entity instanceof Enderman && EntityHelper.isAngryAtPlayer(mod, entity);
        if (CritTiming.noBackOff(LavaShore.underFire(mod.getControllerExtras().ticksSinceHurt(), inReach(mod, entity)), angryEnderman)) {
            dropBackOff(mod, true);
            return;
        }
        // (no shot to take is nothing to stay close for: a ready swing that cannot be aimed is how the step back never came)
        boolean stayClose = !_aimStalled && (mod.getControllerExtras().attackReady()
                || (wantsCritHops() && mod.getControllerExtras().hopWantsToStayClose(entity)));
        if (CritTiming.shouldBackOff(tooClose, stayClose)) {
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                _backOffGoal = new GoalRunAway(ReachRules.BACK_OFF_TO, entity.blockPosition());
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_backOffGoal);
            }
        } else if (stayClose) {
            dropBackOff(mod, CritTiming.hardDropBackOff(mod.getControllerExtras().swordInHand(), stayClose));
        }
    }

    // the run-away we started, so the hop can stop exactly that and nobody else's path. a hop only works while baritone is
    // not walking (the jump press is ignored under its movement input), and the back-off is the walking that was always
    // there. cancel the path too, not just the goal, or isPathing() still says yes for the tick we wanted to jump in
    private void dropBackOff(AltoClef mod) {
        dropBackOff(mod, false);
    }

    // hard: the charge is full and a half walked back-off step is all that stands between us and the hit, so it ends on the first safe
    // tick instead of whenever the walk is done (cutting a step before that is how you walk off a ledge)
    private void dropBackOff(AltoClef mod, boolean hard) {
        if (_backOffGoal == null) {
            return;
        }
        var goalProcess = mod.getClientBaritone().getCustomGoalProcess();
        if (goalProcess.getGoal() == _backOffGoal) {
            goalProcess.onLostControl();
            if (hard) {
                mod.getClientBaritone().getPathingBehavior().cancelWhenSafe();
            } else {
                mod.getClientBaritone().getPathingBehavior().cancelSegmentIfSafe();
            }
        }
        _backOffGoal = null;
    }

    private Task approach(AltoClef mod, Entity entity, double maintainDistance) {
        if (_blockerTarget != entity) {
            _blockerTarget = entity;
            _blockerDigs = 0;
            _blocker = null;
        }
        if (_blocker != null) {
            if (!_blocker.isFinished(mod) && ++_blockerTicks <= BLOCKER_TICKS) {
                setDebugState("Breaking what is in the way");
                _progress.reset();
                return _blocker;
            }
            _blocker = null;
            _progress.reset();
        }
        setDebugState("Approaching target");
        if (!_progress.check(mod)) {
            _progress.reset();
            BlockPos blocker = findBlocker(mod, entity);
            if (blocker != null) {
                Debug.logMessage("Something is in the way of the target, breaking it.");
                _blockerDigs++;
                _blockerTicks = 0;
                _blocker = new DestroyBlockTask(blocker);
                return _blocker;
            }
            Debug.logMessage("Failed to get to target, blacklisting.");
            mod.getEntityTracker().requestEntityUnreachable(entity);
        }
        // Move to target
        return approachTask(entity, maintainDistance);
    }

    // a fresh GetToEntityTask every tick never let its stuck checks build up anything, same target means same task
    private GetToEntityTask approachTask(Entity entity, double maintainDistance) {
        boolean same = _approach != null && _approachEntity == entity && Math.abs(_approachDistance - maintainDistance) < 0.1;
        if (!same) {
            _approach = new GetToEntityTask(entity, maintainDistance);
            _approachEntity = entity;
            _approachDistance = maintainDistance;
        }
        _approachLive = true;
        return _approach;
    }

    // the first solid thing between our eye and the middle of the target, if digging it is allowed. collision shapes, because
    // that is what stops a swing: a fence is see through and still wins the raycast
    private BlockPos findBlocker(AltoClef mod, Entity entity) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, entity.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        double distance = eye.distanceTo(hit.getLocation());
        boolean ok = EntityBlockerRules.shouldMine(isBreakable(mod, pos), _blockerDigs, distance, holdsUsUp(player, pos), opensLavaNextToUs(level, player, pos));
        return ok ? pos : null;
    }

    // canBreak covers bedrock and the avoid lists. block entities are out because that is chests and furnaces and, worst of
    // all, the blaze spawner we are standing here for
    private static boolean isBreakable(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || state.is(Blocks.SPAWNER) || state.is(Blocks.BEDROCK)) return false;
        if (mod.getClientBaritoneSettings().blocksToAvoidBreaking.value.contains(state.getBlock())) return false;
        return WorldHelper.canBreak(mod, pos);
    }

    // any cell under the footprint of our feet
    private static boolean holdsUsUp(LocalPlayer player, BlockPos pos) {
        var box = player.getBoundingBox();
        int y = (int) Math.floor(box.minY - 0.001);
        if (pos.getY() != y) return false;
        return pos.getX() >= (int) Math.floor(box.minX) && pos.getX() <= (int) Math.floor(box.maxX)
                && pos.getZ() >= (int) Math.floor(box.minZ) && pos.getZ() <= (int) Math.floor(box.maxZ);
    }

    // lava touching the hole we would make, close enough to our feet that it would come to us
    private static boolean opensLavaNextToUs(Level level, LocalPlayer player, BlockPos pos) {
        BlockPos feet = player.blockPosition();
        if (Math.abs(pos.getX() - feet.getX()) > 2 || Math.abs(pos.getZ() - feet.getZ()) > 2) return false;
        int dy = pos.getY() - feet.getY();
        if (dy < -2 || dy > 1) return false;
        for (Direction side : Direction.values()) {
            if (level.getFluidState(pos.relative(side)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof AbstractDoToEntityTask task) {
            if (!doubleCheck(task._maintainDistance, _maintainDistance)) return false;
            if (!doubleCheck(task._combatGuardLowerFieldRadius, _combatGuardLowerFieldRadius)) return false;
            if (!doubleCheck(task._combatGuardLowerRange, _combatGuardLowerRange)) return false;
            return isSubEqual(task);
        }
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean doubleCheck(double a, double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.isInfinite(a) == Double.isInfinite(b);
        return Math.abs(a - b) < 0.1;
    }

    protected abstract boolean isSubEqual(AbstractDoToEntityTask other);

    protected abstract Task onEntityInteract(AltoClef mod, Entity entity);

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getMobDefenseChain().resetForceField();
        // a back-off we started is ours to stop, or the bot keeps running from a mob nobody is fighting any more
        dropBackOff(mod);
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}
