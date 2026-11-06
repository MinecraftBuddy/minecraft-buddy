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

package baritone.pathing.sim;

// what PlayerSim needs to know about the world, a block at a time. the game one reads real blocks, the tests make a few up
public interface CollisionProvider {

    // appends the collision boxes of one block cell, in world coordinates
    void boxes(int x, int y, int z, BoxList out);

    // false for anything the sim does not do the maths for: friction other than 0.6, speed or jump factors, fluids,
    // climbables, cobwebs, powder snow and so on. standing in or on one of these makes the sim give up instead of lying
    boolean plain(int x, int y, int z);
}
