package adris.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

// vanilla renamed chain to iron_chain and the catalogue key quietly stayed "chain": it resolved to the right item (the
// key points at a java field), it just wasn't the name anybody would type. this walks every key against the registry
// so the next rename shows up as a failure instead
public class TaskCatalogueKeysTest {

    // keys that are short for something, or a spelling people use. each one points at the vanilla item it stands for
    private static final Set<String> ALIASES = Set.of(
            "chain", "lapis", "netherite_upgrade", "book_and_quill", "eye_of_ender",
            "wooden_pick", "stone_pick", "iron_pick", "gold_pick", "diamond_pick", "netherite_pick",
            "minecart_with_chest", "minecart_with_furnace", "minecart_with_hopper", "minecart_with_tnt",
            // the catalogue's own names for things whose vanilla id is a worse name (stem, raft, short_grass, milk_bucket;
            // potion, which is every drink there is)
            "milk", "grass", "bamboo_boat", "warped_log", "crimson_log", "water_bottle");

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void everyKeyIsAVanillaIdOrAKnownAlias() {
        List<String> bad = new ArrayList<>();
        for (String name : TaskCatalogue.resourceNames()) {
            Item[] matches = TaskCatalogue.getItemMatches(name);
            // woodTasks names the stripped log of each wood <wood>_stripped_logs
            if (matches.length != 1 || ALIASES.contains(name) || name.endsWith("_stripped_logs")) {
                continue;
            }
            Item vanilla = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(name)).orElse(null);
            if (vanilla == null) {
                bad.add(name + " is not a vanilla item id (resolves to " + BuiltInRegistries.ITEM.getKey(matches[0]) + ")");
            } else if (vanilla != matches[0]) {
                bad.add(name + " is a vanilla id but resolves to " + BuiltInRegistries.ITEM.getKey(matches[0]));
            }
        }
        assertTrue("catalogue keys off the registry: " + bad, bad.isEmpty());
    }

    @Test
    public void aliasesPointAtTheSameThingAsTheirTarget() {
        assertArrayEquals(TaskCatalogue.getItemMatches("iron_chain"), TaskCatalogue.getItemMatches("chain"));
        assertTrue(TaskCatalogue.getItemMatches("chain").length == 1);
        assertTrue(Arrays.stream(TaskCatalogue.getItemMatches("chain")).allMatch(i -> BuiltInRegistries.ITEM.getKey(i).getPath().equals("iron_chain")));
        assertArrayEquals(TaskCatalogue.getItemMatches("lapis_lazuli"), TaskCatalogue.getItemMatches("lapis"));
    }
}
