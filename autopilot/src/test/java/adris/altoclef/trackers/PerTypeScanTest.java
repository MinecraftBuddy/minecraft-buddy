package adris.altoclef.trackers;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// the tracker's scan used to give every tracked block type one shared budget. the fake world below hands out
// results like MineProcess.searchWorld does (nearest first, cut to max across the whole filter), which is all the
// real scan's budget behaviour comes down to
public class PerTypeScanTest {

    private static final int MAX = 25;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private final Map<BlockPos, Block> world = new HashMap<>();
    private int scans = 0;

    private void put(Block block, int count, int baseX) {
        for (int i = 0; i < count; i++) {
            world.put(new BlockPos(baseX + i, 0, 0), block);
        }
    }

    private List<BlockPos> fakeSearch(Block[] types, int max) {
        scans++;
        List<Block> wanted = List.of(types);
        List<BlockPos> hits = new ArrayList<>();
        for (Map.Entry<BlockPos, Block> e : world.entrySet()) {
            if (wanted.contains(e.getValue())) hits.add(e.getKey());
        }
        hits.sort(Comparator.comparingInt(BlockPos::getX));
        return hits.size() > max ? hits.subList(0, max) : hits;
    }

    private Map<Block, List<BlockPos>> run(Block... types) {
        return PerTypeScan.run(types, MAX, (asked, max) -> PerTypeScan.Scanned.of(fakeSearch(asked, max)), world::get);
    }

    @Test
    public void oneSharedBudgetHidesTheFarOre() {
        // what the tracker used to do: one call, max for everything
        put(Blocks.COAL_ORE, 100, 0);
        put(Blocks.DEEPSLATE_DIAMOND_ORE, 3, 500);
        List<BlockPos> old = fakeSearch(new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, MAX);
        assertTrue(old.stream().noneMatch(p -> world.get(p) == Blocks.DEEPSLATE_DIAMOND_ORE));
    }

    @Test
    public void crowdedOutTypeStillGetsFound() {
        put(Blocks.COAL_ORE, 100, 0);
        put(Blocks.IRON_ORE, 100, 200);
        put(Blocks.DEEPSLATE_DIAMOND_ORE, 3, 500);
        Map<Block, List<BlockPos>> found = run(Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        assertEquals(MAX, found.get(Blocks.COAL_ORE).size());
        assertEquals(MAX, found.get(Blocks.IRON_ORE).size());
        assertEquals(3, found.get(Blocks.DEEPSLATE_DIAMOND_ORE).size());
    }

    @Test
    public void bothVariantsOfAnOreGetTheirOwnShare() {
        put(Blocks.DIAMOND_ORE, 60, 0);
        put(Blocks.DEEPSLATE_DIAMOND_ORE, 60, 100);
        Map<Block, List<BlockPos>> found = run(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        // the old single scan with max 25 would have been all stone diamonds
        assertEquals(MAX, found.get(Blocks.DIAMOND_ORE).size());
        assertEquals(MAX, found.get(Blocks.DEEPSLATE_DIAMOND_ORE).size());
    }

    @Test
    public void everyResultIsNearestForItsType() {
        put(Blocks.COAL_ORE, 100, 0);
        put(Blocks.IRON_ORE, 100, 1000);
        Map<Block, List<BlockPos>> found = run(Blocks.COAL_ORE, Blocks.IRON_ORE);
        for (int i = 0; i < MAX; i++) {
            assertEquals(1000 + i, found.get(Blocks.IRON_ORE).get(i).getX());
            assertEquals(i, found.get(Blocks.COAL_ORE).get(i).getX());
        }
    }

    @Test
    public void absentTypeIsEmptyAndDoesNotLoop() {
        put(Blocks.COAL_ORE, 100, 0);
        Map<Block, List<BlockPos>> found = run(Blocks.COAL_ORE, Blocks.EMERALD_ORE);
        assertEquals(MAX, found.get(Blocks.COAL_ORE).size());
        assertTrue(found.get(Blocks.EMERALD_ORE).isEmpty());
        // coal fills up in round one, emerald alone gets round two and comes up under budget
        assertEquals(2, scans);
    }

    @Test
    public void sparseWorldIsOneScan() {
        put(Blocks.COAL_ORE, 5, 0);
        put(Blocks.IRON_ORE, 5, 100);
        run(Blocks.COAL_ORE, Blocks.IRON_ORE);
        assertEquals(1, scans);
    }

    @Test
    public void singleTypeIsTheOldBehaviour() {
        put(Blocks.COAL_ORE, 100, 0);
        Map<Block, List<BlockPos>> found = run(Blocks.COAL_ORE);
        assertEquals(MAX, found.get(Blocks.COAL_ORE).size());
        assertEquals(1, scans);
    }

    @Test
    public void unknownBlocksDoNotSpinForever() {
        // the scan is saturated by something that isn't ours (block changed under it), must still stop
        put(Blocks.COAL_ORE, 100, 0);
        Map<Block, List<BlockPos>> found = PerTypeScan.run(new Block[]{Blocks.IRON_ORE, Blocks.EMERALD_ORE}, MAX,
                (types, max) -> PerTypeScan.Scanned.of(fakeSearch(new Block[]{Blocks.COAL_ORE}, max)), world::get);
        assertTrue(found.get(Blocks.IRON_ORE).isEmpty());
        assertEquals(1, scans);
    }

    @Test
    public void prunedHitsAreNotTheWorldRunningOut() {
        // a scan whose budget got eaten by hits that prune threw away comes back short, but it saw a full budget of
        // them. that used to read as "swept everything": iron went home empty and got marked as covered
        put(Blocks.COAL_ORE, 100, 0);
        put(Blocks.IRON_ORE, 3, 500);
        Map<Block, List<BlockPos>> found = PerTypeScan.run(new Block[]{Blocks.COAL_ORE, Blocks.IRON_ORE}, MAX, (types, max) -> {
            List<BlockPos> hits = fakeSearch(types, max);
            // half the coal gets pruned: survivors are short of max, raw still saw a full budget
            int kept = hits.size() > MAX ? MAX : hits.size();
            return new PerTypeScan.Scanned(new ArrayList<>(hits.subList(0, kept)), hits.size());
        }, world::get);
        assertEquals(MAX, found.get(Blocks.COAL_ORE).size());
        assertEquals(3, found.get(Blocks.IRON_ORE).size());
        assertEquals(2, scans);
    }

    @Test
    public void emptyTypesScanNothing() {
        assertTrue(run().isEmpty());
        assertEquals(0, scans);
    }

    @Test
    public void multiBlockFilterMatchesBothVariants() {
        // the filter the scanner is handed: has() has to say yes to every block in it, not just the first
        for (Block[] pair : new Block[][]{
                {Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE},
                {Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE},
                {Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE},
                {Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE},
                {Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE},
                {Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE},
                {Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE},
                {Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE}}) {
            BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(pair);
            assertTrue(pair[0].toString(), filter.has(pair[0].defaultBlockState()));
            assertTrue(pair[1].toString(), filter.has(pair[1].defaultBlockState()));
            assertFalse(filter.has(Blocks.STONE.defaultBlockState()));
        }
    }
}
