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

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

// "what if we had blocks (and a stone pickaxe)". the real player, the real settings, a pretend bag. placing still costs
// what placing costs, it's just never COST_INF for want of a block
public final class WhatIfContext extends CalculationContext {

    private static final ItemStack STONE_PICKAXE = new ItemStack(Items.STONE_PICKAXE);

    private WhatIfContext(IBaritone baritone, ToolSet toolSet, boolean hasThrowaway) {
        super(baritone, true, toolSet, hasThrowaway);
    }

    // tests: no player, no baritone, a fake world
    WhatIfContext(BlockStateInterface bsi, ToolSet toolSet) {
        super(null, true, null, null, bsi, toolSet, true, false, true, 0, 1.0f, 20.0, false);
    }

    // main thread, same as every other context. altoclef pausing interactions for a meal or a fight would make this
    // just as blockless as the real one, so the pause is off for the build, same trick as BreakContextCache
    public static WhatIfContext create(Baritone baritone, boolean pretendBlocks, boolean pretendPickaxe) {
        LocalPlayer player = baritone.getPlayerContext().player();
        ToolSet tools = pretendPickaxe ? new PretendPickaxe(player) : new ToolSet(player);
        boolean throwaway = Baritone.settings().allowPlace.value && (pretendBlocks || baritone.getInventoryBehavior().hasGenericThrowaway());
        return unpaused(() -> new WhatIfContext(baritone, tools, throwaway));
    }

    // the real bag to hold the what-if up against. built the same unpaused way or a meal would make the real route look
    // blockless and every what-if look faster than it is
    public static CalculationContext real(Baritone baritone) {
        return unpaused(() -> new CalculationContext(baritone, true, false));
    }

    private static <T> T unpaused(Supplier<T> build) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        boolean paused = alto.isInteractionPaused();
        alto.setInteractionPaused(false);
        try {
            return build.get();
        } finally {
            alto.setInteractionPaused(paused);
        }
    }

    // stone-or-better anywhere in the bag. the planner only looks at the hotbar, but one in the inventory gets swapped in
    // the moment it's needed, so it isn't something to go and make. iron ore wants stone tier, that's the whole trick
    public static boolean hasStonePickaxe(LocalPlayer player) {
        BlockState ironOre = Blocks.IRON_ORE.defaultBlockState();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(ItemTags.PICKAXES) && stack.isCorrectToolForDrops(ironOre)) {
                return true;
            }
        }
        return false;
    }

    public static boolean inNether(Level world) {
        return ExperimentalMovement.inNether(world);
    }

    // the hotbar, plus a stone pickaxe that isn't there. whichever is faster wins, so an iron one we really hold still counts
    static final class PretendPickaxe extends ToolSet {

        PretendPickaxe(LocalPlayer player) {
            super(player);
        }

        @Override
        public double getStrVsBlock(BlockState state) {
            double real = super.getStrVsBlock(state);
            if (Baritone.settings().blocksToAvoidBreaking.value.contains(state.getBlock())) {
                return real; // the user said slow, the pretend pickaxe doesn't get to undo that
            }
            // the real side has mining fatigue and haste in it, the pretend pickaxe gets the same
            return Math.max(real, potionMult() * ToolSet.calculateSpeedVsBlock(STONE_PICKAXE, state));
        }
    }
}
