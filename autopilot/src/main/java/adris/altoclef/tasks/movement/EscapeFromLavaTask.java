package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.HopLatch;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.LavaExit;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

public class EscapeFromLavaTask extends CustomBaritoneGoalTask {

    private final float _strength;

    public EscapeFromLavaTask(float strength) {
        _strength = strength;
    }

    public EscapeFromLavaTask() {
        this(100);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getFoodChain().shouldStop(true);
        mod.getBehaviour().push();
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        boolean inLava = mod.getPlayer().isInLava();
        _outOfLava = inLava ? 0 : LAVA_GRACE_TICKS;
        _lavaAllowed = lavaPathingAllowed(inLava, _outOfLava);
        mod.getBehaviour().allowSwimThroughLava(_lavaAllowed);
        // Encourage placing of all blocks!
        mod.getBehaviour().setBlockPlacePenalty(0);
        mod.getBehaviour().setBlockBreakAdditionalPenalty(0); // Normally 2
        // do NOT ever wander
        _checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);
    }

    // swimming through lava is how you get out of a lake you're in. this task also runs when we're only on fire, and then a
    // path through the lava is a path back into it: with this on the planner lets lava be walked through. so it's only on while
    // we're really in lava, plus a few ticks for the surface flicker at the shore. a flip rebuilds PrecomputedData, which
    // is fine at a couple of flips per escape
    private static final int LAVA_GRACE_TICKS = 10;
    private int _outOfLava;
    private boolean _lavaAllowed;

    static boolean lavaPathingAllowed(boolean inLava, int ticksOutOfLava) {
        return inLava || ticksOutOfLava < LAVA_GRACE_TICKS;
    }

    private void updateLavaRule(AltoClef mod) {
        boolean inLava = mod.getPlayer().isInLava();
        _outOfLava = inLava ? 0 : Math.min(_outOfLava + 1, LAVA_GRACE_TICKS);
        applyLavaRule(mod, inLava);
    }

    private void applyLavaRule(AltoClef mod, boolean inLava) {
        boolean allow = lavaPathingAllowed(inLava, _outOfLava);
        if (allow == _lavaAllowed) {
            return;
        }
        _lavaAllowed = allow;
        mod.getBehaviour().allowSwimThroughLava(allow);
        if (!allow && mod.getClientBaritone().getPathingBehavior().isPathing()) {
            // the path we're on was planned with lava allowed, the next one won't be
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        updateLavaRule(mod);
        boolean inLava = mod.getPlayer().isInLava();
        boolean lavaBelow = mod.getWorld().getBlockState(mod.getPlayer().blockPosition().below()).getBlock() == Blocks.LAVA;
        boolean onGround = mod.getPlayer().onGround();
        // tryStair holds back when it's leaning on the cell, and every way out of it has to let go
        mod.getInputControls().release(Input.MOVE_BACK);
        if (inLava && !_hop.active() && tryStair(mod)) {
            setDebugState("Building a step out of the lava");
            return null;
        }
        if (_hop.active()) {
            HopLatch.Keys keys = _hop.step(inLava, onGround, lavaBelow);
            if (keys != HopLatch.Keys.NONE) {
                hopInputs(mod, keys);
                setDebugState("Hopping out of the lava.");
                return null;
            }
            // the hop is over: let go, nothing else may be holding these and no path is guaranteed to take over
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SPRINT);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            if (!inLava && !lavaBelow) {
                // the latch used to outlive the grace window, so baritone never got to plan inside it. landing early hands it
                // that window, and a path planned with lava allowed walks right back through the lake. we're out, shut it now
                _outOfLava = LAVA_GRACE_TICKS;
                applyLavaRule(mod, false);
            }
        }
        // Sprint through lava + jump, it's faster
        if (HopLatch.swimKeys(inLava, lavaBelow, onGround)) {
            mod.getInputControls().hold(Input.JUMP);
            faceTheShore(mod);
            mod.getInputControls().hold(Input.SPRINT);
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            if (inLava && startHop(mod)) {
                setDebugState("Hopping out of the lava.");
                return null;
            }
        } else {
            // these are sticky, and with no path to take over they'd run us off the lip we just got onto
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SPRINT);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        return super.onTick(mod);
    }

    // a shore one or two cells away is faster jumped at than planned: a path through lava is a crawl however short, and
    // the hop is the same crawl with no turns in it. baritone owns the inputs while it has a path (PlayerMovementInput
    // reads its forced state and drops ours), so it has to let go first. lava is ~25 ticks a block, so the far end of a two
    // cell reach needs about 30 ticks to touch the wall. once started the hop is latched (see HopLatch): it keeps the inputs
    // through the surface flicker, and one that still hasn't got us out by 50 hands back to baritone for good
    private final HopLatch _hop = new HopLatch();

    private boolean startHop(AltoClef mod) {
        if (_hop.spent()) return false;
        BlockPos at = mod.getPlayer().blockPosition();
        LavaExit.Cell exit = LavaExit.nearest(at.getX(), at.getY(), at.getZ(), ItemPickupRules.terrain(mod.getWorld()), ItemPickupRules.lava(mod.getWorld()));
        if (!LavaExit.withinHop(exit, at.getX(), at.getY(), at.getZ())) return false;
        if (!_hop.begin()) return false;
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        hopInputs(mod, HopLatch.Keys.SWIM);
        return true;
    }

    // a rim two above the lava is a wall and the hop only climbs one, so when no exit is in hop range we set a block into the
    // lava beside us and hop onto that (see LavaExit.stairCell). only clicks that really went out count as a try, the placement
    // needs a free cell (our own box included) and a few ticks for the server to answer before we look again
    private static final int STAIR_TRIES = 2;
    private static final int STAIR_TICKS = 40;
    private static final int STAIR_SETTLE_TICKS = 4;
    private int _stairTries;
    private int _stairTicks;
    private int _stairSettle;

    private boolean tryStair(AltoClef mod) {
        if (_stairTries >= STAIR_TRIES || _stairTicks >= STAIR_TICKS) return false;
        BlockPos at = mod.getPlayer().blockPosition();
        ItemPickupRules.Terrain terrain = ItemPickupRules.terrain(mod.getWorld());
        ItemPickupRules.Lava lava = ItemPickupRules.lava(mod.getWorld());
        if (LavaExit.withinHop(LavaExit.nearest(at.getX(), at.getY(), at.getZ(), terrain, lava), at.getX(), at.getY(), at.getZ())) return false;
        LavaExit.Cell cell = LavaExit.stairCell(at.getX(), at.getY(), at.getZ(), terrain, lava, mod.getPlayer().getYRot());
        if (cell == null) return false;
        BlockPos target = new BlockPos(cell.x(), cell.y(), cell.z());
        baritone.Baritone b = (baritone.Baritone) mod.getClientBaritone();
        if (!b.getInventoryBehavior().selectThrowawayForLocation(false, target.getX(), target.getY(), target.getZ(), true)) return false;
        _stairTicks++;
        if (b.getPathingBehavior().isPathing()) {
            b.getPathingBehavior().forceCancel();
        }
        b.getCustomGoalProcess().onLostControl();
        // stay at the surface and hold still, a block can't go where we are leaning
        mod.getInputControls().hold(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_BACK);
        if (_stairSettle > 0) {
            _stairSettle--;
            return true;
        }
        MovementState state = new MovementState();
        MovementHelper.PlaceResult result = MovementHelper.attemptToPlaceABlock(state, b, target, false, false);
        if (result == MovementHelper.PlaceResult.NO_OPTION) return false;
        state.getTarget().getRotation().ifPresent(r -> mod.getInputControls().forceLook(r.getYaw(), r.getPitch()));
        if (mod.getPlayer().getBoundingBox().intersects(new AABB(target))) {
            // facing the cell, so back is away from it
            mod.getInputControls().hold(Input.MOVE_BACK);
            return true;
        }
        // tryPress skips the swap guard, so a click while the block is still being swapped in uses the old item
        if (result == MovementHelper.PlaceResult.READY_TO_PLACE && !b.getInventoryBehavior().handWaitingOnSwap()) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            _stairTries++;
            _stairSettle = STAIR_SETTLE_TICKS;
            _hop.rearm();
        }
        return true;
    }

    private void hopInputs(AltoClef mod, HopLatch.Keys keys) {
        boolean walkOn = keys == HopLatch.Keys.WALK_ON;
        if (walkOn) {
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SPRINT);
            // letting go of the key doesn't stop a sprint while forward is down
            mod.getPlayer().setSprinting(false);
        } else {
            mod.getInputControls().hold(Input.JUMP);
            mod.getInputControls().hold(Input.SPRINT);
        }
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        BlockPos at = mod.getPlayer().blockPosition();
        LavaExit.Cell under = walkOn ? LavaExit.under(mod.getPlayer().getX(), at.getY(), mod.getPlayer().getZ(), ItemPickupRules.terrain(mod.getWorld())) : null;
        _hopAim = hopAim(keys, _hopAim, under, () -> LavaExit.nearest(at.getX(), at.getY(), at.getZ(), ItemPickupRules.terrain(mod.getWorld()), ItemPickupRules.lava(mod.getWorld())));
        if (_hopAim == null) return;
        float yaw = LookHelper.getLookRotation(mod, new Vec3(_hopAim.x() + 0.5, _hopAim.y() + 0.5, _hopAim.z() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
    }

    private LavaExit.Cell _hopAim;

    // on the lip we walk onto the block we are hanging off, which a knockback can make a different one than we came for. asking
    // nearest from up here won't do: our middle is over the lava, so the far shore of a narrow channel can tie with it and walk us
    // back off. in the middle of a blink there's no block under us at all, so the last answer stands
    static LavaExit.Cell hopAim(HopLatch.Keys keys, LavaExit.Cell held, LavaExit.Cell under, Supplier<LavaExit.Cell> fresh) {
        if (keys != HopLatch.Keys.WALK_ON) return fresh.get();
        return under != null ? under : held;
    }

    // baritone steers while it has a path. with none (the first ticks, or a search that failed) holding forward swims
    // wherever we happened to face, which was usually deeper into the lake, so turn to the closest dry cell first. nothing
    // standable in range = a blind swim is all that is left
    private void faceTheShore(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) return;
        BlockPos at = mod.getPlayer().blockPosition();
        LavaExit.Cell exit = LavaExit.nearest(at.getX(), at.getY(), at.getZ(), ItemPickupRules.terrain(mod.getWorld()), ItemPickupRules.lava(mod.getWorld()));
        if (exit == null) return;
        float yaw = LookHelper.getLookRotation(mod, new Vec3(exit.x() + 0.5, exit.y() + 0.5, exit.z() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getFoodChain().shouldStop(false);
        mod.getBehaviour().pop();
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_BACK);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromLavaGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromLavaTask task && task._strength == _strength;
    }

    // the chain keeps the wheel for this task until we stand where the goal would have taken us: out of the lava, no lava
    // beside us, a floor under us. a lava fall that was still coming took the cell we hopped to, the chain had let go at the
    // first dry tick and the user task walked us back into it, eating on the way (practice run 116)
    public boolean clearOfLava(AltoClef mod) {
        if (_hop.active() || mod.getPlayer().isInLava()) return false;
        BlockPos at = mod.getPlayer().blockPosition();
        return new EscapeFromLavaGoal().isInGoal(at.getX(), at.getY(), at.getZ());
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // a latched hop sees its landing out, a flicker tick with the fire out is not an escape. and dry beside the lava is
        // not one either (the buddy's fork): the buddy's own water put the fire out at once, the escape counted as done with
        // lava at head height next door, the chain's hold (clearOfLava) was never asked, and the user task walked it back in
        // (attempt 171). the chain still lets go ESCAPE_HOLD_TICKS after the last lava, so a goal out of reach holds nothing
        return !_hop.active() && !mod.getPlayer().isInLava() && !mod.getPlayer().isOnFire() && clearOfLava(mod);
    }

    @Override
    protected String toHudString() {
        return "Getting out of the lava";
    }

    @Override
    protected String toDebugString() {
        return "Escaping lava";
    }

    // the air right over a lake is dry and has no lava beside it, and it is no place to be: the path to it is a one cell pillar
    // we can never finish, the lava just bobs us under the block we want to place. only a cell with something to stand on counts
    static boolean isSafeCell(boolean lava, boolean lavaAdjacent, boolean hasFloor) {
        return !lava && !lavaAdjacent && hasFloor;
    }

    private class EscapeFromLavaGoal implements Goal {

        private static boolean isLava(int x, int y, int z) {
            if (Minecraft.getInstance().level == null) return false;
            return MovementHelper.isLava(Minecraft.getInstance().level.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isLavaAdjacent(int x, int y, int z) {
            return isLava(x + 1, y, z) || isLava(x - 1, y, z) || isLava(x, y, z + 1) || isLava(x, y, z - 1)
                    || isLava(x + 1, y, z - 1) || isLava(x + 1, y, z + 1) || isLava(x - 1, y, z - 1)
                    || isLava(x - 1, y, z + 1);
        }

        private static boolean isWater(int x, int y, int z) {
            if (Minecraft.getInstance().level == null) return false;
            return MovementHelper.isWater(Minecraft.getInstance().level.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean hasFloor(int x, int y, int z) {
            var level = Minecraft.getInstance().level;
            if (level == null) return true;
            BlockPos below = new BlockPos(x, y - 1, z);
            var state = level.getBlockState(below);
            return state.getFluidState().isEmpty() && state.isFaceSturdy(level, below, Direction.UP);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return isSafeCell(isLava(x, y, z), isLavaAdjacent(x, y, z), hasFloor(x, y, z) || isWater(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isLava(x, y, z)) {
                return _strength;
            } else if (isLavaAdjacent(x, y, z)) {
                return _strength * 0.5f;
            }
            if (isWater(x, y, z)) {
                return -100;
            }
            if (!hasFloor(x, y, z)) {
                // air over the lake is not a goal, so it should not score like one or the search wanders out over the lava
                return _strength * 0.25f;
            }
            return 0;
        }
    }
}
