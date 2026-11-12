package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.GoalAnd;
import adris.altoclef.util.baritone.GoalBlockSide;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.PhantomScoops;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.ui.HudText;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Left or Right click on a block on a particular (or any) side of the block.
 */
public class InteractWithBlockTask extends Task {
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker();
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final ItemTarget _toUse;
    private final Direction _direction;
    private final BlockPos _target;
    private final boolean _walkInto;
    private final Vec3i _interactOffset;
    private final Input _interactInput;
    private final boolean _shiftClick;
    // scooping a liquid with a bucket. set by scoop(), see the notes there
    private boolean _scoop;
    private final TimerGame _clickTimer = new TimerGame(5);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);
    private Task _unstuckTask = null;
    static final double UNSTUCK_SECONDS = 10;
    private final TimerGame _unstuckTimer = new TimerGame(UNSTUCK_SECONDS);
    private ClickResponse _cachedClickStatus = ClickResponse.CANT_REACH;
    static final int EQUIP_PATIENCE = 20;
    private int _equipMisses;

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        _toUse = toUse;
        _direction = direction;
        _target = target;
        _interactInput = interactInput;
        _walkInto = walkInto;
        _interactOffset = interactOffset;
        _shiftClick = shiftClick;
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(toUse, direction, target, interactInput, walkInto, Vec3i.ZERO, shiftClick);
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(toUse, direction, target, Input.CLICK_RIGHT, walkInto, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        // null means any side is OK
        this(toUse, null, target, Input.CLICK_RIGHT, walkInto, interactOffset, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto) {
        this(toUse, target, walkInto, Vec3i.ZERO);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target) {
        this(toUse, target, false);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, interactOffset, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), direction, target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), target, walkInto, interactOffset);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), null, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target) {
        this(new ItemTarget(toUse, 1), target);
    }

    public InteractWithBlockTask(BlockPos target, boolean shiftClick) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, shiftClick);
    }

    public InteractWithBlockTask(BlockPos target) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, false);
    }

    // a bucket on a liquid source. differs from a plain click in two ways:
    // - it never sneaks. the sneak was there so a click can't open whatever is behind the target. that is a risk we take on
    //   (vanilla's crosshair ignores fluid, so a chest right behind the source could eat the click), because the crouch
    //   moves the eyes from 1.62 to 1.27: a reach that was fine standing may not be crouched, the next tick lets go and
    //   walks, stands up, and it flips forever
    // - a click that stays unanswered for a whole click timeout bans the source, so the collector moves on to the next one
    //   instead of wandering off and coming back to the same one for good
    public static InteractWithBlockTask scoop(ItemTarget bucket, BlockPos source, boolean walkInto) {
        InteractWithBlockTask task = new InteractWithBlockTask(bucket, null, source, Input.CLICK_RIGHT, walkInto, Vec3i.ZERO, false);
        task._scoop = true;
        return task;
    }

    // the sneak after a click is for plain interactions only (see scoop)
    static boolean sneaksAfterClick(boolean shiftClick, boolean scoop) {
        return shiftClick && !scoop;
    }

    // an unanswered click timeout is only news for a scoop, a door that doesn't open is a different problem
    static boolean bansOnClickTimeout(boolean scoop) {
        return scoop;
    }

    // wandering off crouched is slow and pointless, the sneak is for the click
    private Task wander(AltoClef mod) {
        if (_shiftClick) {
            mod.getInputControls().release(Input.SNEAK);
        }
        return _wanderTask;
    }

    // where to walk to instead of the stock goal, for whoever knows better where the block is best used from (the station walk)
    private Function<BlockPos, Goal> _goalFor;

    public InteractWithBlockTask walkTo(Function<BlockPos, Goal> goalFor) {
        _goalFor = goalFor;
        return this;
    }

    // for a parent that put us on the exact cell itself (the lava mold). out of reach means "tell me", not "walk": the
    // stock goal for a bucket on a frame cell walks straight into the cell the lava is going in. the parent reads
    // getClickStatus / stillAiming and decides
    private boolean _stayPut;
    // the last tick had the target in reach but the crosshair wasn't on it yet (or was on the wrong face, see pourAimOff)
    private boolean _aiming;

    public InteractWithBlockTask stayPut() {
        _stayPut = true;
        return this;
    }

    public boolean stillAiming() {
        return _aiming;
    }

    // (the buddy's fork: a click that goes out only with the item asked for in hand. the equip fallback below clicks with
    // whatever the hand holds after EQUIP_PATIENCE misses, and a click that places a block then places that: a brew box's wall
    // cell got the brewing stand, and the brew had no stand. the pillbox's clicks are made this way, BlazePillboxTask)
    private boolean _onlyWith;

    public InteractWithBlockTask onlyWith() {
        _onlyWith = true;
        return this;
    }

    // the click waits for the equip to show: always with onlyWith, else for EQUIP_PATIENCE misses and then clicks anyway
    static boolean waitsForEquip(boolean onlyWith, int misses) {
        return onlyWith || misses < EQUIP_PATIENCE;
    }

    static boolean walksWhenOutOfReach(boolean stayPut) {
        return !stayPut;
    }

    // a stalled move checker or an unanswered click both end in a wander, which is walking too
    static boolean wandersWhenStuck(boolean stayPut) {
        return !stayPut;
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.offset(1, 0, 0),
                pos.offset(-1, 0, 0),
                pos.offset(0, 0, 1),
                pos.offset(0, 0, -1),
                pos.offset(1, 0, -1),
                pos.offset(1, 0, 1),
                pos.offset(-1, 0, -1),
                pos.offset(-1, 0, 1)
        };
    }

    private static Goal createGoalForInteract(BlockPos target, int reachDistance, Direction interactSide, Vec3i interactOffset, boolean walkInto) {

        boolean sideMatters = interactSide != null;
        if (sideMatters) {
            Vec3i offs = interactSide.getUnitVec3i();
            if (offs.getY() == -1) {
                // If we're below, place ourselves two blocks below.
                offs = offs.below();
            }
            target = target.offset(offs);
        }

        if (walkInto) {
            return new GoalTwoBlocks(target);
        } else {
            if (sideMatters) {
                // Make sure we're on the right side of the block.
                Goal sideGoal = new GoalBlockSide(target, interactSide, 1);
                return new GoalAnd(sideGoal, new GoalNear(target.offset(interactOffset), reachDistance));
            } else {
                // TODO: Cleaner method of picking which side to approach from. This is only here for the lava stuff.
                return new GoalTwoBlocks(target.above());
                //return new GoalNear(target.add(interactOffset), reachDistance);
            }
        }
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    // This happens all the time in mineshafts and swamps/jungles
    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().blockPosition();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.above())) return p.above();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.above());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        // (the buddy's fork: and the goal another task left: while it stays active the click below never sets its own, and
        // a portal frame's click walked on to the last cell's stand for twenty minutes, m4cream2-1 run 1, 6 Nov 2026)
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        _moveChecker.reset();
        stuckCheck.reset();
        _wanderTask.resetWander();
        _clickTimer.reset();
        _equipMisses = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _moveChecker.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                PortalExit.step(mod);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        // (the buddy's fork: the shimmy out of grass for UNSTUCK_SECONDS at most. it never finishes by itself, and held by a
        // drop beside the feet it only turns the head: by a portal frame it ran 19 and 21 game minutes, until the drill's
        // cap, m4cream-3 runs 1 and 2, 6 Nov 2026. after that the click goes on, and a stall again starts it again)
        if (_unstuckTask != null && _unstuckTimer.elapsed()) {
            _unstuckTask = null;
            stuckCheck.reset();
            _moveChecker.reset();
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        _aiming = false;
        if (!wandersWhenStuck(_stayPut)) {
            // the shimmy out of grass is a walk too, and standing still beside some is the plan here
            stuckCheck.reset();
        } else if (!_moveChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                _unstuckTimer.reset();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }

        _cachedClickStatus = ClickResponse.CANT_REACH;

        // Get our use item first
        if (!ItemTarget.nullOrEmpty(_toUse) && !StorageHelper.itemTargetsMet(mod, _toUse)) {
            _moveChecker.reset();
            _clickTimer.reset();
            return TaskCatalogue.getItemTask(_toUse);
        }

        // Wander and check
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _moveChecker.reset();
            _clickTimer.reset();
            return wander(mod);
        }
        if (!wandersWhenStuck(_stayPut)) {
            // standing still is the whole point, the move checker would call that a stall
            _moveChecker.reset();
        } else if (!_moveChecker.check(mod)) {
            Debug.logMessage("Failed, blacklisting and wandering.");
            mod.getBlockTracker().requestBlockUnreachable(_target);
            return wander(mod);
        }

        int reachDistance = 0;
        Goal moveGoal;
        if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(_target.above())) {
            // (the buddy's fork: beside the target on any side, not north alone: north was a wall's cell of the pillbox, and the
            // douse of a fire in the box's front cell walked to it, never got there and gave up while the buddy burned outside,
            // realfort-b-raised2 run 3. a switch until 5 Nov 2026, on for good since)
            moveGoal = new baritone.api.pathing.goals.GoalComposite(new GoalTwoBlocks(_target.north()), new GoalTwoBlocks(_target.south()),
                    new GoalTwoBlocks(_target.east()), new GoalTwoBlocks(_target.west()));
        } else if (_goalFor != null) {
            moveGoal = _goalFor.apply(_target);
        } else {
            moveGoal = createGoalForInteract(_target, reachDistance, _direction, _interactOffset, _walkInto);
        }
        ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();

        _cachedClickStatus = rightClick(mod);
        switch (Objects.requireNonNull(_cachedClickStatus)) {
            case CANT_REACH -> {
                // (S137, the repair after smoke7, Tuning risk_goal_once: the way to it given up from where the buddy stands, a round
                // of search from here found none: a wander, as after a stall, and the target kept. smoke7 r1 stood 2.5 minutes
                // under its brewing stand, a shimmy every 16 s keeping the stall checks from ever firing)
                if (wandersWhenStuck(_stayPut) && mod.getClientBaritone().getPathingBehavior().givenUpHere(moveGoal)) {
                    Debug.logInternal("interact: no way to " + _target.toShortString() + " from here, wandering");
                    _moveChecker.reset();
                    return wander(mod);
                }
                setDebugState("Getting to our goal");
                // Get to our goal then
                if (walksWhenOutOfReach(_stayPut) && !proc.isActive()) {
                    proc.setGoalAndPath(moveGoal);
                }
                _clickTimer.reset();
            }
            case WAIT_FOR_CLICK -> {
                setDebugState("Waiting for click");
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                _clickTimer.reset();
            }
            case CLICK_ATTEMPTED -> {
                setDebugState("Clicking.");
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                if (_clickTimer.elapsed()) {
                    // We tried clicking but failed.
                    _clickTimer.reset();
                    if (bansOnClickTimeout(_scoop)) {
                        Debug.logInternal("interact: clicked " + _target.toShortString() + " for a whole click timeout and the bucket never filled, giving up on it");
                        mod.getBlockTracker().requestBlockUnreachable(_target, 0, "the bucket never filled");
                    }
                    if (!wandersWhenStuck(_stayPut)) {
                        // the parent's step watchdog owns a click that does nothing
                        return null;
                    }
                    return wander(mod);
                }
            }
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        mod.getInputControls().release(Input.SNEAK);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
        //return _trying && !proc(mod).isActive();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof InteractWithBlockTask task) {
            if ((task._direction == null) != (_direction == null)) return false;
            if (task._direction != null && !task._direction.equals(_direction)) return false;
            if ((task._toUse == null) != (_toUse == null)) return false;
            if (task._toUse != null && !task._toUse.equals(_toUse)) return false;
            if (!task._target.equals(_target)) return false;
            if (!task._interactInput.equals(_interactInput)) return false;
            return task._walkInto == _walkInto && task._scoop == _scoop && task._stayPut == _stayPut && task._onlyWith == _onlyWith && Objects.equals(task._goalFor, _goalFor);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Interact using " + _toUse + " at " + _target + " dir " + _direction;
    }

    @Override
    protected String toHudString() {
        String where = blockName();
        if (_toUse == null || _toUse.isEmpty()) {
            return "Opening " + where;
        }
        return "Using " + HudText.items(_toUse) + " on " + where;
    }

    // the block under the target when its chunk is loaded, "the block at 1, 2, 3" otherwise
    private String blockName() {
        try {
            Level level = Minecraft.getInstance().level;
            if (level != null && _target != null && level.isLoaded(_target)) {
                return "the " + HudText.block(level.getBlockState(_target).getBlock());
            }
        } catch (Throwable ignored) {
        }
        return "the block at " + HudText.pos(_target);
    }

    public ClickResponse getClickStatus() {
        return _cachedClickStatus;
    }

    private ClickResponse rightClick(AltoClef mod) {
        _aiming = false;

        // Don't interact if baritone can't interact.
        if (mod.getExtraBaritoneSettings().isInteractionPaused() || mod.getFoodChain().needsToEat() ||
                mod.getPlayer().isBlocking())
            return ClickResponse.WAIT_FOR_CLICK;

        // We can't interact while a screen is open.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                if (ItemHelper.canDropStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                return ClickResponse.WAIT_FOR_CLICK;
            } else {
                StorageHelper.closeScreen();
            }
        }

        Optional<Rotation> reachable = getCurrentReach();
        if (reachable.isPresent() && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            // a bucket that isn't aimed at the face we asked for gets aimed again instead of clicked, see pourAimOff
            if (LookHelper.isLookingAt(mod, _target) && !pourAimOff(mod) && !placeAimOff(mod)) {
                if (_toUse != null) {
                    boolean equipped = mod.getSlotHandler().forceEquipItem(_toUse, false);
                    if (ItemTarget.nullOrEmpty(_toUse) || equipped) {
                        _equipMisses = 0;
                    } else if (waitsForEquip(_onlyWith, _equipMisses++)) {
                        // a seed click with an empty hand is a click that plants nothing and still counts as an attempt.
                        // wait for the swap to show, but not forever: the old behaviour (click anyway) is the fallback
                        return ClickResponse.WAIT_FOR_CLICK;
                    }
                } else {
                    mod.getSlotHandler().forceDeequipRightClickableItem();
                }
                mod.getInputControls().hold(_interactInput);
                if (mod.getInputControls().isHeldDown(_interactInput)) {
                    if (sneaksAfterClick(_shiftClick, _scoop)) {
                        mod.getInputControls().hold(Input.SNEAK);
                    }
                    mod.getInputControls().release(_interactInput);
                    if (_scoop) {
                        watchScoop(mod);
                    }
                    _placeAimOff = 0;
                    return ClickResponse.CLICK_ATTEMPTED;
                }
                //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(_interactInput, true);
            } else {
                _aiming = true;
                LookHelper.lookAt(mod, reachable.get());
            }
            return ClickResponse.WAIT_FOR_CLICK;
        }
        if (_shiftClick) {
            mod.getInputControls().release(Input.SNEAK);
        }
        return ClickResponse.CANT_REACH;
    }

    // the cell the bucket's own ray (sources only, same as vanilla's empty bucket) actually hit, not the one we aimed for.
    // PhantomScoops looks at it a few ticks on and tells a scoop the server took from one it didn't
    private static void watchScoop(AltoClef mod) {
        // the equip fallback clicks with whatever is in hand, and a click with no empty bucket scoops nothing for real.
        // one watch at a time too, a second click would swap the cell out from under the first
        if (!mod.getPlayer().getMainHandItem().is(Items.BUCKET) || PhantomScoops.pending()) {
            return;
        }
        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Vec3 start = mod.getPlayer().getEyePosition(1.0F);
        Vec3 end = start.add(RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).scale(ctx.playerController().getBlockReachDistance()));
        BlockHitResult hit = mod.getWorld().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mod.getPlayer()));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos at = hit.getBlockPos();
        if (mod.getWorld().getBlockState(at).getFluidState().is(FluidTags.LAVA)) {
            PhantomScoops.arm(WorldHelper.getCurrentDimension(), at.getX(), at.getY(), at.getZ(), WorldHelper.getTicks());
        }
    }

    // a full bucket empties onto the cell on whatever face the ray is on, not the one we asked for. the crosshair being on
    // the right block isn't enough: on the wrong face the lava goes somewhere nobody picked, beside us included. so the
    // ray vanilla's bucket throws (outlines, liquids don't count) has to be on our block AND our face. the reach we aim
    // with comes in through that face, so a pour that was fine before still is
    private boolean pourAimOff(AltoClef mod) {
        if (_scoop || !pours(_toUse)) {
            return false;
        }
        BlockHitResult hit = bucketRay(mod);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        // the aim goes for the face of a full cube. on a slab or a path block that point can be on another face, aiming
        // again lands on the same one forever, so for those the block has to do
        boolean fullCube = Block.isShapeFullBlock(mod.getWorld().getBlockState(_target).getShape(mod.getWorld(), _target));
        return !BucketPour.aimedRight(hit.getBlockPos(), hit.getDirection(), _target, fullCube ? _direction : null);
    }

    // (the buddy's fork: a block click made onlyWith, the pillbox's and the brew box's, goes on the face asked for. the crosshair
    // on the right block was enough, so a roof click whose ray met the underside of the block it builds on put the block in the
    // cell below: the brew box's cell B got netherrack in its head and the box never shut, three tries, the run dead without
    // its potion (realfort-p4-cream2-mc2, lane t1 run 1, 9 Nov 2026). on a block that is no full cube the aimed point may lie
    // on another face, so there the block has to do, as for a pour. after PLACE_AIM_TRIES ticks aimed off the click goes as
    // before: no build waits forever on a face it cannot get)
    static final int PLACE_AIM_TRIES = 40;
    private int _placeAimOff;

    private boolean placeAimOff(AltoClef mod) {
        if (!_onlyWith || _direction == null || _scoop || ItemTarget.nullOrEmpty(_toUse)) {
            return false;
        }
        if (!Block.isShapeFullBlock(mod.getWorld().getBlockState(_target).getShape(mod.getWorld(), _target))) {
            return false;
        }
        HitResult hit = mod.getClientBaritone().getPlayerContext().objectMouseOver();
        boolean right = hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(_target)
                && b.getDirection() == _direction;
        if (right) {
            _placeAimOff = 0;
            return false;
        }
        if (++_placeAimOff == PLACE_AIM_TRIES) {
            Debug.logInternal("click: the face " + _direction + " of " + _target.toShortString() + " not met in " + PLACE_AIM_TRIES
                    + " ticks, clicked as aimed");
        }
        return _placeAimOff < PLACE_AIM_TRIES;
    }

    // a bucket with something in it. the empty one scoops, that's a different click
    static boolean pours(ItemTarget toUse) {
        if (ItemTarget.nullOrEmpty(toUse)) {
            return false;
        }
        for (Item item : toUse.getMatches()) {
            if (item instanceof BucketItem && item != Items.BUCKET) {
                return true;
            }
        }
        return false;
    }

    // same start and direction as baritone's objectMouseOver, but with the fluid mode the bucket uses. ours can be
    // SOURCE_ONLY while something collects liquid, and a full bucket looks straight through water
    private static BlockHitResult bucketRay(AltoClef mod) {
        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Vec3 start = mod.getPlayer().getEyePosition(1.0F);
        Vec3 end = start.add(RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).scale(ctx.playerController().getBlockReachDistance()));
        return mod.getWorld().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mod.getPlayer()));
    }

    public Optional<Rotation> getCurrentReach() {
        return LookHelper.getReach(_target, _direction);
    }

    public enum ClickResponse {
        CANT_REACH,
        WAIT_FOR_CLICK,
        CLICK_ATTEMPTED
    }
}
