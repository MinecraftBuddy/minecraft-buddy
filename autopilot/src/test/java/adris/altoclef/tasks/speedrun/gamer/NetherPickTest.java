package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.NetherPick.Bag;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Tier;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetherPickTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static FakeFacts nether() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.NETHER;
        return f;
    }

    @Test
    public void anEmptyBagMakesNothing() {
        assertEquals(Tier.NONE, NetherPick.choose(nether()));
    }

    @Test
    public void threeIngotsAndNoWoodMakeNothing() {
        assertEquals(Tier.NONE, NetherPick.choose(nether().give(Items.IRON_INGOT, 3)));
    }

    @Test
    public void threeIngotsOneStemAndATableMakeAnIronPick() {
        // the stem is 4 planks, 2 of them the sticks, the table is already in the bag
        FakeFacts f = nether().give(Items.IRON_INGOT, 3).give(Items.CRIMSON_STEM, 1).give(Items.CRAFTING_TABLE, 1);
        assertEquals(Tier.IRON, NetherPick.choose(f));
    }

    @Test
    public void threeIngotsAndAStemButNoTableIsOnePlankShort() {
        // 2 for the sticks + 4 for the table = 6 planks, one stem is 4
        FakeFacts f = nether().give(Items.IRON_INGOT, 3).give(Items.CRIMSON_STEM, 1);
        assertEquals(Tier.NONE, NetherPick.choose(f));
        assertEquals(Tier.IRON, NetherPick.choose(f.give(Items.WARPED_PLANKS, 2)));
    }

    @Test
    public void cobbleAndSticksNeedNoWoodBeyondTheTable() {
        FakeFacts f = nether().give(Items.COBBLESTONE, 3).give(Items.STICK, 2);
        // no table and no planks to make one
        assertEquals(Tier.NONE, NetherPick.choose(f));
        assertEquals(Tier.STONE, NetherPick.choose(f.give(Items.CRAFTING_TABLE, 1)));
    }

    @Test
    public void aTableStandingNextToUsCountsAsOne() {
        FakeFacts f = nether().give(Items.COBBLESTONE, 3).give(Items.STICK, 2);
        f.tablePlaced = true;
        assertEquals(Tier.STONE, NetherPick.choose(f));
    }

    @Test
    public void twoStemsAloneMakeAWoodenPick() {
        // 8 planks: 3 head + 2 sticks + 4 table = 9. one short
        FakeFacts f = nether().give(Items.CRIMSON_STEM, 2);
        assertEquals(Tier.NONE, NetherPick.choose(f));
        // a table in the bag makes it 5 of 8
        assertEquals(Tier.WOODEN, NetherPick.choose(f.give(Items.CRAFTING_TABLE, 1)));
    }

    @Test
    public void aUsablePickMeansNoCraft() {
        FakeFacts f = nether().give(Items.IRON_INGOT, 3).give(Items.STICK, 2).give(Items.CRAFTING_TABLE, 1)
                .give(Items.STONE_PICKAXE, 1);
        assertEquals(Tier.NONE, NetherPick.choose(f));
    }

    @Test
    public void aWornPickDoesNotCount() {
        // MinecraftFacts keeps worn picks out of count(), they are only 'spent'
        FakeFacts f = nether().give(Items.IRON_INGOT, 3).give(Items.STICK, 2).give(Items.CRAFTING_TABLE, 1);
        f.spent.put(Items.IRON_PICKAXE, 1);
        assertEquals(Tier.IRON, NetherPick.choose(f));
    }

    @Test
    public void ironBeatsStoneBeatsWood() {
        Bag all = new Bag(3, 3, 20, 4, 0, true);
        assertEquals(Tier.IRON, NetherPick.choose(all));
        assertEquals(Tier.STONE, NetherPick.choose(new Bag(2, 3, 20, 4, 0, true)));
        assertEquals(Tier.WOODEN, NetherPick.choose(new Bag(2, 2, 20, 4, 0, true)));
    }

    // the whole point: a tier only ever comes back when every input count is in the bag, whatever the bag is
    @Test
    public void aTierIsNeverChosenWhenAnInputIsShort() {
        for (int ingots = 0; ingots <= 4; ingots++) {
            for (int cobble = 0; cobble <= 4; cobble++) {
                for (int planks = 0; planks <= 10; planks++) {
                    for (int sticks = 0; sticks <= 3; sticks++) {
                        for (int logs = 0; logs <= 3; logs++) {
                            for (boolean table : new boolean[]{false, true}) {
                                Bag b = new Bag(ingots, cobble, planks, sticks, logs, table);
                                Tier t = NetherPick.choose(b);
                                if (t == Tier.NONE) {
                                    continue;
                                }
                                String why = t + " from " + b;
                                int head = t == Tier.IRON ? ingots : t == Tier.STONE ? cobble : planks + 4 * logs;
                                assertTrue(why, head >= 3);
                                assertTrue(why, NetherPick.woodNeeded(b, t) <= planks + 4 * logs);
                                // sticks short = wood for them is in the count, none short = no wood asked for them
                                if (sticks >= 2 && table && t != Tier.WOODEN) {
                                    assertEquals(why, 0, NetherPick.woodNeeded(b, t));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theCraftRunsPlanksSticksTableThenThePick() {
        // two stems and ingots, nothing else: cut them up (8 planks), sticks (2), the table (4), then the pick
        Bag b = new Bag(3, 0, 0, 0, 2, false);
        assertEquals(NetherPick.Stage.PLANKS, NetherPick.stage(b, Tier.IRON));
        b = new Bag(3, 0, 8, 0, 0, false);
        assertEquals(NetherPick.Stage.STICKS, NetherPick.stage(b, Tier.IRON));
        // the sticks took 2 planks and the need dropped by 2 with them: still covered
        b = new Bag(3, 0, 6, 4, 0, false);
        assertEquals(NetherPick.Stage.TABLE, NetherPick.stage(b, Tier.IRON));
        b = new Bag(3, 0, 2, 4, 0, true);
        assertEquals(NetherPick.Stage.PICK, NetherPick.stage(b, Tier.IRON));
    }

    @Test
    public void aStageNeverSurvivesAShortInput() {
        // the table went down but the planks were the last 4 of the table's: nothing is owed any more, still fine
        assertEquals(NetherPick.Stage.PICK, NetherPick.stage(new Bag(3, 0, 0, 2, 0, true), Tier.IRON));
        // an ingot fell out of the bag mid craft
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(2, 0, 0, 2, 0, true), Tier.IRON));
        // the sticks are gone and so is the wood to make them
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(3, 0, 1, 0, 0, true), Tier.IRON));
    }

    // every stage on the way keeps the whole craft covered, whatever bag it starts from, so ABORT can only mean something was lost
    @Test
    public void everyStageStepKeepsTheCraftCovered() {
        for (int planks = 0; planks <= 10; planks++) {
            for (int sticks = 0; sticks <= 3; sticks++) {
                for (int logs = 0; logs <= 3; logs++) {
                    for (boolean table : new boolean[]{false, true}) {
                        Bag b = new Bag(3, 3, planks, sticks, logs, table);
                        for (Tier t : new Tier[]{Tier.IRON, Tier.STONE, Tier.WOODEN}) {
                            if (!NetherPick.covered(b, t)) {
                                continue;
                            }
                            Bag next = switch (NetherPick.stage(b, t)) {
                                case PLANKS -> new Bag(b.ingots(), b.cobble(), b.planks() + 4, b.sticks(), b.logs() - 1, b.table());
                                case STICKS -> new Bag(b.ingots(), b.cobble(), b.planks() - 2, b.sticks() + 4, b.logs(), b.table());
                                case TABLE -> new Bag(b.ingots(), b.cobble(), b.planks() - 4, b.sticks(), b.logs(), true);
                                default -> null;
                            };
                            if (next != null) {
                                assertTrue(b + " -> " + next + " as " + t, NetherPick.covered(next, t));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aTableThatJustWentDownIsStillThereForAFewSeconds() {
        // 3 cobble and 2 sticks, the table was in the bag a tick ago and is on the ground now, the book does not know it yet
        Bag gone = new Bag(0, 3, 0, 2, 0, false);
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(gone, Tier.STONE));
        Bag graced = NetherPick.graceTable(gone, true, 1);
        assertTrue(graced.table());
        assertEquals(NetherPick.Stage.PICK, NetherPick.stage(graced, Tier.STONE));
        // but not for ever, and not when we were not at the pick stage
        assertFalse(NetherPick.graceTable(gone, true, NetherPick.TABLE_GRACE_SECONDS + 1).table());
        assertFalse(NetherPick.graceTable(gone, false, 0).table());
    }

    @Test
    public void theLinesNameTheBagAndTheTier() {
        assertEquals("nether: no pickaxe left, crafting stone from the bag", NetherPick.craftLine(Tier.STONE));
        assertEquals("nether: no pickaxe and nothing to make one from (ingots 2, cobble 1, planks 9, sticks 0, table no)",
                NetherPick.shortLine(new Bag(2, 1, 1, 0, 2, false)));
        assertFalse(NetherPick.covered(new Bag(3, 0, 0, 0, 0, false), Tier.IRON));
    }

    @Test
    public void aSwordPrefersDiamondThenIronThenCobble() {
        NetherPick.Kind sw = NetherPick.Kind.SWORD;
        // table and sticks in the bag, only the head varies. (diamonds, ingots, cobble)
        assertEquals(Tier.DIAMOND, NetherPick.choose(new Bag(2, 2, 0, 2, 0, true, 2), sw));
        assertEquals(Tier.IRON, NetherPick.choose(new Bag(2, 2, 0, 2, 0, true, 1), sw));
        assertEquals(Tier.STONE, NetherPick.choose(new Bag(1, 2, 0, 2, 0, true, 1), sw));
        assertEquals(Tier.NONE, NetherPick.choose(new Bag(1, 1, 9, 2, 0, true, 1), sw));
        // planks are no sword head, and a pick never goes to diamond
        assertEquals(Tier.WOODEN, NetherPick.choose(new Bag(0, 0, 9, 2, 0, true, 5)));
        assertEquals(Tier.NONE, NetherPick.choose(new Bag(0, 0, 0, 2, 0, true, 5)));
    }

    @Test
    public void headsAreTwoForASwordAndThreeForAPick() {
        Bag two = new Bag(2, 0, 0, 1, 0, true);
        assertTrue(NetherPick.covered(two, Tier.IRON, NetherPick.Kind.SWORD));
        assertFalse(NetherPick.covered(two, Tier.IRON, NetherPick.Kind.PICKAXE));
        // one stick is enough for a sword, a pick wants two
        assertEquals(NetherPick.Stage.PICK, NetherPick.stage(two, Tier.IRON, NetherPick.Kind.SWORD));
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(3, 0, 0, 1, 0, true), Tier.IRON));
    }

    @Test
    public void aSwordWithoutSticksOrMaterialsAborts() {
        NetherPick.Kind sw = NetherPick.Kind.SWORD;
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(2, 0, 0, 0, 0, true), Tier.IRON, sw));
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(1, 0, 0, 1, 0, true), Tier.IRON, sw));
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(0, 0, 0, 1, 0, true, 1), Tier.DIAMOND, sw));
        // the sticks can come out of planks in the bag
        assertEquals(NetherPick.Stage.STICKS, NetherPick.stage(new Bag(2, 0, 2, 0, 0, true), Tier.IRON, sw));
        // and a table out of planks, but only with enough of them
        assertEquals(NetherPick.Stage.TABLE, NetherPick.stage(new Bag(0, 0, 4, 1, 0, false, 2), Tier.DIAMOND, sw));
        assertEquals(NetherPick.Stage.ABORT, NetherPick.stage(new Bag(0, 0, 3, 1, 0, false, 2), Tier.DIAMOND, sw));
    }

    @Test
    public void aSwordSkipsTheTierOfOneWeHoldWorn() {
        Bag iron = new Bag(2, 2, 0, 2, 0, true, 0);
        assertEquals(Tier.STONE, NetherPick.choose(iron, NetherPick.Kind.SWORD, t -> t == Tier.IRON));
        assertEquals(Tier.NONE, NetherPick.choose(new Bag(2, 0, 0, 2, 0, true), NetherPick.Kind.SWORD, t -> t == Tier.IRON));
    }

    @Test
    public void swordNamesFollowTheCatalogue() {
        assertEquals("diamond_sword", NetherPick.catalogueName(Tier.DIAMOND, NetherPick.Kind.SWORD));
        assertEquals("stone_pickaxe", NetherPick.catalogueName(Tier.STONE));
    }

    @Test
    public void aWornSwordDoesNotCountAsUsable() {
        FakeFacts f = nether();
        f.give(Items.IRON_SWORD, 1);
        assertTrue(NetherPick.hasUsableSword(f));
        f.wornSwords = 1;
        assertFalse(NetherPick.hasUsableSword(f));
    }
}
