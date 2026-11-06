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

package baritone.utils;

import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CurvedMovement;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CurvedPathLengthTest {

    // a bump out of the straight line from (0,0,0) to (4,0,0): up to (2,1,0) and back, so two sqrt(5)s
    private static final Vec3[] BUMP = {new Vec3(0, 0, 0), new Vec3(2, 1, 0), new Vec3(4, 0, 0)};
    private static final double BUMP_LENGTH = 2 * Math.sqrt(5);

    // every movement is a proxy, none of them do anything except admit to being curved (or not)
    private static IMovement curved(Vec3[] curve) {
        return (IMovement) Proxy.newProxyInstance(CurvedPathLengthTest.class.getClassLoader(),
                new Class<?>[]{IMovement.class, CurvedMovement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("curve")) {
                        return curve;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IMovement straight() {
        return (IMovement) Proxy.newProxyInstance(CurvedPathLengthTest.class.getClassLoader(),
                new Class<?>[]{IMovement.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void curveLengthIsTheSumOfItsSegments() {
        assertEquals(BUMP_LENGTH, CurvedMovement.length(BUMP), 1.0E-9);
        assertEquals(0, CurvedMovement.length(new Vec3[]{new Vec3(1, 2, 3)}), 0);
    }

    @Test
    public void ofOnlyFindsCurvedMovements() {
        List<IMovement> movements = Arrays.asList(straight(), curved(BUMP));
        assertNull(CurvedMovement.of(movements, 0));
        assertSame(BUMP, CurvedMovement.of(movements, 1));
        assertNull(CurvedMovement.of(movements, 2));
        assertNull(CurvedMovement.of(movements, -1));
        // unverified paths don't have movements at all
        assertNull(CurvedMovement.of(null, 0));
        // a curve that is one point is not a line, don't try to draw it
        assertNull(CurvedMovement.of(Arrays.asList(curved(new Vec3[]{new Vec3(0, 0, 0)})), 0));
    }

    @Test
    public void ribbonMeasuresTheCurveNotTheChord() {
        List<BetterBlockPos> positions = Arrays.asList(new BetterBlockPos(0, 0, 0), new BetterBlockPos(4, 0, 0), new BetterBlockPos(4, 0, 3));
        List<IMovement> movements = Arrays.asList(curved(BUMP), straight());
        // the shimmer is measured from the start of the path, so it has to come out the same however you slice it
        assertEquals(BUMP_LENGTH, PathRibbon.length(positions, movements, 0, 1, true), 1.0E-9);
        assertEquals(BUMP_LENGTH + 3, PathRibbon.length(positions, movements, 0, 2, true), 1.0E-9);
        assertEquals(3, PathRibbon.length(positions, movements, 1, 2, true), 1.0E-9);
        // and without movements it's the straight line it always was
        assertEquals(7, PathRibbon.length(positions, null, 0, 2, true), 1.0E-9);
    }
}
