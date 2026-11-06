package adris.altoclef.trackers;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

// getNearest used to score and validate everything and keep the min. now it hands out candidates nearest first and
// stops early. the old loop lives in here as the reference so "same answer" is a thing we check and not a thing we hope
public class PosCacheNearestTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // chebyshev-ish on purpose: lots of exact ties, which is where the order could go wrong. the real heuristic needs a game
    private static final BlockTracker.PosCache.Scorer SCORER = (fx, fy, fz, tx, ty, tz) -> Math.max(Math.abs(tx - fx), Math.max(Math.abs(ty - fy), Math.abs(tz - fz)));

    // the old loop minus the purge bit, which never touched the answer
    private static Optional<BlockPos> reference(List<BlockPos> list, Vec3 from, Predicate<BlockPos> valid, Predicate<BlockPos> blockIsValid) {
        BlockPos closest = null;
        double min = Double.POSITIVE_INFINITY;
        for (BlockPos pos : list) {
            if (!blockIsValid.test(pos)) continue;
            if (!valid.test(pos)) continue;
            double score = SCORER.score(from.x, from.y, from.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (score < min) {
                min = score;
                closest = pos;
            }
        }
        return Optional.ofNullable(closest);
    }

    @Test
    public void picksTheNearestThatPassesThePredicate() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(1, 0, 0));
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(5, 0, 0));
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(9, 0, 0));
        BlockPos nope = new BlockPos(1, 0, 0);
        Optional<BlockPos> got = cache.getNearest(new Vec3(0, 0, 0), p -> !p.equals(nope), p -> true, SCORER, Blocks.COAL_ORE);
        assertEquals(Optional.of(new BlockPos(5, 0, 0)), got);
    }

    @Test
    public void emptyAndUntrackedAnswerNothing() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        assertTrue(cache.getNearest(Vec3.ZERO, p -> true, p -> true, SCORER, Blocks.COAL_ORE).isEmpty());
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(1, 0, 0));
        assertTrue(cache.getNearest(Vec3.ZERO, p -> false, p -> true, SCORER, Blocks.COAL_ORE).isEmpty());
        assertTrue(cache.getNearest(Vec3.ZERO, p -> true, p -> true, SCORER, Blocks.IRON_ORE).isEmpty());
    }

    @Test
    public void invalidEntriesAreRemovedAsTheyAreMetAndTheRestStay() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        BlockPos stale = new BlockPos(1, 0, 0);
        BlockPos good = new BlockPos(2, 0, 0);
        BlockPos far = new BlockPos(50, 0, 0);
        BlockPos farStale = new BlockPos(60, 0, 0);
        for (BlockPos p : new BlockPos[]{far, farStale, good, stale}) cache.addBlock(Blocks.COAL_ORE, p);
        Set<BlockPos> asked = new HashSet<>();
        Optional<BlockPos> got = cache.getNearest(Vec3.ZERO, p -> true, p -> {
            asked.add(p);
            return !p.equals(stale) && !p.equals(farStale);
        }, SCORER, Blocks.COAL_ORE);
        assertEquals(Optional.of(good), got);
        // never looked past the answer
        assertEquals(Set.of(stale, good), asked);
        List<BlockPos> left = cache.getKnownLocations(Blocks.COAL_ORE);
        assertFalse(left.contains(stale));
        assertTrue(left.containsAll(List.of(good, far, farStale)));
    }

    @Test
    public void sameAnswerAsTheOldLoopOnRandomCaches() {
        Random rng = new Random(1337);
        Block[] types = {Blocks.COAL_ORE, Blocks.IRON_ORE};
        for (int round = 0; round < 500; round++) {
            BlockTracker.PosCache cache = new BlockTracker.PosCache();
            int count = rng.nextInt(60);
            for (int i = 0; i < count; i++) {
                // small ranges on purpose so there are equal scores and the tie break gets used
                BlockPos pos = new BlockPos(rng.nextInt(21) - 10, rng.nextInt(7) - 3, rng.nextInt(21) - 10);
                cache.addBlock(types[rng.nextInt(2)], pos);
            }
            Vec3 from = new Vec3(rng.nextInt(21) - 10 + rng.nextDouble(), rng.nextInt(7) - 3 + rng.nextDouble(), rng.nextInt(21) - 10 + rng.nextDouble());
            int validMod = 1 + rng.nextInt(4);
            int predMod = 1 + rng.nextInt(4);
            Predicate<BlockPos> blockIsValid = p -> Math.floorMod(p.getX() + p.getZ(), validMod) != 0;
            Predicate<BlockPos> valid = p -> Math.floorMod(p.getY() + p.getX(), predMod) != 0;
            List<BlockPos> list = cache.getKnownLocations(types);
            Optional<BlockPos> want = reference(list, from, valid, blockIsValid);
            Optional<BlockPos> got = cache.getNearest(from, valid, blockIsValid, SCORER, types);
            assertEquals("round " + round, want, got);
        }
    }

    @Test
    public void aBannedBlockIsSkippedAndComesBackWhenTheBanEnds() {
        Bans bans = new Bans(line -> {
        });
        bans.tick(0);
        BlockTracker.PosCache cache = new BlockTracker.PosCache(bans, Dimension.OVERWORLD);
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(1, 0, 0));
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(5, 0, 0));
        BanPolicy.miningStalled(bans, Dimension.OVERWORLD, 1, 0, 0);
        assertEquals(Optional.of(new BlockPos(5, 0, 0)), cache.getNearest(new Vec3(0, 0, 0), p -> true, p -> true, SCORER, Blocks.COAL_ORE));
        assertTrue(cache.blockUnreachable(new BlockPos(1, 0, 0)));
        bans.tick(BanPolicy.TASK_GAVE_UP);
        // still in the cache, nothing had to rescan it
        assertEquals(Optional.of(new BlockPos(1, 0, 0)), cache.getNearest(new Vec3(0, 0, 0), p -> true, p -> true, SCORER, Blocks.COAL_ORE));
    }

    @Test
    public void aBanInAnotherDimensionIsNotThisCachesBusiness() {
        Bans bans = new Bans(line -> {
        });
        BlockTracker.PosCache nether = new BlockTracker.PosCache(bans, Dimension.NETHER);
        nether.addBlock(Blocks.COAL_ORE, new BlockPos(1, 0, 0));
        BanPolicy.miningStalled(bans, Dimension.OVERWORLD, 1, 0, 0);
        assertEquals(Optional.of(new BlockPos(1, 0, 0)), nether.getNearest(new Vec3(0, 0, 0), p -> true, p -> true, SCORER, Blocks.COAL_ORE));
    }

    @Test
    public void aCoalDetourBanIsNotTheTrackersBusiness() {
        // the fuel task asks the tracker, and the detour giving up on an ore says nothing about it
        Bans bans = new Bans(line -> {
        });
        BlockTracker.PosCache cache = new BlockTracker.PosCache(bans, Dimension.OVERWORLD);
        cache.addBlock(Blocks.COAL_ORE, new BlockPos(1, 0, 0));
        BanPolicy.coal(bans, 1, 0, 0);
        assertEquals(Optional.of(new BlockPos(1, 0, 0)), cache.getNearest(new Vec3(0, 0, 0), p -> true, p -> true, SCORER, Blocks.COAL_ORE));
        assertFalse(cache.blockUnreachable(new BlockPos(1, 0, 0)));
    }

    @Test
    public void aTrimmedPositionCanBeLearnedAgain() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        for (int x = 0; x < 30; x++) cache.addBlock(Blocks.COAL_ORE, new BlockPos(x, 0, 0));
        cache.smartPurge(null, Vec3.ZERO);
        BlockPos dropped = new BlockPos(29, 0, 0);
        assertFalse(cache.getKnownLocations(Blocks.COAL_ORE).contains(dropped));
        cache.addBlock(Blocks.COAL_ORE, dropped);
        assertTrue(cache.getKnownLocations(Blocks.COAL_ORE).contains(dropped));
    }

    @Test
    public void aPositionThatChangedBlockLeavesTheOldList() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        BlockPos pos = new BlockPos(1, 0, 0);
        cache.addBlock(Blocks.COAL_ORE, pos);
        cache.addBlock(Blocks.IRON_ORE, pos);
        assertFalse(cache.anyFound(Blocks.COAL_ORE));
        assertEquals(List.of(pos), cache.getKnownLocations(Blocks.IRON_ORE));
    }

    @Test
    public void entityLookupsSkipBannedIds() {
        Bans bans = new Bans(line -> {
        });
        record Mob(int id, double dist) {
        }
        List<Mob> mobs = List.of(new Mob(1, 2), new Mob(2, 5), new Mob(3, 9));
        assertEquals(1, EntityTracker.closest(bans, mobs, Mob::id, m -> true, Mob::dist).id());
        BanPolicy.noPath(bans, 1, "cow");
        assertEquals(2, EntityTracker.closest(bans, mobs, Mob::id, m -> true, Mob::dist).id());
        assertEquals(3, EntityTracker.closest(bans, mobs, Mob::id, m -> m.id() != 2, Mob::dist).id());
        BanPolicy.noPath(bans, 2, "cow");
        BanPolicy.noPath(bans, 3, "cow");
        assertNull(EntityTracker.closest(bans, mobs, Mob::id, m -> true, Mob::dist));
    }
}
