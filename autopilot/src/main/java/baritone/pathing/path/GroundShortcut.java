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

package baritone.pathing.path;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

final class GroundShortcut {

    private static final int MAX_MOVEMENTS = 8;
    // standing player half-width plus a little room for look quantization and sideways drift
    private static final double HALF_WIDTH = 0.35;
    private static final double MAX_PATH_DEVIATION = 1.0;
    // the safety margin for "where could momentum put us", a little more than the real slide on purpose
    private static final double COAST_TICKS = 2.5;
    // the real slide once W is let go: v + v * f + v * f^2 ... = v / (1 - f), f being 0.6 * 0.91. letting go at 2.5 v out
    // left us 0.3 v short of the node every time
    private static final double SLIDE_TICKS = 1 / (1 - SprintJump.GROUND_FRICTION);

    final int endIndex;
    final Vec3 start;
    final Vec3 target;

    private GroundShortcut(int endIndex, Vec3 start, Vec3 target) {
        this.endIndex = endIndex;
        this.start = start;
        this.target = target;
    }

    static GroundShortcut find(List<BetterBlockPos> path, int index, Vec3 player,
                               IntPredicate plainMovement, Predicate<BlockPos> clearColumn) {
        if (index < 0 || index + 2 >= path.size()) {
            return null;
        }
        Vec3 source = center(path.get(index));
        Vec3 first = center(path.get(index + 1));
        if (Math.abs(player.y - source.y) > 0.05 || distanceToSegment(player, source, first) > 0.4) {
            return null; // recover from knockback using the original movement
        }
        int end = index;
        for (int i = index; i < Math.min(index + MAX_MOVEMENTS, path.size() - 1); i++) {
            BlockPos step = path.get(i + 1).subtract(path.get(i));
            if (step.getY() != 0 || Math.abs(step.getX()) > 1 || Math.abs(step.getZ()) > 1
                    || step.getX() == 0 && step.getZ() == 0 || !plainMovement.test(i)) {
                break;
            }
            end = i + 1;
        }
        for (int i = end; i >= index + 2; i--) {
            Vec3 target = center(path.get(i));
            if (usefulShortcut(path, index, i, player, target)
                    && clearSegment(player, target, clearColumn)) {
                return new GroundShortcut(i, player, target);
            }
        }
        return null;
    }

    private static boolean usefulShortcut(List<BetterBlockPos> path, int index, int end, Vec3 player, Vec3 target) {
        double direct = player.distanceTo(target);
        double original = player.distanceTo(center(path.get(index + 1)));
        double previousProgress = 0;
        Vec3 direction = target.subtract(player);
        for (int i = index + 1; i <= end; i++) {
            Vec3 node = center(path.get(i));
            double progress = node.subtract(player).dot(direction);
            if (progress < previousProgress || distanceToSegment(node, player, target) > MAX_PATH_DEVIATION) {
                return false; // no U-turns or long cuts across the inside of a detour
            }
            previousProgress = progress;
            if (i < end) {
                original += node.distanceTo(center(path.get(i + 1)));
            }
        }
        // straight runs already have smoothing and sprint jumps. only take over for an actual bend.
        return direct >= 1.5 && original - direct > 0.1;
    }

    boolean canContinue(Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn) {
        return stopReason(player, velocity, clearColumn) == null;
    }

    // null when the shortcut can go on, else the first thing wrong with it
    String stopReason(Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn) {
        if (Math.abs(player.y - target.y) > 0.05) {
            return "not at the height of its target";
        }
        double off = distanceToSegment(player, start, target);
        if (off > 0.6) {
            return String.format(Locale.ROOT, "%.2f blocks off its line", off);
        }
        if (!clearSegment(player, target, clearColumn)) {
            return "the way to its target is blocked";
        }
        if (!clearSegment(player, clipAtTarget(player, coastPosition(player, velocity)), clearColumn)) {
            return "the slide from here is blocked";
        }
        return null;
    }

    // the cell that made stopReason say a way is blocked, for the telemetry: the same two checks in the same order, so it is the one
    // stopReason tripped on. null when neither is
    BlockPos stopCell(Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn) {
        BlockPos cell = firstBlocked(player, target, HALF_WIDTH, clearColumn);
        return cell != null ? cell : firstBlocked(player, clipAtTarget(player, coastPosition(player, velocity)), HALF_WIDTH, clearColumn);
    }

    boolean arrived(Vec3 player, Vec3 velocity) {
        Vec3 coast = coastPosition(player, velocity);
        return player.distanceTo(target) <= 0.3
                && Math.abs(coast.x - target.x) < 0.4 && Math.abs(coast.z - target.z) < 0.4;
    }

    boolean shouldMoveForward(Vec3 player, Vec3 velocity) {
        Vec3 remaining = target.subtract(player);
        // releasing W early lets ground friction catch us before a turn, goal, or precise movement.
        return remaining.lengthSqr() > 0.04
                && remaining.dot(velocity.scale(SLIDE_TICKS)) < remaining.lengthSqr();
    }

    private static Vec3 coastPosition(Vec3 player, Vec3 velocity) {
        return player.add(velocity.x * COAST_TICKS, 0, velocity.z * COAST_TICKS);
    }

    // what W is worth on the ground at sprint pace, a tick of acceleration along the look before the move
    static final double PUSH = 0.1274;
    // W held for this many more ticks (it is looked at again every tick, so the next one can always let go) and then the slide
    private static final int HELD_TICKS = 2, TRAJECTORY_TICKS = 12;

    // where the next ticks take the player: `push` is added to the velocity before each of the first `held` moves, the way W does,
    // and friction takes it down afterward. one point per tick. the old check was one straight line to the far end of this, and
    // on a bend the real path bulges away from that line (old heading first, new heading after), which is exactly where the
    // wall corner on the outside of the turn sits
    static List<Vec3> trajectory(Vec3 from, Vec3 velocity, Vec3 push, int held) {
        List<Vec3> points = new ArrayList<>(TRAJECTORY_TICKS + 1);
        points.add(from);
        Vec3 position = from;
        Vec3 v = velocity;
        for (int tick = 0; tick < TRAJECTORY_TICKS; tick++) {
            if (tick < held) {
                v = v.add(push);
            }
            position = position.add(v.x, 0, v.z);
            v = v.scale(SprintJump.GROUND_FRICTION);
            points.add(position);
        }
        return points;
    }

    // true if the player's footprint can go along the whole line of points
    static boolean clearPath(List<Vec3> points, Predicate<BlockPos> clearColumn) {
        return firstBlockedOnPath(points, clearColumn) == null;
    }

    private static BlockPos firstBlockedOnPath(List<Vec3> points, Predicate<BlockPos> clearColumn) {
        for (int i = 1; i < points.size(); i++) {
            BlockPos cell = firstBlocked(points.get(i - 1), points.get(i), HALF_WIDTH, clearColumn);
            if (cell != null) {
                return cell;
            }
        }
        return null;
    }

    // holding W towards `look` (a direction, any length) from here: is the way clear for the next ticks and the slide after them
    static boolean clearAhead(Vec3 player, Vec3 velocity, Vec3 look, Predicate<BlockPos> clearColumn) {
        return clearPath(ahead(player, velocity, look), clearColumn);
    }

    private static List<Vec3> ahead(Vec3 player, Vec3 velocity, Vec3 look) {
        Vec3 flat = new Vec3(look.x, 0, look.z);
        Vec3 push = flat.lengthSqr() == 0 ? Vec3.ZERO : flat.normalize().scale(PUSH);
        return trajectory(player, velocity, push, HELD_TICKS);
    }

    // which check said no to holding W, and on which cell
    record Blocked(String check, BlockPos cell) {
    }

    // holding W with the camera at `yaw` (radians) from here, on a shortcut from `start` to `target`: null when the line to the far
    // end of the push and the curve the momentum really takes there (both clipped at the target) are clear, else the first thing in the way
    static Blocked blockedAhead(Vec3 start, Vec3 target, Vec3 player, Vec3 velocity, double yaw, Predicate<BlockPos> clearColumn) {
        Vec3 look = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 projected = player.add(velocity.x * 2.5 + look.x * 0.5, 0, velocity.z * 2.5 + look.z * 0.5);
        BlockPos cell = firstBlocked(player, clipAtTarget(start, target, player, projected), HALF_WIDTH, clearColumn);
        if (cell != null) {
            return new Blocked("forward projection", cell);
        }
        cell = firstBlockedOnPath(clipAtTarget(start, target, ahead(player, velocity, look)), clearColumn);
        return cell == null ? null : new Blocked("trajectory", cell);
    }

    private Vec3 clipAtTarget(Vec3 player, Vec3 end) {
        return clipAtTarget(start, target, player, end);
    }

    // how far along start -> target a point is: 0 at the start, 1 at the target, only looking at the floor plane
    private static double progress(Vec3 start, Vec3 target, Vec3 point) {
        double dx = target.x - start.x, dz = target.z - start.z;
        double length = dx * dx + dz * dz;
        return length == 0 ? 0 : ((point.x - start.x) * dx + (point.z - start.z) * dz) / length;
    }

    // the projections all run on past the node (momentum, half a block of push, the slide) and what is past the node is not ours to
    // judge: the wall on the outside of the bend, the step block of an ascend that starts there. so a segment from `from` to `end`
    // stops where it crosses the plane through the target, square to the way the shortcut is going. one that starts past the
    // plane and heads further out has nothing left to test. W is let go of early enough for the slide to land on the node, and a
    // player who overshoots anyway runs into "hit something"
    static Vec3 clipAtTarget(Vec3 start, Vec3 target, Vec3 from, Vec3 end) {
        double a = progress(start, target, from), b = progress(start, target, end);
        if (b <= 1 || b <= a) {
            return end;
        }
        return a >= 1 ? from : from.add(end.subtract(from).scale((1 - a) / (b - a)));
    }

    // same for a whole trajectory: it ends at the first point that crosses that plane, at the plane
    static List<Vec3> clipAtTarget(Vec3 start, Vec3 target, List<Vec3> points) {
        List<Vec3> kept = new ArrayList<>(points.size());
        kept.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            Vec3 clipped = clipAtTarget(start, target, points.get(i - 1), points.get(i));
            if (clipped != points.get(i)) {
                if (!clipped.equals(points.get(i - 1))) {
                    kept.add(clipped);
                }
                break;
            }
            kept.add(points.get(i));
        }
        return kept;
    }

    // the movement a shortcut that lets go can carry on with, or -1 when the feet are on none of them. standing on the end node
    // is the end movement itself (the last one when there is none, it finishes on the spot). the end movement's other valid
    // positions (a runway, a diagonal's corners) don't count, it has not started and would be handed a player off its start.
    // before it, the last of the movements from..to-1 whose valid positions hold the feet: the node between two movements is
    // the end of one and the start of the next, and the next is the one still to do
    static <T> int resumeIndex(IntFunction<? extends Collection<? extends T>> validAt, int from, int to, int count, T feet, T endNode) {
        if (feet.equals(endNode)) {
            return Math.min(to, count - 1);
        }
        for (int i = Math.min(to - 1, count - 1); i >= from; i--) {
            if (validAt.apply(i).contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    // exact segment-versus-expanded-cell intersections, not a ray or samples that can miss a corner.
    // every cell touched by the player's swept footprint must have both clearance and full support.
    static boolean clearSegment(Vec3 from, Vec3 to, Predicate<BlockPos> clearColumn) {
        return clearSegment(from, to, HALF_WIDTH, clearColumn);
    }

    static boolean clearSegment(Vec3 from, Vec3 to, double halfWidth, Predicate<BlockPos> clearColumn) {
        return firstBlocked(from, to, halfWidth, clearColumn) == null;
    }

    // the first cell the footprint touches that is no good, null when they all are. a segment too long or not level has no cell
    // to blame, so it blames the one the player is in
    static BlockPos firstBlocked(Vec3 from, Vec3 to, double halfWidth, Predicate<BlockPos> clearColumn) {
        if (Math.abs(from.y - to.y) > 0.05 || from.distanceToSqr(to) > 256) {
            return BlockPos.containing(from);
        }
        int y = (int) Math.floor(from.y + 0.05);
        for (int x = (int) Math.floor(Math.min(from.x, to.x) - halfWidth);
             x <= (int) Math.floor(Math.max(from.x, to.x) + halfWidth); x++) {
            for (int z = (int) Math.floor(Math.min(from.z, to.z) - halfWidth);
                 z <= (int) Math.floor(Math.max(from.z, to.z) + halfWidth); z++) {
                if (intersectsCell(from, to, x, z, halfWidth) && !clearColumn.test(new BlockPos(x, y, z))) {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    private static boolean intersectsCell(Vec3 from, Vec3 to, int x, int z, double halfWidth) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double minX = x - halfWidth;
        double maxX = x + 1 + halfWidth;
        double minZ = z - halfWidth;
        double maxZ = z + 1 + halfWidth;
        if (dx == 0 && (from.x < minX || from.x > maxX)
                || dz == 0 && (from.z < minZ || from.z > maxZ)) {
            return false;
        }
        double enterX = dx == 0 ? Double.NEGATIVE_INFINITY : Math.min((minX - from.x) / dx, (maxX - from.x) / dx);
        double exitX = dx == 0 ? Double.POSITIVE_INFINITY : Math.max((minX - from.x) / dx, (maxX - from.x) / dx);
        double enterZ = dz == 0 ? Double.NEGATIVE_INFINITY : Math.min((minZ - from.z) / dz, (maxZ - from.z) / dz);
        double exitZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.max((minZ - from.z) / dz, (maxZ - from.z) / dz);
        return Math.max(0, Math.max(enterX, enterZ)) <= Math.min(1, Math.min(exitX, exitZ));
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.lengthSqr();
        double progress = length == 0 ? 0 : Math.max(0, Math.min(1, point.subtract(from).dot(delta) / length));
        return point.distanceTo(from.add(delta.scale(progress)));
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
