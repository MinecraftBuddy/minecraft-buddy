package adris.altoclef.util.helpers;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

// what the bot is allowed to eat. anything with a FOOD component used to count, which includes pufferfish
public final class FoodHelper {
    public enum Kind {
        // fine to eat whenever hungry
        NORMAL,
        // only when starving and there is nothing better
        ROTTEN_FLESH,
        // teleports you, so only when there is nothing else at all
        CHORUS_FRUIT,
        // gapples are for fights, not for hunger
        RESERVED,
        // poison, or an effect we can't see (suspicious stew), never
        NEVER,
        NOT_FOOD
    }

    private static final Set<Item> NEVER = Set.of(
            Items.PUFFERFISH,
            Items.POISONOUS_POTATO,
            Items.SPIDER_EYE,
            // the effect lives on the stack, not the item, and it can be blindness or fire
            Items.SUSPICIOUS_STEW
    );

    private static final Set<Item> RESERVED = Set.of(
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE
    );

    private FoodHelper() {
    }

    // what the furnace turns this into, the item itself for everything that is not raw meat or fish
    public static Item cookedForm(Item item) {
        if (item == Items.PORKCHOP) return Items.COOKED_PORKCHOP;
        if (item == Items.BEEF) return Items.COOKED_BEEF;
        if (item == Items.CHICKEN) return Items.COOKED_CHICKEN;
        if (item == Items.MUTTON) return Items.COOKED_MUTTON;
        if (item == Items.RABBIT) return Items.COOKED_RABBIT;
        if (item == Items.COD) return Items.COOKED_COD;
        if (item == Items.SALMON) return Items.COOKED_SALMON;
        return item;
    }

    // raw meat or fish: anything with a cooked form. potatoes are not in here, a raw one is just a poor lunch
    public static boolean isRawMeat(Item item) {
        return cookedForm(item) != item;
    }

    // nutrition the planner books for one of these: raw meat at its cooked value, because a gamer run always has a furnace and
    // CollectFoodTask counts it that way (6 raw mutton is 36, not the 12 it is raw, or the kit asked for food while the bag
    // was full of dinner). 0 for anything with no food component
    public static int plannedNutrition(Item item) {
        var food = cookedForm(item).components().get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    // what one of these gives when it is eaten as it is, so raw meat at its raw value. 0 for anything with no food component
    public static int ownNutrition(Item item) {
        var food = item.components().get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    public static Kind kindOf(Item item) {
        if (!item.components().has(DataComponents.FOOD)) {
            return Kind.NOT_FOOD;
        }
        if (NEVER.contains(item)) {
            return Kind.NEVER;
        }
        if (RESERVED.contains(item)) {
            return Kind.RESERVED;
        }
        if (item == Items.ROTTEN_FLESH) {
            return Kind.ROTTEN_FLESH;
        }
        if (item == Items.CHORUS_FRUIT) {
            return Kind.CHORUS_FRUIT;
        }
        return Kind.NORMAL;
    }

    // food worth keeping in the bag: edible, and not poison. gapples count here (we hoard them), they just
    // aren't eaten for hunger. for places where a plain "has a FOOD component" check counted pufferfish as a lunch
    public static boolean isSafeFood(Item item) {
        Kind kind = kindOf(item);
        return kind != Kind.NOT_FOOD && kind != Kind.NEVER;
    }
}
