package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

// KitRunner only finds out about a typo in the config when the run gets there, so this finds out first
public class KitNamesTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static Set<String> everyNameTheOverworldCanAskFor(OverworldConfig cfg) {
        Set<String> names = new LinkedHashSet<>();
        FakeFacts bare = new FakeFacts();
        for (ArmorPlan plan : ArmorPlan.values()) {
            cfg.armorPlan = plan;
            for (KitNeed n : KitPlanner.gather(bare, cfg, 8)) {
                names.add(n.catalogueName());
            }
            for (KitNeed n : KitPlanner.plan(bare, cfg, 8)) {
                names.add(n.catalogueName());
            }
            for (KitNeed n : PortalPlanner.gate(bare, cfg, 10)) {
                names.add(n.catalogueName());
            }
        }
        // an owned-but-unworn piece asks for the equip step, which has no catalogue entry on purpose
        names.remove(KitNeed.FOOD);
        names.remove(KitNeed.BUILD_BLOCKS);
        names.remove(KitNeed.EQUIP_ARMOR);
        return names;
    }

    @Test
    public void everyDefaultNeedIsInTheCatalogue() {
        Set<String> names = everyNameTheOverworldCanAskFor(new OverworldConfig());
        assertTrue(names.contains("iron_ingot"));
        assertTrue(names.contains("wool"));
        for (String name : names) {
            assertTrue("TaskCatalogue has no '" + name + "'", TaskCatalogue.taskExists(name));
        }
    }

    @Test
    public void everyConfiguredKitItemIsInTheCatalogue() {
        OverworldConfig cfg = new OverworldConfig();
        for (OverworldConfig.KitItem k : cfg.starterKit) {
            assertTrue(k.item, TaskCatalogue.taskExists(k.item));
        }
        for (OverworldConfig.KitItem k : cfg.ironKit) {
            assertTrue(k.item, TaskCatalogue.taskExists(k.item));
        }
    }
}
