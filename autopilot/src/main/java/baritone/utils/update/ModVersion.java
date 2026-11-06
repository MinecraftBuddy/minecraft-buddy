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

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "1.1.6", "1.1.6-anyversion", "1.1.7-nightly.2" or "1.1.7-nightly.2-anyversion". anything else (-beta, -12-gabc,
// -dirty) is null on purpose: a jar we can't place on a channel and a track must never be offered the base channel's
// stable update just because the numbers look alright. anyversion goes last, release.yml strips it off the end
public final class ModVersion implements Comparable<ModVersion> {

    public static final String ANYVERSION = "anyversion";

    public static final String NIGHTLY = "nightly";

    private static final Pattern FORMAT = Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-" + NIGHTLY + "\\.(\\d+))?(?:-(" + ANYVERSION + "))?");

    private final String raw;
    private final int[] parts;
    private final String channel;
    // 0 is a stable build, nightly.0 doesn't parse so the two can't be mixed up
    private final int nightly;

    private ModVersion(String raw, int[] parts, String channel, int nightly) {
        this.raw = raw;
        this.parts = parts;
        this.channel = channel;
        this.nightly = nightly;
    }

    public static ModVersion parse(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = FORMAT.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        String[] bits = m.group(1).split("\\.");
        int[] parts = new int[bits.length];
        int nightly;
        try {
            for (int i = 0; i < bits.length; i++) {
                parts[i] = Integer.parseInt(bits[i]);
            }
            nightly = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            // 99999999999.0 is not a version, it's a cat on the keyboard
            return null;
        }
        if (m.group(2) != null && nightly == 0) {
            return null;
        }
        return new ModVersion(s.trim(), parts, m.group(3) == null ? "" : m.group(3), nightly);
    }

    public String raw() {
        return raw;
    }

    // "" for the normal builds, "anyversion" for the viafabricplus ones
    public String channel() {
        return channel;
    }

    public boolean sameChannel(ModVersion other) {
        return other != null && channel.equals(other.channel);
    }

    public boolean isNightly() {
        return nightly > 0;
    }

    // the track is stable or nightly, nothing to do with the channel. a stable player only ever sees stables
    public boolean sameTrack(ModVersion other) {
        return other != null && isNightly() == other.isNightly();
    }

    public boolean newerThan(ModVersion other) {
        return compareTo(other) > 0;
    }

    // numbers first. 1.1.10 beats 1.1.9 and 1.1 equals 1.1.0, string compare would get both wrong. same numbers and the
    // stable wins over every nightly of it, the nightlies are the road to it, not past it. channel stays out of it
    @Override
    public int compareTo(ModVersion o) {
        int n = Math.max(parts.length, o.parts.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts.length ? parts[i] : 0;
            int b = i < o.parts.length ? o.parts[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        if (isNightly() != o.isNightly()) {
            return isNightly() ? -1 : 1;
        }
        return Integer.compare(nightly, o.nightly);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModVersion v && compareTo(v) == 0 && channel.equals(v.channel);
    }

    @Override
    public int hashCode() {
        // trailing zeros don't count, 1.1 and 1.1.0 are equal so they'd better hash the same
        int len = parts.length;
        while (len > 0 && parts[len - 1] == 0) {
            len--;
        }
        return 31 * (31 * Arrays.hashCode(Arrays.copyOf(parts, len)) + channel.hashCode()) + nightly;
    }

    @Override
    public String toString() {
        return raw;
    }
}
