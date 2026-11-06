package adris.altoclef.tasks.resources;

import adris.altoclef.trackers.Bans;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeepDarkRulesTest {
    @Test
    public void onlyWoolInTheDeepDark() {
        assertTrue(DeepDarkRules.bansWool(true, true));
        assertFalse("wool in a plains village is fine", DeepDarkRules.bansWool(false, true));
        assertFalse("deep dark stone is not our business", DeepDarkRules.bansWool(true, false));
        assertFalse(DeepDarkRules.bansWool(false, false));
    }

    // the city sits at x < 0, everything at x >= 0 is plain overworld
    private static final Predicate<BlockPos> CITY = pos -> pos.getX() < 0;
    private static final Predicate<BlockPos> ALL_LOADED = pos -> true;

    @Test
    public void onePassBansTheCityWoolWithOneLine() {
        List<String> lines = new ArrayList<>();
        Bans bans = new Bans(lines::add);
        List<BlockPos> tracked = List.of(new BlockPos(-10, -40, 0), new BlockPos(-20, -40, 10), new BlockPos(30, 70, 0));
        assertEquals(2, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, tracked, ALL_LOADED, pos -> true, CITY));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, -10, -40, 0));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, -20, -40, 10));
        assertFalse("village wool up top stays fair", bans.blockBanned(Dimension.OVERWORLD, 30, 70, 0));
        assertEquals(lines.toString(), 1, lines.size());
        assertEquals("ban: + 2 wool down low or in the deep dark near -15 5, for the run", lines.get(0));
        // the next pass finds them banned already and keeps quiet
        assertEquals(0, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, tracked, ALL_LOADED, pos -> true, CITY));
        assertEquals(1, lines.size());
    }

    @Test
    public void unloadedChunksWaitForTheNextPass() {
        Bans bans = new Bans(line -> {
        });
        BlockPos far = new BlockPos(-300, 40, 0);
        List<BlockPos> tracked = List.of(new BlockPos(-10, 40, 0), far);
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, tracked, pos -> !pos.equals(far), pos -> true, CITY));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, -300, 40, 0));
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, tracked, ALL_LOADED, pos -> true, CITY));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, -300, 40, 0));
    }

    private static final Predicate<BlockPos> NOT_LOADED = pos -> false;
    private static final Predicate<BlockPos> NEVER_DARK = pos -> false;

    @Test
    public void lowWoolGoesEvenIfTheChunkNeverLoadedOrTheBiomeLies() {
        Bans bans = new Bans(line -> {
        });
        // what an unloaded chunk reads as, air and plains
        Predicate<BlockPos> notWool = pos -> false;
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(50, -46, 0)), NOT_LOADED, notWool, NEVER_DARK));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 50, -46, 0));
        // loaded, and the biome test missed it
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(60, -46, 0)), ALL_LOADED, pos -> true, NEVER_DARK));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 60, -46, 0));
    }

    @Test
    public void theLineIsAtThirty() {
        Bans bans = new Bans(line -> {
        });
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(1, 29, 1)), ALL_LOADED, pos -> true, NEVER_DARK));
        assertEquals(0, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(1, 30, 1)), ALL_LOADED, pos -> true, NEVER_DARK));
        assertEquals(0, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(1, 30, 2)), NOT_LOADED, pos -> true, NEVER_DARK));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 1, 30, 1));
    }

    @Test
    public void staleTrackerEntriesThatAreNotWoolAnyMoreAreLeftAlone() {
        Bans bans = new Bans(line -> {
        });
        BlockPos mined = new BlockPos(-10, -40, 0);
        Set<BlockPos> stillWool = Set.of(new BlockPos(-11, -40, 0));
        List<BlockPos> tracked = List.of(mined, new BlockPos(-11, -40, 0));
        assertEquals(1, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, tracked, ALL_LOADED, stillWool::contains, CITY));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, -10, -40, 0));
    }

    @Test
    public void nothingInTheDeepDarkSaysNothing() {
        List<String> lines = new ArrayList<>();
        Bans bans = new Bans(lines::add);
        assertEquals(0, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(5, 70, 5)), ALL_LOADED, pos -> true, CITY));
        assertEquals(0, DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(), ALL_LOADED, pos -> true, CITY));
        assertTrue(lines.isEmpty());
    }

    @Test
    public void theBanIsForTheWholeRunAndOnlyAClearRunEndsIt() {
        Bans bans = new Bans(line -> {
        });
        bans.tick(0);
        DeepDarkRules.banPass(bans, Dimension.OVERWORLD, List.of(new BlockPos(-10, -40, 0)), ALL_LOADED, pos -> true, CITY);
        bans.tick(10_000_000L);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, -10, -40, 0));
        bans.clearRun("world left");
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, -10, -40, 0));
    }
}
