package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaterialListTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the coloured blocks aren't plain fields on Blocks any more, so look them up by id
    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:" + id));
    }

    private static MaterialList of(BlockState... states) {
        MaterialList.Builder builder = new MaterialList.Builder();
        for (BlockState state : states) {
            builder.add(state);
        }
        return builder.build();
    }

    private static Map<String, Integer> items(MaterialList list) {
        Map<String, Integer> out = new HashMap<>();
        for (MaterialLine line : list.items()) {
            assertEquals(MaterialLine.Kind.ITEM, line.kind());
            out.put(line.item(), line.count());
        }
        return out;
    }

    private static Map<String, Integer> one(String id, int count) {
        return Map.of("minecraft:" + id, count);
    }

    @Test
    public void airIsFree() {
        MaterialList list = of(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState());
        assertTrue(list.items().isEmpty());
        assertTrue(list.cantPlace().isEmpty());
        assertTrue(list.notCounted().isEmpty());
    }

    @Test
    public void plainBlocksAddUp() {
        MaterialList list = of(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        assertEquals(Map.of("minecraft:stone", 2, "minecraft:dirt", 1), items(list));
        // biggest first
        assertEquals("minecraft:stone", list.items().get(0).item());
    }

    @Test
    public void everyOddBlockWithoutItsOwnItemResolvesToTheItemYouWouldHold() {
        // asItem() answers these on its own, but this is the list that would silently go wrong if a version changed it
        Map<Block, String> expected = new HashMap<>();
        expected.put(Blocks.WALL_TORCH, "torch");
        expected.put(Blocks.REDSTONE_WALL_TORCH, "redstone_torch");
        expected.put(Blocks.SOUL_WALL_TORCH, "soul_torch");
        expected.put(Blocks.OAK_WALL_SIGN, "oak_sign");
        expected.put(block("white_wall_banner"), "white_banner");
        expected.put(Blocks.ZOMBIE_WALL_HEAD, "zombie_head");
        expected.put(Blocks.TUBE_CORAL_WALL_FAN, "tube_coral_fan");
        expected.put(Blocks.REDSTONE_WIRE, "redstone");
        expected.put(Blocks.TRIPWIRE, "string");
        expected.put(Blocks.WHEAT, "wheat_seeds");
        expected.put(Blocks.CARROTS, "carrot");
        expected.put(Blocks.POTATOES, "potato");
        expected.put(Blocks.BEETROOTS, "beetroot_seeds");
        expected.put(Blocks.PUMPKIN_STEM, "pumpkin_seeds");
        expected.put(Blocks.MELON_STEM, "melon_seeds");
        expected.put(Blocks.COCOA, "cocoa_beans");
        expected.put(Blocks.SWEET_BERRY_BUSH, "sweet_berries");
        expected.put(Blocks.TORCHFLOWER_CROP, "torchflower_seeds");
        expected.put(Blocks.PITCHER_CROP, "pitcher_pod");
        expected.put(Blocks.CAVE_VINES, "glow_berries");
        expected.put(Blocks.BIG_DRIPLEAF_STEM, "big_dripleaf");
        expected.put(Blocks.POWDER_SNOW, "powder_snow_bucket");
        // and the ones the table has to catch
        expected.put(Blocks.KELP_PLANT, "kelp");
        expected.put(Blocks.CAVE_VINES_PLANT, "glow_berries");
        expected.put(Blocks.WEEPING_VINES_PLANT, "weeping_vines");
        expected.put(Blocks.TWISTING_VINES_PLANT, "twisting_vines");
        expected.put(Blocks.BAMBOO_SAPLING, "bamboo");
        expected.put(Blocks.ATTACHED_PUMPKIN_STEM, "pumpkin_seeds");
        expected.put(Blocks.ATTACHED_MELON_STEM, "melon_seeds");
        expected.forEach((block, item) -> {
            MaterialList list = of(block.defaultBlockState());
            assertEquals(BuiltInRegistries.BLOCK.getKey(block).toString(), one(item, 1), items(list));
            assertTrue(list.cantPlace().isEmpty());
        });
    }

    @Test
    public void tableTargetsAreRealItems() {
        // the table is strings, so a typo wouldn't fail to compile. every block it covers is run through the builder
        // and has to land on an item that exists
        for (Block block : BuiltInRegistries.BLOCK) {
            MaterialList list = of(block.defaultBlockState());
            for (MaterialLine line : list.items()) {
                assertTrue(line.item(), BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(line.item())));
            }
        }
    }

    @Test
    public void blocksWithNoItemAreCantPlaceNotErrors() {
        for (Block block : List.of(Blocks.FROSTED_ICE, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.NETHER_PORTAL,
            Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.BUBBLE_COLUMN)) {
            MaterialList list = of(block.defaultBlockState(), block.defaultBlockState());
            assertTrue(list.items().isEmpty());
            assertEquals(1, list.cantPlace().size());
            MaterialLine line = list.cantPlace().get(0);
            assertEquals(MaterialLine.Kind.CANT_PLACE, line.kind());
            assertEquals(BuiltInRegistries.BLOCK.getKey(block).toString(), line.item());
            assertEquals(2, line.count());
        }
        assertEquals("walk over water with frost walker boots", of(Blocks.FROSTED_ICE.defaultBlockState()).cantPlace().get(0).note());
    }

    @Test
    public void everyBlockInTheGameEndsUpSomewhere() {
        // nothing may throw and nothing may vanish: each non-air block is an item line, a can't place line, a fluid
        // or one of the deliberate zeroes (piston parts, second halves)
        List<String> zero = List.of("minecraft:piston_head", "minecraft:moving_piston");
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (state.isAir()) {
                continue;
            }
            MaterialList list = of(state);
            int lines = list.items().size() + list.cantPlace().size() + list.notCounted().size();
            String id = MaterialList.blockId(block);
            boolean upperHalf = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            // beds default to the foot, which rides along with the head
            boolean bedFoot = block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT;
            if (zero.contains(id) || upperHalf || bedFoot) {
                assertEquals(id, 0, lines);
            } else {
                assertTrue(id, lines >= 1);
            }
        }
    }

    @Test
    public void pottedPlantsAreAPotAndThePlant() {
        MaterialList list = of(Blocks.POTTED_DANDELION.defaultBlockState(), Blocks.POTTED_BAMBOO.defaultBlockState(),
            Blocks.FLOWER_POT.defaultBlockState());
        assertEquals(Map.of("minecraft:flower_pot", 3, "minecraft:dandelion", 1, "minecraft:bamboo", 1), items(list));
        assertTrue(list.cantPlace().isEmpty());
        for (Block block : BuiltInRegistries.BLOCK) {
            if (MaterialList.blockId(block).startsWith("minecraft:potted_")) {
                assertTrue(MaterialList.blockId(block), of(block.defaultBlockState()).cantPlace().isEmpty());
            }
        }
    }

    @Test
    public void candleCakesAreCakePlusTheRightCandle() {
        assertEquals(Map.of("minecraft:cake", 1, "minecraft:candle", 1), items(of(Blocks.CANDLE_CAKE.defaultBlockState())));
        assertEquals(Map.of("minecraft:cake", 1, "minecraft:red_candle", 1), items(of(block("red_candle_cake").defaultBlockState())));
    }

    @Test
    public void cauldronsAreJustTheCauldron() {
        MaterialList list = of(Blocks.CAULDRON.defaultBlockState(), Blocks.WATER_CAULDRON.defaultBlockState(), Blocks.LAVA_CAULDRON.defaultBlockState());
        assertEquals(one("cauldron", 3), items(list));
        assertEquals("contents not counted", list.items().get(0).note());
    }

    @Test
    public void bothHalvesOfADoorAreOneDoor() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertEquals(one("oak_door", 1), items(of(lower, upper)));
    }

    @Test
    public void tallPlantsAreCountedOnceFromTheLowerHalf() {
        for (Block block : List.of(Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.SUNFLOWER, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.LILAC, Blocks.TALL_SEAGRASS)) {
            BlockState lower = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
            BlockState upper = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
            MaterialList list = of(lower, upper);
            assertEquals(MaterialList.blockId(block), 1, list.items().get(0).count());
            assertEquals(1, list.items().size());
        }
        // the lower half of tall seagrass is the only part with a name to give
        assertEquals(one("seagrass", 1), items(of(Blocks.TALL_SEAGRASS.defaultBlockState())));
    }

    @Test
    public void aBedIsCountedFromTheHead() {
        BlockState head = block("red_bed").defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD);
        BlockState foot = block("red_bed").defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT);
        assertEquals(one("red_bed", 1), items(of(head, foot)));
        // the foot alone is nothing, the head is what counts
        assertTrue(items(of(foot)).isEmpty());
    }

    @Test
    public void doubleSlabIsTwo() {
        BlockState single = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockState top = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        BlockState full = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        assertEquals(one("oak_slab", 1 + 1 + 2), items(of(single, top, full)));
    }

    @Test
    public void snowLayersCountTheLayers() {
        BlockState three = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3);
        BlockState eight = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 8);
        assertEquals(one("snow", 11), items(of(three, eight)));
        // snow blocks are a different item with no layers to speak of
        assertEquals(one("snow_block", 1), items(of(Blocks.SNOW_BLOCK.defaultBlockState())));
    }

    @Test
    public void candlesPicklesAndEggsCountTheirProperty() {
        assertEquals(one("candle", 4), items(of(Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 4))));
        assertEquals(one("blue_candle", 2), items(of(block("blue_candle").defaultBlockState().setValue(BlockStateProperties.CANDLES, 2))));
        assertEquals(one("sea_pickle", 3), items(of(Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 3))));
        assertEquals(one("turtle_egg", 4), items(of(Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 4))));
        assertEquals(one("pink_petals", 3), items(of(Blocks.PINK_PETALS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 3))));
    }

    @Test
    public void multifaceBlocksCountTheirFaces() {
        BlockState vine = Blocks.VINE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.EAST, true);
        assertEquals(one("vine", 2), items(of(vine)));
        // a vine with no faces set still has to be placed once
        assertEquals(one("vine", 1), items(of(Blocks.VINE.defaultBlockState())));
        BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState()
            .setValue(BlockStateProperties.DOWN, true).setValue(BlockStateProperties.UP, true).setValue(BlockStateProperties.WEST, true);
        assertEquals(one("glow_lichen", 3), items(of(lichen)));
        BlockState sculk = Blocks.SCULK_VEIN.defaultBlockState().setValue(BlockStateProperties.SOUTH, true);
        assertEquals(one("sculk_vein", 1), items(of(sculk)));
        // fences have the same properties and must not be multiplied
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true);
        assertEquals(one("oak_fence", 1), items(of(fence)));
    }

    @Test
    public void creativeOnlyItemsCarryANote() {
        for (Block block : List.of(Blocks.BEDROCK, Blocks.SPAWNER, Blocks.CHORUS_PLANT, Blocks.INFESTED_STONE, Blocks.FARMLAND)) {
            MaterialList list = of(block.defaultBlockState());
            assertTrue(MaterialList.blockId(block), list.cantPlace().isEmpty());
            assertTrue(MaterialList.blockId(block), list.items().get(0).note().contains("no survival item"));
        }
    }

    @Test
    public void waterloggedCountsTheBlockOnly() {
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        MaterialList list = of(wet, Blocks.OAK_STAIRS.defaultBlockState());
        assertEquals(one("oak_stairs", 2), items(list));
        assertTrue(list.notCounted().isEmpty());
        assertTrue(list.notes().stream().anyMatch(n -> n.contains("1 waterlogged block")));
    }

    @Test
    public void fluidSourcesAreListedButNotCounted() {
        BlockState source = Blocks.WATER.defaultBlockState();
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        MaterialList list = of(source, source, flowing, Blocks.LAVA.defaultBlockState());
        assertTrue(list.items().isEmpty());
        assertTrue(list.cantPlace().isEmpty());
        Map<String, MaterialLine> byId = new HashMap<>();
        list.notCounted().forEach(l -> byId.put(l.item(), l));
        assertEquals(2, byId.size());
        assertEquals(2, byId.get("minecraft:water").count());
        assertEquals(MaterialLine.Kind.NOT_COUNTED, byId.get("minecraft:water").kind());
        assertTrue(byId.get("minecraft:water").note().contains("1 flowing"));
        assertEquals(1, byId.get("minecraft:lava").count());
    }

    @Test
    public void pistonHeadsAndMovingPistonsAreFree() {
        MaterialList list = of(Blocks.PISTON.defaultBlockState(), Blocks.PISTON_HEAD.defaultBlockState(),
            Blocks.STICKY_PISTON.defaultBlockState(), Blocks.PISTON_HEAD.defaultBlockState(), Blocks.MOVING_PISTON.defaultBlockState());
        assertEquals(Map.of("minecraft:piston", 1, "minecraft:sticky_piston", 1), items(list));
        assertTrue(list.cantPlace().isEmpty());
    }

    @Test
    public void farmlandAndPathCarrySurvivalHints() {
        // both have creative items in this version, so they're item lines, but with a note about how a survival build gets them
        MaterialList list = of(Blocks.FARMLAND.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState());
        assertEquals(Map.of("minecraft:farmland", 1, "minecraft:dirt_path", 1), items(list));
        for (MaterialLine line : list.items()) {
            assertFalse(line.item(), line.note().isEmpty());
        }
    }

    @Test
    public void entitiesAreAlwaysFlaggedAsNotCounted() {
        assertTrue(of().notes().stream().anyMatch(n -> n.contains("entities") && n.contains("not counted")));
    }

    @Test
    public void ofASchematicWalksEveryPosition() {
        BlockState[][][] states = new BlockState[2][2][2];
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                states[x][z][0] = Blocks.STONE.defaultBlockState();
                states[x][z][1] = Blocks.OAK_DOOR.defaultBlockState();
            }
        }
        IStaticSchematic schematic = new StaticSchematic(states);
        MaterialList list = MaterialList.of(schematic);
        assertEquals(Map.of("minecraft:stone", 4, "minecraft:oak_door", 4), items(list));
    }

    @Test
    public void inventoryIsCountedByItemNotBlock() {
        List<net.minecraft.world.item.ItemStack> stacks = new ArrayList<>();
        stacks.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 12));
        stacks.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 5));
        stacks.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_DOOR, 2));
        stacks.add(net.minecraft.world.item.ItemStack.EMPTY);
        stacks.add(null);
        Map<String, Integer> have = InventoryCounts.of(stacks);
        assertEquals(Map.of("minecraft:torch", 17, "minecraft:oak_door", 2), have);
        // a wall torch in the schematic is asked for as "torch", so the same key answers it
        assertEquals(17, (int) have.get(items(of(Blocks.WALL_TORCH.defaultBlockState())).keySet().iterator().next()));
    }

    @Test
    public void inventoryCountsAContainer() {
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(4);
        container.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        container.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
        assertEquals(Map.of("minecraft:stone", 74), InventoryCounts.of(container));
    }

    // one state priced alone is the same as a list of one, so the builder's missing map and the tab agree
    @Test
    public void itemsForOneStateFollowsTheListRules() {
        assertEquals(one("stone", 1), MaterialList.itemsFor(Blocks.STONE.defaultBlockState()));
        assertEquals(one("oak_slab", 2), MaterialList.itemsFor(
            Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        assertEquals(one("torch", 1), MaterialList.itemsFor(Blocks.WALL_TORCH.defaultBlockState()));
        assertEquals(one("kelp", 1), MaterialList.itemsFor(Blocks.KELP_PLANT.defaultBlockState()));
        assertEquals(Map.of("minecraft:flower_pot", 1, "minecraft:poppy", 1),
            MaterialList.itemsFor(Blocks.POTTED_POPPY.defaultBlockState()));
        // second halves, air, fluids and fire have nothing to buy
        assertTrue(MaterialList.itemsFor(Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)).isEmpty());
        assertTrue(MaterialList.itemsFor(Blocks.AIR.defaultBlockState()).isEmpty());
        assertTrue(MaterialList.itemsFor(Blocks.WATER.defaultBlockState()).isEmpty());
        assertTrue(MaterialList.itemsFor(Blocks.FIRE.defaultBlockState()).isEmpty());
        assertTrue(MaterialList.itemsFor(null).isEmpty());
    }
}
