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

package baritone.pathing.movement;

import baritone.api.pathing.movement.IMovement;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// a movement that isn't a straight line from src to dest and wants the path renderer to say so
// (neos swing out round their wall, momentum jumps arc over the gap). the renderer only knows this, never the movements
public interface CurvedMovement {

    // block corner coordinates like path positions are, so the renderer's +0.5 centers them. first point is src, last is dest
    Vec3[] curve();

    // the cheap question, for when the answer doesn't need the points
    static boolean isCurved(List<IMovement> movements, int i) {
        return movements != null && i >= 0 && i < movements.size() && movements.get(i) instanceof CurvedMovement;
    }

    // movement i's curve, or null if it's a plain straight one (or there are no movements, unverified paths don't have any)
    static Vec3[] of(List<IMovement> movements, int i) {
        if (!isCurved(movements, i)) {
            return null;
        }
        Vec3[] curve = ((CurvedMovement) movements.get(i)).curve();
        // one point is not a line
        return curve == null || curve.length < 2 ? null : curve;
    }

    // how far you'd walk along it. the renderer's shimmer is measured in this, so everything has to agree on it
    static double length(Vec3[] curve) {
        double length = 0;
        for (int k = 1; k < curve.length; k++) {
            length += curve[k].distanceTo(curve[k - 1]);
        }
        return length;
    }
}
