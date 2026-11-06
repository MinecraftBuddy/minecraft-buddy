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

package adris.altoclef;

import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.BlockRange;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;

// the few altoclef settings that are more than a field read. the plain ones are read straight off
// Baritone.settings().alto*.value at the call site, live, so a #set applies on the next read. the lists in there are
// whatever #set parsed (or the immutable default), nothing here ever writes to them: save() only notices a changed
// reference, and the default list is the same object as what reset puts back
public final class AltoSettings {

    private AltoSettings() {
    }

    // nan from a hand edited settings.txt would make the hud vanish without a word
    public static float hudScale() {
        float scale = Baritone.settings().altoHudScale.value;
        return scale > 0 ? Math.min(2f, Math.max(0.5f, scale)) : 1f;
    }

    // the idle command only exists while altoRunsWhenIdle is on. without that gate a finished task used to restart the
    // idle command forever, and #stop restarted it too, which is not "does nothing until you start a task"
    public static boolean shouldRunIdleCommandWhenNotActive() {
        String idle = Baritone.settings().altoIdleCommand.value;
        return Baritone.settings().altoRunsWhenIdle.value && idle != null && !idle.isBlank();
    }

    public static boolean isThrowaway(Item item) {
        return Baritone.settings().altoThrowawayItems.value.contains(item);
    }

    // the one answer to "can this be built with". leaves, magma and soul sand sit in the alto list as junk to dump, not as
    // something to bridge with, and anything that falls over is no floor. the gate's count, the restock's count, the pillar's
    // spare count and the collect target all ask this, or they disagree on how full the bag is
    public static boolean isBuildBlock(Item item) {
        return item instanceof BlockItem b && !(b.getBlock() instanceof LeavesBlock)
                && !(b.getBlock() instanceof FallingBlock) && item != Items.MAGMA_BLOCK && item != Items.SOUL_SAND;
    }

    public static boolean isImportant(Item item) {
        return Baritone.settings().altoImportantItems.value.contains(item);
    }

    public static boolean isSupportedFuel(Item item) {
        return !Baritone.settings().altoLimitFuelsToSupportedFuels.value || Baritone.settings().altoSupportedFuels.value.contains(item);
    }

    public static Item[] getThrowawayItems(AltoClef mod, boolean includeProtected) {
        return Baritone.settings().altoThrowawayItems.value.stream()
                .filter(item -> includeProtected || !mod.getBehaviour().isProtected(item))
                .toArray(Item[]::new);
    }

    public static Item[] getThrowawayItems(AltoClef mod) {
        return getThrowawayItems(mod, false);
    }

    public static boolean isPositionExplicitlyProtected(BlockPos pos) {
        // one read, the list can be swapped under us by a #set on the main thread while a search runs on another
        var areas = Baritone.settings().altoAreasToProtect.value;
        if (areas.isEmpty()) {
            return false;
        }
        var dimension = WorldHelper.getCurrentDimension();
        for (BlockRange area : areas) {
            if (area.contains(pos, dimension)) {
                return true;
            }
        }
        return false;
    }
}
