package baritone.utils.schematic.library;

import baritone.api.schematic.ISchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// what a schematic costs to build, by item. pure: it only looks at block states, so tests can feed it states directly
// and never need a world. what the player is carrying is a separate question, see InventoryCounts
public record MaterialList(List<MaterialLine> items, List<MaterialLine> cantPlace, List<MaterialLine> notCounted,
                           List<String> notes) {

    // blocks that have no item of their own but are obviously made from one. asItem() gives air for all of these
    // (checked against the whole block registry, the crops, vines and wall variants already resolve on their own)
    private static final Map<String, String> NO_ITEM = Map.ofEntries(
        Map.entry("kelp_plant", "kelp"),
        Map.entry("tall_seagrass", "seagrass"),
        Map.entry("bamboo_sapling", "bamboo"),
        Map.entry("cave_vines_plant", "glow_berries"),
        Map.entry("weeping_vines_plant", "weeping_vines"),
        Map.entry("twisting_vines_plant", "twisting_vines"),
        Map.entry("attached_pumpkin_stem", "pumpkin_seeds"),
        Map.entry("attached_melon_stem", "melon_seeds")
    );

    // for the blocks that really can't be placed, what the player can do about it
    private static final Map<String, String> CANT_PLACE_HINT = Map.of(
        "frosted_ice", "walk over water with frost walker boots",
        "fire", "light it with flint and steel",
        "soul_fire", "light soul soil with flint and steel"
    );

    // these do have an item, but it only exists in creative, so a survival build has to make them another way
    private static final Map<String, String> CREATIVE_ONLY_HINT = Map.of(
        "farmland", "no survival item, till dirt with a hoe",
        "dirt_path", "no survival item, right click grass or dirt with a shovel",
        "chorus_plant", "no survival item, grows from chorus flowers"
    );

    // creative inventory only, a survival build can't get these at all (silk touch doesn't count)
    private static final Set<String> CREATIVE_ONLY = Set.of(
        "bedrock", "barrier", "light", "command_block", "chain_command_block", "repeating_command_block",
        "structure_block", "structure_void", "jigsaw", "spawner", "trial_spawner", "vault", "budding_amethyst",
        "reinforced_deepslate", "end_portal_frame", "petrified_oak_slab", "test_block", "test_instance_block"
    );

    private static final List<BooleanProperty> FACES = List.of(
        BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH,
        BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN
    );

    private static final List<IntegerProperty> STACKED = List.of(
        BlockStateProperties.LAYERS,
        BlockStateProperties.CANDLES,
        BlockStateProperties.PICKLES,
        BlockStateProperties.EGGS,
        BlockStateProperties.FLOWER_AMOUNT,
        BlockStateProperties.SEGMENT_AMOUNT
    );

    public static MaterialList of(ISchematic schematic) {
        Builder builder = new Builder();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    // inSchematic first, a multi region litematic has gaps desiredState would throw on
                    if (schematic.inSchematic(x, y, z, null)) {
                        builder.add(schematic.desiredState(x, y, z, null, Collections.emptyList()));
                    }
                }
            }
        }
        return builder.build();
    }

    // one state's share of the bill, by item id. the same rules as the whole list (second halves are free, a double
    // slab is two, candles and pickles stack), so the builder's missing map gets priced like the tab prices the file.
    // empty for air, fluids, second halves and the blocks with no item at all
    public static Map<String, Integer> itemsFor(BlockState state) {
        Builder one = new Builder();
        one.add(state);
        return Map.copyOf(one.items);
    }

    public static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static final class Builder {
        private final Map<String, Integer> items = new LinkedHashMap<>();
        private final Map<String, Set<String>> itemNotes = new LinkedHashMap<>();
        private final Map<String, Integer> cantPlace = new LinkedHashMap<>();
        private final Map<String, Integer> sources = new LinkedHashMap<>();
        private final Map<String, Integer> flowing = new LinkedHashMap<>();
        private long waterlogged;

        public Builder add(BlockState state) {
            if (state == null || state.isAir()) {
                return this;
            }
            Block block = state.getBlock();
            if (block instanceof LiquidBlock) {
                // only level 0 is a source, the rest is water that would flow there by itself
                (state.getFluidState().isSource() ? sources : flowing).merge(blockId(block), 1, Integer::sum);
                return this;
            }
            if (isSecondHalf(state)) {
                return this;
            }
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                waterlogged++;
            }
            int count = stackedCount(state);
            if (block instanceof AbstractCauldronBlock) {
                // the water or lava in it is a bucket nobody is counting
                addItem(Items.CAULDRON, count, block == Blocks.CAULDRON ? null : "contents not counted");
            } else if (block instanceof FlowerPotBlock pot) {
                addItem(Items.FLOWER_POT, count, null);
                if (pot.getPotted() != Blocks.AIR) {
                    addItem(pot.getPotted().asItem(), count, "potted in " + blockId(block));
                }
            } else if (blockId(block).endsWith("candle_cake")) {
                addItem(Items.CAKE, count, null);
                addNamed(blockId(block).replace("_cake", ""), count, "from " + blockId(block));
            } else {
                addPlain(block, count);
            }
            return this;
        }

        private void addPlain(Block block, int count) {
            Item item = block.asItem();
            String id = blockId(block);
            String path = id.substring(id.indexOf(':') + 1);
            if (item != Items.AIR) {
                String hint = CREATIVE_ONLY_HINT.get(path);
                if (hint == null && (CREATIVE_ONLY.contains(path) || path.startsWith("infested_"))) {
                    hint = "no survival item";
                }
                addItem(item, count, hint);
                return;
            }
            String viaTable = NO_ITEM.get(path);
            if (viaTable != null) {
                addNamed("minecraft:" + viaTable, count, "from " + id);
            } else {
                cantPlace.merge(id, count, Integer::sum);
            }
        }

        private void addItem(Item item, int count, String note) {
            addNamed(itemId(item), count, note);
        }

        private void addNamed(String itemId, int count, String note) {
            items.merge(itemId, count, Integer::sum);
            if (note != null) {
                itemNotes.computeIfAbsent(itemId, k -> new LinkedHashSet<>()).add(note);
            }
        }

        public MaterialList build() {
            List<MaterialLine> itemLines = new ArrayList<>();
            items.forEach((id, count) -> itemLines.add(new MaterialLine(id, count, MaterialLine.Kind.ITEM,
                String.join("; ", itemNotes.getOrDefault(id, Set.of())))));
            itemLines.sort(Comparator.comparingInt(MaterialLine::count).reversed().thenComparing(MaterialLine::item));

            List<MaterialLine> cantLines = new ArrayList<>();
            cantPlace.forEach((id, count) -> cantLines.add(new MaterialLine(id, count, MaterialLine.Kind.CANT_PLACE,
                CANT_PLACE_HINT.getOrDefault(id.substring(id.indexOf(':') + 1), ""))));
            cantLines.sort(Comparator.comparingInt(MaterialLine::count).reversed().thenComparing(MaterialLine::item));

            List<MaterialLine> fluidLines = new ArrayList<>();
            sources.forEach((id, count) -> {
                int moving = flowing.getOrDefault(id, 0);
                fluidLines.add(new MaterialLine(id, count, MaterialLine.Kind.NOT_COUNTED,
                    "source blocks" + (moving > 0 ? ", " + moving + " flowing not counted" : "")));
            });
            flowing.forEach((id, moving) -> {
                if (!sources.containsKey(id)) {
                    fluidLines.add(new MaterialLine(id, 0, MaterialLine.Kind.NOT_COUNTED, moving + " flowing not counted"));
                }
            });

            List<String> notes = new ArrayList<>();
            // the schematic api only hands out blocks, so there's nothing to count even if the file had them
            notes.add("entities (item frames, paintings, armor stands) are not counted");
            if (waterlogged > 0) {
                notes.add(waterlogged + " waterlogged " + (waterlogged == 1 ? "block" : "blocks") + ", the water is not counted");
            }
            return new MaterialList(List.copyOf(itemLines), List.copyOf(cantLines), List.copyOf(fluidLines), List.copyOf(notes));
        }
    }

    // the upper half of a door or tall plant and the foot of a bed place together with the other half
    private static boolean isSecondHalf(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT;
    }

    // vines, glow lichen, sculk veins and resin clumps are one item per face
    private static int faces(BlockState state) {
        int faces = 0;
        for (BooleanProperty face : FACES) {
            if (state.hasProperty(face) && state.getValue(face)) {
                faces++;
            }
        }
        return Math.max(1, faces);
    }

    // double slab is two slabs, snow layers / candles / pickles / eggs are one item per step of the property
    private static int stackedCount(BlockState state) {
        if (state.getBlock() instanceof VineBlock || state.getBlock() instanceof MultifaceBlock) {
            return faces(state);
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
            && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        for (IntegerProperty property : STACKED) {
            if (state.hasProperty(property)) {
                return state.getValue(property);
            }
        }
        return 1;
    }
}
