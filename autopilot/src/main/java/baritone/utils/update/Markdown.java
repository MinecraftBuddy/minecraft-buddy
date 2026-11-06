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

import java.util.regex.Pattern;

// release notes are markdown and the title screen is not a browser. this is just enough to stop the card from
// showing asterisks and link urls, not a markdown parser. the links go away on purpose, nothing in the card is clickable
public final class Markdown {

    // ![alt](url) and [text](url), the bang is optional so images just leave their alt text behind
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private Markdown() {
    }

    public static String plain(String md) {
        if (md == null) {
            return "";
        }
        String s = md.replace("\r\n", "\n").replace('\r', '\n');
        s = HTML_COMMENT.matcher(s).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        s = HEADING.matcher(s).replaceAll("");
        s = s.replace("**", "").replace("`", "");
        // three blank lines where a comment used to be look like the card broke
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }
}
