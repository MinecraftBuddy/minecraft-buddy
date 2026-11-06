package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.resources.MineAndCollectTask.Part;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

// the bits of a together task that decide what is still worth mining. "short" and "have the pick" are handed in, no game
public class MineOresTogetherTest {

    private static Part iron;
    private static Part diamond;
    private static Part coal;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        iron = new Part(new ItemTarget(Items.RAW_IRON, 5), MiningRequirement.STONE, new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE});
        diamond = new Part(new ItemTarget(Items.DIAMOND, 3), MiningRequirement.IRON, new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE});
        coal = new Part(new ItemTarget(Items.COAL, 8), MiningRequirement.WOOD, new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE});
    }

    private static Predicate<MiningRequirement> pick(MiningRequirement held) {
        return need -> need.ordinal() <= held.ordinal();
    }

    @Test
    public void blocksOfShortOresWeCanMine() {
        Map<Block, List<Part>> partsOf = MineAndCollectTask.partsOf(List.of(iron, diamond));
        Set<Block> wanted = MineAndCollectTask.wantedBlocks(partsOf, part -> true, pick(MiningRequirement.IRON));
        assertEquals(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), wanted);
    }

    @Test
    public void metOreDropsOutOfTheFilter() {
        // iron is done, its vein right next to us is not a pick anymore
        Map<Block, List<Part>> partsOf = MineAndCollectTask.partsOf(List.of(iron, diamond));
        Set<Block> wanted = MineAndCollectTask.wantedBlocks(partsOf, part -> part != iron, pick(MiningRequirement.IRON));
        assertEquals(Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), wanted);
    }

    @Test
    public void oreWithoutItsPickIsLeftAlone() {
        // stone pick: diamonds would break into nothing
        Map<Block, List<Part>> partsOf = MineAndCollectTask.partsOf(List.of(iron, diamond));
        Set<Block> wanted = MineAndCollectTask.wantedBlocks(partsOf, part -> true, pick(MiningRequirement.STONE));
        assertEquals(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), wanted);
    }

    @Test
    public void sharedBlocksCountWhileAnyPartIsShort() {
        // coal 5 and coal 10 off the same blocks: the 5 being met doesn't stop the 10
        Part coal5 = new Part(new ItemTarget(Items.COAL, 5), MiningRequirement.WOOD, coal.blocks());
        Part coal10 = new Part(new ItemTarget(Items.COAL, 10), MiningRequirement.WOOD, coal.blocks());
        Map<Block, List<Part>> partsOf = MineAndCollectTask.partsOf(List.of(coal5, coal10));
        assertEquals(2, partsOf.get(Blocks.COAL_ORE).size());
        Set<Block> wanted = MineAndCollectTask.wantedBlocks(partsOf, part -> part == coal10, pick(MiningRequirement.WOOD));
        assertEquals(Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), wanted);
        assertTrue(MineAndCollectTask.wantedBlocks(partsOf, part -> false, pick(MiningRequirement.WOOD)).isEmpty());
    }

    @Test
    public void partsOfKeepsInsertionOrder() {
        Map<Block, List<Part>> partsOf = MineAndCollectTask.partsOf(List.of(diamond, iron));
        assertArrayEquals(new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE},
                partsOf.keySet().toArray(Block[]::new));
    }

    @Test
    public void requirementIsTheCheapestShortOne() {
        List<Part> parts = List.of(diamond, iron, coal);
        assertEquals(MiningRequirement.WOOD, MineAndCollectTask.lowestShort(parts, part -> true));
        // coal done: iron's stone pick before the diamonds' iron one
        assertEquals(MiningRequirement.STONE, MineAndCollectTask.lowestShort(parts, part -> part != coal));
        // only the diamonds left, now the iron pick is what we need
        assertEquals(MiningRequirement.IRON, MineAndCollectTask.lowestShort(parts, part -> part == diamond));
        assertNull(MineAndCollectTask.lowestShort(parts, part -> false));
    }

    @Test
    public void onlyShortDropsAreChased() {
        List<Part> parts = List.of(iron, diamond, coal);
        assertArrayEquals(new ItemTarget[]{iron.target(), diamond.target(), coal.target()}, MineAndCollectTask.shortTargets(parts, part -> true));
        assertArrayEquals(new ItemTarget[]{diamond.target()}, MineAndCollectTask.shortTargets(parts, part -> part == diamond));
        assertEquals(0, MineAndCollectTask.shortTargets(parts, part -> false).length);
    }

    @Test
    public void dimensionCheckAsksAboutShortOresOnly() {
        List<Part> parts = List.of(iron, diamond);
        // no pick filter here: a short ore we can't mine yet is still a reason to be in this world
        assertArrayEquals(new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, MineAndCollectTask.shortBlocks(parts, part -> part == diamond));
        assertArrayEquals(new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE},
                MineAndCollectTask.shortBlocks(parts, part -> true));
        assertEquals(0, MineAndCollectTask.shortBlocks(parts, part -> false).length);
    }
}
