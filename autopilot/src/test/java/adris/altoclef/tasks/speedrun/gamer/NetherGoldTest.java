package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.NetherGold.Bag;
import adris.altoclef.tasks.speedrun.gamer.NetherGold.Stage;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.BeforeClass;
import org.junit.Test;

// the nether gold fallback: which step the bag is on, when a try may begin, the box a block break angers piglins in, and which
// piece the nuggets are for
public class NetherGoldTest {
    private static final int BOOTS = PiglinGold.BOOTS_INGOTS;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static Bag bag(int nuggets, int ingots, int planks, int logs, boolean table) {
        return new Bag(nuggets, ingots, planks, logs, table);
    }

    // ---- the stages

    @Test
    public void emptyHandedWithATableDigs() {
        assertEquals(Stage.MINE, NetherGold.stage(bag(0, 0, 0, 0, true), BOOTS));
        assertEquals(Stage.MINE, NetherGold.stage(bag(35, 0, 0, 0, true), BOOTS));
    }

    @Test
    public void wholeSetsOfNuggetsAreFoldedBeforeAnyMoreDigging() {
        assertEquals(Stage.INGOTS, NetherGold.stage(bag(36, 0, 0, 0, true), BOOTS));
        assertEquals(Stage.INGOTS, NetherGold.stage(bag(18, 2, 0, 0, true), BOOTS));
    }

    @Test
    public void enoughIngotsMakeThePiece() {
        assertEquals(Stage.PIECE, NetherGold.stage(bag(0, 4, 0, 0, true), BOOTS));
        assertEquals(Stage.PIECE, NetherGold.stage(bag(0, 5, 0, 0, true), PiglinGold.HELMET_INGOTS));
    }

    @Test
    public void noTableMeansPlanksThenATable() {
        assertEquals(Stage.TABLE, NetherGold.stage(bag(0, 4, 4, 0, false), BOOTS));
        assertEquals(Stage.PLANKS, NetherGold.stage(bag(0, 4, 0, 1, false), BOOTS));
        // a partial pile of planks with a log behind it is cut first
        assertEquals(Stage.PLANKS, NetherGold.stage(bag(0, 4, 2, 1, false), BOOTS));
    }

    @Test
    public void noTableAndNoWoodForOneIsAbortEvenBeforeTheDigging() {
        assertEquals(Stage.ABORT, NetherGold.stage(bag(0, 0, 3, 0, false), BOOTS));
        assertEquals(Stage.ABORT, NetherGold.stage(bag(40, 0, 0, 0, false), BOOTS));
    }

    @Test
    public void nuggetsStillToDigCountTheIngotsWeHold() {
        assertEquals(36, NetherGold.nuggetsToMine(bag(0, 0, 0, 0, true), BOOTS));
        assertEquals(27, NetherGold.nuggetsToMine(bag(0, 1, 0, 0, true), BOOTS));
        assertEquals(10, NetherGold.nuggetsToMine(bag(26, 0, 0, 0, true), BOOTS));
        assertEquals(0, NetherGold.nuggetsToMine(bag(40, 0, 0, 0, true), BOOTS));
    }

    // ---- when a try begins

    @Test
    public void startsOnlyWithOreWeKnowWhenItHasToDig() {
        assertTrue(NetherGold.mayStart(bag(0, 0, 0, 0, true), BOOTS, true));
        assertFalse(NetherGold.mayStart(bag(0, 0, 0, 0, true), BOOTS, false));
    }

    @Test
    public void craftsNeedNoOre() {
        assertTrue(NetherGold.mayStart(bag(36, 0, 0, 0, true), BOOTS, false));
    }

    @Test
    public void noTableAndNoWoodSkipsTheTry() {
        assertFalse(NetherGold.mayStart(bag(0, 0, 0, 0, false), BOOTS, true));
        // a log is a table
        assertTrue(NetherGold.mayStart(bag(0, 0, 0, 1, false), BOOTS, true));
        assertTrue(NetherGold.mayStart(bag(0, 0, 4, 0, false), BOOTS, true));
    }

    @Test
    public void theTryEndsOnTheNinetySecondClock() {
        assertFalse(NetherGold.overTime(89.9));
        assertTrue(NetherGold.overTime(90));
        assertTrue(NetherGold.CAP_SECONDS < 120);
    }

    // ---- the piglin box

    @Test
    public void theAngerBoxIsSixteenAroundThePlayerWithNoSightCheck() {
        AABB me = new AABB(0, 64, 0, 0.6, 65.8, 0.6);
        AABB box = NetherGold.angerBox(me);
        // 16 blocks off, on the other side of a wall or not, is inside
        assertTrue(box.intersects(new AABB(16, 64, 0, 16.6, 65.9, 0.6)));
        assertTrue(box.intersects(new AABB(0, 64 + 16, 0, 0.6, 65.9 + 16, 0.6)));
        assertTrue(box.intersects(new AABB(0, 64 - 16, 0, 0.6, 65 - 16, 0.6)));
        assertFalse(box.intersects(new AABB(17, 64, 0, 17.6, 65.9, 0.6)));
        assertFalse(box.intersects(new AABB(0, 64, -17.7, 0.6, 65.9, -17.1)));
    }

    // ---- which piece

    @Test
    public void nothingToGoOnDigsForTheBoots() {
        FakeFacts f = new FakeFacts();
        assertEquals("boots", PiglinGold.netherSlot(f, List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")));
        assertEquals(BOOTS, PiglinGold.ingotsFor("boots"));
        assertEquals(Items.GOLDEN_BOOTS, PiglinGold.piece("boots"));
    }

    @Test
    public void aSlotTheKitAlreadyChoseWins() {
        // 5 ingots and a helmet in the plan: that is the helmet (no armor lost), the same call slot() makes at the gate
        FakeFacts f = new FakeFacts().give(Items.GOLD_INGOT, 5);
        List<String> plan = List.of("iron_helmet", "iron_boots");
        assertEquals("helmet", PiglinGold.netherSlot(f, plan));
        assertEquals(PiglinGold.HELMET_INGOTS, PiglinGold.ingotsFor("helmet"));
        assertEquals(Items.GOLDEN_HELMET, PiglinGold.piece("helmet"));
        // a piece in the bag in its slot is that slot
        FakeFacts held = new FakeFacts().give(Items.GOLDEN_BOOTS, 1);
        assertEquals("boots", PiglinGold.netherSlot(held, plan));
        assertNull(PiglinGold.piece("nope"));
    }

    @Test
    public void oreHasToBeAboutOurHeight() {
        // beside us at our level, and the edges of the reach
        assertTrue(NetherGold.oreInReach(10, 70, 10, 0, 70, 0));
        assertTrue(NetherGold.oreInReach(0, 70 + NetherGold.ORE_DY, 0, 0, 70, 0));
        assertTrue(NetherGold.oreInReach(0, 70 - NetherGold.ORE_DY, 0, 0, 70, 0));
        // up by the roof or down by the lava sea is not a dig, however close sideways
        assertFalse(NetherGold.oreInReach(0, 113, 0, 0, 70, 0));
        assertFalse(NetherGold.oreInReach(0, 70 - NetherGold.ORE_DY - 1, 0, 0, 70, 0));
        // and the old radius still holds sideways
        assertFalse(NetherGold.oreInReach(49, 70, 0, 0, 70, 0));
    }

    @Test
    public void retriesAfterTheWaitWithOreKnown() {
        assertTrue(NetherGold.mayRetry(NetherGold.RETRY_SECONDS, 1, true));
        assertFalse(NetherGold.mayRetry(NetherGold.RETRY_SECONDS, 1, false));
    }

    @Test
    public void noRetryBeforeTheWait() {
        assertFalse(NetherGold.mayRetry(NetherGold.RETRY_SECONDS - 1, 1, true));
    }

    @Test
    public void noRetryAfterThreeTries() {
        assertTrue(NetherGold.mayRetry(100, NetherGold.MAX_TRIES - 1, true));
        assertFalse(NetherGold.mayRetry(100, NetherGold.MAX_TRIES, true));
    }

    @Test
    public void aDeathResetsTheTries() {
        assertTrue(NetherGold.resetOnDeath(2, 3));
        assertFalse(NetherGold.resetOnDeath(3, 3));
    }
}
