package adris.altoclef.util.helpers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the fuel table in ItemHelper is hand copied from the vanilla data (the real answer needs a server level), so this
// reads the same data out of the jar and makes every fuel item in the registry agree with it. a new fuel item, or a
// changed burn time, shows up here instead of as a furnace that silently gets the wrong amount of coal
public class FuelTableTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static int plainFurnaceTicks(ResolvableInt.Reference reference) throws Exception {
        String path = "/data/" + reference.key().identifier().getNamespace() + "/context_int_provider/" + reference.key().identifier().getPath() + ".json";
        try (Reader reader = new InputStreamReader(Item.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            // div(left, 1 or 2): the plain furnace divides by 1, the smoker and blast furnace by 2
            return json.get("left").getAsInt();
        }
    }

    @Test
    public void everyFuelItemMatchesTheVanillaData() throws Exception {
        List<String> wrong = new ArrayList<>();
        java.util.Set<String> fuelNames = new java.util.HashSet<>();
        int fuels = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            CookingFuel fuel = item.components().get(DataComponents.COOKING_FUEL);
            if (fuel == null) {
                continue;
            }
            fuels++;
            Integer expected = null;
            if (fuel.burnTime() instanceof ResolvableInt.Constant constant) {
                expected = constant.value();
            } else if (fuel.burnTime() instanceof ResolvableInt.Reference reference) {
                expected = plainFurnaceTicks(reference);
            }
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            fuelNames.add(name);
            if (expected == null || !ItemHelper.isFuel(item)) {
                wrong.add(name + " is not in the table");
            } else if (Math.round(ItemHelper.getFuelAmount(item) * 200) != expected) {
                wrong.add(name + " burns " + Math.round(ItemHelper.getFuelAmount(item) * 200) + " in the table, " + expected + " in the data");
            }
        }
        // and the other way: nothing the table calls fuel that the data stopped calling fuel
        for (Item item : BuiltInRegistries.ITEM) {
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (ItemHelper.isFuel(item) && !fuelNames.contains(name)) {
                wrong.add(name + " is in the table but not fuel in the data");
            }
        }
        assertTrue("fuel table disagrees with vanilla: " + wrong, wrong.isEmpty());
        assertTrue("found " + fuels + " fuel items, the data didn't load", fuels > 50);
    }

    @Test
    public void coalAndLavaBurnTheirKnownAmounts() {
        // 1600 ticks of coal is 8 smelts of 200, the number the smelting code plans around
        assertEquals(8.0, ItemHelper.getFuelAmount(net.minecraft.world.item.Items.COAL), 1e-9);
        assertEquals(100.0, ItemHelper.getFuelAmount(net.minecraft.world.item.Items.LAVA_BUCKET), 1e-9);
        assertTrue(!ItemHelper.isFuel(net.minecraft.world.item.Items.STONE));
    }
}
