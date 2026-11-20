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

package adris.altoclef.util.helpers;

// (Tuning creeper_way_ban, 20 Nov 2026) a run from a creeper that kept coming back ended clear, and the task walked straight
// back to the same oak log past the same unlit creeper, every ~17 s for 4.8 minutes, no log. what the task gathers by that
// creeper, or whose straight way from where the run ended passes it, is left a while so the next pick is another tree or vein.
// pure: the caller feeds it positions (horizontal, x and z)
public final class CreeperWay {
    // a block this close to the creeper is by it
    public static final double NEAR = 6;
    // a straight way that passes the creeper this close goes by it
    public static final double WAY = 4;
    // a creeper this far above or below the buddy is in another cave or up a hill, not on the way
    public static final double LEVEL = 8;
    // a creeper this close to where the run began is the one it began for
    public static final double ORIGIN = 10;

    private CreeperWay() {
    }

    // the block at (bx, bz) is by the creeper at (cx, cz), or the straight way to it from (px, pz) passes it
    public static boolean onTheWay(double px, double pz, double cx, double cz, double bx, double bz) {
        double dbx = bx - cx, dbz = bz - cz;
        if (dbx * dbx + dbz * dbz <= NEAR * NEAR) return true;
        double sx = bx - px, sz = bz - pz;
        double len2 = sx * sx + sz * sz;
        if (len2 < 1e-6) return false;
        // where along the way the creeper is closest: before the start or past the block, the way does not go by it
        double t = ((cx - px) * sx + (cz - pz) * sz) / len2;
        if (t <= 0 || t >= 1) return false;
        double qx = px + t * sx - cx, qz = pz + t * sz - cz;
        return qx * qx + qz * qz <= WAY * WAY;
    }

    // what a task goes out to gather (a block's registry path): logs and stems, ores. a crafting table, a chest or the portal
    // being built are ours and stay offered
    public static boolean gathered(String path) {
        return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_ore") || path.equals("ancient_debris");
    }
}
