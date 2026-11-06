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

package baritone.api.utils;

/**
 * What AltoClef does when it needs the nether and there is no portal in sight, see
 * {@code Settings#altoOverworldToNetherBehaviour}.
 */
public enum OverworldToNetherBehaviour {
    /**
     * Build a portal, out of obsidian or with a water bucket and a lava pool.
     */
    BUILD_PORTAL_VANILLA,
    /**
     * Walk to the home base and assume a portal is there.
     */
    GO_TO_HOME_BASE
}
