package adris.altoclef.util.helpers;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ItemHelperStackSignTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void anExactFillOfAStackStillStacks() {
        assertTrue(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.COBBLESTONE, 32)));
        assertTrue(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 1), new ItemStack(Items.COBBLESTONE, 63)));
    }

    @Test
    public void goingOverTheMaxDoesNotStack() {
        assertFalse(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 33), new ItemStack(Items.COBBLESTONE, 32)));
        assertFalse(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 1), new ItemStack(Items.COBBLESTONE, 64)));
    }

    @Test
    public void differentItemsAndEmptyCursorsKeepTheirOldAnswers() {
        assertFalse(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 1), new ItemStack(Items.DIRT, 1)));
        assertTrue(ItemHelper.canStackTogether(new ItemStack(Items.COBBLESTONE, 64), ItemStack.EMPTY));
    }

    // every sign block the game has should be in the all-arrays, crimson and warped included
    private static void everySignOfKindIsListed(Block[] listed, Class<?>... kinds) {
        List<Block> have = Arrays.asList(listed);
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            for (Class<?> kind : kinds) {
                if (kind.isInstance(block)) {
                    assertTrue(block + " missing", have.contains(block));
                }
            }
        }
    }

    @Test
    public void signsAllListsEveryStandingAndWallSign() {
        everySignOfKindIsListed(ItemHelper.WOOD_SIGNS_ALL, StandingSignBlock.class, WallSignBlock.class);
        assertTrue(Arrays.asList(ItemHelper.WOOD_SIGNS_ALL).containsAll(
                List.of(Blocks.CRIMSON_SIGN, Blocks.CRIMSON_WALL_SIGN, Blocks.WARPED_SIGN, Blocks.WARPED_WALL_SIGN)));
    }

    @Test
    public void hangingSignsAllListsEveryCeilingAndWallHangingSign() {
        everySignOfKindIsListed(ItemHelper.WOOD_HANGING_SIGNS_ALL, CeilingHangingSignBlock.class, WallHangingSignBlock.class);
        assertEquals(ItemHelper.WOOD_HANGING_SIGN.length * 2, ItemHelper.WOOD_HANGING_SIGNS_ALL.length);
    }
}
