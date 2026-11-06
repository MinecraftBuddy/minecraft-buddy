package adris.altoclef.chains;

import adris.altoclef.util.helpers.FoodHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.function.Predicate;

// picks what FoodChain should eat. pure on purpose (no player, no mod) so the rules can be tested
final class FoodSelector {
    // at or below this the bot is hungry enough to put up with rotten flesh (it can't sprint under 6 anyway)
    static final int STARVING_HUNGER = 6;

    record Result(int foodTotal, Optional<Item> best) {
    }

    private FoodSelector() {
    }

    // the food a bite started with stays the food until that bite lands, is stopped or the stack is gone. the pick moves with hp (saturation first
    // under 8), and every flip force-equips the other food, which restarts the bite and never finishes one. hits do not break
    // the latch: damage does not stop a bite in the game either
    static Optional<Item> latch(Optional<Item> pick, Item latched, boolean eating, int inInventory) {
        if (eating && latched != null && inInventory > 0) return Optional.of(latched);
        return pick;
    }

    // usable says which stacks we may touch at all (protected items say no), hunger is the food level out of 20.
    // foodTotal only counts what we'd eat as normal food, last resorts stay out of it or a stack of
    // rotten flesh would stop us from ever going to find real food
    static Result select(Iterable<ItemStack> stacks, Predicate<ItemStack> usable, float health, float hunger, float saturation,
                         FoodChain.FoodChainConfig config) {
        Item bestFood = null;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        Item bestRaw = null;
        double bestRawScore = Double.NEGATIVE_INFINITY;
        Item rawChicken = null;
        int foodTotal = 0;
        boolean hasRottenFlesh = false;
        boolean hasChorusFruit = false;

        for (ItemStack stack : stacks) {
            Item item = stack.getItem();
            FoodHelper.Kind kind = FoodHelper.kindOf(item);
            // pufferfish and friends are skipped, gapples are saved for something more dire than a rumbling stomach
            if (kind == FoodHelper.Kind.NOT_FOOD || kind == FoodHelper.Kind.NEVER || kind == FoodHelper.Kind.RESERVED) continue;
            // Ignore protected items
            if (!usable.test(stack)) continue;

            if (kind == FoodHelper.Kind.ROTTEN_FLESH) {
                hasRottenFlesh = true;
                continue;
            }
            if (kind == FoodHelper.Kind.CHORUS_FRUIT) {
                hasChorusFruit = true;
                continue;
            }

            FoodProperties food = item.components().get(DataComponents.FOOD);
            if (food == null) continue;

            float hungerIfEaten = Math.min(hunger + food.nutrition(), 20);
            float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.saturation());
            float gainedSaturation = (saturationIfEaten - saturation);
            float gainedHunger = (hungerIfEaten - hunger);
            float hungerNotFilled = 20 - hungerIfEaten;

            float saturationWasted = food.saturation() - gainedSaturation;
            float hungerWasted = food.nutrition() - gainedHunger;

            boolean prioritizeSaturation = health < config.prioritizeSaturationWhenBelowHealth;
            float saturationGoodScore = prioritizeSaturation ? gainedSaturation * config.foodPickPrioritizeSaturationSaturationMultiplier : gainedSaturation;
            float saturationLossPenalty = prioritizeSaturation ? 0 : saturationWasted * config.foodPickSaturationWastePenaltyMultiplier;
            float hungerLossPenalty = hungerWasted * config.foodPickHungerWastePenaltyMultiplier;
            float hungerNotFilledPenalty = hungerNotFilled * config.foodPickHungerNotFilledPenaltyMultiplier;

            float score = saturationGoodScore - saturationLossPenalty - hungerLossPenalty - hungerNotFilledPenalty;

            if (item == Items.CHICKEN) {
                // 30% hunger effect, the last thing we eat before rotten flesh
                rawChicken = item;
            } else if (FoodHelper.isRawMeat(item)) {
                if (score > bestRawScore) {
                    bestRawScore = score;
                    bestRaw = item;
                }
            } else if (score > bestFoodScore) {
                bestFoodScore = score;
                bestFood = item;
            }

            foodTotal += food.nutrition() * stack.getCount();
        }

        // anything cooked (or just not raw) beats raw meat, however good the raw one scores on a nearly full stomach: it
        // scores well there because it gives so little, and then the bag fills up with uncooked dinner
        if (bestFood == null) {
            bestFood = bestRaw != null ? bestRaw : rawChicken;
        }
        if (bestFood == null) {
            // last resorts, in order: rotten flesh if we're starving, chorus fruit if there is truly nothing else
            if (hasRottenFlesh) {
                if (hunger <= STARVING_HUNGER) {
                    bestFood = Items.ROTTEN_FLESH;
                }
            } else if (hasChorusFruit) {
                bestFood = Items.CHORUS_FRUIT;
            }
        }

        return new Result(foodTotal, Optional.ofNullable(bestFood));
    }
}
