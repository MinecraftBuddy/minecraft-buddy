/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.trip;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// which items a trip gather goes for and which blocks drop them. the items come from acceptableThrowawayItems as the
// user has it: collecting tuff because altoclef likes it is no use once the task is over and plain baritone won't place it
public final class GatherSources {

    // what a stone pickaxe is made of. any of these will do
    public static final List<Block> STONE_TOOL_BLOCKS = List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE);

    private static List<Block> logs;

    private GatherSources() {
    }

    // sand and gravel fall on whoever is bridging with them, and anything that breaks into something else is no use
    public static List<Item> items(List<Item> throwaway) {
        List<Item> out = new ArrayList<>();
        for (Item item : throwaway) {
            if (item instanceof BlockItem blockItem && item != Items.SAND && item != Items.GRAVEL && dropsItself(blockItem.getBlock()) && !out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    // the loot tables live on the server, so this is a list of the usual suspects (stone wants silk touch, nylium turns
    // into netherrack, snow into snowballs). a throwaway someone added that breaks
    // into something else (glass, deepslate, grass) would have the gather mine it forever and never hold one
    static boolean dropsItself(Block block) {
        if (block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRASS_BLOCK || block == Blocks.MYCELIUM
                || block == Blocks.PODZOL || block == Blocks.DIRT_PATH || block == Blocks.FARMLAND || block == Blocks.ICE
                || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE || block == Blocks.GLOWSTONE || block == Blocks.SEA_LANTERN
                || block == Blocks.BOOKSHELF || block == Blocks.CLAY || block == Blocks.MELON || block == Blocks.SNOW_BLOCK
                || block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM) {
            return false;
        }
        // the classes and the tags both: tags are only bound once a world is loaded
        if (block instanceof TransparentBlock || block instanceof LeavesBlock) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return !state.is(BlockTags.IMPERMEABLE) && !state.is(BlockTags.LEAVES);
    }

    // the item's own block, plus the common blocks that break into it. nobody finds cobblestone lying around, they find stone
    public static List<Block> sources(List<Item> items) {
        Set<Block> out = new LinkedHashSet<>();
        for (Item item : items) {
            if (item instanceof BlockItem blockItem) {
                out.add(blockItem.getBlock());
            }
            if (item == Items.COBBLESTONE) {
                out.add(Blocks.STONE);
            } else if (item == Items.COBBLED_DEEPSLATE) {
                out.add(Blocks.DEEPSLATE);
            } else if (item == Items.DIRT) {
                out.add(Blocks.GRASS_BLOCK);
                out.add(Blocks.PODZOL);
                out.add(Blocks.MYCELIUM);
                out.add(Blocks.DIRT_PATH);
                out.add(Blocks.FARMLAND);
            }
        }
        return new ArrayList<>(out);
    }

    // drops nothing without the right tool. for the throwaway blocks that's always a pickaxe
    public static boolean needsPickaxe(Block block) {
        return block.defaultBlockState().requiresCorrectToolForDrops();
    }

    // the ones that come out by hand. ten dirt is a lot quicker than a tree, a crafting table, a pickaxe and then the stone
    public static List<Block> byHand(List<Block> sources) {
        List<Block> out = new ArrayList<>();
        for (Block b : sources) {
            if (!needsPickaxe(b)) {
                out.add(b);
            }
        }
        return out;
    }

    // logs and stems, for a wooden pickaxe and a crafting table on the way to a stone one
    public static synchronized List<Block> logs() {
        if (logs == null) {
            List<Block> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block.defaultBlockState().is(BlockTags.LOGS)) {
                    found.add(block);
                }
            }
            logs = List.copyOf(found);
        }
        return logs;
    }
}
