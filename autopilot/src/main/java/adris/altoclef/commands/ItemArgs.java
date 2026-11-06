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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

// the "iron_ingot 3 diamond 2" grammar that get/deposit/stash/equip all share. no minecraft in here on purpose, so it
// can be unit tested without booting a game: whether a name is real is the caller's problem (AltoItem)
public final class ItemArgs {

    private ItemArgs() {
    }

    public record Entry(String name, int count) {
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    // "[iron_ingot 3, diamond 2]" is the old altoclef spelling, it ends up as the same tokens as the pair form
    public static List<String> tokens(String rest) {
        List<String> out = new ArrayList<>();
        for (String t : rest.replace('[', ' ').replace(']', ' ').replace(',', ' ').trim().split("\\s+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // lowercase and no minecraft: namespace, the catalogue only knows bare names
    public static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    private static boolean isNumber(String token) {
        // not Integer.parseInt: "99999999999" is still a count, just a silly one, and should say so
        if (token.isEmpty()) {
            return false;
        }
        int start = token.charAt(0) == '-' || token.charAt(0) == '+' ? 1 : 0;
        if (start == token.length()) {
            return false;
        }
        for (int i = start; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static List<Entry> parse(String rest) throws ParseException {
        return parse(tokens(rest));
    }

    // names with an optional count after each one. the same name twice adds up, like it always did
    public static List<Entry> parse(List<String> tokens) throws ParseException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int i = 0;
        while (i < tokens.size()) {
            String name = tokens.get(i++);
            if (isNumber(name)) {
                throw new ParseException("\"" + name + "\" is a count but there is no item in front of it");
            }
            int count = 1;
            if (i < tokens.size() && isNumber(tokens.get(i))) {
                String raw = tokens.get(i++);
                try {
                    count = Integer.parseInt(raw.startsWith("+") ? raw.substring(1) : raw);
                } catch (NumberFormatException e) {
                    throw new ParseException("\"" + raw + "\" is not a count i can work with");
                }
                if (count <= 0) {
                    throw new ParseException("the count for " + name + " has to be at least 1, got " + count);
                }
            }
            counts.merge(normalize(name), count, Integer::sum);
        }
        if (counts.isEmpty()) {
            throw new ParseException("no items given");
        }
        List<Entry> out = new ArrayList<>();
        counts.forEach((name, count) -> out.add(new Entry(name, count)));
        return out;
    }

    // what to suggest for the last word of `rest`. `names` is everything that can go in an item slot, `firstOnly` is
    // extra stuff that is only valid as the very first word (equip's "iron" armor sets). a count is free text so
    // while the user is typing digits there is nothing to offer, and after a count the next word has to be an item
    public static Stream<String> complete(String rest, Collection<String> names, Collection<String> firstOnly) {
        int split = lastBoundary(rest);
        String partial = rest.substring(split);
        String lead = "";
        if (partial.startsWith("[")) {
            // vanilla replaces the whole last word with the suggestion, so keep the bracket the user typed
            lead = "[";
            partial = partial.substring(1);
        }
        if (isNumber(partial) || partial.contains("]")) {
            return Stream.empty();
        }
        List<String> before = tokens(rest.substring(0, split));
        // the only thing that can go wrong in the words before is two counts in a row or a count up front, either way
        // the command will refuse it, so don't suggest anything on top of it
        for (int j = 0; j < before.size(); j++) {
            if (isNumber(before.get(j)) && (j == 0 || isNumber(before.get(j - 1)))) {
                return Stream.empty();
            }
        }
        String prefix = normalize(partial);
        String finalLead = lead;
        Stream<String> pool = before.isEmpty() ? Stream.concat(firstOnly.stream(), names.stream()) : names.stream();
        return pool
                .filter(n -> n.startsWith(prefix))
                .distinct()
                .map(n -> finalLead + n);
    }

    // index where the word being typed starts: after the last space or comma, or at a bracket that opens it
    private static int lastBoundary(String rest) {
        int i = rest.length();
        while (i > 0) {
            char c = rest.charAt(i - 1);
            if (Character.isWhitespace(c) || c == ',') {
                break;
            }
            i--;
        }
        return i;
    }
}
