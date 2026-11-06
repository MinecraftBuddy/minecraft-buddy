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

import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A box between two corners (both inclusive) in one dimension. Immutable.
 * <p>
 * The text form used by settings is {@code x1/y1/z1->x2/y2/z2}, with {@code @nether} or {@code @end} on the end for
 * anything that is not the overworld. It has no commas and no spaces on purpose: lists of settings are split on commas
 * and {@code #set} takes one word.
 */
public final class BlockRange {

    private static final Pattern TEXT = Pattern.compile(
            "(-?\\d+)/(-?\\d+)/(-?\\d+)->(-?\\d+)/(-?\\d+)/(-?\\d+)(?:@(\\w+))?");

    public final BlockPos start;
    public final BlockPos end;
    public final Dimension dimension;

    /**
     * The corners can come in any order, {@link #start} ends up the low one and {@link #end} the high one.
     */
    public BlockRange(BlockPos a, BlockPos b, Dimension dimension) {
        this.start = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.end = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.dimension = Objects.requireNonNull(dimension);
    }

    public boolean contains(BlockPos pos, Dimension dimension) {
        return this.dimension == dimension
                && start.getX() <= pos.getX() && pos.getX() <= end.getX()
                && start.getY() <= pos.getY() && pos.getY() <= end.getY()
                && start.getZ() <= pos.getZ() && pos.getZ() <= end.getZ();
    }

    public BlockPos getCenter() {
        BlockPos sum = start.offset(end);
        return new BlockPos(sum.getX() / 2, sum.getY() / 2, sum.getZ() / 2);
    }

    /**
     * Parses the settings text form, see the class description.
     *
     * @throws IllegalArgumentException if it does not look like that
     */
    public static BlockRange parse(String raw) {
        Matcher m = TEXT.matcher(raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("expected x/y/z->x/y/z[@nether|@end], got " + raw);
        }
        BlockPos a = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        BlockPos b = new BlockPos(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        return new BlockRange(a, b, m.group(7) == null ? Dimension.OVERWORLD : parseDimension(m.group(7)));
    }

    /**
     * The settings text form, what {@link #parse} reads back.
     */
    public String format() {
        return start.getX() + "/" + start.getY() + "/" + start.getZ() + "->"
                + end.getX() + "/" + end.getY() + "/" + end.getZ()
                + (dimension == Dimension.OVERWORLD ? "" : "@" + dimension.name().toLowerCase(Locale.ROOT));
    }

    private static Dimension parseDimension(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "overworld":
                return Dimension.OVERWORLD;
            case "nether":
                return Dimension.NETHER;
            case "end":
            case "the_end":
                return Dimension.END;
            default:
                throw new IllegalArgumentException("unknown dimension " + name + ", use overworld, nether or end");
        }
    }

    @Override
    public String toString() {
        return "[" + start.toShortString() + " -> " + end.toShortString() + ", (" + dimension + ")]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockRange)) return false;
        BlockRange that = (BlockRange) o;
        return start.equals(that.start) && end.equals(that.end) && dimension == that.dimension;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, dimension);
    }
}
