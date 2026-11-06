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

package baritone.behavior.trip;

import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

// "is there anything to collect around here at all". a question we can't follow through on is worse than no question,
// so before asking we look at the loaded chunks for the blocks the gather would go for. client thread, once per question
public final class GatherFeasibility {

    // six chunks is about a hundred blocks, further than that and it isn't a quick detour any more
    static final int SCAN_CHUNKS = 6;

    private GatherFeasibility() {
    }

    public static boolean plausible(IPlayerContext ctx, GatherPlan plan, List<Item> items) {
        boolean anyPickaxe = hasAnyPickaxe(ctx);
        // wood for a wooden pickaxe (and the crafting table) is only worth looking for when we'd need it
        Boolean logs = null;
        if (plan.pickaxe()) {
            logs = anyPickaxe || found(ctx, GatherSources.logs());
            if (!logs || !found(ctx, GatherSources.STONE_TOOL_BLOCKS)) {
                return false;
            }
        }
        if (plan.blocks() <= 0) {
            return true;
        }
        List<Block> all = GatherSources.sources(items);
        List<Block> hand = GatherSources.byHand(all);
        List<Block> tool = new ArrayList<>(all);
        tool.removeAll(hand);
        if (!hand.isEmpty() && found(ctx, hand)) {
            return true;
        }
        if (tool.isEmpty()) {
            return false;
        }
        boolean canMine = anyPickaxe || plan.pickaxe() || (logs != null ? logs : found(ctx, GatherSources.logs()));
        return canMine && found(ctx, tool);
    }

    private static boolean found(IPlayerContext ctx, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return false;
        }
        return !BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, blocks, 1, -1, SCAN_CHUNKS).isEmpty();
    }

    private static boolean hasAnyPickaxe(IPlayerContext ctx) {
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (stack.is(ItemTags.PICKAXES)) {
                return true;
            }
        }
        return false;
    }
}
