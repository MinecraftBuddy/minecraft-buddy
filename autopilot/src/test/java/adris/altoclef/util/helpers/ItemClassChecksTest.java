package adris.altoclef.util.helpers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

// isSword/isAxe/isDigger/isArmor used to be instanceof SwordItem/AxeItem/DiggerItem/ArmorItem and are tag checks now.
// the class hierarchy is gone, so the stand-in for "what the class matched" is the vanilla naming: every item the old
// classes covered was called something_sword, something_pickaxe and so on, and nothing else was. if a tag swallows an
// extra item (a spear in the sword tag) or drops one, it shows up here as something that changed sides
public class ItemClassChecksTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static List<String> sideChanges(Predicate<Item> check, String namePattern) {
        Pattern pattern = Pattern.compile(namePattern);
        List<String> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            boolean old = pattern.matcher(path).matches();
            if (old != check.test(item)) {
                out.add(path + (old ? " lost" : " gained"));
            }
        }
        return out;
    }

    @Test
    public void swordsAreTheSameItemsAsBefore() {
        List<String> changed = sideChanges(ItemHelper::isSword, ".*_sword");
        assertTrue("isSword changed sides: " + changed, changed.isEmpty());
    }

    @Test
    public void axesAreTheSameItemsAsBefore() {
        List<String> changed = sideChanges(ItemHelper::isAxe, ".*_axe");
        assertTrue("isAxe changed sides: " + changed, changed.isEmpty());
    }

    @Test
    public void diggersAreTheSameItemsAsBefore() {
        List<String> changed = sideChanges(ItemHelper::isDigger, ".*_(pickaxe|axe|shovel|hoe)");
        assertTrue("isDigger changed sides: " + changed, changed.isEmpty());
    }

    // turtle_helmet was an ArmorItem too, elytra, skulls and carved pumpkins were not, and horse/wolf armor was a different class
    @Test
    public void armorIsTheSameItemsAsBefore() {
        List<String> changed = sideChanges(ItemHelper::isArmor, ".*_(helmet|chestplate|leggings|boots)");
        assertTrue("isArmor changed sides: " + changed, changed.isEmpty());
    }

    @Test
    public void thereAreActuallyItemsOnEachSide() {
        // a tag that failed to load would make every check above agree on "nothing" and pass for the wrong reason
        int swords = 0, axes = 0, diggers = 0, armor = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            swords += ItemHelper.isSword(item) ? 1 : 0;
            axes += ItemHelper.isAxe(item) ? 1 : 0;
            diggers += ItemHelper.isDigger(item) ? 1 : 0;
            armor += ItemHelper.isArmor(item) ? 1 : 0;
        }
        assertTrue(swords >= 6 && axes >= 6 && diggers >= 24 && armor >= 24);
    }
}
