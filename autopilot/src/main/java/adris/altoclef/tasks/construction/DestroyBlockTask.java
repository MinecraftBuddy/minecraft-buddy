package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.util.helpers.BreakRules;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.ReachHold;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.ToolPick;
import adris.altoclef.util.helpers.ToolSwap;
import baritone.Baritone;
import baritone.utils.ToolSet;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
import adris.altoclef.util.baritone.GoalReachBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import baritone.utils.FallingColumn;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker();
    private final BlockPos _pos;
    private final BlockPos _above;
    private Task _unstuckTask = null;
    private boolean isMining;
    // once this block has been judged dangerous from above it stays "mine from the side" for the
    // whole task, so a re-plan can't quietly go back to the goal that stands on top of it.
    // volatile because the place-avoid predicate is read from baritone's thread
    private volatile boolean _fromSide;
    private boolean _dangerChecked;
    private boolean _pushedBehaviour;
    // step off -> baritone walks right back on -> step off... more than this and we give the block up
    private final StepOffGuard _stepOffs = new StepOffGuard(3);
    private final ToolSwap _toolSwap = new ToolSwap();
    // ticks the swap was held off for pathing / eating. a stuck isPathing() is the same never-swings stall
    private int _swapBlockedTicks;
    private static final int SWAP_BLOCKED_CAP = 20;
    private boolean _swapGiveUpLogged;
    // what is in the cell, for the debug line and the log. kept from the last tick it was still there, an air cell is a bad name
    private String _what = "block";
    // said once that we are swinging with something that is not a tool
    private boolean _handSaid;
    // said once that this block is not worth the time, and banned it
    private boolean _slowSaid;
    // when it was refused and when we last complained about still being asked, for the warning in refuseSlowBreak
    private int _refusedAt;
    private int _stillAskedAt = Integer.MIN_VALUE;
    private static final int STILL_ASKED_TICKS = 100;
    private static final int STILL_ASKED_REPEAT_TICKS = 600;
    // the swinging distance goal didn't work out here, stand next to it
    private boolean closeIn;
    // cells we stood in beside the block without a ray to it, so closing in tries somewhere else each time
    private final Set<BlockPos> _triedFeet = new HashSet<>();
    // how long we've been soaked with the block in reach, and the couple seconds a bob gets out of reach. see WaterBreakGuard
    private final WaterBreakGuard.Clock _wet = new WaterBreakGuard.Clock();
    private final WaterBreakGuard.ReachGrace _wetReach = new WaterBreakGuard.ReachGrace();
    // the dry spots to get to first, looked up every so often instead of every tick. null = none (or not looked yet)
    private Goal _landGoal;
    private int _landLookAge;
    private boolean _landGoalSet;
    private int _landPathAt;

    // a column hangs on the block, so the shaft under it is as bad a place to stand as the top
    private boolean _sidesOnly;
    // ticks spent waiting for a column to come down into the block, so a stack that never lands (held up by something we
    // can't see, somebody's sand cannon) doesn't park us here for good
    private int _fallWaited;
    private int _dropPending;
    private static final int FALL_WAIT_MAX = 60;
    private static final int DROP_PENDING_MAX = 10;

    // the from-side approach's own give-up clocks. idle only runs with no live path, total runs whatever the path is doing.
    // nothing but a swing that really cracks the block clears them, unlike the movement checkers, which a path (or a
    // search that just landed) keeps clearing
    private final TaskClock _sideIdle = new TaskClock();
    private final TaskClock _sideTotal = new TaskClock();
    private boolean _sideGaveUp;
    private boolean _stillAskedSaid;
    // ticks the back-up keys were held since the last real crack, in all and not per cell: a shuttle between two cells
    // would re-arm a per cell count forever
    private int _backedUp;
    // we have SNEAK and MOVE_BACK (or the portal exit's MOVE_FORWARD) down right now. the back-up only ever let go inside
    // its own two block circle, so backing out past it (or stopping just outside it) left the bot crouched there for good
    private boolean _keysDown;
    // searches that came back empty, as of the last look. stays set until a path really starts, a window of ticks would
    // lapse between two failed searches and let the keys back down for a few ticks
    private int _calcFailuresSeen = -1;
    private boolean _searchFailed;
    // when we gave up, so a parent that never reads the ban gets a warning and, after a while, another go
    private int _gaveUpAt;
    private static final int SIDE_STILL_ASKED_TICKS = 100;
    private static final int SIDE_RETRY_TICKS = 600;

    // the reach ray flickers on a corner, this keeps us swinging (or walking) through the flicker. see ReachHold
    private final ReachHold _reachHold = new ReachHold();
    // where we last had a clean ray, and at what (the vine counts). the hold keeps looking there
    private Rotation _lastReach;
    private BlockPos _lastSwingAt;
    private int _reachHoldTick = Integer.MIN_VALUE / 2;

    public DestroyBlockTask(BlockPos pos) {
        _pos = pos;
        _above = pos.above();
    }

    // true while something is falling into the block's cell, or is about to. the second half is the two ticks between the
    // block under a stack going and the stack turning into entities: the cell is open, nothing is falling yet, and a
    // task that checked "is it air" in there would call itself done with the sand still in the sky
    private boolean fallingStillComing(AltoClef mod) {
        boolean inFlight = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos);
        boolean pending = !inFlight && WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        _fallWaited = inFlight ? _fallWaited + 1 : 0;
        _dropPending = pending ? _dropPending + 1 : 0;
        return inFlight && _fallWaited <= FALL_WAIT_MAX || pending && _dropPending <= DROP_PENDING_MAX;
    }

    private Set<BlockPos> triedFeet(BlockPos feet) {
        _triedFeet.add(feet.immutable());
        return _triedFeet;
    }

    // the back-up-sneaking below holds keys across ticks, so a tick that returns early has to let go of them itself
    private void stopBackingUp(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.SNEAK);
        if (_keysDown) {
            // the portal exit's, nobody else lifts it
            mod.getInputControls().release(Input.MOVE_FORWARD);
            _keysDown = false;
        }
    }

    // the plain "not backing up this tick" case. only lets go of what we pressed: SNEAK down because baritone is
    // edge-sneaking is none of our business
    private void letGoOfOurKeys(AltoClef mod) {
        if (_keysDown) {
            stopBackingUp(mod);
        }
    }

    // decides (once, and only when the chunks around it are really loaded) if this block has to be
    // mined from the side. sticky: true stays true
    private boolean mustMineFromSide(AltoClef mod) {
        if (_fromSide) {
            return true;
        }
        if (_dangerChecked || !_pos.closerToCenterThan(mod.getPlayer().position(), 16)) {
            return false;
        }
        _dangerChecked = true;
        if (WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
            markFromSide(mod);
        }
        return _fromSide;
    }

    // air cells straight under the block, so GoalMineFromSide knows how far down the column we can stand and still swing up
    private static int openBelow(AltoClef mod, BlockPos pos) {
        int open = 0;
        while (open < GoalMineFromSide.MAX_UNDER && mod.getWorld().getBlockState(pos.below(open + 1)).isAir()) {
            open++;
        }
        return open;
    }

    private void markFromSide(AltoClef mod) {
        if (!_fromSide) {
            _fromSide = true;
            Debug.logInternal("Destroy " + _what + " at " + _pos.toShortString() + " is dangerous from above, mining it from the side");
            // whatever plain goal is running would happily stand on top of it
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
    }

    // where to stand. next to it, unless it's wrapped in vines: then the cells touching it are vines, and getting into
    // those off the ground means climbing (or hopping, the planner finds them cheap) for a log we could swing at from
    // the bottom. closeIn is for when swinging distance didn't turn out to see the block
    public static Goal pickGoal(BlockGetter world, BlockPos pos, boolean closeIn) {
        return pickGoal(world, pos, closeIn, null);
    }

    // standingIn is the cells we stood in beside the block and still couldn't hit it: GoalNear counts the one we're in as
    // arrived, so we'd stand there until the progress checker gave the block up, and leaving only the latest one would
    // just shuffle between two. null = nothing to step off of
    public static Goal pickGoal(BlockGetter world, BlockPos pos, boolean closeIn, Set<BlockPos> standingIn) {
        return pickGoal(world, pos, closeIn, standingIn, false);
    }

    // sidesOnly is a block we won't break from on top of (or under, with a column hanging on it)
    static Goal pickGoal(BlockGetter world, BlockPos pos, boolean closeIn, Set<BlockPos> standingIn, boolean sidesOnly) {
        return pickGoal(world, pos, closeIn, standingIn, sidesOnly, null);
    }

    // mayDig says which side cells the pather may dig out to stand in, for when no open one is left. null = never dig
    static Goal pickGoal(BlockGetter world, BlockPos pos, boolean closeIn, Set<BlockPos> standingIn, boolean sidesOnly, Predicate<BlockPos> mayDig) {
        if (world.getBlockState(pos.above()).getBlock() == Blocks.SNOW) {
            return new GoalBlock(pos);
        }
        if (!closeIn && vineNextTo(world, pos)) {
            return new GoalReachBlock(pos);
        }
        return closeIn && standingIn != null && !standingIn.isEmpty() ? touchingElsewhere(world, pos, standingIn, sidesOnly, mayDig) : new GoalNear(pos, 1);
    }

    // the cells touching the block that we could stand in, minus the ones we've been in. nothing under it, that's our head
    // in the block. once none are left it's the plain goal again and the progress checker takes it from there
    private static Goal touchingElsewhere(BlockGetter world, BlockPos pos, Set<BlockPos> standingIn, boolean sidesOnly, Predicate<BlockPos> mayDig) {
        List<Goal> spots = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos spot = pos.relative(side);
            if (side != Direction.DOWN && !(sidesOnly && side == Direction.UP) && !standingIn.contains(spot) && world.getBlockState(spot).getCollisionShape(world, spot).isEmpty()) {
                spots.add(new GoalBlock(spot));
            }
        }
        if (spots.isEmpty() && mayDig != null) {
            digInBeside(pos, standingIn, mayDig, spots);
        }
        return spots.isEmpty() ? new GoalNear(pos, 1) : new GoalComposite(spots.toArray(new Goal[0]));
    }

    // every open face tried (or there never was one, sand in a dune is sand on all sides): stand IN a side cell and let the
    // pather dig it out. the four sides only. never the top, and never the target's own cell, that is the "stand where the
    // column lands" spot _sidesOnly and looseColumn are there to keep us out of. a side cell isn't under the column, so this
    // is fine for a loose stack too
    private static void digInBeside(BlockPos pos, Set<BlockPos> standingIn, Predicate<BlockPos> mayDig, List<Goal> spots) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos spot = pos.relative(side);
            if (!standingIn.contains(spot) && mayDig.test(spot)) {
                spots.add(new GoalBlock(spot));
            }
        }
    }

    // standing in the goal with no ray to the block means the goal is done and nothing will move us: baritone cancels an
    // arrived goal on the spot and we hand it the same one two ticks later, forever. that's the signal to close in
    static boolean arrivedBlind(Goal goal, BlockPos feet, boolean hasRay) {
        return !hasRay && goal.isInGoal(feet);
    }

    // a side cell is only worth handing over when both halves of us may be dug out there
    private static Predicate<BlockPos> digRules(AltoClef mod) {
        return spot -> WorldHelper.pathMayDig(mod, spot) && WorldHelper.pathMayDig(mod, spot.above());
    }

    private static boolean vineNextTo(BlockGetter world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (world.getBlockState(pos.relative(side)).is(Blocks.VINE)) {
                return true;
            }
        }
        return false;
    }

    // how far around the block we look for somewhere dry, and how many we hand the pathfinder. a composite goal's
    // heuristic asks every one of them for every node, so a whole beach of them is for people who want slow code
    private static final int LAND_RADIUS = 4;
    private static final int LAND_SPOTS_MAX = 24;
    private static final int LAND_LOOK_TICKS = 20;
    private static final double EYE_HEIGHT = 1.62;

    // squares to stand on instead of swimming: solid dry ground under, two dry clear squares for us, and the block in
    // reach and in plain sight from the eyes there. closest to `from` first, so the cap drops the far ones
    public static List<BlockPos> landSpots(BlockGetter world, BlockPos target, double reach, BlockPos from) {
        List<BlockPos> spots = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double reachSq = reach * reach;
        Vec3 center = Vec3.atCenterOf(target);
        for (int dx = -LAND_RADIUS; dx <= LAND_RADIUS; dx++) {
            for (int dy = -LAND_RADIUS; dy <= LAND_RADIUS; dy++) {
                for (int dz = -LAND_RADIUS; dz <= LAND_RADIUS; dz++) {
                    int x = target.getX() + dx;
                    int y = target.getY() + dy;
                    int z = target.getZ() + dz;
                    // most of the cube is air or water and dies on the floor check
                    pos.set(x, y - 1, z);
                    if (!dryFloor(world, pos)) {
                        continue;
                    }
                    if (pos.equals(target) || (x == target.getX() && z == target.getZ() && y > target.getY())) {
                        continue; // on top of it is the one square we'd rather not be on
                    }
                    pos.set(x, y, z);
                    if (!dryAndClear(world, pos)) {
                        continue;
                    }
                    pos.set(x, y + 1, z);
                    if (!dryAndClear(world, pos)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, y + EYE_HEIGHT, z + 0.5);
                    if (eye.distanceToSqr(center) > reachSq) {
                        continue;
                    }
                    BlockHitResult hit = world.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) {
                        continue; // something is in the way, a wall isn't a place to mine from
                    }
                    spots.add(new BlockPos(x, y, z));
                }
            }
        }
        spots.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return spots.size() > LAND_SPOTS_MAX ? new ArrayList<>(spots.subList(0, LAND_SPOTS_MAX)) : spots;
    }

    private static boolean dryFloor(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isFaceSturdy(world, pos, Direction.UP);
    }

    private static boolean dryAndClear(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty();
    }

    // the dry spots as one goal for baritone, or null when there aren't any
    private static Goal landGoal(BlockGetter world, BlockPos target, double reach, BlockPos from) {
        List<BlockPos> spots = landSpots(world, target, reach, from);
        if (spots.isEmpty()) {
            return null;
        }
        Goal[] goals = new Goal[spots.size()];
        for (int i = 0; i < goals.length; i++) {
            goals[i] = new GoalBlock(spots.get(i));
        }
        return new GoalComposite(goals);
    }

    // soaked as in vanilla's mining speed is cut: feet in water and then eyes under it or no ground to stand on.
    // wading with your head up isn't slowed, so that isn't waited out
    private static boolean slowedByWater(AltoClef mod) {
        return mod.getPlayer().isInWater() && (!mod.getPlayer().onGround() || mod.getPlayer().isEyeInFluid(FluidTags.WATER));
    }

    // vanilla's getDestroyProgress already has the water and air penalties in it, so 1 or more is one tick no matter what
    private static boolean breaksInstantly(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        return state.getDestroyProgress(mod.getPlayer(), mod.getWorld(), pos) >= 1f;
    }

    private Goal landGoalNow(AltoClef mod) {
        if (_landLookAge-- <= 0) {
            _landLookAge = LAND_LOOK_TICKS;
            double reach = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance() - 0.5;
            _landGoal = landGoal(mod.getWorld(), _pos, reach, mod.getPlayer().blockPosition());
        }
        return _landGoal;
    }

    // the first thing in line between the eyes and the block, when it's ours to take down on the way and in reach. a vine,
    // or loose stuff (sand, gravel, powder) that fell into the line uninvited. the middle goes first, then the same face
    // points RotationUtils.reachable tries, so gravel that only blocks the ray to one face still counts. anything else in
    // the way (leaves, a wall) isn't ours to clear from here, and neither is loose stuff when the block itself is out of
    // reach: chewing through gravel three blocks short of a far ore gets us nowhere
    static BlockPos clearableInTheWay(BlockGetter world, Vec3 eye, BlockPos target, double reach) {
        boolean targetClose = eye.distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach;
        for (Vec3 point : rayPoints(world, target)) {
            // nothing past reach counts, so the ray doesn't need to go there. this runs every tick of a long walk
            Vec3 end = eye.distanceToSqr(point) > reach * reach ? eye.add(point.subtract(eye).normalize().scale(reach)) : point;
            BlockHitResult hit = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target) || eye.distanceTo(hit.getLocation()) > reach) {
                continue;
            }
            BlockState state = world.getBlockState(hit.getBlockPos());
            // vines too: cutting the one that's in the ray to a log we can't reach yet just takes our way up away
            if (targetClose && (state.is(Blocks.VINE) || looseInTheWay(state))) {
                return hit.getBlockPos();
            }
        }
        return null;
    }

    // the middle, then the middle of each face of the shape, in RotationUtils.reachable's order (its list is private)
    private static List<Vec3> rayPoints(BlockGetter world, BlockPos target) {
        VoxelShape shape = world.getBlockState(target).getShape(world, target);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        List<Vec3> points = new ArrayList<>(7);
        points.add(Vec3.atCenterOf(target));
        for (Vec3 side : FACE_MULTIPLIERS) {
            points.add(new Vec3(target.getX(), target.getY(), target.getZ()).add(
                    shape.min(Direction.Axis.X) * side.x + shape.max(Direction.Axis.X) * (1 - side.x),
                    shape.min(Direction.Axis.Y) * side.y + shape.max(Direction.Axis.Y) * (1 - side.y),
                    shape.min(Direction.Axis.Z) * side.z + shape.max(Direction.Axis.Z) * (1 - side.z)));
        }
        return points;
    }

    private static final Vec3[] FACE_MULTIPLIERS = {
            new Vec3(0.5, 0, 0.5), new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0), new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5), new Vec3(1, 0.5, 0.5)
    };

    // sand and friends. not an anvil (a weapon, and slow), not the egg (a click teleports it somewhere worse)
    static boolean looseInTheWay(BlockState state) {
        Block block = state.getBlock();
        return FallingColumn.isGravity(block) && !(block instanceof AnvilBlock) && !(block instanceof DragonEggBlock);
    }

    // somewhere under our feet. the gravel we stand on (or the gravel holding that up) is in the way of anything down
    // and to the side, and taking it out drops us into whatever opened up the face we wanted
    static boolean underBox(BlockPos pos, AABB box) {
        return pos.getY() < box.minY
                && pos.getX() < box.maxX && pos.getX() + 1 > box.minX
                && pos.getZ() < box.maxZ && pos.getZ() + 1 > box.minZ;
    }

    // the world side of a loose block in the way: breaking it has to be as fine as breaking any sand. vines go in one
    // swing and drop nothing on anyone, those were never asked
    private static boolean mayClearInTheWay(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.is(Blocks.VINE)) {
            return true;
        }
        return !underBox(pos, mod.getPlayer().getBoundingBox())
                && state.getDestroySpeed(mod.getWorld(), pos) >= 0
                && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)
                && WorldHelper.fallingBlockSafeToBreak(pos)
                && !(WorldHelper.letsFallingLoose(mod.getWorld(), pos) && WorldHelper.breakingDropsOnUs(mod, pos))
                && !WorldHelper.tooSlowToBreak(mod, pos);
    }

    /**
     * Generates the surrounding BlockPos based on the given position.
     *
     * @param pos The center BlockPos
     * @return An array of surrounding BlockPos
     */
    private static BlockPos[] generateSides(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        return new BlockPos[]{
                new BlockPos(x + 1, y, z),
                new BlockPos(x - 1, y, z),
                new BlockPos(x, y, z + 1),
                new BlockPos(x, y, z - 1),
                new BlockPos(x + 1, y, z - 1),
                new BlockPos(x + 1, y, z + 1),
                new BlockPos(x - 1, y, z - 1),
                new BlockPos(x - 1, y, z + 1)
        };
    }

    /**
     * Checks if the block at the specified position is annoying.
     *
     * @param mod the AltoClef instance
     * @param pos the position to check
     * @return true if the block is annoying, false otherwise
     */
    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    /**
     * Finds the position where the player is stuck in a block.
     *
     * @param mod the mod instance
     * @return the position where the player is stuck, or null if not stuck
     */
    private BlockPos stuckInBlock(AltoClef mod) {
        // Check if player is stuck in their current position
        if (isAnnoying(mod, mod.getPlayer().blockPosition())) {
            return mod.getPlayer().blockPosition();
        }

        // Check if player is stuck when moving up
        if (isAnnoying(mod, mod.getPlayer().blockPosition().above())) {
            return mod.getPlayer().blockPosition().above();
        }

        // Check for stuck positions in the sides of the player's current position
        for (BlockPos check : generateSides(mod.getPlayer().blockPosition())) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }

        // Check for stuck positions in the sides of the player's position when moving up
        for (BlockPos check : generateSides(mod.getPlayer().blockPosition().above())) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }

        return null; // Player is not stuck
    }

    /**
     * Gets a task to unstick a fence.
     *
     * @return the task to unstick the fence, or null if an exception is caught
     */
    private Task getFenceUnstuckTask() {
        try {
            // Create a safe random shimmy task
            Task task = createSafeRandomShimmyTask();

            // Return the task
            return task;
        } catch (Exception e) {
            e.printStackTrace();
            // Handle the exception or rethrow it as needed
            return null;
        }
    }

    /**
     * Creates and returns a new instance of SafeRandomShimmyTask.
     *
     * @return a new SafeRandomShimmyTask instance
     */
    private Task createSafeRandomShimmyTask() {
        return new SafeRandomShimmyTask();
    }

    /**
     * This method is called when the AltoClef mod starts.
     * It cancels any ongoing pathing behavior, resets move checker and stuck check,
     * and handles the item stack in the cursor slot.
     * If the cursor stack is not empty, it calls handleNonEmptyCursorStack,
     * otherwise, it closes the screen.
     */
    @Override
    protected void onStart(AltoClef mod) {
        // Cancel any ongoing pathing behavior.
        mod.getClientBaritone().getPathingBehavior().forceCancel();

        // Reset move checker and stuck check.
        _moveChecker.newTarget();
        stuckCheck.newTarget();
        _stepOffs.reset();
        _reachHold.reset();
        _lastReach = null;
        _lastSwingAt = null;
        _triedFeet.clear();
        _wet.reset();
        _wetReach.reset();
        // the land wait is keyed off ticksWet, so it starts over with the clock or it would sit out a stale retry gap
        _landGoalSet = false;
        _landPathAt = 0;
        _landGoal = null;
        _landLookAge = 0;

        // never scaffold onto the square above the block we're here to break. only bites once
        // _fromSide is set, so ordinary blocks path exactly like before
        if (!_pushedBehaviour && mod.getBehaviour() != null) {
            mod.getBehaviour().push();
            _pushedBehaviour = true;
            mod.getBehaviour().avoidBlockPlacing(pos -> _fromSide && _above.equals(pos));
        }

        // Get the item stack in the cursor slot.
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();

        // If the cursor stack is not empty, handle it.
        if (!cursorStack.isEmpty()) {
            handleNonEmptyCursorStack(mod, cursorStack);
        } else {
            // If the cursor stack is empty, close the screen.
            StorageHelper.closeScreen();
        }
    }

    /**
     * Handles the non-empty cursor stack by performing various actions.
     * @param mod the AltoClef mod
     * @param cursorStack the cursor stack
     */
    private void handleNonEmptyCursorStack(AltoClef mod, ItemStack cursorStack) {
        // Get the slot that can fit the cursor stack in the player inventory and click it to pick up the item.
        mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false)
                .ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));

        // If the cursor stack can be thrown away, click an undefined slot to pick up the item.
        if (ItemHelper.canDropStack(mod, cursorStack)) {
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        }

        // Get the garbage slot and click it to pick up the item.
        StorageHelper.getGarbageSlot(mod)
                .ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));

        // Click an undefined slot to pick up the item.
        mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
    }

    // the clocks and the call for one tick spent trying to get beside a block we won't mine from on top of
    // a block that is in reach but not being swung at yet (eating, not on the ground) is not a failure to get beside it,
    // so those ticks are not billed
    private FromSideRules.Verdict sideVerdict(AltoClef mod, boolean cracking, boolean inRange) {
        var pathing = mod.getClientBaritone().getPathingBehavior();
        if (!inRange) {
            _sideTotal.tick();
            if (!pathing.isPathing()) {
                _sideIdle.tick();
            }
        }
        int failures = pathing.calcFailures();
        if (_calcFailuresSeen >= 0 && failures > _calcFailuresSeen) {
            _searchFailed = true;
        }
        _calcFailuresSeen = failures;
        if (pathing.isPathing()) {
            _searchFailed = false;
        }
        return FromSideRules.decide(_sideIdle.spentSeconds(), _sideTotal.spentSeconds(), pathing.isPathing(),
                _searchFailed, cracking, _backedUp);
    }

    private boolean crackingThis(AltoClef mod) {
        return mod.getControllerExtras().isBreakingBlock() && _pos.equals(mod.getControllerExtras().getBreakingBlockPos());
    }

    // fails the way a failed break does: the ban is what the parent reads (a source whose lid is banned drops out of
    // CollectBucketLiquidTask's pick), and then we stand down instead of swinging at the wall
    private Task giveUpOnSide(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_sideGaveUp && now - _gaveUpAt >= SIDE_RETRY_TICKS) {
            // somebody keeps asking for this block (a parent with a fixed target never reads the ban). it is a different
            // world by now, try again from scratch
            _sideGaveUp = false;
            _sideIdle.reset();
            _sideTotal.reset();
            _backedUp = 0;
            _searchFailed = false;
            return null;
        }
        if (!_sideGaveUp) {
            _sideGaveUp = true;
            _gaveUpAt = now;
            _stillAskedSaid = false;
            long spent = Math.round(Math.max(_sideIdle.spentSeconds(), _sideTotal.spentSeconds()) * 20);
            Debug.logInternal("destroy: could not get beside " + _what + " at " + _pos.toShortString() + " to mine it from the side, giving up after "
                    + spent + "t");
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getBlockTracker().requestBlockUnreachable(_pos, 0, "couldn't get beside it to mine it from the side");
        }
        if (!_stillAskedSaid && now - _gaveUpAt >= SIDE_STILL_ASKED_TICKS) {
            _stillAskedSaid = true;
            Debug.logWarning("destroy: still asked for " + _what + " at " + _pos.toShortString() + " " + (now - _gaveUpAt) + "t after giving up on getting beside it");
        }
        setDebugState("Giving up on " + _what + " at " + _pos.toShortString() + ", can't get beside it", "Couldn't get beside it");
        isMining = false;
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        stopBackingUp(mod);
        return null;
    }

    private Task refuseSlowBreak(AltoClef mod, BlockState there) {
        String time = BreakRules.describe(WorldHelper.bagOf(mod), there);
        int now = WorldHelper.getTicks();
        if (!_slowSaid) {
            _slowSaid = true;
            _refusedAt = now;
            Debug.logMessage("not breaking " + _what + " at " + _pos.toShortString() + ", " + time);
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getBlockTracker().requestBlockUnreachable(_pos, 0, "takes " + time + " to break");
        }
        // idling after the ban is a way to stand still, so a parent that never reads the ban gets told about once in a
        // while. (Task keeps its parent private, so the line can't name it)
        if (now - _refusedAt >= STILL_ASKED_TICKS && (_stillAskedAt == Integer.MIN_VALUE || now - _stillAskedAt >= STILL_ASKED_REPEAT_TICKS)) {
            _stillAskedAt = now;
            Debug.logWarning("destroy: still asked for " + _what + " at " + _pos.toShortString() + " " + (now - _refusedAt) + "t after refusing it");
        }
        setDebugState("Refusing " + _what + " at " + _pos.toShortString() + ", too slow (" + time + ")", "Skipping a block that takes too long");
        isMining = false;
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        stopBackingUp(mod);
        return null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        BlockState there = mod.getWorld().getBlockState(_pos);
        if (!there.isAir()) {
            _what = BuiltInRegistries.BLOCK.getKey(there.getBlock()).getPath();
        }
        // Check if there is white wool at the specified position
        if (there.getBlock() == Blocks.WOOL.white()) {
            // Iterate over all entities in the world
            Iterable<Entity> entities = mod.getWorld().entitiesForRendering();
            for (Entity entity : entities) {
                // Check if the entity is a PillagerEntity and is within a distance of 144 blocks from the position
                if (entity instanceof Pillager && _pos.closerToCenterThan(entity.position(), 144)) {
                    // Request the block at the position to be marked as unreachable
                    mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
                }
            }
        }

        // Reset the move checker if Baritone is currently pathing
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _moveChecker.reset();
        }

        // Check if the player is in a Nether portal
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                PortalExit.step(mod);
                _keysDown = true;
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
                _keysDown = false;
            }
        } else if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            _keysDown = false;
        }

        // Check if there is an active unstuck task and the player is stuck in a block
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.", "Stuck, wiggling free");
            stuckCheck.reset();
            // Release control of Baritone's custom goal process and explore process
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }

        // a break that takes minutes is not a break. the ban is what a failed break ends in anyway (the progress checker
        // below does the same after its timeout), this just doesn't make us swing at it until then. after the portal exit
        // and the wiggle above, those are still ours to do. the block our head is buried in is the one exception: digging
        // slowly beats standing in it
        if (!there.isAir() && !_pos.equals(WorldHelper.buriedInFallenBlock(mod))
                && BreakRules.tooSlow(WorldHelper.bagOf(mod), there)) {
            return refuseSlowBreak(mod, there);
        }

        if (_sideGaveUp) {
            return giveUpOnSide(mod);
        }

        // Check if the move checker or the stuck check failed
        // asked once a tick: the aim allowance counts calls
        boolean moveOk = _moveChecker.check(mod, _pos);
        if (!moveOk || !stuckCheck.check(mod, _pos)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }

        // Check if the move checker failed
        if (!moveOk) {
            _moveChecker.reset();
            // Request the block at the position to be marked as unreachable
            mod.getBlockTracker().requestBlockUnreachable(_pos);
        }

        // Check if the block above the position is not solid, the player is above the position,
        // and the player is within a distance of 0.89 blocks from the position
        boolean steppingOff = false;
        if (!WorldHelper.isSolid(mod, _pos.above()) && mod.getPlayer().position().y > _pos.getY() && _pos.closerToCenterThan(mod.getPlayer().onGround() ? mod.getPlayer().position() : mod.getPlayer().position().add(0, -1, 0), 0.89)) {
            if (_fromSide || WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
                markFromSide(mod);
                steppingOff = true;
            }
        }
        if (_stepOffs.tick(steppingOff)) {
            // we keep ending up on top of it. a different ore is a better idea than a tenth lap
            Debug.logInternal("Destroy " + _what + " at " + _pos.toShortString() + ": stepped off it too many times, giving it up");
            mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
        }
        if (steppingOff) {
            setDebugState("It's dangerous to break as we're right above it, moving away and trying again.", "Stepping off it first");
            return new RunAwayFromPositionTask(3, _pos.getY(), _pos);
        }

        // sand, gravel and the like on top of it (or a stalactite under it) come down the hole when it goes. we never
        // swing from where that lands on us, and we never swing at a block while the stack is still falling into it
        boolean looseColumn = WorldHelper.letsFallingLoose(mod.getWorld(), _pos);
        // already buried in it is the one case where the answer is to dig, there is no better place to be
        boolean buried = _pos.equals(WorldHelper.buriedInFallenBlock(mod));
        boolean dropsOnUs = looseColumn && !buried && WorldHelper.breakingDropsOnUs(mod, _pos);
        // buried, waiting is just suffocating with extra steps. dig now, the rest lands in the hole we make
        if (!buried && fallingStillComing(mod)) {
            setDebugState("Waiting for the falling blocks to land.", "Letting the sand settle");
            stuckCheck.reset();
            _moveChecker.reset();
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            stopBackingUp(mod);
            return null;
        }
        Optional<Rotation> reach = LookHelper.getReach(_pos);
        if (reach.isEmpty() && buried) {
            // the ray from an eye inside the block can come back empty, and the "get to block" branch would then walk us off
            // toward a side goal with our head still in the sand. we're in it, it's in reach, just look at it
            reach = Optional.of(RotationUtils.calcRotationFromVec3d(mod.getPlayer().getEyePosition(), Vec3.atCenterOf(_pos),
                    mod.getClientBaritone().getPlayerContext().playerRotations()));
        }
        BlockPos swingAt = _pos;
        if (reach.isEmpty()) {
            // vines count as a hit for the ray even though you walk through them, so a log behind a curtain of them is
            // "out of reach" until the curtain is gone. they break instantly, so take the one in the way down first.
            // same for gravel that slid in front of it: no spot ever gets a ray through, so the stall clock was all we had
            BlockPos inTheWay = clearableInTheWay(mod.getWorld(), mod.getPlayer().getEyePosition(), _pos, mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance());
            Optional<Rotation> wayReach = inTheWay == null || !mayClearInTheWay(mod, inTheWay) ? Optional.empty() : LookHelper.getReach(inTheWay);
            if (wayReach.isPresent()) {
                reach = wayReach;
                swingAt = inTheWay;
            }
        }
        // the ray is checked against face points but the distance against the center, so a ray that hit counts as close
        // enough on its own, or a block right at the edge of reach would never get swung at
        double reachDist = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance();
        boolean inDistance = reach.isPresent() || mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(_pos)) <= reachDist * reachDist;
        // a tick that returned early (stepping off, waiting on sand, unsticking) didn't feed the hold, and the look it
        // remembers is from wherever we stood before all that. start over instead of holding on to it
        int now = WorldHelper.getTicks();
        if (now - _reachHoldTick > 1) {
            _reachHold.reset();
            _lastReach = null;
        }
        _reachHoldTick = now;
        // buried can't wait for three rays in a row, that's three ticks of suffocating
        boolean inRange = _reachHold.step(reach.isPresent(), mod.getClientBaritone().getPathingBehavior().isPathing() && !buried, inDistance);
        if (reach.isPresent()) {
            _lastReach = reach.get();
            _lastSwingAt = swingAt;
        }
        // no ray this tick but the hold says we're still in: keep the last look and swing at what it was on
        // (unless what it was on is gone, a vine we already cut down is nothing to stare at)
        boolean holding = inRange && reach.isEmpty() && _lastReach != null && !mod.getWorld().getBlockState(_lastSwingAt).isAir();
        if (holding) {
            swingAt = _lastSwingAt;
        }
        inRange = inRange && (reach.isPresent() || holding);
        // breaking while soaked is up to 25x slower, so we get somewhere dry first unless that's the worse deal
        // the top of a bob still counts as wet (see WaterBreakGuard.Clock), so a hop out of the water doesn't restart
        // the wait, and once we gave up waiting the dry tick at the top keeps swinging instead of going off to path
        boolean soaked = _wet.step(slowedByWater(mod), mod.getPlayer().onGround(), inRange);
        if (!soaked) {
            // out of the water, or only wading in it. the spots get looked up fresh next time
            _landLookAge = 0;
            _landGoal = null;
        }
        boolean waitForLand = false;
        boolean mayBreak = false;
        if (inRange) {
            if (soaked) {
                double air = mod.getPlayer().getAirSupply() / (double) Math.max(1, mod.getPlayer().getMaxAirSupply());
                mayBreak = WaterBreakGuard.mayBreak(mod.getPlayer().onGround(), true, breaksInstantly(mod, swingAt), _wet.ticksWet(), air, landGoalNow(mod) != null);
                waitForLand = !mayBreak;
            } else {
                mayBreak = WaterBreakGuard.mayBreak(mod.getPlayer().onGround(), false, false, 0, 1, false);
            }
        }
        if (!waitForLand) {
            _landGoalSet = false;
        }
        if (looseColumn && !_sidesOnly) {
            // whatever plain goal is running would happily stand under it
            _sidesOnly = true;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
        // buried skips the ground and water patience too: in the air, on a ladder or wet, the head still has to come out
        if (inRange && (mayBreak || buried) && !dropsOnUs
                && (buried || !mod.getFoodChain().needsToEat()) && !WorldHelper.isInNetherPortal(mod)
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            setDebugState("Block in range, mining...");
            // swinging for real, not a glimpse of a ray on the way past: the spots we gave up on can be tried again
            _triedFeet.clear();
            stuckCheck.reset();
            _stepOffs.reset();
            if (crackingThis(mod)) {
                _sideIdle.reset();
                _sideTotal.reset();
                _backedUp = 0;
            }
            isMining = true;
            _wetReach.reset();
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            _keysDown = false;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            Rotation look = reach.orElse(_lastReach);
            if (!LookHelper.isLookingAt(mod, look)) {
                LookHelper.lookAt(mod, look);
            }
            BlockState state = mod.getWorld().getBlockState(swingAt);
            Optional<Slot> bestToolSlot = StorageHelper.getBestToolSlot(mod, state);
            Slot currentEquipped = PlayerSlot.getEquipSlot();
            // if baritone is running, only accept tools OUTSIDE OF HOTBAR!
            // Baritone will take care of tools inside the hotbar.
            // (and not with a crack on the block: a different item in hand starts the break over)
            boolean crackedAlready = MineStick.toolSwapWouldReset(mod.getControllerExtras().isBreakingBlock(),
                    mod.getControllerExtras().getBreakingBlockPos(), mod.getControllerExtras().getBreakingBlockProgress(), swingAt);
            ItemStack held = StorageHelper.getItemStackInSlot(currentEquipped);
            // not while a fight or a run is committed (or the heal-up hold is on): a resumed task swapping the sword out for a
            // pick mid fight is how the weapon ends up in the bag
            boolean combatHolds = mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE || mod.getMobDefenseChain().isRecovering();
            boolean handIsATool = ItemHelper.isTool(held.getItem()) || held.getItem() == Items.SHEARS;
            // the two quiet ways to end up swinging a bucket at a wall: nothing in the bag does better, or a crack is in progress
            // (an empty hand isn't news, that is every block at the start of a run)
            if (!_handSaid && (bestToolSlot.isEmpty() || crackedAlready) && !handIsATool && !held.isEmpty() && !breaksInstantly(mod, swingAt)) {
                _handSaid = true;
                String why = crackedAlready ? "a swap would reset the crack" : "nothing in the bag does better";
                Debug.logInternal("mining " + _what + " at " + _pos.toShortString() + " with " + ToolPick.nameOf(held) + " in hand (" + why + ")");
            }
            if (_handSaid && !handIsATool && !held.isEmpty()) {
                setDebugState("Block in range, mining... (swinging " + ToolPick.nameOf(held) + ")");
            }
            // a block that goes in one swing doesn't care what is in the hand
            if (bestToolSlot.isPresent() && !crackedAlready && !breaksInstantly(mod, swingAt)) {
                ItemStack best = StorageHelper.getItemStackInSlot(bestToolSlot.get());
                double heldSpeed = ToolSet.calculateSpeedVsBlock(held, state);
                double bestSpeed = ToolSet.calculateSpeedVsBlock(best, state);
                // by item and by speed, not by slot: the slot object is new every tick, and two tools that are equally
                // fast would trade places forever (see ToolSwap). a best that is only the fastest wrong tool (iron on
                // obsidian) has to be strictly faster than the hand, "the hand is wrong too" is not a reason to swap
                // (or the other way round, the wear chain wants a slower tool that is next to die. decided once per block, the
                // crackedAlready guard above keeps it from swapping out from under a half broken one)
                boolean faster = ToolSwap.worthSwapping(false, held.getItem(), held.isCorrectToolForDrops(state), heldSpeed, best.getItem(), bestSpeed)
                        && (best.isCorrectToolForDrops(state) || bestSpeed > heldSpeed);
                boolean chained = !faster && Baritone.settings().toolWearChain.value
                        && ToolSwap.worthSwappingDown(held.getItem(), ToolPick.optionOf(held, state), best.getItem(), ToolPick.optionOf(best, state));
                if (faster || chained) {
                    boolean isAllowedToManage = !mod.getClientBaritone().getPathingBehavior().isPathing()
                            && !mod.getFoodChain().isTryingToEat() && !combatHolds;
                    if (!isAllowedToManage && _swapBlockedTicks++ < SWAP_BLOCKED_CAP) {
                        return null;
                    }
                    Item bestToolItem = best.getItem();
                    // three misses and we swing with what we hold, a hand we can't change is no reason to never swing
                    if (isAllowedToManage && _toolSwap.mayTry(bestToolItem)) {
                        // the log file only: mining swaps pick and shovel every other block and chat drowned in it
                        Debug.logInternal("Found better tool in inventory, equipping " + bestToolItem.getDescriptionId() + ".");
                        if (mod.getSlotHandler().forceEquipItem(bestToolItem)) {
                            _toolSwap.landed();
                        } else {
                            _toolSwap.missed(bestToolItem);
                        }
                        return null;
                    }
                    // the log the next stall needs: what we mined with, what we wanted, where it was
                    if (!_swapGiveUpLogged) {
                        _swapGiveUpLogged = true;
                        Debug.logInternal("tool swap given up on " + state.getBlock().getDescriptionId() + ": holding " + held.getItem().getDescriptionId()
                            + ", wanted " + bestToolItem.getDescriptionId() + " from slot " + bestToolSlot.get().getWindowSlot());
                    }
                } else {
                    _toolSwap.landed();
                }
            }
            if (bestToolSlot.isEmpty() && !combatHolds && net.minecraft.client.Minecraft.getInstance().gui.screen() == null && !crackedAlready && held.isDamageableItem() && !breaksInstantly(mod, swingAt)) {
                // no tool in the bag beats the hand here, so the pick in it is only taking wear
                var inv = mod.getPlayer().getInventory().getNonEquipmentItems();
                boolean[] empty = new boolean[9];
                boolean[] damageable = new boolean[9];
                for (int i = 0; i < 9; i++) {
                    empty[i] = inv.get(i).isEmpty();
                    damageable[i] = inv.get(i).isDamageableItem();
                }
                int calm = MineStick.calmSlot(empty, damageable, mod.getPlayer().getInventory().getSelectedSlot());
                if (calm >= 0) {
                    mod.getClientBaritone().getInventoryBehavior().selectHotbar(calm, "destroy");
                }
            }
            // holding on an old look: the crosshair might be on the dirt in front now, and cracking that is the exact thing
            // this is here to stop. no click until it's back on the block
            boolean click = !holding || LookHelper.isLookingAt(mod, swingAt);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, click);
        } else {
            boolean fromSide = mustMineFromSide(mod);
            if (dropsOnUs) {
                setDebugState("Breaking it from here would bring the column down on us, stepping aside.", "Mining it from the side");
            } else if (waitForLand) {
                setDebugState("Getting out of the water before mining", "Getting out of the water first");
            } else if (fromSide) {
                setDebugState("Getting to block...", "Mining it from the side");
            } else {
                setDebugState("Getting to block...", "Walking to " + HudText.pos(_pos));
            }
            // we were swinging in the water (that only happens when WaterBreakGuard ran out of patience) and the current
            // took the block out of reach: it isn't worth chasing across a lake, let someone pick another. a bob that
            // dips out of reach for a moment isn't that, so it gets a couple seconds first (WaterBreakGuard.ReachGrace).
            // when it's still in reach and we just stopped for dry land that's not a reason to give up on it
            // soaked too: the dry top of a bob is out of the water but not out of the episode, it shouldn't wipe the count
            if (_wetReach.step(isMining, !inRange && (soaked || mod.getPlayer().isInWater()))) {
                mod.getBlockTracker().requestBlockUnreachable(_pos);
            }
            isMining = false;
            if (waitForLand) {
                // we're doing what we meant to, the progress checkers don't get a say. the clock in WaterBreakGuard is the way out
                _moveChecker.reset();
                stuckCheck.reset();
                // swimming around with the button held down is the exact thing we're not doing
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                stopBackingUp(mod);
                // the block is in reach, so whatever goal we had is probably satisfied right where we float and wouldn't budge
                if (!_landGoalSet) {
                    _landGoalSet = true;
                    _landPathAt = _wet.ticksWet();
                    mod.getClientBaritone().getBuilderProcess().onLostControl();
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_landGoal);
                } else if (!mod.getClientBaritone().getCustomGoalProcess().isActive() && _wet.ticksWet() - _landPathAt >= LAND_LOOK_TICKS) {
                    // that path ended and we're still wet (no route, or the current won). try again, but not every tick
                    _landPathAt = _wet.ticksWet();
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_landGoal);
                }
                return null;
            }
            // the from-side approach has its own way out. a search that finds nothing is not "not pathing, so back up", and
            // ten seconds without a swing or a path is not a reason to keep trying
            FromSideRules.Verdict side = fromSide ? sideVerdict(mod, crackingThis(mod), inRange) : FromSideRules.Verdict.KEEP_TRYING;
            if (side == FromSideRules.Verdict.GIVE_UP) {
                return giveUpOnSide(mod);
            }
            boolean isCloseToMoveBack = _pos.closerToCenterThan(mod.getPlayer().position(), 2);
            // the hold can start while the first search is still running and carry us out past two blocks, so letting go
            // can't live inside the distance check. every tick that isn't backing up lets go of what we pressed
            if (side == FromSideRules.Verdict.KEEP_TRYING && isCloseToMoveBack && !mod.getClientBaritone().getPathingBehavior().isPathing()
                    && !mod.getPlayer().isInWater() && !mod.getFoodChain().needsToEat()) {
                mod.getInputControls().hold(Input.MOVE_BACK);
                mod.getInputControls().hold(Input.SNEAK);
                _keysDown = true;
                _backedUp++;
            } else {
                letGoOfOurKeys(mod);
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                // GoalNear and GoalBlock both count standing on top of the block as arrived, which is the
                // one place we refuse to break it from (and the step-off task shoves us off of)
                Goal goal;
                BlockPos feet = mod.getClientBaritone().getPlayerContext().playerFeet();
                if (fromSide || looseColumn) {
                    // with a stack hanging on it the shaft two below is out as well, it comes down that shaft
                    goal = new GoalMineFromSide(_pos, !looseColumn, openBelow(mod, _pos));
                    if (goal.isInGoal(feet) && reach.isEmpty()) {
                        // "from the side" only looks at offsets, so a spot by the trunk with leaves in the way (or a cell
                        // across a wall's corner) counts as arrived and the goal finishes instantly, every tick. same way out
                        // as the branch below: walk up and touch it
                        closeIn = true;
                        // never the top or the shaft here. every open side tried means digging into a solid one, and
                        // when the rules say no digging either (GoalNear back) the side goal stays and the clocks end it
                        Goal touching = pickGoal(mod.getWorld(), _pos, true, triedFeet(feet), true, digRules(mod));
                        if (!(touching instanceof GoalNear)) {
                            goal = touching;
                        }
                    }
                } else {
                    goal = pickGoal(mod.getWorld(), _pos, closeIn, closeIn && reach.isEmpty() ? triedFeet(feet) : null, false, digRules(mod));
                    if (arrivedBlind(goal, feet, reach.isPresent())) {
                        // already there and it still can't be hit (leaves, other logs, a wall, a ray that only grazes an
                        // edge), so touch it like before. the reach hold already let go to get us here, that's the debounce
                        closeIn = true;
                        goal = pickGoal(mod.getWorld(), _pos, true, triedFeet(feet), false, digRules(mod));
                    }
                }
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            }
        }
        return null;
    }

    /**
     * This method is called when the task is interrupted.
     * It cancels Baritone pathing and releases input controls if in game.
     *
     * @param mod The AltoClef mod instance
     * @param interruptTask The interrupting task
     */
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Cancel Baritone pathing
        mod.getClientBaritone().getPathingBehavior().forceCancel();

        if (_pushedBehaviour) {
            _pushedBehaviour = false;
            if (mod.getBehaviour() != null) {
                mod.getBehaviour().pop();
            }
        }

        // If not in game, return
        if (!AltoClef.inGame()) {
            return;
        }

        // Release input controls
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        _keysDown = false;
    }

    /**
     * Check if the specified position is finished.
     *
     * @param mod The AltoClef instance
     * @return True if the block at the specified position is air, false otherwise.
     */
    @Override
    public boolean isFinished(AltoClef mod) {
        // Check if the world or position is null
        if (mod.getWorld() == null || _pos == null) {
            return false;
        }
        // Get the block state at the specified position and check if it's air
        BlockState blockState = mod.getWorld().getBlockState(_pos);
        if (!blockState.isAir()) {
            return false;
        }
        // air with a stack about to land in it is not a cleared block, it's a block that is about to have sand in it.
        // onTick owns the counters, so a column that never lands runs out of patience there
        boolean inFlight = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos);
        boolean pending = !inFlight && WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        return !(inFlight && _fallWaited <= FALL_WAIT_MAX || pending && _dropPending <= DROP_PENDING_MAX);
    }

    /**
     * Overrides the isEqual method to compare with another Task object.
     *
     * @param other The other Task object to compare with.
     * @return true if the other object is a DestroyBlockTask and has the same position, false otherwise.
     */
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DestroyBlockTask destroyBlockTask) {
            return Objects.equals(destroyBlockTask._pos, _pos);
        }
        return false;
    }

    /**
     * Returns a debug string describing the block destruction action.
     * If the position is known, it includes the position in the string, otherwise it indicates an unknown position.
     */
    @Override
    protected String toDebugString() {
        if (_pos != null) {
            return "Destroy " + _what + " at " + _pos.toShortString();
        } else {
            return "Destroy block at unknown position";
        }
    }

    @Override
    protected String toHudString() {
        try {
            Level level = Minecraft.getInstance().level;
            if (level != null && _pos != null && level.isLoaded(_pos)) {
                return "Breaking " + HudText.block(level.getBlockState(_pos).getBlock());
            }
        } catch (Throwable ignored) {
        }
        return "Breaking the block at " + HudText.pos(_pos);
    }
}
