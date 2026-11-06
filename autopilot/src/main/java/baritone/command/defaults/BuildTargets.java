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

package baritone.command.defaults;

import org.apache.commons.io.FilenameUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

// which placed schematic a "#build <file>" means. the gui keys its placements by the path under the schematics folder
// with forward slashes (SchematicLibrary), so this turns what somebody typed into that same shape and looks it up.
// no minecraft in here, the tests poke it directly
final class BuildTargets {

    private BuildTargets() {
    }

    // the typed path as a placement key, null when it points outside the folder (absolute, or climbing out with ..).
    // those were never placed in the gui, they get the feet like they always did
    static String libraryKey(String typed) {
        // a colon is a windows drive ("c:castle" is relative to c:'s cwd, not to us), and FilenameUtils throws on
        // one there anyway
        if (typed == null || typed.isEmpty() || typed.startsWith("/") || typed.startsWith("\\") || typed.contains(":")) {
            return null;
        }
        try {
            if (Paths.get(typed).isAbsolute()) {
                return null;
            }
        } catch (InvalidPathException e) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (String part : typed.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                return null;
            }
            if (key.length() > 0) {
                key.append('/');
            }
            key.append(part);
        }
        return key.length() == 0 ? null : key.toString();
    }

    // the placed keys the typed key means: the exact path, or with no extension typed every file with that name. the
    // spelling as typed wins, and only when that misses does case stop mattering (windows found the file either way,
    // so a placement it can't find over a capital letter would be a weird way to end up at your feet)
    static List<String> matches(Collection<String> placed, String key) {
        List<String> hits = find(placed, key, false);
        return hits.isEmpty() ? find(placed, key, true) : hits;
    }

    private static List<String> find(Collection<String> placed, String key, boolean anyCase) {
        List<String> exact = new ArrayList<>();
        List<String> byStem = new ArrayList<>();
        boolean stem = FilenameUtils.getExtension(key).isEmpty();
        for (String path : placed) {
            if (same(path, key, anyCase)) {
                exact.add(path);
            } else if (stem && same(FilenameUtils.removeExtension(path), key, anyCase)) {
                byStem.add(path);
            }
        }
        return exact.isEmpty() ? byStem : exact;
    }

    private static boolean same(String a, String b, boolean anyCase) {
        return anyCase ? a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT)) : a.equals(b);
    }
}
