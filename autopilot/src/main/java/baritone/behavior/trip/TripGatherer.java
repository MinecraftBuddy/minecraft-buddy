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

package baritone.behavior.trip;

import net.minecraft.world.item.Item;

import java.util.List;

// whoever actually goes and gets the stuff. altoclef does (baritone.altoclef.TripGatherBridge), the trip only knows
// this much about it. client thread only
public interface TripGatherer {

    enum Status {
        NONE,
        RUNNING,
        DONE,
        FAILED
    }

    // collect `blocks` more of any of these items (on top of what's held) and/or a stone pickaxe. false if it can't even
    // start, altoclef being off or busy with something of the user's
    boolean start(long tripId, List<Item> items, int blocks, boolean pickaxe);

    Status status(long tripId);

    // the trip is over, so is its gather. a gather for some other trip id is left alone
    void cancel(long tripId);
}
