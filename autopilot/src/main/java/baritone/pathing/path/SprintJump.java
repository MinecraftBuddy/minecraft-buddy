/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.SolidFloor;
import baritone.pathing.movement.movements.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * One sprint jump along a straight run of path, simulated tick by tick the same way LivingEntity does it
 */
final class SprintJump {

    // straight out of LivingEntity.travelInAir / jumpFromGround / Player.getFlyingSpeed (same on 1.21.4 and 26.3), aiStep
    // scales move input by 0.98 (applyInput on 26.3)
    static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, AIR_ACCEL = 0.026 * 0.98, JUMP_BOOST = 0.2;
    // S with W up is no forward impulse, so aiStep drops sprint before travel and the brake only gets the walking 0.02.
    // the next W with the sprint key down starts it again mid air (canStartSprinting doesn't care about onGround, see
    // sprintsOnJumpTick), and before travel, so a push after a brake is back to AIR_ACCEL
    static final double BACK_ACCEL = 0.02 * 0.98;
    static final int PUSH = 1, COAST = 0, BACK = -1;
    // how far the lean can move in a tick. ~9 degrees near the middle, nobody's neck snaps from that
    private static final double MAX_LEAN_STEP = 0.15;
    // drift this small isn't worth a head turn, and it's exactly where the sign flips every tick
    private static final double POS_DEADBAND = 0.02, VEL_DEADBAND = 0.003;
    // ~3 degrees, a lean the server would notice swapping sides
    static final double VISIBLE_LEAN = 0.05;
    private static final double JUMP = 0.42, GRAVITY = 0.08, SAFE_FALL = 3, EPS = 1e-6;
    // a braked hop that lands short is a slow way to walk. ground sprint is ~0.28 a tick, going down steps or bonking one is way worse
    private static final double FLAT_PACE = 0.28, SLOW_PACE = 0.15;
    // ~4 blocks a jump, plus enough past that to see whether the next hop can pop a step
    static final int MAX_RUNWAY = 10, MAX_TICKS = 40;
    private static final double[] MARGINS = {0.85, 1, 1.15}; // we're exact about vanilla, not about what the server does with our yaw
    private static final double BRAKE_THEN_PUSH = Double.NEGATIVE_INFINITY; // not a spot on the line, a throttle policy

    // the floor of a cell a parkour hop flies over. far enough down that nothing lands on it inside MAX_TICKS
    static final double NO_FLOOR = -1000;

    // pushed: W was down on the touchdown tick, so sprint is back up for the next takeoff
    record Landing(double s, double y, double apex, double v, int ticks, boolean pushed) {}

    final double[] floors; // relative to the takeoff floor, cell 0 is the one we take off from
    private final double spacing, halfWidth, groundAccel;
    private double originX, originZ, takeoffY, ux, uz, target, apex;
    // how the plan slows down before it pushes for good: COAST (let go of W) or BACK (hold S). picked along with target
    int brake = COAST;
    private boolean pushing;
    // a parkour hop has to cross its gap, so a hop that lands short of "walk pace" is still the whole point
    boolean hop;
    int ticks;

    SprintJump(double[] floors, double spacing, double groundAccel) {
        this.floors = floors;
        this.spacing = spacing;
        this.halfWidth = 0.3 * spacing; // an axis aligned box projected onto a diagonal reaches sqrt 2 times further
        this.groundAccel = groundAccel;
    }

    static SprintJump plan(IPlayerContext ctx, IPath path, int pathPosition) {
        Player player = ctx.player();
        BetterBlockPos start = path.positions().get(pathPosition);
        Movement lead = (Movement) path.movements().get(pathPosition);
        boolean hop = isHop(lead);
        // a parkour moves 2 or 3 cells at once, the run is measured in cells and heads along the sign of that
        BlockPos dir = new BlockPos(Integer.signum(lead.getDirection().getX()), lead.getDirection().getY(), Integer.signum(lead.getDirection().getZ()));
        boolean diagonal = dir.getX() != 0 && dir.getZ() != 0;
        // the sim knows nothing about potions or modded physics, and jump boost off a hill is how you break your legs.
        // the head tops out at y + 3.05, so y + 3 has to be clear. a ceiling at y + 2 is a head hitter, not ours
        if (!player.onGround() || !sprintsOnJumpTick(player) || player.isInWater() || player.isInLava() || player.onClimbable()
                || !ctx.playerFeet().equals(start) || Math.abs(player.getY() - start.y) > 1e-3
                || diagonal && !Baritone.settings().sprintJumpingDiagonals.value
                || player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)
                || Math.abs(player.getAttributeValue(Attributes.JUMP_STRENGTH) - JUMP) > EPS || player.getAttributeValue(Attributes.GRAVITY) != GRAVITY
                || player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) < SAFE_FALL
                || !solidFloor(ctx, start.below()) || !clear(ctx, start.x, start.y, start.z, start.y + 3)) {
            return null;
        }
        double[] floors = new double[MAX_RUNWAY + 1];
        int n = 1;
        for (int i = pathPosition; i < path.movements().size() && n <= MAX_RUNWAY; i++) {
            Movement m = (Movement) path.movements().get(i);
            BetterBlockPos src = m.getSrc();
            BetterBlockPos dest = m.getDest();
            int top = Math.max(dest.y + 2, start.y + 3); // we fly over lower cells at takeoff height
            int cells = Math.max(Math.abs(dest.x - src.x), Math.abs(dest.z - src.z));
            boolean walking = diagonal ? m instanceof MovementDiagonal && dest.y == src.y
                    : m instanceof MovementTraverse || m instanceof MovementAscend || m instanceof MovementDescend || i == pathPosition && hop;
            // breaking is like 5x slower when you're jumping, and momentum doesn't wait for a block to be placed.
            // on a diagonal the hitbox corners sweep through both side cells, and drift can land us in one
            if (!walking || n + cells - 1 > MAX_RUNWAY || dest.x - src.x != dir.getX() * cells || dest.z - src.z != dir.getZ() * cells
                    || m.toBreakCached == null || !m.toBreakCached.isEmpty() || m.toPlaceCached == null || !m.toPlaceCached.isEmpty()
                    || !solidFloor(ctx, dest.below()) || !clear(ctx, dest.x, dest.y, dest.z, top)
                    || diagonal && !(side(ctx, src.x + dir.getX(), src.y, src.z, top) && side(ctx, src.x, src.y, src.z + dir.getZ(), top))) {
                break;
            }
            // the cells a hop flies over have no floor to speak of, which is the point. they only need air up to the head
            boolean airy = true;
            for (int c = 1; c < cells; c++) {
                airy &= clear(ctx, src.x + dir.getX() * c, src.y, src.z + dir.getZ() * c, top);
            }
            if (!airy) {
                break;
            }
            for (int c = 1; c < cells; c++) {
                floors[n++] = NO_FLOOR;
            }
            floors[n++] = dest.y - start.y;
        }
        double spacing = diagonal ? Math.sqrt(2) : 1;
        // solidFloor pins friction to 0.6, so the 0.216 / f^3 in the ground acceleration is 1
        SprintJump jump = new SprintJump(Arrays.copyOf(floors, n), spacing, player.getSpeed() * 0.98);
        jump.hop = hop;
        jump.originX = start.x + 0.5;
        jump.originZ = start.z + 0.5;
        jump.takeoffY = start.y;
        jump.ux = dir.getX() / spacing;
        jump.uz = dir.getZ() / spacing;
        Vec3 pos = player.position().subtract(jump.originX, 0, jump.originZ);
        Vec3 vel = player.getDeltaMovement();
        // drifting sideways is how you clip a corner
        if (n < 3 || !SprintPolicy.centered(Math.abs(jump.across(pos)), sideOpen(ctx, start, dir, floors, pos)) || Math.abs(jump.across(vel)) > 0.08) {
            return null;
        }
        Double target = jump.plan(jump.along(pos), jump.along(vel));
        if (target == null) {
            return null;
        }
        jump.target = target;
        return jump;
    }

    // asking "is the player sprinting" was the wrong question. 26.3 starts a sprint inside aiStep whenever the key is down and forward
    // is held, on the ground or off it, and all a sprint jump wants is the flag up on the tick jumpFromGround runs. so a plan
    // that said no one tick ago (and made the executor let go of the key) could never say yes again until some other movement
    // re-armed the flag. this asks what aiStep will make of it this tick instead (LocalPlayer.canStartSprinting and
    // shouldStopRunSprinting), and not at all with the food or the setting off
    static boolean sprintsOnJumpTick(Player player) {
        return Baritone.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6
                && !(player.horizontalCollision && !player.minorHorizontalCollision)
                && !player.hasEffect(MobEffects.BLINDNESS) && !player.isCrouching() && !player.isUsingItem() && !player.isFallFlying();
    }

    // is the column beside the first cells of the run, on the side we lean to, free of anything solid. the lane is narrower
    // with a wall there (SprintPolicy.centered). lean is relative to the middle of the takeoff cell
    private static boolean sideOpen(IPlayerContext ctx, BlockPos start, BlockPos dir, double[] floors, Vec3 lean) {
        for (int i = 0; i < 3; i++) {
            // at the takeoff level (the hitbox is that high over a lower floor too) and at the cell's own floor
            int own = start.getY() + (floors[i] <= NO_FLOOR / 2 ? 0 : (int) Math.floor(floors[i]));
            for (int y : new int[]{start.getY(), own}) {
                if (!MovementHelper.sideOpen(ctx, start.getX() + dir.getX() * i, y, start.getZ() + dir.getZ() * i, dir.getX(), dir.getZ(), lean.x, lean.z)) {
                    return false;
                }
            }
        }
        return true;
    }

    // a flat parkour over a 1 or 2 block gap: the two the walking approach used to take at walk speed. 4 gaps already sprint
    // and the ascends do too, and one that has to place a block mid air has nothing to do with a sim
    static boolean isHop(Movement m) {
        if (!(m instanceof MovementParkour)) {
            return false;
        }
        int dx = m.getDest().x - m.getSrc().x;
        int dz = m.getDest().z - m.getSrc().z;
        int cells = Math.abs(dx) + Math.abs(dz);
        return m.getDest().y == m.getSrc().y && (dx == 0 || dz == 0) && (cells == 2 || cells == 3);
    }

    private static boolean solidFloor(IPlayerContext ctx, BlockPos pos) {
        return SolidFloor.plain(ctx.world().getBlockState(pos), ctx.world(), pos);
    }

    private static boolean side(IPlayerContext ctx, int x, int y, int z, int top) {
        return solidFloor(ctx, new BlockPos(x, y - 1, z)) && clear(ctx, x, y, z, top);
    }

    private static boolean clear(IPlayerContext ctx, int x, int y, int z, int top) {
        for (int yy = y; yy <= top; yy++) {
            if (!MovementHelper.fullyPassable(ctx, new BlockPos(x, yy, z))) {
                return false;
            }
        }
        return true;
    }

    private double along(Vec3 vec) {
        return vec.x * ux + vec.z * uz;
    }

    private double across(Vec3 vec) {
        return vec.z * ux - vec.x * uz;
    }

    // full send (NaN), brake to land around a distance along the line (and which brake, in the brake field), or don't jump (null)
    Double plan(double s, double v) {
        if (hop && nothingPastTheGap()) {
            return null; // MovementParkour.hopPrice never prices a hop with no floor past the landing, so it gets walked
        }
        // a hop that lands right in front of a step leaves the next one nothing to do but bonk the face, lose sprint,
        // and hand it to MovementAscend. so with a step ahead, pick a hop the next one can pop it from, or sprint
        // a tick or two more if that sets one up
        boolean climb = stepUpAhead(0);
        Double chained = climb ? choose(s, v, MARGINS, true) : null;
        return chained != null || climb && canHop(s, v, MARGINS, true, 1) ? chained : choose(s, v, MARGINS, false);
    }

    private Double choose(double s, double v, double[] margins, boolean chain) {
        if (works(s, v, margins, Double.NaN, chain)) {
            return Double.NaN;
        }
        // full send flies too far (over the first step of a hill and down the second) or sets up a bonk. braking only
        // ever shortens a hop, so nothing past where full send lands
        Landing full = jump(s, v, Double.NaN);
        for (int k = floors.length - 1; k >= 1; k--) {
            if (full != null && k * spacing > full.s() + spacing / 2) {
                continue;
            }
            // letting go is the quiet one, S only when coasting can't shed enough
            for (int kind : BRAKES) {
                if (works(s, v, margins, k * spacing, kind, chain)) {
                    brake = kind;
                    return k * spacing;
                }
            }
        }
        return null;
    }

    private static final int[] BRAKES = {COAST, BACK};

    private boolean works(double s, double v, double[] margins, double target, boolean chain) {
        return works(s, v, margins, target, PUSH, chain);
    }

    private boolean works(double s, double v, double[] margins, double target, int kind, boolean chain) {
        for (double margin : margins) {
            Landing l = jump(s, v * margin, target, kind);
            if (!safe(l) || !hop && !Double.isNaN(target) && l.s() - s < (l.y() < -EPS || chain ? SLOW_PACE : FLAT_PACE) * l.ticks()) {
                return false;
            }
            if (!Double.isNaN(target) && l.v() < 0) {
                return false; // braked so hard it comes down sliding back toward where it came from
            }
        }
        return !chain || chains(jump(s, v, target, kind));
    }

    private boolean nothingPastTheGap() {
        int landing = 1;
        while (landing < floors.length && floors[landing] <= NO_FLOOR / 2) {
            landing++;
        }
        return landing >= floors.length - 1;
    }

    private boolean chains(Landing l) {
        int cell = (int) Math.round(l.s() / spacing);
        if (!stepUpAhead(cell)) {
            return true;
        }
        double[] next = new double[floors.length - cell];
        Arrays.setAll(next, i -> floors[cell + i] - l.y());
        return new SprintJump(next, spacing, groundAccel).canHop(l.s() - cell * spacing, l.v(), new double[]{1}, false, 0);
    }

    private boolean canHop(double s, double v, double[] margins, boolean chain, int from) {
        for (int wait = 0; wait <= 2 && highest(s) <= EPS; wait++) {
            if (wait >= from && choose(s, v, margins, chain) != null) {
                return true;
            }
            s += v + groundAccel;
            v = (v + groundAccel) * GROUND_FRICTION;
        }
        return false;
    }

    private boolean stepUpAhead(int cell) {
        for (int k = cell + 1; k < floors.length; k++) {
            if (floors[k] > floors[cell] + EPS) {
                return true;
            }
        }
        return false;
    }

    // null if we hit a wall, leave the runway, or never come down
    Landing jump(double s, double v, double target) {
        return jump(s, v, target, brake);
    }

    Landing jump(double s, double v, double target, int kind) {
        return fly(s, v + JUMP_BOOST, 0, JUMP, 0, true, target, kind);
    }

    static double airAccel(int throttle) {
        return throttle == PUSH ? AIR_ACCEL : throttle == BACK ? -BACK_ACCEL : 0;
    }

    private Landing fly(double s, double v, double y, double vy, double apex, boolean jumping, double target, int kind) {
        boolean committed = false;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            // onGround is still true on the jump tick, so it gets ground acceleration and ground friction
            boolean jumpTick = jumping && tick == 0;
            int throttle = jumpTick || touchdown(s, y, vy) ? PUSH
                    : target == BRAKE_THEN_PUSH ? (tick == 0 ? kind : PUSH) : throttle(s, v, y, vy, apex, target, kind, committed);
            committed = !jumpTick && throttle == PUSH; // W on takeoff is for the jump boost, it doesn't commit to anything
            double disp = v + (jumpTick ? groundAccel : airAccel(throttle));
            // vanilla collides y before x/z, so we're already as high as we're going to get this tick when we move forward
            y += vy;
            apex = Math.max(apex, y);
            boolean landed = vy < 0 && y <= highest(s) + EPS;
            y = landed ? highest(s) : y;
            s += disp;
            if (highest(s) > y + EPS) {
                return null; // face first into a step, sprint gets cancelled and we just fall back down
            }
            if (landed) {
                return new Landing(s, y, apex, disp * AIR_FRICTION, tick + 1, throttle == PUSH);
            }
            v = disp * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
            vy = (vy - GRAVITY) * 0.98;
        }
        return null;
    }

    // does this tick's move put us on the floor. same test fly() lands with
    private boolean touchdown(double s, double y, double vy) {
        return vy < 0 && y + vy <= highest(s) + EPS;
    }

    // PUSH (W) or the plan's brake (COAST lets go, BACK holds S). turning the head around to brake with W held kept
    // sprint, but the server watched the yaw spin 180 to do it. the touchdown tick always pushes (fly() sees to that) so
    // we land sprinting and the next hop's getSpeed() is the sprinting one it plans with
    private int throttle(double s, double v, double y, double vy, double apex, double target, int kind, boolean committed) {
        if (Double.isNaN(target) || committed) {
            return PUSH;
        }
        // brake first, push last. same landing spot, but we come down moving, which is what the next hop needs to pop a
        // step. brake while braking now and pushing after still gets there, then push for good: one switch, no chatter
        Landing late = fly(s, v, y, vy, apex, false, BRAKE_THEN_PUSH, kind);
        return late == null || late.s() >= target ? kind : PUSH;
    }

    boolean safe(Landing l) {
        if (l == null || l.apex() - l.y() >= SAFE_FALL) {
            return false; // small hills only
        }
        int cell = (int) Math.round(l.s() / spacing);
        // the lip of a step puts our feet in a block that isn't on the path. and we slide after landing (v / (1 - 0.546)
        // with W let go), which has to end over the runway too in case the path turns right after it
        return cell >= 0 && cell < floors.length && Math.abs(floors[cell] - l.y()) < EPS
                && highest(l.s() + Math.max(0, l.v()) / (1 - GROUND_FRICTION)) != Double.POSITIVE_INFINITY;
    }

    /**
     * Tallest floor under a hitbox centered at s. Past the runway is a wall so the whole flight stays over checked cells,
     * behind it is where we came from (a hard braked prediction can drift back there)
     */
    private double highest(double s) {
        double top = Double.NEGATIVE_INFINITY;
        for (int k = (int) Math.floor((s - halfWidth) / spacing - 0.5) + 1; k <= Math.ceil((s + halfWidth) / spacing + 0.5) - 1; k++) {
            if (k >= floors.length) {
                return Double.POSITIVE_INFINITY;
            }
            top = Math.max(top, floors[Math.max(k, 0)]);
        }
        return top;
    }

    // which way to face this tick and which key to hold (throttle()). always down the line, leaned to kill sideways drift
    Rotation steer(IPlayerContext ctx) {
        Vec3 pos = ctx.player().position().subtract(originX, 0, originZ);
        Vec3 vel = ctx.player().getDeltaMovement();
        boolean takeoff = ticks++ == 0;
        int throttle = PUSH;
        double accel = JUMP_BOOST + groundAccel;
        if (!takeoff) {
            apex = Math.max(apex, pos.y - takeoffY);
            throttle = touchdown(along(pos), pos.y - takeoffY, vel.y) ? PUSH
                    : throttle(along(pos), along(vel), pos.y - takeoffY, vel.y, apex, target, brake, pushing);
            pushing = throttle == PUSH;
            accel = AIR_ACCEL;
        }
        lastThrottle = throttle;
        return new Rotation(yaw(ux, uz, lean.next(across(pos), across(vel), accel, throttle)), ctx.playerRotations().getPitch());
    }

    // one flight's worth of lean, sideways from the line (positive is the way across() measures)
    static final class Lean {
        double value = Double.NaN; // NaN until takeoff, nothing to ease from
        private int side, swaps;

        // ask for a sideways speed that shrinks with the offset, or we sail past the line and swing back forever (the
        // diagonal wiggle). deadbanded and eased, since a one tick deadbeat slams side to side around zero
        double next(double offset, double drift, double accel, int throttle) {
            if (throttle != PUSH && !Double.isNaN(value)) {
                return value; // the head holds still through a brake, it only steers while W is down
            }
            double want = Math.max(-0.5, Math.min(0.5, (-deadband(offset, POS_DEADBAND) * 0.3 - deadband(drift, VEL_DEADBAND)) / accel));
            double lean = Double.isNaN(value) ? want : Math.max(value - MAX_LEAN_STEP, Math.min(value + MAX_LEAN_STEP, want));
            // easing lags, so the lean overshoots and has to come back. one swap of sides is steering, the second one is
            // chasing our own overshoot and is the left right wobble, so that one stops at straight ahead instead
            int sign = Math.abs(lean) > VISIBLE_LEAN ? (int) Math.signum(lean) : 0;
            if (sign != 0 && sign != side) {
                if (side != 0 && swaps++ > 0) {
                    lean = 0;
                } else {
                    side = sign;
                }
            }
            return value = lean;
        }
    }

    private static double deadband(double x, double band) {
        return Math.signum(x) * Math.max(0, Math.abs(x) - band);
    }

    // facing down the line (ux, uz), leaned sideways by lean (positive is the way across() measures)
    static float yaw(double ux, double uz, double lean) {
        double forward = Math.sqrt(1 - lean * lean);
        return (float) Math.toDegrees(Math.atan2(uz * forward + ux * lean, ux * forward - uz * lean)) - 90;
    }

    private final Lean lean = new Lean();
    // the executor holds W, S or nothing off this
    private int lastThrottle = PUSH;

    int throttle() {
        return lastThrottle;
    }
}
