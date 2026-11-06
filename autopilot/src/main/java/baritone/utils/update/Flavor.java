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

package baritone.utils.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// which of the release jars we are: soprano-<variant>-<loader>-<ver>.jar. an api fabric player gets the api fabric
// jar back, handing them the unoptimized one would be a weird way to say thanks for updating
public record Flavor(String variant, String loader) {

    public static final String FABRIC = "fabric";
    public static final String FORGE = "forge";
    public static final String NEOFORGE = "neoforge";

    private static final Pattern NAME = Pattern.compile("soprano-([a-z]+)-(neoforge|forge|fabric)-(.+)\\.jar", Pattern.CASE_INSENSITIVE);

    // the release asset this flavor wants for a given version string ("1.1.7" or "1.1.7-anyversion")
    public String assetName(String version) {
        return "soprano-" + variant + "-" + loader + "-" + version + ".jar";
    }

    // null when the file was renamed into something we can't read, SelfJar falls back to the jar's metadata then
    public static Flavor fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return new Flavor(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2).toLowerCase(java.util.Locale.ROOT));
    }

    // what a jar of this loader must have, both for working out a renamed jar and for checking a download
    public static String metadataFile(String loader) {
        return switch (loader) {
            case FABRIC -> "fabric.mod.json";
            case FORGE -> "META-INF/mods.toml";
            case NEOFORGE -> "META-INF/neoforge.mods.toml";
            default -> null;
        };
    }
}
