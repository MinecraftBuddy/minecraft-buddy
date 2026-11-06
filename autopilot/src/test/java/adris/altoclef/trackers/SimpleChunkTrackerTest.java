package adris.altoclef.trackers;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

// the section skip is the whole point of findInSections, so these poke at the ways it could silently miss things:
// wrong y index, wrong local axis, and a skip that eats the hit
public class SimpleChunkTrackerTest {

    // overworld shaped: 24 sections, the lowest one starts at y -64
    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the section wants a biome container and nothing here cares what's in it
    private static final IdMap<Holder<Biome>> NO_BIOMES = new IdMap<>() {
        @Override
        public int getId(Holder<Biome> value) {
            return -1;
        }

        @Override
        public Holder<Biome> byId(int id) {
            return null;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public Iterator<Holder<Biome>> iterator() {
            return List.<Holder<Biome>>of().iterator();
        }
    };

    private static LevelChunkSection[] emptyColumn() {
        LevelChunkSection[] out = new LevelChunkSection[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(Holder.direct(null), Strategy.createForBiomes(NO_BIOMES));
            out[i] = new LevelChunkSection(states, biomes);
        }
        return out;
    }

    private static final Predicate<BlockState> BRICKS = s -> s.is(Blocks.STONE_BRICKS);

    // chunk at world x 32, z -48 on purpose, so a mixed up local/world coordinate can't pass by living at 0,0
    private static BlockPos find(LevelChunkSection[] sections, Predicate<BlockState> p) {
        return SimpleChunkTracker.findInSections(sections, MIN_Y, 32, -48, p);
    }

    @Test
    public void emptyChunkFindsNothing() {
        assertNull(find(emptyColumn(), BRICKS));
    }

    @Test
    public void findsBlockAtWorldCoords() {
        LevelChunkSection[] sections = emptyColumn();
        // world y 70 -> section index (70 + 64) / 16 = 8, local y 6
        sections[8].setBlockState(3, 6, 11, Blocks.STONE_BRICKS.defaultBlockState());
        assertEquals(new BlockPos(32 + 3, 70, -48 + 11), find(sections, BRICKS));
    }

    @Test
    public void findsBlockInLowestAndHighestSection() {
        LevelChunkSection[] low = emptyColumn();
        low[0].setBlockState(0, 0, 0, Blocks.STONE_BRICKS.defaultBlockState());
        assertEquals(new BlockPos(32, -64, -48), find(low, BRICKS));

        LevelChunkSection[] high = emptyColumn();
        high[SECTIONS - 1].setBlockState(15, 15, 15, Blocks.STONE_BRICKS.defaultBlockState());
        assertEquals(new BlockPos(32 + 15, 319, -48 + 15), find(high, BRICKS));
    }

    @Test
    public void localAxesAreNotSwapped() {
        LevelChunkSection[] sections = emptyColumn();
        sections[4].setBlockState(1, 2, 3, Blocks.STONE_BRICKS.defaultBlockState());
        BlockPos hit = find(sections, BRICKS);
        assertEquals(32 + 1, hit.getX());
        assertEquals(MIN_Y + 4 * 16 + 2, hit.getY());
        assertEquals(-48 + 3, hit.getZ());
    }

    @Test
    public void ignoresOtherBlocksAndPicksTheRightOne() {
        LevelChunkSection[] sections = emptyColumn();
        // a section full of palette entries that are not the target, then the target further up
        sections[2].setBlockState(5, 5, 5, Blocks.STONE.defaultBlockState());
        sections[2].setBlockState(6, 5, 5, Blocks.COBBLESTONE.defaultBlockState());
        sections[9].setBlockState(7, 1, 2, Blocks.STONE_BRICKS.defaultBlockState());
        assertEquals(new BlockPos(32 + 7, MIN_Y + 9 * 16 + 1, -48 + 2), find(sections, BRICKS));
    }

    @Test
    public void staleBlockEntryInPaletteDoesNotFakeAHit() {
        LevelChunkSection[] sections = emptyColumn();
        // palette keeps the brick entry after the block is gone, so maybeHas says yes and the scan has to say no
        sections[3].setBlockState(1, 1, 1, Blocks.STONE_BRICKS.defaultBlockState());
        sections[3].setBlockState(1, 1, 1, Blocks.AIR.defaultBlockState());
        assertNull(find(sections, BRICKS));
    }

    @Test
    public void missingSectionIsSkipped() {
        LevelChunkSection[] sections = emptyColumn();
        sections[0] = null;
        sections[5].setBlockState(0, 0, 0, Blocks.STONE_BRICKS.defaultBlockState());
        assertEquals(new BlockPos(32, MIN_Y + 80, -48), find(sections, BRICKS));
    }

    @Test
    public void predicateMayWantAir() {
        // no hasOnlyAir shortcut, so an all air section still answers "is there air here"
        BlockPos hit = find(emptyColumn(), s -> s.isAir());
        assertEquals(new BlockPos(32, MIN_Y, -48), hit);
    }
}
