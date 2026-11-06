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

package adris.altoclef.commands;

import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;

// the part of altoclef's goto that baritone's goto can't do: walking through portals to another dimension. soprano's
// #goto hands off to this when its last word is a dimension, and the butler/idle path uses it for every goto
public final class CrossDimensionGoto {

    // what completes. "end" works as well, it just isn't the name the game uses
    public static final List<String> DIMENSION_NAMES = List.of("overworld", "nether", "the_end");

    private CrossDimensionGoto() {
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    // null when the word is not a dimension
    public static Dimension parseDimension(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "overworld" -> Dimension.OVERWORLD;
            case "nether", "the_nether" -> Dimension.NETHER;
            case "end", "the_end" -> Dimension.END;
            default -> null;
        };
    }

    public static boolean endsInDimension(List<String> words) {
        return !words.isEmpty() && parseDimension(words.get(words.size() - 1)) != null;
    }

    // [x y z], [x z], [y] or nothing, then an optional dimension. nothing at all is not a destination
    // altoclef never took ~ here, and ~ in another dimension doesn't mean anything useful anyway
    public static Task parse(List<String> words) throws ParseException {
        List<String> parts = new ArrayList<>(words);
        // "(1 2 3)" used to be fine
        if (!parts.isEmpty() && parts.get(0).startsWith("(")) {
            parts.set(0, parts.get(0).substring(1));
        }
        if (!parts.isEmpty() && parts.get(parts.size() - 1).endsWith(")")) {
            String last = parts.get(parts.size() - 1);
            parts.set(parts.size() - 1, last.substring(0, last.length() - 1));
        }
        parts.removeIf(String::isEmpty);

        Dimension dimension = null;
        if (endsInDimension(parts)) {
            dimension = parseDimension(parts.remove(parts.size() - 1));
        }
        List<Integer> nums = new ArrayList<>();
        for (String p : parts) {
            try {
                nums.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                throw new ParseException("\"" + p + "\" is not a whole number or a dimension (overworld, nether, the_end)");
            }
        }
        switch (nums.size()) {
            case 0:
                if (dimension == null) {
                    throw new ParseException("need coordinates, a dimension, or both");
                }
                return new DefaultGoToDimensionTask(dimension);
            case 1:
                return new GetToYTask(nums.get(0), dimension);
            case 2:
                return new GetToXZTask(nums.get(0), nums.get(1), dimension);
            case 3:
                return new GetToBlockTask(new BlockPos(nums.get(0), nums.get(1), nums.get(2)), dimension);
            default:
                throw new ParseException("that is " + nums.size() + " numbers, a position is at most 3 (x y z)");
        }
    }
}
