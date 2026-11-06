package adris.altoclef.takealong;

import adris.altoclef.util.MiningRequirement;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the raw items of phase 1 in the world (TakeAlongRules.YIELD names them): the blocks each comes out of, the items that count
// for it, and the pick the mining task is told. the same blocks and picks as TaskCatalogue's ores, so the take-along mines
// what the catalogue would. built on first use, the registries are up by then
public final class TakeAlongKinds {
    // column = gravel's rule, no block of a loose column of more than two straight over the head; surface = logs only while
    // the player stands under the open sky, a tree and not a mineshaft's beam
    public record Kind(String raw, Block[] blocks, Item[] drops, MiningRequirement requirement, boolean column, boolean surface) {
    }

    private static List<Kind> _all;
    private static Map<String, Kind> _byRaw;
    private static Map<Block, Kind> _byBlock;
    private static Map<Item, Kind> _byItem;

    private TakeAlongKinds() {
    }

    private static synchronized void build() {
        if (_all != null) {
            return;
        }
        List<Kind> all = new ArrayList<>();
        all.add(ore("coal", Items.COAL, MiningRequirement.WOOD, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        all.add(ore("raw_iron", Items.RAW_IRON, MiningRequirement.STONE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
        all.add(ore("raw_copper", Items.RAW_COPPER, MiningRequirement.STONE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        all.add(ore("raw_gold", Items.RAW_GOLD, MiningRequirement.IRON, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE));
        all.add(ore("diamond", Items.DIAMOND, MiningRequirement.IRON, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
        all.add(ore("redstone", Items.REDSTONE, MiningRequirement.IRON, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
        // plain gravel only: suspicious gravel wants a brush and gives loot, not flint
        all.add(new Kind("flint", new Block[]{Blocks.GRAVEL}, new Item[]{Items.FLINT}, MiningRequirement.HAND, true, false));
        // the overworld trees' logs. the nether stems are a nether trip, the wood and stripped blocks nobody grows
        Item[] logs = {Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
                Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.PALE_OAK_LOG, Items.POPLAR_LOG};
        List<Block> logBlocks = new ArrayList<>();
        for (Item log : logs) {
            Block block = Block.byItem(log);
            if (block != Blocks.AIR) {
                logBlocks.add(block);
            }
        }
        all.add(new Kind(TakeAlongRules.LOG, logBlocks.toArray(Block[]::new), logs, MiningRequirement.HAND, false, true));
        Map<String, Kind> byRaw = new HashMap<>();
        Map<Block, Kind> byBlock = new HashMap<>();
        Map<Item, Kind> byItem = new HashMap<>();
        for (Kind kind : all) {
            byRaw.put(kind.raw(), kind);
            for (Block block : kind.blocks()) {
                byBlock.put(block, kind);
            }
            for (Item item : kind.drops()) {
                byItem.put(item, kind);
            }
        }
        _byRaw = byRaw;
        _byBlock = byBlock;
        _byItem = byItem;
        _all = List.copyOf(all);
    }

    private static Kind ore(String raw, Item drop, MiningRequirement requirement, Block... blocks) {
        return new Kind(raw, blocks, new Item[]{drop}, requirement, false, false);
    }

    public static List<Kind> all() {
        build();
        return _all;
    }

    // null for a raw item phase 1 does not take along
    public static Kind of(String raw) {
        build();
        return _byRaw.get(raw);
    }

    public static Kind ofBlock(Block block) {
        build();
        return _byBlock.get(block);
    }

    // the kind an item counts for (raw iron, an oak log), null for anything else
    public static Kind ofItem(Item item) {
        build();
        return _byItem.get(item);
    }

    private static final Map<Item, Kind> _drops = new java.util.concurrent.ConcurrentHashMap<>();

    // the kind a drop on the floor counts for: a raw item's own (every log is one), else one of its own with no blocks (an iron
    // ingot, a helmet), the same object each time so a detour's targets compare
    public static Kind ofDrop(String raw, Item item) {
        Kind own = of(raw);
        if (own != null) {
            return own;
        }
        return _drops.computeIfAbsent(item, i -> new Kind(raw, new Block[0], new Item[]{i}, MiningRequirement.HAND, false, false));
    }
}
