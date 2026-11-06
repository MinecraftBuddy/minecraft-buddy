/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

// one class for both halves: a grab is a jump from floor onto a ladder or vine, a leap is climbing out sideways off one.
// both just do what ClimbJump.decide says, which is the same thing its rollout did when it told A* this works
public class MovementClimb extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int LEAP_MAX = 4;

    private final boolean grab;
    private final Direction direction, side;
    private final int dist, dy;
    // recalculateCost runs every tick and refreshes this, so someone breaking a block mid run up changes the plan
    private ClimbJump.Shape shape;
    private ClimbJump.Control control;
    // running is the grab's run up (we're off the staging spot), failedRuns the ones that ended without a catch
    private boolean running, sneaked;
    private int failedRuns, stuckTicks, stageTicks;

    private MovementClimb(IBaritone baritone, BetterBlockPos src, Direction dir, ClimbJump.Shape shape) {
        super(baritone, src, shape == null ? src : src.relative(dir, shape.dist()).above(shape.dy()), EMPTY);
        this.grab = shape != null && shape.grab();
        this.direction = dir;
        this.side = dir.getClockWise();
        this.dist = shape == null ? 0 : shape.dist();
        this.dy = shape == null ? 0 : shape.dy();
        this.shape = shape;
        this.control = new ClimbJump.Control(false);
    }

    // the executor rewinds and replays movements it already ran, all of this is per run. shape is refreshed by
    // calculateCost so it stays
    @Override
    public void reset() {
        super.reset();
        control = new ClimbJump.Control(false);
        running = sneaked = false;
        failedRuns = stuckTicks = stageTicks = 0;
    }

    public static MovementClimb grab(CalculationContext context, BetterBlockPos src, Direction dir) {
        // dest == src never matches anything in Path.runBackwards, and calculateCost can't find a shape. so it's inert
        return new MovementClimb(context.getBaritone(), src, dir, grabShape(context, src.x, src.y, src.z, dir));
    }

    public static MovementClimb leap(CalculationContext context, BetterBlockPos src, Direction dir) {
        return new MovementClimb(context.getBaritone(), src, dir, leapShape(context, src.x, src.y, src.z, dir));
    }

    public static void grab(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        result(grabShape(context, x, y, z, dir), x, y, z, dir, context, res);
    }

    public static void leap(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        result(leapShape(context, x, y, z, dir), x, y, z, dir, context, res);
    }

    private static void result(ClimbJump.Shape shape, int x, int y, int z, Direction dir, CalculationContext context, MutableMoveResult res) {
        if (shape == null) {
            return;
        }
        res.x = x + dir.getStepX() * shape.dist();
        res.y = y + shape.dy();
        res.z = z + dir.getStepZ() * shape.dist();
        res.cost = cost(context, shape);
    }

    private static double cost(CalculationContext context, ClimbJump.Shape shape) {
        // a parkour jump's worth of getting there, plus a couple of blocks of walk for the catch and settling in (grab) or
        // for climbing out and the fall (leap). the fall part is what a fall of that many blocks costs on its own
        double base = (shape.dist() + 2) * WALK_ONE_BLOCK_COST + context.jumpPenalty;
        if (!shape.grab() && shape.dy() < 0) {
            base += FALL_N_BLOCKS_COST[-shape.dy()];
        }
        return context.biasJump(base);
    }

    // ---- looking for a shape. most nodes aren't anywhere near a ladder, so everything that can say no cheaply does first

    private static ClimbJump.Shape grabShape(CalculationContext context, int x, int y, int z, Direction dir) {
        if (!context.allowParkour || !context.allowClimbJumps) {
            return null;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.maxY) {
            return null;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ();
        // same two rejects parkour opens with, they're the common case
        if (!MovementHelper.fullyPassable(context, x + dx, y, z + dz)) {
            return null;
        }
        if (MovementHelper.canWalkOn(context, x + dx, y - 1, z + dz)) {
            return null; // we'd just walk
        }
        if (!plainFloor(context, x, y - 1, z) || !context.get(x, y, z).getFluidState().isEmpty() || !clear(context, x, y, z)) {
            return null;
        }
        int maxDist = context.canSprint ? ClimbJump.MAX_DIST : ClimbJump.MAX_DIST - 1;
        for (int i = 2; i <= maxDist; i++) {
            int gx = x + dx * (i - 1), gz = z + dz * (i - 1);
            // anything we'd walk onto along the way isn't a gap, and the gap has to be open all the way up
            if (i > 2 && MovementHelper.canWalkOn(context, gx, y - 1, gz)) {
                break;
            }
            if (!clear(context, gx, y, gz)) {
                break;
            }
            int cx = x + dx * i, cz = z + dz * i;
            for (int d = 0; d <= 1; d++) {
                BlockState state = context.get(cx, y + d, cz);
                if (MovementHelper.isClimbable(state.getBlock())) {
                    ClimbJump.Shape found = grabDest(context, x, y, z, dir, i, d, state);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static ClimbJump.Shape grabDest(CalculationContext context, int x, int y, int z, Direction dir, int dist, int dy, BlockState dest) {
        boolean ladder = dest.getBlock() == Blocks.LADDER;
        // a ladder hangs on the wall behind it, and the front of it is the side that faces back at us
        if (ladder && dest.getValue(LadderBlock.FACING) != dir.getOpposite() || !dest.getFluidState().isEmpty()) {
            return null;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ(), cx = x + dx * dist, cz = z + dz * dist;
        Direction facing = dir.getOpposite();
        int below = column(context, cx, y + dy - 1, cz, ladder, facing);
        int above = rowsAbove(context, cx, y + dy, cz, 3, ladder, facing);
        int wall = beyond(context, cx + dx, y + dy, cz + dz);
        if (below < 0 || above < 0 || wall < 0 || ladder && wall == 0) {
            return null;
        }
        int runway = 0;
        while (runway < ClimbJump.MAX_RUNWAY) {
            int bx = x - dx * (runway + 1), bz = z - dz * (runway + 1);
            if (!plainFloor(context, bx, y - 1, bz) || !clear(context, bx, y, bz)) {
                break;
            }
            runway++;
        }
        int kind = ladder ? ClimbJump.KIND_LADDER : ClimbJump.KIND_VINE;
        // more runway isn't always better as far as the table goes (it only tries a few spots to start from), so if the
        // whole thing doesn't work out, see if less of it does. walking before sprinting, sprint is the one that can go wrong
        for (int r = runway; r >= 0; r--) {
            for (int s = 0; s <= (context.canSprint ? 1 : 0); s++) {
                ClimbJump.Shape shape = new ClimbJump.Shape(true, dist, dy, 0, kind, below, above, false, 0, wall == 1, r, s == 1);
                ClimbJump.Result result = ClimbJump.lookupIfKnown(shape);
                if (result != null && result.ok()) {
                    // last, this runs on every node near a vine. every shape here misses the same way, so one answer is enough
                    return missIsSurvivable(context, x, y, z, dir, dist, dy) ? shape : null;
                }
            }
        }
        return null;
    }

    // a missed grab drops us down the gap columns (as grabShape walks them) or the vine's own column. none of them may end in
    // lava or fire, or in nothing at all
    private static boolean missIsSurvivable(CalculationContext context, int x, int y, int z, Direction dir, int dist, int dy) {
        int dx = dir.getStepX(), dz = dir.getStepZ();
        for (int k = 1; k <= dist; k++) {
            int cx = x + dx * k, cz = z + dz * k;
            // the vine column starts under the vine, the gap columns under our feet
            int top = k == dist ? y + dy - 1 : y - 1;
            if (!ClimbMiss.safeFloor(yy -> context.get(cx, yy, cz), top)) {
                return false;
            }
        }
        return true;
    }

    private static final int[] LEAP_CLIMBS = {0, -1, 1, -2};

    private static ClimbJump.Shape leapShape(CalculationContext context, int x, int y, int z, Direction dir) {
        if (!context.allowParkour || !context.allowClimbJumps) {
            return null;
        }
        BlockState here = context.get(x, y, z);
        Block block = here.getBlock();
        if (!MovementHelper.isClimbable(block)) {
            return null; // this is the one that says no to nearly every node
        }
        boolean ladder = block == Blocks.LADDER;
        // a ladder's front faces the way we're going: the wall it hangs on is behind us
        if (ladder && here.getValue(LadderBlock.FACING) != dir || !here.getFluidState().isEmpty()) {
            return null;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.maxY) {
            return null;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ();
        int below = column(context, x, y - 1, z, ladder, dir);
        int above = rowsAbove(context, x, y, z, 2, ladder, dir);
        // standing on something is a parkour jump, and a real one
        if (below < 0 || below == ClimbJump.BELOW_SOLID || above < 0) {
            return null;
        }
        // we hang from the wall behind us, and push off it
        if (!solid(context, x - dx, y, z - dz) || !ladder && !solid(context, x - dx, y + 1, z - dz)) {
            return null;
        }
        for (int i = 2; i <= LEAP_MAX; i++) {
            int gx = x + dx * (i - 1), gz = z + dz * (i - 1);
            // the head goes through here on the way out whatever happens
            if (!MovementHelper.fullyPassable(context, gx, y, gz) || !MovementHelper.fullyPassable(context, gx, y + 1, gz)
                    || !MovementHelper.fullyPassable(context, gx, y + 2, gz)) {
                break;
            }
            // anything we'd stand on in the gap means it isn't one, that's a walk
            if (MovementHelper.canWalkOn(context, gx, y - 1, gz)) {
                break;
            }
            int cx = x + dx * i, cz = z + dz * i;
            // climbables first, then the first floor we'd come down on
            for (int d : LEAP_CLIMBS) {
                if (-d > context.maxFallHeightNoWater) {
                    continue;
                }
                BlockState state = context.get(cx, y + d, cz);
                if (MovementHelper.isClimbable(state.getBlock())) {
                    ClimbJump.Shape found = leapToClimb(context, x, y, z, dir, i, d, state, ladder, below == 1, above);
                    if (found != null) {
                        return found;
                    }
                }
            }
            // the fall has to be one we'd take without a splash, it's 3 blocks by default and a leap lands from further out the
            // further down it goes
            for (int d = 0; d >= -Math.min(3, context.maxFallHeightNoWater); d--) {
                if (!MovementHelper.fullyPassable(context, cx, y + d, cz)) {
                    break;
                }
                if (MovementHelper.canWalkOn(context, cx, y + d - 1, cz)) {
                    ClimbJump.Shape found = leapToFloor(context, x, y, z, dir, i, d, ladder, below == 1, above);
                    if (found != null) {
                        return found;
                    }
                    break;
                }
            }
        }
        return null;
    }

    private static ClimbJump.Shape leapToClimb(CalculationContext context, int x, int y, int z, Direction dir, int dist, int dy, BlockState dest,
                                               boolean srcLadder, boolean srcBelow, int srcAbove) {
        boolean ladder = dest.getBlock() == Blocks.LADDER;
        if (ladder && dest.getValue(LadderBlock.FACING) != dir.getOpposite() || !dest.getFluidState().isEmpty()) {
            return null;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ(), cx = x + dx * dist, cz = z + dz * dist;
        if (!gapOpen(context, x, y, z, dx, dz, dist, Math.min(0, dy))) {
            return null;
        }
        Direction facing = dir.getOpposite();
        int below = column(context, cx, y + dy - 1, cz, ladder, facing);
        int above = rowsAbove(context, cx, y + dy, cz, 3, ladder, facing);
        int wall = beyond(context, cx + dx, y + dy, cz + dz);
        if (below < 0 || above < 0 || wall < 0 || ladder && wall == 0) {
            return null;
        }
        ClimbJump.Shape shape = new ClimbJump.Shape(false, dist, dy, srcLadder ? ClimbJump.KIND_LADDER : ClimbJump.KIND_VINE,
                ladder ? ClimbJump.KIND_LADDER : ClimbJump.KIND_VINE, below, above, srcBelow, srcAbove, wall == 1, 0, false);
        ClimbJump.Result result = ClimbJump.lookupIfKnown(shape);
        return result != null && result.ok() ? shape : null;
    }

    private static ClimbJump.Shape leapToFloor(CalculationContext context, int x, int y, int z, Direction dir, int dist, int dy,
                                               boolean srcLadder, boolean srcBelow, int srcAbove) {
        int dx = dir.getStepX(), dz = dir.getStepZ(), cx = x + dx * dist, cz = z + dz * dist;
        if (!plainFloor(context, cx, y + dy - 1, cz) || !gapOpen(context, x, y, z, dx, dz, dist, dy)) {
            return null;
        }
        if (!context.get(cx, y + dy, cz).getFluidState().isEmpty()
                || !MovementHelper.fullyPassable(context, cx, y + dy + 1, cz) || !MovementHelper.fullyPassable(context, cx, y + dy + 2, cz)) {
            return null;
        }
        int wall = beyond(context, cx + dx, y + dy, cz + dz);
        if (wall < 0 || wall == 0 && !MovementParkour.checkOvershootSafety(context.bsi, cx + dx, y + dy, cz + dz)) {
            return null;
        }
        ClimbJump.Shape shape = new ClimbJump.Shape(false, dist, dy, srcLadder ? ClimbJump.KIND_LADDER : ClimbJump.KIND_VINE,
                ClimbJump.KIND_FLOOR, ClimbJump.BELOW_SOLID, 0, srcBelow, srcAbove, wall == 1, 0, false);
        ClimbJump.Result result = ClimbJump.lookupIfKnown(shape);
        return result != null && result.ok() ? shape : null;
    }

    // how many cells of the climbable column sit right above (x, y, z), up to max. the rest of the way up to max has to be
    // open air for the sim, so -1 if there's anything else (or a climbable that starts after a gap)
    private static int rowsAbove(CalculationContext context, int x, int y, int z, int max, boolean ladder, Direction facing) {
        int count = 0;
        for (int r = 1; r <= max; r++) {
            int cell = column(context, x, y + r, z, ladder, facing);
            if (cell == ClimbJump.BELOW_CLIMB && count == r - 1) {
                count = r;
            } else if (cell != ClimbJump.BELOW_AIR) {
                return -1;
            }
        }
        return count;
    }

    // every column between us and the landing, from the lowest the fall goes up to where our head goes
    private static boolean gapOpen(CalculationContext context, int x, int y, int z, int dx, int dz, int dist, int lowest) {
        for (int k = 1; k < dist; k++) {
            for (int r = lowest; r <= 3; r++) {
                if (!MovementHelper.fullyPassable(context, x + dx * k, y + r, z + dz * k)) {
                    return false;
                }
            }
        }
        return true;
    }

    // what's in a cell next to a ladder or vine we're looking at: air, more of the same, or something solid. -1 if it's
    // something the sim can't say anything about (water, fences, a different kind of ladder)
    private static int column(CalculationContext context, int x, int y, int z, boolean ladder, Direction facing) {
        BlockState state = context.get(x, y, z);
        if (!state.getFluidState().isEmpty()) {
            return -1;
        }
        Block block = state.getBlock();
        if (MovementHelper.isClimbable(block)) {
            boolean same = ladder ? block == Blocks.LADDER && state.getValue(LadderBlock.FACING) == facing : block != Blocks.LADDER;
            return same ? ClimbJump.BELOW_CLIMB : -1;
        }
        if (MovementHelper.fullyPassable(context, x, y, z, state)) {
            return ClimbJump.BELOW_AIR;
        }
        return MovementHelper.canWalkOn(context, x, y, z, state) ? ClimbJump.BELOW_SOLID : -1;
    }

    // the wall past a landing: 1 if it's solid at both heights we'd bump it, 0 if it's open at both, -1 for anything else
    private static int beyond(CalculationContext context, int x, int y, int z) {
        int low = wallCell(context, x, y, z), high = wallCell(context, x, y + 1, z);
        return low == high ? low : -1;
    }

    private static int wallCell(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        if (!state.getFluidState().isEmpty()) {
            return -1;
        }
        if (MovementHelper.fullyPassable(context, x, y, z, state)) {
            return 0;
        }
        return !MovementHelper.isClimbable(state.getBlock()) && MovementHelper.canWalkOn(context, x, y, z, state) ? 1 : -1;
    }

    private static boolean solid(CalculationContext context, int x, int y, int z) {
        return wallCell(context, x, y, z) == 1;
    }

    // same list as MovementNeo.plainFloor, the sim is vanilla friction on a full block or nothing
    private static boolean plainFloor(CalculationContext context, int x, int y, int z) {
        BlockState floor = context.get(x, y, z);
        Block block = floor.getBlock();
        return MovementHelper.canWalkOn(context, x, y, z, floor) && floor.getFluidState().isEmpty()
                && !MovementHelper.isClimbable(block) && !(block instanceof StairBlock) && !MovementHelper.isBottomSlab(floor)
                && block != Blocks.MAGMA_BLOCK && block != Blocks.FARMLAND && block != Blocks.DIRT_PATH
                && block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.ENDER_CHEST
                && block.getFriction() == 0.6F && block.getSpeedFactor() == 1.0F && block.getJumpFactor() == 1.0F;
    }

    // the head tops out at y + 3.05, so y + 3 counts
    private static boolean clear(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && MovementHelper.fullyPassable(context, x, y + 2, z) && MovementHelper.fullyPassable(context, x, y + 3, z);
    }

    // ---- the movement

    @Override
    public double calculateCost(CalculationContext context) {
        ClimbJump.Shape found = grab ? grabShape(context, src.x, src.y, src.z, direction) : leapShape(context, src.x, src.y, src.z, direction);
        if (found == null || found.dist() != dist || found.dy() != dy || found.grab() != grab) {
            return COST_INF;
        }
        shape = found;
        return cost(context, found);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        if (shape == null) {
            set.add(src);
            return set;
        }
        // every cell the feet can be in. the runway because we walk back onto it, a bit under the lowest end because a
        // catch can be a block low and climb back up, and a bit over the highest because a jump goes up
        int low = Math.min(0, dy) - 1, high = Math.max(0, dy) + 3;
        for (int k = -shape.runway(); k <= dist + 1; k++) {
            for (int r = low; r <= high; r++) {
                set.add(src.relative(direction, k).above(r));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // shuffling around before the jump is fine to interrupt, the flight very much isn't
        return state.getStatus() != MovementStatus.RUNNING || !control.launched && (grab ? ctx.player().onGround() : ctx.player().onClimbable());
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (shape == null) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        LocalPlayer player = ctx.player();
        if (player.getY() < Math.min(src.y, dest.y) - 1) {
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        String why = cantFly(player);
        if (why != null) {
            logDebug(why);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        int dx = direction.getStepX(), dz = direction.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
        Vec3 pos = player.position().subtract(src.x + 0.5, src.y, src.z + 0.5);
        Vec3 vel = player.getDeltaMovement();
        double u = pos.x * dx + pos.z * dz, v = pos.x * sx + pos.z * sz;
        double vu = vel.x * dx + vel.z * dz, vv = vel.x * sx + vel.z * sz;
        boolean sprinting = player.isSprinting();
        // the attribute has the sprint bonus in it once we're sprinting, the sim wants it without
        double base = player.getAttributeValue(Attributes.MOVEMENT_SPEED) / (sprinting ? ClimbJump.SPRINT_BONUS : 1);
        ClimbJump sim = new ClimbJump(shape, base);
        boolean wantSprint = shape.sprint();
        if (wantSprint && (!Baritone.settings().allowSprint.value || player.getFoodData().getFoodLevel() <= 6)) {
            logDebug("can't climb jump without sprinting");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        boolean ground = player.onGround();
        if (grab && !control.launched && !running) {
            return stage(state, u, v, vel);
        }
        if (!grab && !control.launched && !player.onClimbable()) {
            logDebug("not on the ladder anymore");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // decide flips launched on every GO, so the sneak release below has to look at what it was going in
        boolean launchedBefore = control.launched;
        ClimbJump.Action action = sim.decide(control, u, player.getY() - src.y, vu, vel.y, ground, sprinting, player.horizontalCollision, wantSprint);
        switch (action.status()) {
            case ClimbJump.DONE:
                return state.setStatus(MovementStatus.SUCCESS);
            case ClimbJump.STAGE:
                return hang(state, player.getY() - src.y);
            case ClimbJump.FAIL:
                return failed(state, player, u, v);
            default:
                break;
        }
        if (!grab && sneaked && !launchedBefore) {
            // good to go, except we're still crouched, and that's a tick of 0.3 x speed that the sim doesn't know about
            sneaked = false;
            control = new ClimbJump.Control(false);
            return state;
        }
        stuckTicks = 0;
        return press(state, action.input(), action.jump(), wantSprint, v, vv);
    }

    private MovementState press(MovementState state, int input, boolean jump, boolean wantSprint, double v, double vv) {
        // a bit of sideways to keep v at 0. it's a few degrees at most, so the sim not knowing about it is fine
        double hv = Math.max(-0.1, Math.min(0.1, -2 * v - 6 * vv));
        steer(state, input == ClimbJump.BRAKE ? -1 : 1, hv);
        if (input != ClimbJump.COAST) {
            state.setInput(Input.MOVE_FORWARD, true);
            if (wantSprint) {
                state.setInput(Input.SPRINT, true);
            }
        }
        if (jump) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }

    // the sim said no. in the air there's nothing left to do but lean at the dest and hope, on the ground it's a run
    // that didn't work out
    private MovementState failed(MovementState state, LocalPlayer player, double u, double v) {
        if (!player.onGround() && control.launched) {
            steer(state, dist - u, -v);
            return state.setInput(Input.MOVE_FORWARD, true);
        }
        if (grab && player.onGround()) {
            if (control.launched) {
                // came down on the runway (or somewhere worse)
                BetterBlockPos feet = ctx.playerFeet();
                int back = -((feet.x - src.x) * direction.getStepX() + (feet.z - src.z) * direction.getStepZ());
                boolean onLine = feet.y == src.y && feet.x - src.x == -back * direction.getStepX() && feet.z - src.z == -back * direction.getStepZ();
                if (!onLine || back < 0 || back > shape.runway()) {
                    logDebug("climb jump landed somewhere weird");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                control = new ClimbJump.Control(false);
            }
            running = false;
            if (++failedRuns >= 3) {
                logDebug("no run up that works from here");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            return state;
        }
        logDebug("climb jump lost the plot");
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    // walk over to where the run up starts, same as MovementNeo. sneaking: the spot at the back is right on the edge of
    // the block, and vanilla won't let a sneaking player walk off one. it's slower too, which is how we stop on it
    private MovementState stage(MovementState state, double u, double v, Vec3 vel) {
        ClimbJump.Result result = ClimbJump.lookup(shape);
        if (!result.ok()) {
            logDebug("this climb jump stopped being one");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        state.setInput(Input.SNEAK, true);
        double eu = result.us() - u, ev = -v;
        double off = Math.sqrt(eu * eu + ev * ev);
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (off < 0.08 && speed < 0.01) {
            // let go of sneak this tick, sprint can't start while we're still crouched
            running = true;
            stuckTicks = 0;
            return state.setInput(Input.SNEAK, false);
        }
        // close and still moving: let go and coast. ground friction eats 45% a tick, holding W the whole way just overshoots
        if (off > 0.08 && !(off < 0.3 && speed > 0.05)) {
            steer(state, eu, ev);
            state.setInput(Input.MOVE_FORWARD, true);
        }
        if (speed >= 0.003) {
            stuckTicks = 0;
        } else if (++stuckTicks > 20) {
            logDebug("can't get to where this run up starts");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        return state;
    }

    // a leap that doesn't like where it's hanging yet. nearly every spot in the cell is fine for a one block leap, so this is
    // mostly for the top half of the cell: let go and slide down to the bottom half, then sneak, which is what holds still on a
    // ladder. pushing at the wall would just climb us (bumping a wall on a ladder is how you climb it)
    private MovementState hang(MovementState state, double y) {
        if (++stageTicks > 60) {
            logDebug("can't get set up on this ladder");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        sneaked = y - Math.floor(y) <= 0.5;
        if (sneaked) {
            state.setInput(Input.SNEAK, true);
        }
        return state;
    }

    // same bail outs as SprintJump, the sim is vanilla or nothing
    private static String cantFly(LocalPlayer player) {
        if (player.isInWater() || player.isInLava()) {
            return "can't climb jump out of water";
        }
        if (player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)) {
            return "potions and climb jumps don't mix";
        }
        if (Math.abs(player.getAttributeValue(Attributes.JUMP_STRENGTH) - 0.42) > 1e-6 || player.getAttributeValue(Attributes.GRAVITY) != 0.08) {
            return "somebody changed the physics";
        }
        return null;
    }

    private void steer(MovementState state, double hu, double hv) {
        double wx = hu * direction.getStepX() + hv * side.getStepX();
        double wz = hu * direction.getStepZ() + hv * side.getStepZ();
        // same convention as SprintJump.yaw, yaw 0 faces +z
        float yaw = (float) Math.toDegrees(Math.atan2(wz, wx)) - 90;
        state.setTarget(new MovementTarget(new Rotation(yaw, ctx.playerRotations().getPitch()), false));
    }
}
