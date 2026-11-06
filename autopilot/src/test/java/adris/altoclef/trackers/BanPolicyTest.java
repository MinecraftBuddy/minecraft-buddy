package adris.altoclef.trackers;

import adris.altoclef.trackers.Bans.Key;
import baritone.api.utils.Dimension;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// each old book's rule, as it lives in the registry now
public class BanPolicyTest {
    private final List<String> lines = new ArrayList<>();
    private final Bans bans = new Bans(lines::add);

    @Test
    public void theBlockRuleIsTheOldBlacklistWithAWayOut() {
        bans.tick(0);
        // DestroyBlockTask's default: 4 allowed, the fifth bans
        for (int i = 0; i < 4; i++) {
            assertFalse(BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 1, 2, 3, 4, 50, "couldn't reach it"));
        }
        assertTrue(BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 1, 2, 3, 4, 50, "couldn't reach it"));
        bans.tick(BanPolicy.BLOCK_STRIKES - 1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
        bans.tick(BanPolicy.BLOCK_STRIKES);
        assertFalse("not forever any more", bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
    }

    @Test
    public void theBlockRuleEndsOnABetterPickOrAReload() {
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 1, 2, 3, 0, 50, "r");
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 100, 2, 3, 0, 50, "r");
        bans.toolTier(2);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 1, 2, 3, 0, 50, "r");
        bans.chunkUnloaded(0, 0);
        bans.chunkLoaded(Dimension.OVERWORLD, 0, 0);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 1, 2, 3));
    }

    @Test
    public void aTreeWeStalledOnOutlastsTheMinuteASingleLogGets() {
        bans.tick(0);
        List<Key> logs = List.of(Key.block(Dimension.OVERWORLD, 1, 70, 3), Key.block(Dimension.OVERWORLD, 2, 71, 3));
        BanPolicy.miningStalled(bans, Dimension.OVERWORLD, 1, 70, 3);
        assertEquals(2, BanPolicy.treeGaveUp(bans, logs, 1, 70, 3));
        bans.tick(BanPolicy.TASK_GAVE_UP);
        assertTrue("the stalled log's own minute is over but the tree is still out",bans.blockBanned(Dimension.OVERWORLD, 1, 70, 3));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 2, 71, 3));
        bans.tick(BanPolicy.TREE_GAVE_UP);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 1, 70, 3));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 2, 71, 3));
        assertTrue(BanPolicy.TREE_GAVE_UP > BanPolicy.TASK_GAVE_UP);
    }

    @Test
    public void aGivenUpPoolIsOutForFiveMinutesInOneLine() {
        bans.tick(0);
        List<Key> sources = List.of(Key.block(Dimension.OVERWORLD, 5, 20, 5), Key.block(Dimension.OVERWORLD, 6, 20, 5));
        assertEquals(2, BanPolicy.bucketPoolGaveUp(bans, sources, 5, 20, 5));
        assertEquals("one line for the whole pool", 1, lines.size());
        bans.tick(BanPolicy.BUCKET_POOL_GAVE_UP - 1);
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 5, 20, 5));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 6, 20, 5));
        bans.tick(BanPolicy.BUCKET_POOL_GAVE_UP);
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 5, 20, 5));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 6, 20, 5));
    }

    @Test
    public void theEntityRuleTakesFourMissesAndAHitEndsIt() {
        for (int i = 0; i < 3; i++) {
            assertFalse(BanPolicy.entityStrike(bans, 9, 3, 25, "couldn't reach cow"));
        }
        assertTrue(BanPolicy.entityStrike(bans, 9, 3, 25, "couldn't reach cow"));
        bans.hitBy(9);
        assertFalse(bans.entityBanned(9));
    }

    @Test
    public void aDropWeGaveUpOnStaysGoneForItsLifetime() {
        bans.tick(0);
        BanPolicy.dropGaveUp(bans, 4, "raw_iron");
        bans.hitBy(4);
        bans.toolTier(4);
        assertTrue(bans.entityBanned(4));
        bans.tick(BanPolicy.DROP_GAVE_UP);
        assertFalse(bans.entityBanned(4));
        assertTrue(lines.get(0), lines.get(0).contains("drop never came: raw_iron, 5 min"));
    }

    @Test
    public void noPathEndsWithTheClockOrAHit() {
        bans.tick(0);
        BanPolicy.noPath(bans, 11, "cod");
        bans.tick(BanPolicy.NO_PATH - 1);
        assertTrue(bans.entityBanned(11));
        bans.tick(BanPolicy.NO_PATH);
        assertFalse(bans.entityBanned(11));
        BanPolicy.noPath(bans, 12, "zombie");
        bans.hitBy(12);
        assertFalse(bans.entityBanned(12));
    }

    @Test
    public void coalIsADetourOnlyOverworldBanWithAClock() {
        bans.tick(0);
        assertTrue(BanPolicy.coal(bans, 3, 40, 3));
        assertFalse("the same ore twice is one ban", BanPolicy.coal(bans, 3, 40, 3));
        assertTrue(BanPolicy.coalBanned(bans, 3, 40, 3));
        assertFalse("the trackers, and the fuel task through them, still see it", bans.blockBanned(Dimension.OVERWORLD, 3, 40, 3));
        assertTrue(lines.get(0), lines.get(0).endsWith("only for " + BanPolicy.COAL_SCOPE));
        bans.tick(BanPolicy.COAL);
        assertFalse(BanPolicy.coalBanned(bans, 3, 40, 3));
    }

    @Test
    public void diamondIsItsOwnScopeWithTheSameClock() {
        bans.tick(0);
        assertTrue(BanPolicy.diamond(bans, 3, -50, 3));
        assertFalse(BanPolicy.diamond(bans, 3, -50, 3));
        assertTrue(BanPolicy.diamondBanned(bans, 3, -50, 3));
        assertFalse("a diamond we gave up on says nothing about coal", BanPolicy.coalBanned(bans, 3, -50, 3));
        assertFalse(BanPolicy.gravelBanned(bans, Dimension.OVERWORLD, 3, -50, 3));
        assertFalse("the kit's own diamond task still sees it", bans.blockBanned(Dimension.OVERWORLD, 3, -50, 3));
        assertTrue(lines.get(0), lines.get(0).endsWith("only for " + BanPolicy.DIAMOND_SCOPE));
        assertEquals(6000, BanPolicy.DIAMOND);
        bans.tick(BanPolicy.DIAMOND);
        assertFalse(BanPolicy.diamondBanned(bans, 3, -50, 3));
    }

    @Test
    public void theDetourAlsoHonoursEverybodysBans() {
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 3, 40, 3, 0, 4, "couldn't reach it");
        assertTrue(BanPolicy.coalBanned(bans, 3, 40, 3));
    }

    @Test
    public void theTaskBooksAreShortBans() {
        bans.tick(0);
        BanPolicy.miningStalled(bans, Dimension.NETHER, 0, 30, 0);
        BanPolicy.pickupStalled(bans, 2, "cobblestone");
        BanPolicy.bucketLidStuck(bans, Dimension.OVERWORLD, 0, 10, 0);
        BanPolicy.noRoom(bans, 3, "dirt");
        bans.tick(BanPolicy.TASK_GAVE_UP - 1);
        assertEquals(4, bans.count());
        bans.tick(Math.max(BanPolicy.TASK_GAVE_UP, BanPolicy.NO_ROOM));
        assertEquals(0, bans.count());
    }

    @Test
    public void anOutpostLiftTakesOnlyItsOwnBansInItsReachThatNoOtherOutpostCovers() {
        BanPolicy.outpost(bans, List.of(Key.block(Dimension.OVERWORLD, 10, 70, 0), Key.block(Dimension.OVERWORLD, 30, 70, 0),
                Key.block(Dimension.OVERWORLD, 100, 70, 0)), 0, 0);
        // unreachable for its own reasons, the lift must leave it alone
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 10, 70, 0, 0, 4, "couldn't reach it");
        BanPolicy.trap(bans, Dimension.OVERWORLD, 12, 70, 0, "outpost table");
        int lifted = BanPolicy.liftOutpost(bans, 0, 0, 40, key -> key.x() == 30);
        assertEquals(1, lifted);
        assertTrue("other reason", bans.blockBanned(Dimension.OVERWORLD, 10, 70, 0));
        assertTrue("still covered", bans.blockBanned(Dimension.OVERWORLD, 30, 70, 0));
        assertTrue("out of reach", bans.blockBanned(Dimension.OVERWORLD, 100, 70, 0));
        assertTrue("tables stay", bans.blockBanned(Dimension.OVERWORLD, 12, 70, 0));
        bans.lift("couldn't reach it (1 try)", key -> true);
        assertFalse(bans.banned(Key.block(Dimension.OVERWORLD, 10, 70, 0)));
    }
}
