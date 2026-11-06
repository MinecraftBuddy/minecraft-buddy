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

import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

final class PathSmoothing {

    private static final int MAX_MOVEMENTS = 4;
    private static final double LOOKAHEAD = 2.5;
    private static final double MAX_LATERAL_OFFSET = 0.15;

    private PathSmoothing() {}

    // null means the movement should keep its own precise steering. The predicates read live terrain
    // at execution time; a path calculated through a cached chunk isn't enough to authorize smoothing.
    static Vec3 lookAhead(List<BetterBlockPos> positions, int index, Vec3 player,
                          IntPredicate smoothableMovement, Predicate<BlockPos> clearColumn) {
        if (index < 0 || index + 2 >= positions.size()) {
            return null;
        }
        BetterBlockPos start = positions.get(index);
        BlockPos direction = positions.get(index + 1).subtract(start);
        int dx = direction.getX();
        int dz = direction.getZ();
        if (direction.getY() != 0 || Math.abs(dx) > 1 || Math.abs(dz) > 1 || dx == 0 && dz == 0
                || Math.abs(player.y - start.y) > 0.05) {
            return null;
        }
        double stepLength = Math.sqrt(dx * dx + dz * dz);
        double offX = player.x - (start.x + 0.5);
        double offZ = player.z - (start.z + 0.5);
        double progress = (offX * dx + offZ * dz) / stepLength;
        double lateral = Math.abs(offX * dz - offZ * dx) / stepLength;
        // a movement completes on entering its destination block, half a step before its center
        if (lateral > MAX_LATERAL_OFFSET || progress < -stepLength * 0.5 - 0.01 || progress > stepLength) {
            return null; // let normal movement recover from knockback or a missed waypoint
        }

        int end = index;
        for (int i = index; i < positions.size() - 1 && i < index + MAX_MOVEMENTS; i++) {
            BetterBlockPos from = positions.get(i);
            BetterBlockPos to = positions.get(i + 1);
            if (!to.subtract(from).equals(direction) || !smoothableMovement.test(i)
                    || !clearStep(from, to, clearColumn)) {
                break;
            }
            end = i + 1;
        }
        if (end <= index + 1) {
            return null;
        }
        // slide the target forward with the player, so crossing a block boundary doesn't snap the yaw.
        // Clamp to the end of the verified run: never steer through a turn or beyond the path's goal.
        double distance = Math.min(progress + LOOKAHEAD, (end - index) * stepLength);
        return new Vec3(start.x + 0.5 + dx * distance / stepLength, start.y,
                start.z + 0.5 + dz * distance / stepLength);
    }

    private static boolean clearStep(BlockPos from, BlockPos to, Predicate<BlockPos> clearColumn) {
        // a diagonal sweeps both side columns too, not just the two path nodes
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++) {
            for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                if (!clearColumn.test(new BlockPos(x, from.getY(), z))) {
                    return false;
                }
            }
        }
        return true;
    }
}
