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
 * How hard AltoClef's mob defense force field hits back, see {@code Settings#altoForceFieldStrategy}.
 */
public enum ForceFieldStrategy {
    /**
     * No force field at all.
     */
    OFF,
    /**
     * Every hostile is attacked at every possible moment.
     */
    FASTEST,
    /**
     * The closest hostile is attacked when the sword is charged up.
     */
    DELAY,
    /**
     * The closest hostile is attacked at most every 0.2 seconds.
     */
    SMART
}
