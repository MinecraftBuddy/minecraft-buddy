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
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class PathSmoothingTest {

    @Test
    public void targetIsContinuousAcrossMovementBoundary() {
        List<BetterBlockPos> path = line(1, 0);
        Vec3 before = target(path, 0, new Vec3(0.99, 64, 0.6));
        Vec3 after = target(path, 1, new Vec3(1.01, 64, 0.6));
        assertEquals(2.5, before.x - 0.99, 1e-9);
        assertEquals(0.02, after.x - before.x, 1e-9);
        assertEquals(0.5, after.z, 1e-9);
    }

    @Test
    public void diagonalTargetIsContinuousAcrossMovementBoundary() {
        List<BetterBlockPos> path = line(1, 1);
        Vec3 before = target(path, 0, new Vec3(0.99, 64, 0.99));
        Vec3 after = target(path, 1, new Vec3(1.01, 64, 1.01));
        assertEquals(0.02, after.x - before.x, 1e-9);
        assertEquals(0.02, after.z - before.z, 1e-9);
    }

    @Test
    public void openDiagonalsWorkInEveryDirection() {
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                Vec3 result = target(line(dx, dz), 0, new Vec3(0.5, 64, 0.5));
                assertNotNull(result);
                assertEquals(2.5, Math.hypot(result.x - 0.5, result.z - 0.5), 1e-9);
                assertEquals(dx * (result.x - 0.5), dz * (result.z - 0.5), 1e-9);
            }
        }
    }

    @Test
    public void clampsAtTurnAndUsesPreciseSteeringForLastStep() {
        List<BetterBlockPos> path = path(new int[][]{{0, 64, 0}, {1, 64, 0}, {2, 64, 0}, {2, 64, 1}});
        assertEquals(2.5, target(path, 0, new Vec3(0.8, 64, 0.5)).x, 1e-9);
        assertNull(target(path, 1, new Vec3(1.5, 64, 0.5)));
        assertNull(target(path, 2, new Vec3(2.5, 64, 0.5)));
    }

    @Test
    public void clampsAtGoal() {
        List<BetterBlockPos> path = line(1, 0).subList(0, 3);
        Vec3 result = target(path, 0, new Vec3(0.9, 64, 0.5));
        assertEquals(2.5, result.x, 1e-9);
        assertNull(target(path, 1, new Vec3(1.5, 64, 0.5)));
        assertNull(target(path, 2, new Vec3(2.5, 64, 0.5)));
    }

    @Test
    public void doesNotSmoothAcrossAscentsFallsOrReversals() {
        for (int[] next : new int[][]{{2, 65, 0}, {2, 63, 0}, {2, 60, 0}, {0, 64, 0}, {1, 64, 1}}) {
            assertNull(target(path(new int[][]{{0, 64, 0}, {1, 64, 0}, next}),
                    0, new Vec3(0.5, 64, 0.5)));
        }
    }

    @Test
    public void rejectsSpecialMovementsBeforeLookingBeyondThem() {
        assertNull(PathSmoothing.lookAhead(line(1, 0), 0, new Vec3(0.5, 64, 0.5), i -> i != 0, p -> true));
        assertNull(PathSmoothing.lookAhead(line(1, 0), 0, new Vec3(0.5, 64, 0.5), i -> i != 1, p -> true));
        Vec3 result = PathSmoothing.lookAhead(line(1, 0), 0, new Vec3(0.8, 64, 0.5), i -> i < 2, p -> true);
        assertEquals(2.5, result.x, 1e-9);
    }

    @Test
    public void diagonalChecksBothSideColumnsAndTheirSupport() {
        for (BlockPos obstruction : Arrays.asList(new BlockPos(1, 64, 0), new BlockPos(0, 64, 1),
                new BlockPos(2, 64, 1), new BlockPos(1, 64, 2))) {
            assertNull(PathSmoothing.lookAhead(line(1, 1), 0, new Vec3(0.5, 64, 0.5),
                    i -> true, p -> !p.equals(obstruction)));
        }
    }

    @Test
    public void rechecksClearanceWhenTerrainChanges() {
        Set<BlockPos> blocked = new HashSet<>();
        List<BetterBlockPos> path = line(1, 0);
        Vec3 player = new Vec3(0.5, 64, 0.5);
        assertNotNull(PathSmoothing.lookAhead(path, 0, player, i -> true, p -> !blocked.contains(p)));
        blocked.add(new BlockPos(2, 64, 0));
        assertNull(PathSmoothing.lookAhead(path, 0, player, i -> true, p -> !blocked.contains(p)));
    }

    @Test
    public void recoveryAndAirbornePositionsKeepNormalSteering() {
        for (Vec3 player : Arrays.asList(new Vec3(0.5, 64, 0.8), new Vec3(0.5, 64.2, 0.5),
                new Vec3(-0.1, 64, 0.5), new Vec3(2, 64, 0.5))) {
            assertNull(target(line(1, 0), 0, player));
        }
    }

    @Test
    public void limitsTerrainQueriesToFourMovements() {
        Set<BlockPos> checked = new HashSet<>();
        assertNotNull(PathSmoothing.lookAhead(line(1, 0), 0, new Vec3(0.5, 64, 0.5), i -> true, p -> {
            checked.add(p);
            return true;
        }));
        assertEquals(5, checked.size());
        assertFalse(checked.contains(new BlockPos(5, 64, 0)));
    }

    private static Vec3 target(List<BetterBlockPos> path, int index, Vec3 player) {
        return PathSmoothing.lookAhead(path, index, player, i -> true, p -> true);
    }

    private static List<BetterBlockPos> line(int dx, int dz) {
        return java.util.stream.IntStream.rangeClosed(0, 6)
                .mapToObj(i -> new BetterBlockPos(i * dx, 64, i * dz)).collect(Collectors.toList());
    }

    private static List<BetterBlockPos> path(int[][] points) {
        return Arrays.stream(points).map(p -> new BetterBlockPos(p[0], p[1], p[2])).collect(Collectors.toList());
    }
}
