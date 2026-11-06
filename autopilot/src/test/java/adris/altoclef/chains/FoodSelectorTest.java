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

package adris.altoclef.chains;

import adris.altoclef.util.helpers.FoodHelper;
import baritone.Baritone;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class FoodSelectorTest {
    private static final FoodChain.FoodChainConfig CONFIG = new FoodChain.FoodChainConfig();

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static List<ItemStack> inv(Object... itemsAndCounts) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < itemsAndCounts.length; i += 2) {
            stacks.add(new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
        }
        return stacks;
    }

    private static FoodSelector.Result pick(List<ItemStack> stacks, float hunger) {
        return FoodSelector.select(stacks, stack -> true, 20, hunger, 5, CONFIG);
    }

    @Test
    public void neverEatsThePoisonousOnes() {
        for (Item bad : new Item[]{Items.PUFFERFISH, Items.POISONOUS_POTATO, Items.SPIDER_EYE, Items.SUSPICIOUS_STEW}) {
            FoodSelector.Result result = pick(inv(bad, 16), 3);
            assertTrue(bad + " should never be picked, even starving", result.best().isEmpty());
            assertEquals(bad + " shouldn't count as food we have", 0, result.foodTotal());
        }
    }

    @Test
    public void poisonDoesNotBeatARealMeal() {
        FoodSelector.Result result = pick(inv(Items.PUFFERFISH, 8, Items.COOKED_BEEF, 1), 10);
        assertEquals(Optional.of(Items.COOKED_BEEF), result.best());
        assertEquals(Items.COOKED_BEEF.components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition(), result.foodTotal());
    }

    @Test
    public void goldenApplesAreSavedNotEaten() {
        FoodSelector.Result result = pick(inv(Items.GOLDEN_APPLE, 3, Items.ENCHANTED_GOLDEN_APPLE, 1), 2);
        assertTrue(result.best().isEmpty());
        assertEquals(0, result.foodTotal());
        // and with real food around they still don't get picked
        result = pick(inv(Items.GOLDEN_APPLE, 3, Items.BREAD, 2), 8);
        assertEquals(Optional.of(Items.BREAD), result.best());
    }

    @Test
    public void rottenFleshOnlyWhenStarvingAndNothingElse() {
        // starving, nothing else
        assertEquals(Optional.of(Items.ROTTEN_FLESH), pick(inv(Items.ROTTEN_FLESH, 10), 4).best());
        assertEquals(Optional.of(Items.ROTTEN_FLESH), pick(inv(Items.ROTTEN_FLESH, 10), FoodSelector.STARVING_HUNGER).best());
        // peckish is not starving
        assertTrue(pick(inv(Items.ROTTEN_FLESH, 10), FoodSelector.STARVING_HUNGER + 1).best().isEmpty());
        assertTrue(pick(inv(Items.ROTTEN_FLESH, 10), 14).best().isEmpty());
        // starving but there is real food: take the real food
        assertEquals(Optional.of(Items.COOKED_PORKCHOP), pick(inv(Items.ROTTEN_FLESH, 10, Items.COOKED_PORKCHOP, 1), 2).best());
    }

    @Test
    public void rottenFleshDoesNotCountAsAStockpile() {
        // otherwise 20 rotten flesh in the bag stops the bot from ever going to find food
        assertEquals(0, pick(inv(Items.ROTTEN_FLESH, 20), 3).foodTotal());
    }

    @Test
    public void chorusFruitIsTheVeryLastResort() {
        assertEquals(Optional.of(Items.CHORUS_FRUIT), pick(inv(Items.CHORUS_FRUIT, 4), 10).best());
        // any normal food beats it
        assertEquals(Optional.of(Items.APPLE), pick(inv(Items.CHORUS_FRUIT, 4, Items.APPLE, 1), 10).best());
        // and so does rotten flesh when we're starving
        assertEquals(Optional.of(Items.ROTTEN_FLESH), pick(inv(Items.CHORUS_FRUIT, 4, Items.ROTTEN_FLESH, 1), 3).best());
        // rotten flesh we're too full for still counts as "something else", so no teleporting for nothing
        assertTrue(pick(inv(Items.CHORUS_FRUIT, 4, Items.ROTTEN_FLESH, 1), 12).best().isEmpty());
        assertEquals(0, pick(inv(Items.CHORUS_FRUIT, 4), 10).foodTotal());
    }

    @Test
    public void protectedStacksAreIgnored() {
        List<ItemStack> stacks = inv(Items.COOKED_BEEF, 5, Items.BREAD, 5);
        FoodSelector.Result result = FoodSelector.select(stacks, stack -> stack.getItem() != Items.COOKED_BEEF, 20, 10, 5, CONFIG);
        assertEquals(Optional.of(Items.BREAD), result.best());
    }

    @Test
    public void nonFoodIsIgnoredAndNormalFoodStillSums() {
        FoodSelector.Result result = pick(inv(Items.STICK, 64, Items.BREAD, 3, Items.COOKED_BEEF, 2), 10);
        int bread = Items.BREAD.components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition();
        int beef = Items.COOKED_BEEF.components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition();
        assertEquals(bread * 3 + beef * 2, result.foodTotal());
        assertTrue(result.best().isPresent());
    }

    @Test
    public void classification() {
        assertEquals(FoodHelper.Kind.NORMAL, FoodHelper.kindOf(Items.COOKED_BEEF));
        assertEquals(FoodHelper.Kind.NORMAL, FoodHelper.kindOf(Items.HONEY_BOTTLE));
        assertEquals(FoodHelper.Kind.NOT_FOOD, FoodHelper.kindOf(Items.STICK));
        assertEquals(FoodHelper.Kind.RESERVED, FoodHelper.kindOf(Items.GOLDEN_APPLE));
        assertEquals(FoodHelper.Kind.RESERVED, FoodHelper.kindOf(Items.ENCHANTED_GOLDEN_APPLE));
        assertEquals(FoodHelper.Kind.NEVER, FoodHelper.kindOf(Items.PUFFERFISH));
        assertFalse(FoodHelper.isSafeFood(Items.SPIDER_EYE));
        assertTrue(FoodHelper.isSafeFood(Items.GOLDEN_APPLE));
    }

    @Test
    public void cookedBeatsRawEvenOnANearlyFullStomach() {
        // raw mutton gives 2 and wastes less at 18 hunger, which is how it used to win. cooked first, always
        for (float hunger : new float[]{4, 12, 18}) {
            assertEquals(Optional.of(Items.COOKED_MUTTON), pick(inv(Items.MUTTON, 9, Items.COOKED_MUTTON, 1), hunger).best());
            assertEquals(Optional.of(Items.APPLE), pick(inv(Items.BEEF, 9, Items.APPLE, 1), hunger).best());
        }
    }

    // the heal meal (hurt, hunger under 18) asks the selector for its food like every other meal
    @Test
    public void aHealingMealTakesRealFoodAndLeavesGapplesAndProtectedFoodAlone() {
        List<ItemStack> bag = inv(Items.GOLDEN_APPLE, 3, Items.ENCHANTED_GOLDEN_APPLE, 1, Items.COOKED_PORKCHOP, 4, Items.BREAD, 2);
        // hurt 14, hunger 15: the cooked meat or the bread, never a gapple
        FoodSelector.Result result = FoodSelector.select(bag, stack -> true, 14, 15, 2, CONFIG);
        assertTrue(result.best().isPresent());
        assertNotEquals(Items.GOLDEN_APPLE, result.best().get());
        assertNotEquals(Items.ENCHANTED_GOLDEN_APPLE, result.best().get());
        // the gamer protects some of its food: that is not ours to eat
        result = FoodSelector.select(bag, stack -> stack.getItem() != Items.COOKED_PORKCHOP && stack.getItem() != Items.BREAD, 14, 15, 2, CONFIG);
        assertTrue(result.best().isEmpty());
        // a bag with nothing but gapples heals nothing
        assertTrue(FoodSelector.select(inv(Items.GOLDEN_APPLE, 3), stack -> true, 14, 15, 2, CONFIG).best().isEmpty());
    }

    @Test
    public void theFoodChainConfigDefaultsLiveInCombatRules() {
        assertEquals(adris.altoclef.util.helpers.CombatRules.HUNGRY_AT, CONFIG.alwaysEatWhenBelowHunger);
        assertEquals(adris.altoclef.util.helpers.CombatRules.BRUISED_HEALTH, CONFIG.alwaysEatWhenBelowHealth);
        assertEquals(10, CONFIG.alwaysEatWhenBelowHunger);
        assertEquals(14, CONFIG.alwaysEatWhenBelowHealth);
    }

    @Test
    public void rawChickenIsTheLastThingBeforeRottenFlesh() {
        assertEquals(Optional.of(Items.MUTTON), pick(inv(Items.CHICKEN, 9, Items.MUTTON, 1), 10).best());
        assertEquals(Optional.of(Items.CHICKEN), pick(inv(Items.CHICKEN, 9, Items.ROTTEN_FLESH, 4), 4).best());
        // and the raw stuff still counts as food we hold, it is only the order that changed
        FoodSelector.Result result = pick(inv(Items.CHICKEN, 2), 10);
        assertEquals(Optional.of(Items.CHICKEN), result.best());
        assertEquals(2 * Items.CHICKEN.components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition(), result.foodTotal());
    }

    @Test
    public void aMealKeepsItsFoodWhileEatingEvenWhenThePickMoves() {
        Optional<Item> pick = Optional.of(Items.COOKED_BEEF);
        assertEquals(Optional.of(Items.BREAD), FoodSelector.latch(pick, Items.BREAD, true, 3));
        // not eating, no latch
        assertEquals(pick, FoodSelector.latch(pick, Items.BREAD, false, 3));
        // nothing latched yet
        assertEquals(pick, FoodSelector.latch(pick, null, true, 3));
        // the stack ran out
        assertEquals(pick, FoodSelector.latch(pick, Items.BREAD, true, 0));
    }

    @Test
    public void healthCrossingEightMidMealDoesNotChangeTheLatchedFood() {
        List<ItemStack> stacks = inv(Items.COOKED_MUTTON, 4, Items.COOKED_PORKCHOP, 4);
        Optional<Item> high = FoodSelector.select(stacks, s -> true, 9, 10, 2, CONFIG).best();
        Optional<Item> low = FoodSelector.select(stacks, s -> true, 7.9f, 10, 2, CONFIG).best();
        assertTrue(high.isPresent() && low.isPresent());
        // whatever the raw pick does at the line, a latched meal ignores it
        Item latched = high.get();
        assertEquals(Optional.of(latched), FoodSelector.latch(low, latched, true, 4));
        assertEquals(Optional.of(latched), FoodSelector.latch(high, latched, true, 4));
    }

    // CollectFoodTask protects every food from the bin while it runs. that's no reason to sit hurt next to a stack of it
    @Test
    public void eatsFoodATaskKeepsFromTheBin() {
        List<ItemStack> bag = inv(Items.COOKED_PORKCHOP, 8);
        assertTrue(FoodChain.mayEat(bag.get(0)));
        assertEquals(Optional.of(Items.COOKED_PORKCHOP), FoodSelector.select(bag, FoodChain::mayEat, 5, 16, 0, CONFIG).best());
    }

    @Test
    public void leavesImportantItemsOutOfAMeal() {
        List<Item> was = Baritone.settings().altoImportantItems.value;
        try {
            Baritone.settings().altoImportantItems.value = List.of(Items.COOKED_PORKCHOP);
            assertFalse(FoodChain.mayEat(new ItemStack(Items.COOKED_PORKCHOP, 8)));
            assertTrue(FoodChain.mayEat(new ItemStack(Items.BREAD, 8)));
        } finally {
            Baritone.settings().altoImportantItems.value = was;
        }
    }
}
