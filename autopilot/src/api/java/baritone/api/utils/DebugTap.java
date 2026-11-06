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

package baritone.api.utils;

import java.util.function.Consumer;

/**
 * A listener for every {@link Helper#logDebug(String)} message, whether or not {@code chatDebug} lets it reach chat.
 * Nothing is registered by default, so the messages are dropped like they always were.
 */
public final class DebugTap {

    private static volatile Consumer<String> sink;

    private DebugTap() {
    }

    /**
     * Sets where debug messages go, or {@code null} for nowhere. The sink is called on whatever thread logged,
     * including path calculation threads.
     *
     * @param to The listener, or {@code null} to remove it
     */
    public static void setSink(Consumer<String> to) {
        sink = to;
    }

    // not public: only Helper feeds this
    static void accept(String message) {
        Consumer<String> to = sink;
        if (to == null || message == null) {
            return;
        }
        try {
            to.accept(message);
        } catch (RuntimeException ignored) {
            // a log helper has no business taking the pathing thread down
        }
    }
}
