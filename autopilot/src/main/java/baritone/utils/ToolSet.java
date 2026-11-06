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

package baritone.utils;

import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import adris.altoclef.util.helpers.ToolPick;
import baritone.altoclef.AltoClefSettings;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;

/**
 * A cached list of the best tools on the hotbar for any block
 *
 * @author Avery, Brady, leijurv
 */
public class ToolSet {

    /**
     * A cache mapping a {@link Block} to how long it will take to break
     * with this toolset, given the optimum tool is used.
     */
    private final Object2DoubleOpenHashMap<Block> breakStrengthCache;

    /**
     * My buddy leijurv owned me so we have this to not create a new lambda instance.
     */
    private final ToDoubleFunction<Block> backendCalculation;

    private final LocalPlayer player;

    // altoclef's "use this tool for this block no matter what" rules. null when it has none, which is nearly always.
    // they care about the actual state and the actual stack so they can't live in a per block cache, see getStrVsBlock
    private final BiPredicate<BlockState, ItemStack>[] forceUseTool;
    private final double potionMult;

    public ToolSet(LocalPlayer player) {
        breakStrengthCache = new Object2DoubleOpenHashMap<>();
        this.player = player;
        this.forceUseTool = AltoClefSettings.getInstance().snapshot().forceUseTool;

        if (Baritone.settings().considerPotionEffects.value) {
            double amplifier = potionAmplifier();
            this.potionMult = amplifier;
            backendCalculation = b -> amplifier * this.getBestDestructionTime(b.defaultBlockState());
        } else {
            this.potionMult = 1;
            backendCalculation = b -> this.getBestDestructionTime(b.defaultBlockState());
        }
    }

    /**
     * Using the best tool on the hotbar, how fast we can mine this block
     *
     * @param state the blockstate to be mined
     * @return the speed of how fast we'll mine it. 1/(time in ticks)
     */
    public double getStrVsBlock(BlockState state) {
        if (forceUseTool != null) {
            // a forced tool depends on the state and not just the block, so the per block cache would hand out the wrong
            // answer. the mining memo upstairs makes up for it, this is asked once per position
            return potionMult * getBestDestructionTime(state);
        }
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            // silk touch enchantment is still special cased as affecting block drops
            // not possible to add custom attribute via datapack
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate which tool on the hotbar is best for mining, depending on an override setting,
     * related to auto tool movement cost, it will either return current selected slot, or the best slot.
     *
     * @param b the blockstate to be mined
     * @return An int containing the index in the tools array that worked best
     */

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return getBestSlot(b, preferSilkTouch, false);
    }

    public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {
        return getBestSlot(b.defaultBlockState(), preferSilkTouch, pathingCalculation);
    }

    public int getBestSlot(BlockState blockState, boolean preferSilkTouch, boolean pathingCalculation) {

        /*
        If we actually want know what efficiency our held item has instead of the best one
        possible, this lets us make pathing depend on the actual tool to be used (if auto tool is disabled)
        */
        if (!Baritone.settings().autoTool.value && pathingCalculation) {
            return player.getInventory().getSelectedSlot();
        }

        int best = -1;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        boolean bestSilkTouch = false;
        double handSpeed = calculateSpeedVsBlock(ItemStack.EMPTY, blockState);
        List<ItemStack> chainable = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            chainable.add(ItemStack.EMPTY);
            if (!Baritone.settings().useSwordToMine.value && itemStack.is(ItemTags.SWORDS)) {
                continue;
            }

            if (Baritone.settings().itemSaver.value && (itemStack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= itemStack.getMaxDamage() && itemStack.getMaxDamage() > 1) {
                continue;
            }
            chainable.set(i, itemStack);
            // forced tools beat everything, but only for picking the slot. the time it takes still comes from what the tool really does
            boolean forced = forceUseTool != null && shouldForceUseTool(blockState, itemStack);
            double speed = forced ? Double.POSITIVE_INFINITY : calculateSpeedVsBlock(itemStack, blockState);
            // skip it so a tie goes to a slot that isn't a pickaxe instead of the first one on the bar
            if (!forced && ToolPick.noBetterThanHand(itemStack.isDamageableItem(), speed, handSpeed)) {
                continue;
            }
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed || (speed == highestSpeed && preferSilkTouch && !bestSilkTouch && silkTouch)) {
                highestSpeed = speed;
                best = i;
                bestSilkTouch = silkTouch;
            }
        }
        if (best < 0) {
            // nothing but tools that do nothing here. a slot with no durability to burn if there is one, else wherever we are
            best = player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (!player.getInventory().getItem(i).isDamageableItem()) {
                    return i;
                }
            }
            return best;
        }
        // the wear chain only picks between tools that are right for the block, a forced tool or a wanted silk touch
        // already made their choice above
        if (Baritone.settings().toolWearChain.value && highestSpeed != Double.POSITIVE_INFINITY && !(preferSilkTouch && bestSilkTouch)) {
            int chained = ToolPick.chainIndex(chainable, blockState);
            if (chained >= 0) {
                return chained;
            }
        }
        return best;
    }

    /**
     * Calculate how effectively a block can be destroyed
     *
     * @param b the blockstate to be mined
     * @return A double containing the destruction ticks with the best tool
     */
    private double getBestDestructionTime(BlockState state) {
        ItemStack stack = player.getInventory().getItem(getBestSlot(state, false, true));
        return calculateSpeedVsBlock(stack, state) * avoidanceMultiplier(state.getBlock());
    }

    // what considerPotionEffects multiplies every break speed by, for a subclass that prices a tool we don't hold
    protected double potionMult() {
        return potionMult;
    }

    private boolean shouldForceUseTool(BlockState state, ItemStack stack) {
        for (BiPredicate<BlockState, ItemStack> predicate : forceUseTool) {
            try {
                if (predicate.test(state, stack)) {
                    return true;
                }
            } catch (Throwable t) {
                // altoclef task code, see AltoClefSettings.Snapshot#anyMatch
                AltoClefBridge.onHookError(t);
            }
        }
        return false;
    }

    private double avoidanceMultiplier(Block b) {
        return Baritone.settings().blocksToAvoidBreaking.value.contains(b) ? Baritone.settings().avoidBreakingMultiplier.value : 1;
    }

    /**
     * Calculates how long would it take to mine the specified block given the best tool
     * in this toolset is used. A negative value is returned if the specified block is unbreakable.
     *
     * @param item  the item to mine it with
     * @param state the blockstate to be mined
     * @return how long it would take in ticks
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            // can't easily determine the hardness so treat it as unbreakable
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            final ItemEnchantments itemEnchantments = item.getEnchantments();
            OUTER: for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect e : effects) {
                    if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().get())) {
                        speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /**
     * Calculates any modifier to breaking time based on status effects.
     *
     * @return a double to scale block breaking speed.
     */
    private double potionAmplifier() {
        double speed = 1;
        if (player.hasEffect(MobEffects.HASTE)) {
            speed *= 1 + (player.getEffect(MobEffects.HASTE).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            // 1.21.4 had a switch with 0.3, 0.09, 0.0027, 0.00081 (the 0.0027 was a typo-looking truth). 26.3 made it a real power,
            // so fatigue 3 and 4 are 10x less crippling than that table says
            speed *= fatigueFactor(player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier());
        }
        return speed;
    }

    static double fatigueFactor(int amplifier) {
        return Math.pow(0.3, amplifier + 1);
    }
}
