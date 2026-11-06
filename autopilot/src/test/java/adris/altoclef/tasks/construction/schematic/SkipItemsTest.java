package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SkipItemsTest {

    // set in boot: a static final would touch Blocks before the bootstrap, and a run that starts with this class
    // takes the whole jvm's registries down with it
    private static BlockState AIR;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        AIR = Blocks.AIR.defaultBlockState();
    }

    private static StaticSchematic row(BlockState... states) {
        BlockState[][][] s = new BlockState[states.length][1][1];
        for (int x = 0; x < states.length; x++) {
            s[x][0][0] = states[x];
        }
        return new StaticSchematic(s);
    }

    @Test
    public void cutsTheCellsWhoseItemIsOnTheList() {
        // the coloured blocks aren't plain fields on Blocks any more
        BlockState concrete = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getValue(net.minecraft.resources.Identifier.parse("minecraft:white_concrete")).defaultBlockState();
        IStaticSchematic s = SkipItems.of(row(Blocks.STONE.defaultBlockState(), concrete, AIR), Set.of("minecraft:white_concrete"));
        assertTrue(s.inSchematic(0, 0, 0, AIR));
        assertFalse(s.inSchematic(1, 0, 0, AIR));
        // air costs nothing, so it's never on anybody's list and stays in (the builder clears it)
        assertTrue(s.inSchematic(2, 0, 0, AIR));
        // the raw file is untouched for whoever reads it directly
        assertEquals(concrete, s.getDirect(1, 0, 0));
        assertEquals(concrete, s.desiredState(1, 0, 0, AIR, List.of()));
        assertEquals(3, s.widthX());
    }

    @Test
    public void aSecondHalfGoesWithItsFirstHalf() {
        // the top of a door costs nothing by itself, it's cut when the bottom is
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState lower = upper.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        SkipItems s = new SkipItems(row(lower, upper), Set.of("minecraft:oak_door"));
        assertTrue(s.skipped(upper));
        assertTrue(s.skipped(lower));
        assertFalse(s.inSchematic(1, 0, 0, AIR));
        assertFalse(new SkipItems(row(upper), Set.of("minecraft:spruce_door")).skipped(upper));
        // same for a bed's foot
        BlockState foot = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getValue(net.minecraft.resources.Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT);
        assertTrue(new SkipItems(row(foot), Set.of("minecraft:red_bed")).skipped(foot));
        assertFalse(new SkipItems(row(foot), Set.of("minecraft:blue_bed")).skipped(foot));
    }

    @Test
    public void aPistonHeadGoesWithItsPiston() {
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState();
        BlockState stickyHead = head.setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE,
            net.minecraft.world.level.block.state.properties.PistonType.STICKY);
        assertTrue(new SkipItems(row(head), Set.of("minecraft:piston")).skipped(head));
        assertFalse(new SkipItems(row(head), Set.of("minecraft:sticky_piston")).skipped(head));
        assertTrue(new SkipItems(row(stickyHead), Set.of("minecraft:sticky_piston")).skipped(stickyHead));
    }

    @Test
    public void aPotIsOnlyCutWhenBothItemsAre() {
        BlockState pot = Blocks.POTTED_POPPY.defaultBlockState();
        assertFalse(new SkipItems(row(pot), Set.of("minecraft:poppy")).skipped(pot));
        assertTrue(new SkipItems(row(pot), Set.of("minecraft:poppy", "minecraft:flower_pot")).skipped(pot));
    }

    @Test
    public void nothingToCutIsThePlainFile() {
        StaticSchematic plain = row(Blocks.STONE.defaultBlockState());
        assertSame(plain, SkipItems.of(plain, Set.of()));
        assertSame(plain, SkipItems.leaveOut(plain, Set.of()));
    }

    @Test
    public void anUncheckedItemCutsEveryCellThatCostsIt() {
        // the other way round from the skipped rule: one unchecked item is enough, the pot can't go up without the poppy
        BlockState pot = Blocks.POTTED_POPPY.defaultBlockState();
        IStaticSchematic s = SkipItems.leaveOut(row(pot, Blocks.STONE.defaultBlockState()), Set.of("minecraft:poppy"));
        assertFalse(s.inSchematic(0, 0, 0, AIR));
        assertTrue(s.inSchematic(1, 0, 0, AIR));
        assertTrue(((SkipItems) SkipItems.leaveOut(row(pot), Set.of("minecraft:flower_pot"))).skipped(pot));
    }

    @Test
    public void anUncheckedDoorTakesItsTopAlong() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic s = SkipItems.leaveOut(row(lower, upper), Set.of("minecraft:oak_door"));
        assertFalse(s.inSchematic(0, 0, 0, AIR));
        assertFalse(s.inSchematic(1, 0, 0, AIR));
    }

    @Test
    public void theCountNeverBillsAnUncheckedCell() {
        // what makes a build with unchecked rows end: the cut cells aren't in the bill, so the count runs dry and
        // CountStep calls it done with those cells still empty
        BlockState pot = Blocks.POTTED_POPPY.defaultBlockState();
        IStaticSchematic s = SkipItems.leaveOut(row(Blocks.STONE.defaultBlockState(), pot), Set.of("minecraft:poppy"));
        SchematicDiff.World world = new SchematicDiff.World() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public BlockState state(int x, int y, int z) {
                // the stone is already there, the pot's cell is air
                return x == 0 ? Blocks.STONE.defaultBlockState() : AIR;
            }
        };
        SchematicDiff diff = new SchematicDiff(s, 0, 0, 0, block -> false);
        assertTrue(diff.step(world, 100));
        assertTrue(diff.result().items().isEmpty());
        assertEquals(1, diff.correct());
        assertEquals(0, diff.wrong());
    }

    @Test
    public void theTasksCountKeepsAStandingUncheckedCellButDoesNotBillAMissingOne() {
        // the task diffs the whole file with leftOut, not the cut one: an unticked stone wall that's already up stays
        // "built" (the gather's pickaxe leaves it alone), the unticked cell that isn't up yet costs nothing
        BlockState stone = Blocks.STONE.defaultBlockState();
        StaticSchematic file = row(stone, stone);
        Set<String> off = Set.of("minecraft:stone");
        SchematicDiff.World world = new SchematicDiff.World() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return x == 0 ? stone : AIR;
            }
        };
        SchematicDiff diff = new SchematicDiff(file, 0, 0, 0, block -> false, state -> SkipItems.leftOut(off, state));
        assertTrue(diff.step(world, 100));
        assertTrue(diff.result().items().isEmpty());
        assertEquals(1, diff.correct());
        assertEquals(0, diff.wrong());
        assertTrue(diff.built().contains(net.minecraft.core.BlockPos.asLong(0, 0, 0)));
        assertFalse(SkipItems.leftOut(Set.of(), stone));
        assertTrue(SkipItems.leftOut(Set.of("minecraft:poppy"), Blocks.POTTED_POPPY.defaultBlockState()));
    }
}
