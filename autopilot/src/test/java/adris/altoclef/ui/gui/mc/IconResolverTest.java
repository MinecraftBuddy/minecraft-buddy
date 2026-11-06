package adris.altoclef.ui.gui.mc;

import adris.altoclef.TaskCatalogue;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IconResolverTest {

    @BeforeClass
    public static void bootstrap() {
        // items, blocks and the catalogue all want the registries
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        adris.altoclef.util.helpers.ItemFixture.load();
    }

    private static boolean realItem(String id) {
        if (id == null) {
            return false;
        }
        Identifier rl = Identifier.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) && BuiltInRegistries.ITEM.getValue(rl) != Items.AIR;
    }

    @Test
    public void everyCatalogueNameHasAnIcon() {
        List<String> bad = new ArrayList<>();
        for (String name : TaskCatalogue.resourceNames()) {
            String id = IconResolver.forResource(name);
            if (!realItem(id)) {
                bad.add(name + " -> " + id);
            }
        }
        assertTrue("no icon for " + bad, bad.isEmpty());
        assertTrue(TaskCatalogue.resourceNames().size() > 200);
    }

    @Test
    public void everyBlockTheMineGridOffersHasAnIcon() {
        List<String> bad = new ArrayList<>();
        int offered = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!McItems.isMineable(block)) {
                continue;
            }
            offered++;
            String id = IconResolver.forBlock(block);
            if (!realItem(id)) {
                bad.add(BuiltInRegistries.BLOCK.getKey(block) + " -> " + id);
            }
        }
        assertTrue("no icon for " + bad, bad.isEmpty());
        assertTrue(offered > 500);
    }

    @Test
    public void everyMobHasAnEggOrAnExplicitStandIn() {
        List<String> bad = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == EntityTypes.PLAYER || type.getCategory() == MobCategory.MISC) {
                continue;
            }
            String id = IconResolver.forEntityType(type);
            if (!realItem(id)) {
                bad.add(BuiltInRegistries.ENTITY_TYPE.getKey(type) + " -> " + id);
            }
        }
        assertTrue("no icon for " + bad, bad.isEmpty());
    }

    @Test
    public void onlyTheGiantAndTheIllusionerLackAnEgg() {
        // a version bump that adds an egg-less mob lands here, so it gets a stand-in on purpose
        List<String> eggless = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() != MobCategory.MISC && SpawnEggItem.byId(type).isEmpty()) {
                eggless.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
            }
        }
        eggless.sort(null);
        assertEquals(List.of("giant", "illusioner"), eggless);
    }

    @Test
    public void knownAnswers() {
        assertEquals("minecraft:zombie_spawn_egg", IconResolver.forEntityType(EntityTypes.ZOMBIE));
        assertEquals("minecraft:iron_golem_spawn_egg", IconResolver.forEntityType(EntityTypes.IRON_GOLEM));
        assertEquals("minecraft:zombie_head", IconResolver.forEntityType(EntityTypes.GIANT));
        assertEquals("minecraft:totem_of_undying", IconResolver.forEntityType(EntityTypes.ILLUSIONER));
        assertEquals("minecraft:armor_stand", IconResolver.forEntityType(EntityTypes.ARMOR_STAND));
        assertEquals("minecraft:water_bucket", IconResolver.forBlock(Blocks.WATER));
        assertEquals("minecraft:lava_bucket", IconResolver.forBlock(Blocks.LAVA));
        assertEquals("minecraft:flint_and_steel", IconResolver.forBlock(Blocks.FIRE));
        assertEquals("minecraft:oak_log", IconResolver.forBlock(Blocks.OAK_LOG));
        assertEquals("minecraft:oak_log", IconResolver.forItemId("oak_log"));
        assertEquals("minecraft:oak_log", IconResolver.forItemId("minecraft:oak_log"));
        assertEquals("minecraft:oak_log", IconResolver.forResource("oak_log"));
    }

    @Test
    public void playersAndNonsenseGetNothing() {
        assertNull(IconResolver.forEntityType(EntityTypes.PLAYER));
        assertNull(IconResolver.forEntityType(null));
        assertNull(IconResolver.forBlock(null));
        assertNull(IconResolver.forItemId("not_a_thing"));
        assertNull(IconResolver.forItemId("minecraft:air"));
        assertNull(IconResolver.forItemId("Bad Id!"));
        assertNull(IconResolver.forItemId(null));
        assertNull(IconResolver.forResource(null));
        assertNull(IconResolver.forResource("definitely_not_catalogued"));
    }

    @Test
    public void groupsSpeakThroughTheirFirstItem() {
        String planks = IconResolver.forResource("planks");
        assertTrue(planks, planks != null && planks.endsWith("planks"));
        String log = IconResolver.forResource("log");
        assertTrue(log, log != null && log.endsWith("log"));
    }
}
