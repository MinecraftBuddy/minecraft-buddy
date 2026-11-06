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

/**
 * A jump that brings its own run up: run along the runway behind the takeoff (hop on it if there's room), then jump across
 * one or two gaps in a straight line, landing on a pad in the middle and jumping again the very next tick. Straight lines
 * only, so none of the lateral stuff NeoJump has to worry about. See MomentumJump for the physics.
 */
public class MovementMomentum extends Movement implements CurvedMovement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    // cost rides along so the planner and calculateCost can only ever disagree if the table did
    private record Shape(int dist, int pad, int dy, int runway, boolean after, double cost) {}

    private final Direction direction, side;
    private final int dist, pad, dy;
    // recalculateCost runs every tick and refreshes these, so someone placing a block mid run up changes the plan
    private int runway;
    private boolean after;
    // jumped covers the hop too, so nobody cancels us mid air. hopping is the hop specifically: coming down from it on the
    // runway is the plan, not a bonk, and hopAir counts ticks it spent off the ground so a hop that never left isn't one
    private boolean jumped, hopping;
    private int hopAir;
    // the schedule we're flying, carried tick to tick so a replan can only ever swap it for something better
    private MomentumJump.Plan flight;
    // running is the blind run up from the staging spot, runTicks how long we've been at it, failedRuns the ones that
    // ended without a jump
    private boolean running;
    private int runTicks, failedRuns;
    private int stuckTicks;

    private MovementMomentum(IBaritone baritone, BetterBlockPos src, Direction dir, int dist, int pad, int dy, int runway, boolean after) {
        super(baritone, src, src.relative(dir, dist).above(dy), EMPTY);
        this.direction = dir;
        this.side = dir.getClockWise();
        this.dist = dist;
        this.pad = pad;
        this.dy = dy;
        this.runway = runway;
        this.after = after;
    }

    // the executor rewinds and replays movements it already ran, all of this is per run. runway/after are refreshed by
    // calculateCost so they stay
    @Override
    public void reset() {
        super.reset();
        jumped = hopping = running = false;
        hopAir = runTicks = failedRuns = stuckTicks = 0;
        flight = null;
    }

    // where the table of run ups is kept between launches, see MomentumJump.TABLE_VERSION. MomentumJump doesn't know what a
    // game directory is, so whoever does hands it over
    public static void setTableFile(Path file) {
        MomentumJump.setTableFile(file);
    }

    public static MovementMomentum cost(CalculationContext context, BetterBlockPos src, Direction dir, boolean chain) {
        Shape shape = shape(context, src.x, src.y, src.z, dir, chain);
        if (shape == null) {
            // dest == src never matches anything in Path.runBackwards, and calculateCost can't find a dist 0 shape. so it's inert
            return new MovementMomentum(context.getBaritone(), src, dir, 0, 0, 0, 0, false);
        }
        return new MovementMomentum(context.getBaritone(), src, dir, shape.dist(), shape.pad(), shape.dy(), shape.runway(), shape.after());
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, boolean chain, MutableMoveResult res) {
        Shape shape = shape(context, x, y, z, dir, chain);
        if (shape == null) {
            return;
        }
        res.x = x + dir.getStepX() * shape.dist();
        res.y = y + shape.dy();
        res.z = z + dir.getStepZ() * shape.dist();
        res.cost = shape.cost();
    }

    // ticks, since that's what everything A* adds up is. what we actually do: walk from the takeoff block back to the
    // staging spot, run up, fly (the table counts all of that from the staging spot on). same as MovementNeo
    private static final double SNEAK_EXTRA = WALK_ONE_BLOCK_COST * (1 / 0.3 - 1), SNEAK_STRETCH = 0.6;
    // coming to a stop on the spot and letting go of sneak so sprint can start
    private static final double SETTLE_TICKS = 3;

    private static double cost(CalculationContext context, MomentumJump.Staging st, int pad) {
        double back = Math.abs(st.u());
        double ticks = back * WALK_ONE_BLOCK_COST + Math.min(back, SNEAK_STRETCH) * SNEAK_EXTRA + SETTLE_TICKS + st.ticks();
        int jumps = 1 + (st.hop() ? 1 : 0) + (pad > 0 ? 1 : 0);
        // jumpBias is 1 unless fastMode is on, same as neos. calculateCost reads this too so they can't drift
        return context.biasJump(ticks + jumps * context.jumpPenalty);
    }

    // no floor in the cell, and nothing about it that's a problem either
    private static final int NO_FLOOR = 99, HAZARD = -99;

    // what's under the flight line in one column: dy of the floor we'd land on (1 means a block at feet level, 0 flat, down to
    // -drop), NO_FLOOR for a gap as deep as we care about, HAZARD for anything we'd bonk or burn on
    private static int columnFloor(CalculationContext context, int x, int y, int z, int drop) {
        BlockState feet = context.get(x, y, z);
        if (!MovementHelper.fullyPassable(context, x, y, z, feet)) {
            return MovementHelper.canWalkOn(context, x, y, z, feet) ? 1 : HAZARD;
        }
        for (int b = y - 1; b >= y - 1 - drop; b--) {
            BlockState state = context.get(x, b, z);
            if (MovementHelper.fullyPassable(context, x, b, z, state)) {
                continue;
            }
            return MovementHelper.canWalkOn(context, x, b, z, state) ? b + 1 - y : HAZARD;
        }
        return NO_FLOOR;
    }

    // cheap on the way out, since every node asks: most of them die on the first two lookups, same as parkour
    private static Shape shape(CalculationContext context, int x, int y, int z, Direction dir, boolean chain) {
        if (!context.allowParkour || !context.allowMomentumJumps || !context.canSprint) {
            return null;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.maxY) {
            return null;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + dx, y, z + dz)) {
            return null; // a wall, or a step up. neither is ours
        }
        if (MovementHelper.canWalkOn(context, x + dx, y - 1, z + dz)) {
            return null; // we'd just walk
        }
        int drop = Math.min(-MomentumJump.MIN_DY, context.maxFallHeightNoWater);
        // the first cell has to be a true gap, anything we could land on in it is a traverse or a descend and theirs
        for (int b = y - 1; b >= y - 1 - drop; b--) {
            if (!MovementHelper.fullyPassable(context, x + dx, b, z + dz)) {
                return null;
            }
        }
        if (!fullyPassable3(context, x + dx, y + 1, z + dz) || !plainFloor(context, x, y - 1, z) || !clear(context, x, y, z)
                || !context.get(x, y, z).getFluidState().isEmpty()) {
            return null;
        }
        int pad = 0;
        for (int k = 2; k <= MomentumJump.MAX_DIST; k++) {
            int cx = x + dx * k, cz = z + dz * k;
            if (!fullyPassable3(context, cx, y + 1, cz)) {
                return null;
            }
            int floorDy = columnFloor(context, cx, y, cz, drop);
            if (floorDy == HAZARD) {
                return null;
            }
            if (floorDy == NO_FLOOR) {
                continue;
            }
            // flying past a floor we could've landed on is just asking for it, so the first floor is it
            if (!plainFloor(context, cx, y + floorDy - 1, cz)) {
                return null;
            }
            if (chain && pad == 0) {
                if (floorDy != 0) {
                    return null; // the pad is at takeoff height
                }
                pad = k;
                continue;
            }
            if (pad == 0 ? MomentumJump.parkourCovers(k, floorDy) : k - pad < 2) {
                return null;
            }
            if (!MomentumJump.emits(k, pad, floorDy)) {
                return null;
            }
            return landing(context, x, y, z, dx, dz, k, pad, floorDy);
        }
        return null;
    }

    private static Shape landing(CalculationContext context, int x, int y, int z, int dx, int dz, int dist, int pad, int dy) {
        int lx = x + dx * dist, lz = z + dz * dist, ly = y + dy;
        if (!MovementParkour.checkOvershootSafety(context.bsi, lx + dx, ly, lz + dz)) {
            return null;
        }
        // the floor past the landing only matters for the slide after we touch down
        boolean after = plainFloor(context, lx + dx, ly - 1, lz + dz) && open(context, lx + dx, ly, lz + dz);
        int runway = 0;
        while (runway < MomentumJump.MAX_RUNWAY) {
            int bx = x - dx * (runway + 1), bz = z - dz * (runway + 1);
            if (!plainFloor(context, bx, y - 1, bz) || !clear(context, bx, y, bz)) {
                break;
            }
            runway++;
        }
        // more runway isn't always better, it's a longer walk back and a longer run up. the shorter one might not work out
        // for the table (a hop that has no room says no) so try them all and keep the cheapest
        Shape best = null;
        for (int r = runway; r >= 0; r--) {
            MomentumJump.Staging st = MomentumJump.stagingIfKnown(r, dist, pad, dy, after);
            if (st != null) {
                double cost = cost(context, st, pad);
                if (best == null || cost < best.cost()) {
                    best = new Shape(dist, pad, dy, r, after, cost);
                }
            }
        }
        return best;
    }

    private static boolean plainFloor(CalculationContext context, int x, int y, int z) {
        BlockState floor = context.get(x, y, z);
        Block block = floor.getBlock();
        // same list as MovementNeo.plainFloor: the sim is vanilla friction on a full block or nothing
        return MovementHelper.canWalkOn(context, x, y, z, floor) && floor.getFluidState().isEmpty()
                && !MovementHelper.isClimbable(block) && !(block instanceof StairBlock) && !MovementHelper.isBottomSlab(floor)
                && block != Blocks.MAGMA_BLOCK && block != Blocks.FARMLAND && block != Blocks.DIRT_PATH
                && block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.ENDER_CHEST
                && block.getFriction() == 0.6F && block.getSpeedFactor() == 1.0F && block.getJumpFactor() == 1.0F;
    }

    // the head tops out at y + 3.05, so y + 3 counts
    private static boolean clear(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && fullyPassable3(context, x, y + 1, z);
    }

    private static boolean fullyPassable3(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && MovementHelper.fullyPassable(context, x, y + 2, z);
    }

    // by the time we're out past the landing we're on the way down, so y + 3 doesn't matter there
    private static boolean open(CalculationContext context, int x, int y, int z) {
        return fullyPassable3(context, x, y, z);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        Shape shape = shape(context, src.x, src.y, src.z, direction, pad > 0);
        if (shape == null || shape.dist() != dist || shape.pad() != pad || shape.dy() != dy) {
            return COST_INF;
        }
        runway = shape.runway();
        after = shape.after();
        return shape.cost();
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        // the runway because we walk back onto it, and if that's not valid PathExecutor rewinds us a movement. dist + 1
        // because a landing that overlaps the block by a little still has our feet hanging past it, and every height we fly
        // through on the way (the apex is 1.25 up, so feet are in y + 1, and a drop goes down to the landing)
        int low = Math.min(0, dy), high = Math.max(0, dy) + 1;
        for (int k = -runway; k <= dist + 1; k++) {
            for (int h = low; h <= high; h++) {
                set.add(src.relative(direction, k).above(h));
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
        if (feet.y < Math.min(src.y, dest.y)) {
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
        Vec3 pos = player.position().subtract(src.x + 0.5, src.y, src.z + 0.5);
        Vec3 vel = player.getDeltaMovement();
        double u = pos.x * dx + pos.z * dz, v = pos.x * sx + pos.z * sz, y = pos.y;
        double vu = vel.x * dx + vel.z * dz, vv = vel.x * sx + vel.z * sz;
        // the sprint modifier is a x1.3 on the attribute that only shows up once we're actually sprinting, and we start
        // sprinting the same tick we press it
        double groundAccel = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * (player.isSprinting() ? 1 : 1.3) * 0.98;
        MomentumJump sim = new MomentumJump(runway, dist, pad, dy, after, groundAccel);
        if (!player.onGround() && hopping) {
            // same as MomentumJump.rolloutHop. no jump input in here, the first jump goes in on the first tick we're back on the ground
            MomentumJump.Staging hop = MomentumJump.staging(runway, dist, pad, dy, after);
            hopAir++;
            double[] heading = hop != null && hop.hop() ? sim.hopTick(u, v, vu, vv, y, vel.y, hop.takeoff()) : null;
            if (heading != null) {
                steer(state, heading[0], heading[1]);
            } else {
                steer(state, 1, 0); // nothing lands it on the runway anymore, so just don't wiggle
            }
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (!player.onGround()) {
            flight = sim.plan(u, v, vu, vv, y, vel.y, false, 0, flight);
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
                // touchdown, and the very next tick is the first jump. same as MomentumJump.rolloutHop
                MomentumJump.Plan plan = sim.plan(u, v, vu, vv, 0, 0, true, 0, null);
                if (plan != null && plan.margin() >= MomentumJump.JUMP_MARGIN) {
                    return jump(state, plan);
                }
                // hopped to somewhere we don't like. the bookkeeping below sends us back to try again
            }
        }
        if (jumped) {
            return afterFlight(state, sim, feet, u, v, vu, vv, y);
        }
        MomentumJump.Staging st = MomentumJump.staging(runway, dist, pad, dy, after);
        if (st == null) {
            logDebug("this jump stopped being one");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // same as MomentumJump.rollout, which is what told A* this works. don't get creative here
        MomentumJump.Plan plan = !st.hop() && u > sim.earliest() ? sim.plan(u, v, vu, vv, 0, 0, true, 1, null) : null;
        if (plan != null && plan.margin() >= MomentumJump.JUMP_MARGIN) {
            if (plan.runup() == 0) {
                return jump(state, plan);
            }
            steer(state, plan.headingU(), plan.headingV());
            stuckTicks = 0;
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (st.hop() && running && runTicks <= st.runup() + 3) {
            // the hop's own run up: line the jump up so the hop comes down where the first jump wants to be
            double[] heading = sim.hopRunTick(u, v, vu, vv, st.takeoff());
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
        if (!st.hop() && running && runTicks <= st.runup() + 3 && u < 0.77) {
            // the one tick lookahead can't see a jump that's still five ticks of running away, so until it can, we run
            // the way the table said would work, lining a later tick up with the takeoff spot as we go
            double[] heading = sim.runTick(u, v, vu, vv, st.takeoff());
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
        // walk over to where the run up starts, sneaking for the last stretch (the spots are near the edge, and vanilla won't
        // let a sneaking player walk off one). it's slower too, which is how we stop on the spot. same as MovementNeo
        double eu = st.u() - u, ev = -v;
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

    // the jump tick: heading from the plan, space, and now we're committed
    private MovementState jump(MovementState state, MomentumJump.Plan plan) {
        steer(state, plan.headingU(), plan.headingV());
        flight = plan;
        jumped = true;
        hopping = false;
        stuckTicks = 0;
        return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true).setInput(Input.JUMP, true);
    }

    // on the ground after a flight. the pad is the plan, anything else short of dest is somebody's bonk
    private MovementState afterFlight(MovementState state, MomentumJump sim, BetterBlockPos feet, double u, double v, double vu, double vv, double y) {
        // the pad: same height as the takeoff, with the box over it. the very next tick is the next jump, same as
        // MomentumJump.finish
        if (pad > 0 && Math.abs(y) < 0.01 && u > pad - 0.8 && u < pad + 0.8) {
            MomentumJump.Plan plan = sim.plan(u, v, vu, vv, 0, 0, true, 0, null);
            if (plan != null && plan.margin() >= MomentumJump.JUMP_MARGIN) {
                return jump(state, plan);
            }
            logDebug("landed on the pad wrong");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // came down somewhere that isn't dest
        if (hanging(feet)) {
            // feet over the edge but the box is on the landing block. just shuffle on
            MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }
        int back = -((feet.x - src.x) * direction.getStepX() + (feet.z - src.z) * direction.getStepZ());
        boolean onLine = feet.y == src.y && feet.x - src.x == -back * direction.getStepX() && feet.z - src.z == -back * direction.getStepZ();
        if (!onLine || back < 0 || back > runway) {
            logDebug("momentum jump landed somewhere weird");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        jumped = false; // never left, or bonked and fell back. go again
        hopping = false;
        running = false;
        if (++failedRuns >= 3) {
            logDebug("this jump keeps bonking");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        return state;
    }

    // the line the renderer draws for this movement, in block corner coordinates like path positions are (so its +0.5
    // centers it). an arc over each gap, one per flight, and a short flat bit where we land on the pad. the run up is
    // behind src, which isn't on the path, so there's nothing of it to draw
    @Override
    public Vec3[] curve() {
        int flights = pad > 0 ? 2 : 1;
        Vec3[] points = new Vec3[flights * CURVE_POINTS];
        for (int f = 0; f < flights; f++) {
            // the pad is at takeoff height, so only the last flight changes level
            int from = f == 0 ? 0 : pad, to = flights == 1 || f == 1 ? dist : pad;
            double rise = f == flights - 1 ? dy : 0;
            for (int i = 0; i < CURVE_POINTS; i++) {
                double t = i / (double) (CURVE_POINTS - 1);
                double along = from + (to - from) * t;
                points[f * CURVE_POINTS + i] = new Vec3(src.x + direction.getStepX() * along,
                        src.y + rise * t + CURVE_UP * 4 * t * (1 - t),
                        src.z + direction.getStepZ() * along);
            }
        }
        // floating point at t = 1, and the line should meet the next movement exactly
        points[points.length - 1] = new Vec3(dest.x, dest.y, dest.z);
        return points;
    }

    private static final int CURVE_POINTS = 15;
    private static final double CURVE_UP = 1.0;

    private boolean hanging(BetterBlockPos feet) {
        int along = (feet.x - dest.x) * direction.getStepX() + (feet.z - dest.z) * direction.getStepZ();
        int across = (feet.x - dest.x) * side.getStepX() + (feet.z - dest.z) * side.getStepZ();
        // -1 is the other way to hang: the box is on dest (the sim takes a sliver of overlap) but the feet cell rounds to
        // the block before it. it's standing on the landing block, so shuffling on is right
        return feet.y == dest.y && along >= -1 && along <= 1 && across == 0 && !feet.equals(dest);
    }

    // same bail outs as MovementNeo. the sim is vanilla or nothing
    private static String cantFly(LocalPlayer player) {
        if (player.isInWater() || player.isInLava() || player.onClimbable()) {
            return "can't momentum jump out of water or off a ladder";
        }
        if (player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)) {
            return "potions and momentum don't mix";
        }
        if (Math.abs(player.getAttributeValue(Attributes.JUMP_STRENGTH) - 0.42) > 1e-6 || player.getAttributeValue(Attributes.GRAVITY) != 0.08) {
            return "somebody changed the physics";
        }
        if (!Baritone.settings().allowSprint.value || player.getFoodData().getFoodLevel() <= 6) {
            return "can't momentum jump without sprinting";
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
