package adris.altoclef.ui;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the words on the task hud. no Minecraft client here so english() falls back to true
public class HudTextTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void humanizesClassNames() {
        assertEquals("Mine and collect", HudText.humanizeClassName("MineAndCollectTask"));
        assertEquals("MLG bucket", HudText.humanizeClassName("MLGBucketTask"));
        assertEquals("SCP 173", HudText.humanizeClassName("SCP173Task"));
        assertEquals("Beat minecraft 2", HudText.humanizeClassName("BeatMinecraft2Task"));
        assertEquals("Get to XZ", HudText.humanizeClassName("GetToXZTask"));
        assertEquals("Task", HudText.humanizeClassName("Task"));
        assertEquals("Working", HudText.humanizeClassName(""));
        // anonymous classes have no simple name, the parent's is used
        Object anon = new Object() {
        };
        assertEquals("Object", HudText.humanizeClassName(anon.getClass()));
    }

    @Test
    public void localizedNames() {
        assertEquals("Oak Log", HudText.item(Items.OAK_LOG));
        assertEquals("Crafting Table", HudText.item(Items.CRAFTING_TABLE));
        assertEquals("Oak Log", HudText.block(Blocks.OAK_LOG));
        assertEquals("Zombie", HudText.entityType(EntityTypes.ZOMBIE));
        assertEquals("Zombie", HudText.entityClass(Zombie.class));
        assertEquals("Skeleton", HudText.entityClass(AbstractSkeleton.class));
    }

    @Test
    public void positionsAndDimensions() {
        assertEquals("506, 160, 1125", HudText.pos(new BlockPos(506, 160, 1125)));
        assertEquals("somewhere", HudText.pos((BlockPos) null));
        assertEquals("the Nether", HudText.dimension(baritone.api.utils.Dimension.NETHER));
        assertEquals("the Overworld", HudText.dimension(baritone.api.utils.Dimension.OVERWORLD));
        assertEquals("the End", HudText.dimension(baritone.api.utils.Dimension.END));
    }

    @Test
    public void countsArticlesAndPlurals() {
        assertEquals("a Crafting Table", HudText.one("Crafting Table"));
        assertEquals("an Iron Ingot", HudText.one("Iron Ingot"));
        // uncountable stays bare, so does a name that is already a pile
        assertEquals("Coal", HudText.one("Coal"));
        assertEquals("Oak Planks", HudText.one("Oak Planks"));
        assertEquals("3 Iron Ingots", HudText.count(3, "Iron Ingot"));
        assertEquals("4 Oak Planks", HudText.count(4, "Oak Planks"));
        assertEquals("3 Coal", HudText.count(3, "Coal"));
        assertEquals("3 Raw Iron", HudText.count(3, "Raw Iron"));
        assertEquals("2 Sweet Berries", HudText.count(2, "Sweet Berry"));
        assertEquals("2 Shulker Boxes", HudText.count(2, "Shulker Box"));
        assertEquals("a Crafting Table", HudText.count(1, "Crafting Table"));
        assertEquals("Iron Ingots", HudText.plural("Iron Ingot"));
        assertEquals("Zombies", HudText.pluralMob("Zombie"));
        assertEquals("Endermen", HudText.pluralMob("Enderman"));
        assertEquals("Wolves", HudText.pluralMob("Wolf"));
        assertEquals("Witches", HudText.pluralMob("Witch"));
        assertEquals("Sheep", HudText.pluralMob("Sheep"));
    }

    @Test
    public void collapsesGroups() {
        assertEquals("logs", HudText.some(ItemHelper.LOG));
        assertEquals("planks", HudText.some(ItemHelper.PLANKS));
        assertEquals("wool", HudText.some(ItemHelper.WOOL));
        assertEquals("beds", HudText.some(ItemHelper.BED));
        assertEquals("a log", HudText.items(ItemHelper.LOG, 1));
        assertEquals("16 logs", HudText.items(ItemHelper.LOG, 16));
        assertEquals("4 planks", HudText.items(ItemHelper.PLANKS, 4));
        assertEquals("a bed", HudText.items(ItemHelper.BED, 1));
        // a subset of a group still reads as the group
        assertEquals("logs", HudText.some(new Item[]{Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG}));
        // not a group: name the first and count the rest
        assertEquals("Flint and Steel or 1 other", HudText.some(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}));
        assertEquals("Diamond or 2 others", HudText.some(new Item[]{Items.DIAMOND, Items.EMERALD, Items.COAL}));
    }

    @Test
    public void itemTargets() {
        assertEquals("a Crafting Table", HudText.items(new ItemTarget(Items.CRAFTING_TABLE, 1)));
        assertEquals("3 Iron Ingots", HudText.items(new ItemTarget(Items.IRON_INGOT, 3)));
        assertEquals("4 planks", HudText.items(new ItemTarget("planks", 4)));
        assertEquals("a log", HudText.items(new ItemTarget("log", 1)));
        assertEquals("3 Iron Ingots and a Stone Sword", HudText.items(new ItemTarget(Items.IRON_INGOT, 3), new ItemTarget(Items.STONE_SWORD, 1)));
        assertEquals("logs, planks and 2 Sticks", HudText.items(new ItemTarget("log", 1).infinite(), new ItemTarget("planks", 1).infinite(), new ItemTarget(Items.STICK, 2)));
        // the no-number form for verbs where one is boring
        assertEquals("logs", HudText.some(new ItemTarget("log", 1)));
        assertEquals("16 logs", HudText.some(new ItemTarget("log", 16)));
        assertEquals("Cobblestone", HudText.some(new ItemTarget(Items.COBBLESTONE, 1)));
        assertEquals("3 Cobblestone", HudText.some(new ItemTarget(Items.COBBLESTONE, 3)));
        assertEquals("something", HudText.items(ItemTarget.EMPTY));
        assertEquals("something", HudText.items(new ItemTarget[0]));
    }

    @Test
    public void listsAndBlocks() {
        assertEquals("a", HudText.list(List.of("a")));
        assertEquals("a and b", HudText.list(Arrays.asList("a", "b")));
        assertEquals("a, b and c", HudText.list(Arrays.asList("a", "b", "c")));
        assertEquals("Oak Log", HudText.blocks(new net.minecraft.world.level.block.Block[]{Blocks.OAK_LOG}));
        assertEquals("logs", HudText.blocks(ItemHelper.itemsToBlocks(ItemHelper.LOG)));
        assertTrue(HudText.blocks(null).length() > 0);
    }

    @Test
    public void oreAndDeepslateTwinReadAsOneOre() {
        assertEquals("Diamond Ore", HudText.blocks(new net.minecraft.world.level.block.Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}));
        assertEquals("Lapis Lazuli Ore", HudText.blocks(new net.minecraft.world.level.block.Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE}));
        // a lone deepslate ore is still what it says it is
        assertEquals("Deepslate Diamond Ore", HudText.blocks(new net.minecraft.world.level.block.Block[]{Blocks.DEEPSLATE_DIAMOND_ORE}));
    }
}
