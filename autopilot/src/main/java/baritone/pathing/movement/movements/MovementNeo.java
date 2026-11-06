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
import baritone.pathing.movement.CurvedMovement;
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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class MovementNeo extends Movement implements CurvedMovement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    // cost rides along so the planner and calculateCost can only ever disagree if the table did
    private record Shape(Direction side, int dist, int walls, int runway, boolean openBeyond, double cost) {}

    private final Direction direction, side;
    private final int dist;
    // recalculateCost runs every tick and refreshes these, so someone placing a block mid run up changes the plan
    private int walls, runway;
    private boolean openBeyond;
    // jumped covers the hop too, so nobody cancels us mid air. hopping is the hop specifically: coming down from it on the
    // runway is the plan, not a bonk, and hopAir counts ticks it spent off the ground so a hop that never left isn't one
    private boolean jumped, hopping;
    private int hopAir;
    // the schedule we're flying, carried tick to tick so a replan can only ever swap it for something better
    private NeoJump.Plan flight;
    // running is the blind run up from the staging spot, runTicks how long we've been at it, failedRuns the ones that
    // ended without a jump
    private boolean running;
    private int runTicks, failedRuns;
    private int stuckTicks;

    private MovementNeo(IBaritone baritone, BetterBlockPos src, Direction dir, Direction side, int dist, int walls, int runway, boolean openBeyond) {
        super(baritone, src, src.relative(dir, dist), EMPTY);
        this.direction = dir;
        this.side = side;
        this.dist = dist;
        this.walls = walls;
        this.runway = runway;
        this.openBeyond = openBeyond;
    }

    // the executor rewinds and replays movements it already ran, all of this is per run. walls/runway/openBeyond are
    // refreshed by calculateCost so they stay
    @Override
    public void reset() {
        super.reset();
        jumped = hopping = running = false;
        hopAir = runTicks = failedRuns = stuckTicks = 0;
        flight = null;
    }

    // where the table of run ups is kept between launches, see NeoJump.TABLE_VERSION. NeoJump doesn't know what a game
    // directory is, so whoever does hands it over
    public static void setTableFile(Path file) {
        NeoJump.setTableFile(file);
    }

    public static MovementNeo cost(CalculationContext context, BetterBlockPos src, Direction dir) {
        Shape shape = best(context, src.x, src.y, src.z, dir);
        if (shape == null) {
            // dest == src never matches anything in Path.runBackwards, and calculateCost can't find a dist 0 shape. so it's inert
            return new MovementNeo(context.getBaritone(), src, dir, dir.getClockWise(), 0, 0, 1, false);
        }
        return new MovementNeo(context.getBaritone(), src, dir, shape.side(), shape.dist(), shape.walls(), shape.runway(), shape.openBeyond());
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        Shape shape = best(context, x, y, z, dir);
        if (shape == null) {
            return;
        }
        res.x = x + dir.getStepX() * shape.dist();
        res.y = y;
        res.z = z + dir.getStepZ() * shape.dist();
        res.cost = shape.cost();
    }

    // ticks, since that's what everything A* adds up is. what we actually do: walk from the takeoff block back to the
    // staging spot (the far end of the runway, so a 6 block runway is a 6 block walk), run up, fly. the old (dist + 2)
    // walks looked right for a 2 block neo off a 1 block runway and was off by half for everything else
    // sneaking is 0.3x, and we only do it for the last bit (see updateState), so that bit costs this much extra per block
    private static final double SNEAK_EXTRA = WALK_ONE_BLOCK_COST * (1 / 0.3 - 1), SNEAK_STRETCH = 0.6;
    // coming to a stop on the spot and letting go of sneak so sprint can start
    private static final double SETTLE_TICKS = 3;
    // a jump is ~12 ticks up and back down, and the hop is one more of those
    private static final double FLIGHT_TICKS = 12;

    private static double cost(CalculationContext context, NeoJump.Staging st) {
        double back = Math.sqrt(st.u() * st.u() + st.v() * st.v());
        double ticks = back * WALK_ONE_BLOCK_COST + Math.min(back, SNEAK_STRETCH) * SNEAK_EXTRA + SETTLE_TICKS + st.runup() + FLIGHT_TICKS;
        if (st.hop()) {
            return context.biasJump(ticks + FLIGHT_TICKS + 2 * context.jumpPenalty);
        }
        return context.biasJump(ticks + context.jumpPenalty);
    }

    private static Shape best(CalculationContext context, int x, int y, int z, Direction dir) {
        if (!possible(context, x, y, z, dir.getStepX(), dir.getStepZ())) {
            return null;
        }
        Shape cw = shape(context, x, y, z, dir, dir.getClockWise());
        if (cw != null && cw.dist() == 2) {
            return cw; // can't beat 2
        }
        Shape ccw = shape(context, x, y, z, dir, dir.getCounterClockWise());
        return ccw != null && (cw == null || ccw.dist() < cw.dist()) ? ccw : cw;
    }

    // everything that doesn't care which side we swing out on
    private static boolean possible(CalculationContext context, int x, int y, int z, int dx, int dz) {
        if (!context.allowParkour || !context.allowNeos || !context.canSprint) {
            return false;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.maxY) {
            return false;
        }
        if (MovementHelper.fullyPassable(context, x + dx, y, z + dz) && MovementHelper.canWalkOn(context, x + dx, y - 1, z + dz)) {
            return false; // we'd just walk
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return false;
        }
        return plainFloor(context, x, y - 1, z) && clear(context, x, y, z);
    }

    private static Shape shape(CalculationContext context, int x, int y, int z, Direction dir, Direction side) {
        int dx = dir.getStepX(), dz = dir.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
        if (!clear(context, x + sx, y, z + sz)) {
            return null;
        }
        // how much floor we have to run up on. the first cell that fails ends it, and no cells at all means no neo
        int runway = 0;
        while (runway < NeoJump.MAX_RUNWAY) {
            int bx = x - dx * (runway + 1), bz = z - dz * (runway + 1);
            if (!plainFloor(context, bx, y - 1, bz) || !clear(context, bx, y, bz) || !clear(context, bx + sx, y, bz + sz)) {
                break;
            }
            runway++;
        }
        if (runway == 0) {
            return null;
        }
        int walls = 0;
        for (int i = 1; i <= NeoJump.MAX_DIST; i++) {
            int lx = x + dx * i, lz = z + dz * i;
            if (!clear(context, lx + sx, y, lz + sz)) {
                return null;
            }
            if (!clear(context, lx, y, lz)) {
                walls |= 1 << (i - 1);
                continue;
            }
            BlockState floor = context.get(lx, y - 1, lz);
            if (i >= 2 && MovementHelper.canWalkOn(context, lx, y - 1, lz, floor)) {
                // first floor we see is the landing. flying past one we could've landed on is just asking for it
                return landing(context, lx, y, lz, dx, dz, side, i, walls, runway, floor);
            }
        }
        return null;
    }

    private static Shape landing(CalculationContext context, int lx, int y, int lz, int dx, int dz, Direction side, int dist, int walls, int runway, BlockState floor) {
        int sx = side.getStepX(), sz = side.getStepZ();
        // bottom slabs drop us half a block below where the sim thinks the floor is, and then playerFeet says we fell
        if (walls == 0 || floor.getBlock() == Blocks.FARMLAND || MovementHelper.isBottomSlab(floor)) {
            return null;
        }
        // coming in from the side, we slide on forward or out sideways depending on the jump
        if (!MovementParkour.checkOvershootSafety(context.bsi, lx + dx, y, lz + dz) || !MovementParkour.checkOvershootSafety(context.bsi, lx + sx, y, lz + sz)) {
            return null;
        }
        boolean openBeyond = open(context, lx + dx, y, lz + dz) && open(context, lx + dx + sx, y, lz + dz + sz);
        // more runway isn't always better as far as the table goes (it only tries a few spots to start from), so if the
        // whole thing doesn't work out, see if less of it does
        // and a shorter one can be cheaper too, nobody wants to walk six blocks back to hop when two would've done
        Shape best = null;
        for (int r = runway; r >= 1; r--) {
            NeoJump.Staging st = NeoJump.stagingIfKnown(dist, walls, r, openBeyond);
            if (st != null) {
                double cost = cost(context, st);
                if (best == null || cost < best.cost()) {
                    best = new Shape(side, dist, walls, r, openBeyond, cost);
                }
            }
        }
        return best;
    }

    private static boolean plainFloor(CalculationContext context, int x, int y, int z) {
        BlockState floor = context.get(x, y, z);
        Block block = floor.getBlock();
        // the sim is vanilla friction on a full block or nothing. ice, soul sand and honey would make it lie, and farmland,
        // paths and chests are a hair short. fluids too, a waterlogged anything is not where you take a run up from
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

    // by the time we're out past the landing we're on the way down, so y + 3 doesn't matter there
    private static boolean open(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && MovementHelper.fullyPassable(context, x, y + 2, z);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        if (!possible(context, src.x, src.y, src.z, direction.getStepX(), direction.getStepZ())) {
            return COST_INF;
        }
        Shape shape = shape(context, src.x, src.y, src.z, direction, side);
        if (shape == null || shape.dist() != dist) {
            return COST_INF;
        }
        walls = shape.walls();
        runway = shape.runway();
        openBeyond = shape.openBeyond();
        return shape.cost();
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        // the runway because we walk back onto it, and if that's not valid PathExecutor rewinds us a movement.
        // dist + 1 because a landing that overlaps the block by a little still has our feet hanging past it
        for (int k = -runway; k <= dist + 1; k++) {
            for (int j = 0; j <= 1; j++) {
                BetterBlockPos pos = src.relative(direction, k).relative(side, j);
                set.add(pos);
                set.add(pos.above());
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // shuffling around before the jump is fine to interrupt, the flight very much isn't
        return state.getStatus() != MovementStatus.RUNNING || !jumped && ctx.player().onGround();
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        LocalPlayer player = ctx.player();
        BetterBlockPos feet = ctx.playerFeet();
        if (feet.y < src.y) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        String why = cantFly(player);
        if (why != null) {
            logDebug(why);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (player.onGround() && feet.equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        int dx = direction.getStepX(), dz = direction.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
        Vec3 pos = player.position().subtract(src.x + 0.5, 0, src.z + 0.5);
        Vec3 vel = player.getDeltaMovement();
        double u = pos.x * dx + pos.z * dz, v = pos.x * sx + pos.z * sz;
        double vu = vel.x * dx + vel.z * dz, vv = vel.x * sx + vel.z * sz;
        // the sprint modifier is a x1.3 on the attribute that only shows up once we're actually sprinting, and we start
        // sprinting the same tick we press it
        double groundAccel = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * (player.isSprinting() ? 1 : 1.3) * 0.98;
        NeoJump sim = new NeoJump(dist, walls, runway, openBeyond, groundAccel);
        if (!player.onGround() && hopping) {
            // same as NeoJump.rolloutHop. no jump input in here, the neo jump goes in on the first tick we're back on the ground
            NeoJump.Staging hop = NeoJump.staging(dist, walls, runway, openBeyond);
            hopAir++;
            double[] heading = hop != null && hop.hop() ? sim.hopTick(u, v, vu, vv, player.getY() - src.y, vel.y, hop.runTarget(), hop.takeoff()) : null;
            if (heading != null) {
                steer(state, heading[0], heading[1]);
            } else {
                steer(state, 1, 0); // nothing lands it on the runway anymore, so just don't wiggle
            }
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (!player.onGround()) {
            flight = sim.plan(u, v, vu, vv, player.getY() - src.y, vel.y, false, 0, flight);
            if (flight != null) {
                steer(state, flight.headingU(), flight.headingV());
            } else {
                steer(state, dist - u, -v); // nothing lands it anymore, so at least aim at the block
            }
            // never jump up here, letting go of space resets the vanilla jump delay
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (hopping) {
            hopping = false;
            if (hopAir == 0) {
                // pressed jump and never left the ground, so the hop didn't happen
                jumped = false;
                running = false;
                if (++failedRuns >= 3) {
                    logDebug("can't even hop");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            } else {
                // touchdown, and the very next tick is the neo jump. same as NeoJump.neoAfterHop
                NeoJump.Plan plan = sim.plan(u, v, vu, vv, 0, 0, true, 0, null);
                if (plan != null && plan.margin() >= NeoJump.JUMP_MARGIN) {
                    steer(state, plan.headingU(), plan.headingV());
                    flight = plan;
                    stuckTicks = 0;
                    return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true).setInput(Input.JUMP, true);
                }
                // hopped to somewhere we don't like. the bookkeeping below sends us back to try again
            }
        }
        if (jumped) {
            // came down somewhere that isn't dest
            if (hanging(feet)) {
                // feet over the edge but the box is on the landing block. just shuffle on
                MovementHelper.moveTowards(ctx, state, dest);
                return state;
            }
            int back = -((feet.x - src.x) * dx + (feet.z - src.z) * dz);
            boolean onLine = feet.y == src.y && feet.x - src.x == -back * dx && feet.z - src.z == -back * dz;
            if (!onLine || back < 0 || back > runway) {
                logDebug("neo landed somewhere weird");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            jumped = false; // never left, or bonked and fell back. go again
            hopping = false;
            running = false;
            if (++failedRuns >= 3) {
                logDebug("this neo keeps bonking");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
        }
        NeoJump.Staging st = NeoJump.staging(dist, walls, runway, openBeyond);
        if (st == null) {
            logDebug("this neo stopped being a neo");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // same as NeoJump.rollout, which is what told A* this works. don't get creative here
        NeoJump.Plan plan = !st.hop() && u > NeoJump.EARLIEST ? sim.plan(u, v, vu, vv, 0, 0, true, 1, null) : null;
        if (plan != null && plan.margin() >= NeoJump.JUMP_MARGIN) {
            steer(state, plan.headingU(), plan.headingV());
            state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
            if (plan.runup() == 0) {
                state.setInput(Input.JUMP, true);
                jumped = true;
                flight = plan;
            }
            stuckTicks = 0;
            return state;
        }
        if (st.hop() && running && runTicks <= st.runup() + 3) {
            // the hop's own run up: line the jump up so the hop comes down on the neo takeoff spot
            double[] heading = sim.hopRunTick(u, v, vu, vv, st.runTarget(), st.takeoff());
            if (heading != null) {
                steer(state, heading[0], heading[1]);
                state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
                if (heading[2] > 0) {
                    state.setInput(Input.JUMP, true);
                    jumped = true;
                    hopping = true;
                    hopAir = 0;
                } else {
                    runTicks++;
                }
                stuckTicks = 0;
                return state;
            }
        }
        if (!st.hop() && running && runTicks <= st.runup() + 3 && u < 0.2) {
            // the one tick lookahead can't see a jump that's still five ticks of running away, so until it can, we run
            // the way the table said would work, lining a later tick up with the takeoff spot as we go
            double[] heading = sim.runTick(u, v, vu, vv, st.runTarget(), st.takeoff());
            steer(state, heading[0], heading[1]);
            runTicks++;
            stuckTicks = 0;
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (running) {
            // ran out of road or ticks without ever getting a jump we liked
            running = false;
            if (++failedRuns >= 3) {
                logDebug("no run up that works from here");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
        }
        // walk over to where the run up starts. the good spots are 0.04 from falling off the side of the bridge, and
        // vanilla won't let a sneaking player walk off an edge, so the last stretch is a sneak. it's slower too, which is
        // how we stop on the spot. out in the middle of a runway there's nothing to fall off of, and sneaking the whole
        // way back down a 6 block runway was most of what a neo cost
        double eu = st.u() - u, ev = st.v() - v;
        double off = Math.sqrt(eu * eu + ev * ev);
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (off < SNEAK_STRETCH) {
            state.setInput(Input.SNEAK, true);
        }
        if (off < 0.08 && speed < 0.01) {
            // let go of sneak this tick, sprint can't start while we're still crouched
            running = true;
            runTicks = 0;
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
            logDebug("no run up that works from here");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        return state;
    }

    // the line the renderer draws for this movement, in block corner coordinates like path positions are (so its +0.5
    // centers it). out past the end of the wall and back in, instead of straight through it. a hop's run up is behind
    // src, which isn't on the path, so there's nothing of it to draw
    @Override
    public Vec3[] curve() {
        Vec3[] points = new Vec3[CURVE_POINTS];
        for (int i = 0; i < CURVE_POINTS; i++) {
            double t = i / (double) (CURVE_POINTS - 1);
            double along = dist * t;
            // all the way out by the time we reach the wall's face half a block in, and back in over the last half block.
            // a plain sine bump was only 0.6 out over the first wall block of a long neo, so the line clipped its corner
            double ease = Math.min(1, Math.min(along, dist - along) / 0.5);
            double out = CURVE_OUT * ease * ease * (3 - 2 * ease);
            points[i] = new Vec3(src.x + direction.getStepX() * along + side.getStepX() * out,
                    src.y + CURVE_UP * 4 * t * (1 - t),
                    src.z + direction.getStepZ() * along + side.getStepZ() * out);
        }
        // floating point at t = 1, and the line should meet the next movement exactly
        points[CURVE_POINTS - 1] = new Vec3(dest.x, dest.y, dest.z);
        return points;
    }

    private static final int CURVE_POINTS = 15;
    private static final double CURVE_OUT = 0.85, CURVE_UP = 1.0;

    private boolean hanging(BetterBlockPos feet) {
        int along = (feet.x - dest.x) * direction.getStepX() + (feet.z - dest.z) * direction.getStepZ();
        int across = (feet.x - dest.x) * side.getStepX() + (feet.z - dest.z) * side.getStepZ();
        // -1: the sim takes a sliver of overlap on the landing block, which can leave the feet cell one short of it. that
        // only happens with open air before dest. if that cell is a wall the box can't be overlapping dest from there (it'd
        // be inside the wall), so it's just somebody standing on the side lane's floor and the old replan is right
        boolean wallBeforeDest = (walls >> (dist - 2) & 1) != 0;
        int minAlong = wallBeforeDest ? 0 : -1;
        return feet.y == dest.y && along >= minAlong && along <= 1 && (across == 0 || across == 1) && !feet.equals(dest);
    }

    // same bail outs as SprintJump. the sim is vanilla or nothing
    private static String cantFly(LocalPlayer player) {
        if (player.isInWater() || player.isInLava() || player.onClimbable()) {
            return "can't neo out of water or off a ladder";
        }
        if (player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)) {
            return "potions and neos don't mix";
        }
        if (Math.abs(player.getAttributeValue(Attributes.JUMP_STRENGTH) - 0.42) > 1e-6 || player.getAttributeValue(Attributes.GRAVITY) != 0.08) {
            return "somebody changed the physics";
        }
        if (!Baritone.settings().allowSprint.value || player.getFoodData().getFoodLevel() <= 6) {
            return "can't neo without sprinting";
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
