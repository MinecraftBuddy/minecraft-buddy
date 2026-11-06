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

package baritone.api;

// test only stand in for the real BaritoneAPI, which builds a BaritoneProvider in its static init and that
// wants a running client. the test source set comes first on the test classpath so this one wins there,
// and anything that just asks for settings gets a plain Settings with no client behind it
public final class BaritoneAPI {

    private static final IBaritoneProvider provider = null;
    private static final Settings settings = new Settings();

    public static IBaritoneProvider getProvider() {
        return provider;
    }

    public static Settings getSettings() {
        return settings;
    }
}
