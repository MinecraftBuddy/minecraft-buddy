package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EyesPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.PortalPhase;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the pure bits of the overworld phases: isDone over fake facts, the config defaults, and the small helpers
public class OverworldPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    // the spare shields have their own test (KitPlannerTest), the budgets here are the kit as it was
    {
        cfg.overworld.netherSpareShields = 0;
        cfg.overworld.netherSpareSwords = 0;
    }
    private final RunState state = new RunState();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void phasesNameThemselves() {
        assertEquals(GamerPhase.GATHER, new GatherPhase().phase());
        assertEquals(GamerPhase.IRON, new IronPhase().phase());
        assertEquals(GamerPhase.PORTAL, new PortalPhase().phase());
        assertEquals("Getting started", new GatherPhase().hud());
        assertEquals(null, new IronPhase().hudState());
    }

    @Test
    public void gatherIsDoneWithStoneToolsFurnaceAndFood() {
        GatherPhase gather = new GatherPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(gather.isDone(f, state, cfg));
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1);
        f.foodUnits = 70;
        assertFalse("one pick, no axe, no wood", gather.isDone(f, state, cfg));
        f.give(Items.STONE_PICKAXE, 1).give(Items.WOODEN_AXE, 1);
        assertFalse("still no wood for the rest of the run", gather.isDone(f, state, cfg));
        f.give(Items.OAK_LOG, 64);
        assertFalse("no furnace yet", gather.isDone(f, state, cfg));
        f.give(Items.FURNACE, 1);
        assertTrue(gather.isDone(f, state, cfg));
        // 69 with no trip on is fine, the refill only starts under 45. once one is under way it runs to the minimum
        f.foodUnits = 69;
        assertTrue("69 is not a trip", gather.isDone(f, state, cfg));
        state.foodRefilling = true;
        assertFalse("a trip under way runs to the minimum", gather.isDone(f, state, cfg));
        state.foodRefilling = false;
        f.foodUnits = 44;
        assertFalse("under the refill start", gather.isDone(f, state, cfg));
    }

    @Test
    public void gatherAcceptsBetterToolsAndABlastFurnace() {
        FakeFacts f = new FakeFacts().give(Items.IRON_PICKAXE, 1).give(Items.DIAMOND_SWORD, 1).give(Items.BLAST_FURNACE, 1)
                .give(Items.IRON_AXE, 1).give(Items.OAK_LOG, 64);
        f.foodUnits = 100;
        assertTrue(new GatherPhase().isDone(f, state, cfg));
    }

    @Test
    public void ironIsDoneOnlyWithTheWholeKitWoolAndFood() {
        IronPhase iron = new IronPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(iron.isDone(f, state, cfg));
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
            f.give(i, 1);
        }
        f.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        assertFalse("no wool", iron.isDone(f, state, cfg));
        f.give(Items.WOOL.green(), 3 * cfg.end.beds);
        assertFalse("no diamond pick yet", iron.isDone(f, state, cfg));
        f.diamondHuntGaveUp = true;
        assertTrue("the dig ran out of time, the iron pick it is", iron.isDone(f, state, cfg));
        f.diamondHuntGaveUp = false;
        f.give(Items.DIAMOND_PICKAXE, 1);
        assertTrue(iron.isDone(f, state, cfg));
        f.foodUnits = 99;
        assertFalse("food under the target", iron.isDone(f, state, cfg));
    }

    @Test
    public void ironIsNotDoneWhileIronIsStillCooking() {
        IronPhase iron = new IronPhase();
        cfg.end.beds = 2;
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        FakeFacts f = new FakeFacts().give(Items.WOOL.white(), 6);
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS, Items.DIAMOND_PICKAXE)) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        assertTrue("the kit is whole", iron.isDone(f, state, cfg));
        // a job nobody collected (more ingots than the kit needed): leaving now would leave them in the furnace
        f.cooking("iron_ingot", 5, 30);
        assertFalse(iron.isDone(f, state, cfg));
    }

    // a kit that is whole and a bag that is fed, so the only thing left to say "not done" is a station of ours
    private static FakeFacts gatherDone() {
        FakeFacts f = new FakeFacts().give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1).give(Items.WOODEN_AXE, 1)
                .give(Items.OAK_LOG, 64).give(Items.FURNACE, 1);
        f.foodUnits = 70;
        return f;
    }

    private FakeFacts ironDone() {
        cfg.end.beds = 2;
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        FakeFacts f = new FakeFacts().give(Items.WOOL.white(), 6);
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS, Items.DIAMOND_PICKAXE)) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        return f;
    }

    // WorkbenchRules.phaseMayEnd: an idle table standing in this dimension is the one that gets left behind, so the phase waits for
    // the pickup. the phase never builds the registry itself, the gate syncs it from the saved lists
    @Test
    public void gatherAndIronWaitForATableOfOursStillStanding() {
        GatherPhase gather = new GatherPhase();
        IronPhase iron = new IronPhase();
        FakeFacts g = gatherDone();
        FakeFacts i = ironDone();
        assertTrue(gather.isDone(g, state, cfg));
        assertTrue(iron.isDone(i, state, cfg));
        state.placedTables.add(new RunState.Pos(10, 64, 10));
        assertFalse("table standing", gather.isDone(g, state, cfg));
        assertFalse("table standing", iron.isDone(i, state, cfg));
        // picked up, the list is empty again
        state.placedTables.clear();
        assertTrue(gather.isDone(g, state, cfg));
        assertTrue(iron.isDone(i, state, cfg));
    }

    @Test
    public void aFurnaceOrASmokerOfOursStandingHoldsThePhaseToo() {
        GatherPhase gather = new GatherPhase();
        FakeFacts g = gatherDone();
        state.placedFurnaces.add(new RunState.Pos(3, 64, 3));
        assertFalse(gather.isDone(g, state, cfg));
        state.placedFurnaces.clear();
        state.placedSmokers.add(new RunState.Pos(4, 64, 3));
        assertFalse(gather.isDone(g, state, cfg));
        state.placedSmokers.clear();
        assertTrue(gather.isDone(g, state, cfg));
    }

    // a station in another dimension is already left behind (the registry forgets it), it is not a reason to hold this phase
    @Test
    public void aStationInAnotherDimensionDoesNotHoldThePhase() {
        GatherPhase gather = new GatherPhase();
        IronPhase iron = new IronPhase();
        state.placedTables.add(new RunState.Pos(-3, 70, 9));
        state.placedDimension.put("TABLE:-3,70,9", "NETHER");
        assertTrue(gather.isDone(gatherDone(), state, cfg));
        assertTrue(iron.isDone(ironDone(), state, cfg));
        // the same table with no dimension recorded is an overworld one (a fresh state, the registry keeps the dimension an entry
        // was made with)
        RunState plain = new RunState();
        plain.placedTables.add(new RunState.Pos(-3, 70, 9));
        assertFalse(gather.isDone(gatherDone(), plain, cfg));
        assertFalse(iron.isDone(ironDone(), plain, cfg));
        // and the other way round: a table left standing in the overworld while the facts say we are in the nether
        FakeFacts nether = gatherDone();
        nether.dimension = Dimension.NETHER;
        assertTrue(gather.isDone(nether, plain, cfg));
    }

    @Test
    public void thePhasesThatRunThePickupSaySo() {
        assertTrue(new GatherPhase().ownsBenches());
        assertTrue(new IronPhase().ownsBenches());
        assertTrue(new PortalPhase().ownsBenches());
        // everything else gets the sweep from the engine instead
        assertFalse(new NetherPhase().ownsBenches());
        assertFalse(new EyesPhase().ownsBenches());
        assertFalse(new DragonPhase().ownsBenches());
    }

    @Test
    public void ironWantsWoolForTheConfiguredBedCount() {
        IronPhase iron = new IronPhase();
        cfg.end.beds = 2;
        FakeFacts f = new FakeFacts().give(Items.WOOL.white(), 6);
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS, Items.DIAMOND_PICKAXE)) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        assertTrue(iron.isDone(f, state, cfg));
    }

    @Test
    public void portalIsDoneInTheNetherOnly() {
        PortalPhase portal = new PortalPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(portal.isDone(f, state, cfg));
        f.dimension = Dimension.END;
        assertFalse(portal.isDone(f, state, cfg));
        f.dimension = Dimension.NETHER;
        assertTrue(portal.isDone(f, state, cfg));
    }

    @Test
    public void aSlowDragonFightKeepsGoingAndOnlyTheEndDeathsEndIt() {
        DragonPhase dragon = new DragonPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, dragon.onTimeout(ctx, ctx.cfg.maxAttempts + 50, "slow"));
        for (int i = 0; i < ctx.cfg.end.attempts; i++) {
            RunState.Death d = new RunState.Death();
            d.dimension = "END";
            ctx.state.deaths.add(d);
        }
        assertEquals(Timeout.STUCK_NO_RESTART, dragon.onTimeout(ctx, 1, "slow"));
    }

    @Test
    public void gatherSkipsOnTimeoutOnceBothStoneToolsAreHeld() {
        GatherPhase gather = new GatherPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, gather.onTimeout(ctx, 1, "slow"));
        assertEquals(Timeout.STUCK, gather.onTimeout(ctx, 2, "slow"));
        ctx.facts.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1);
        assertEquals(Timeout.SKIP, gather.onTimeout(ctx, 1, "no food"));
        assertEquals(Timeout.SKIP, gather.onTimeout(ctx, 2, "no food"));
    }

    @Test
    public void ironSkipsOnlyWithTheEssentials() {
        IronPhase iron = new IronPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, iron.onTimeout(ctx, 1, "slow"));
        assertEquals(Timeout.STUCK, iron.onTimeout(ctx, 2, "slow"));
        ctx.facts.give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.BUCKET, 2);
        assertEquals(Timeout.SKIP, iron.onTimeout(ctx, 2, "slow"));
    }

    @Test
    public void portalTimesOutOnceIntoObsidianThenStops() {
        PortalPhase portal = new PortalPhase();
        StubContext ctx = new StubContext();
        ctx.facts.give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(Timeout.RETRY, portal.onTimeout(ctx, 1, "stalled"));
        assertEquals("OBSIDIAN", ctx.state.portalMethod);
        assertEquals(Timeout.STUCK, portal.onTimeout(ctx, 2, "stalled"));
    }

    // obsidian is mined with a diamond pickaxe, without one the retry casts again instead of picking a way that cannot work
    @Test
    public void portalTimeoutWithoutADiamondPickaxeRetriesTheCast() {
        PortalPhase portal = new PortalPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, portal.onTimeout(ctx, 1, "stalled"));
        assertEquals("CAST", ctx.state.portalMethod);
        assertEquals(Timeout.STUCK, portal.onTimeout(ctx, 2, "stalled"));
    }

    @Test
    public void configDefaultsStaySane() {
        OverworldConfig o = cfg.overworld;
        assertEquals(70, o.minFoodUnits);
        assertEquals(100, o.targetFoodUnits);
        assertEquals(7.0, o.castGiveUpMinutes, 0);
        assertEquals(60, o.ruinedPortalLootRadius);
        assertEquals(48, o.villageLootRadius);
        assertEquals(6, o.villageChestJobRadius);
        assertEquals(3, o.villageLootMaxChests);
        assertEquals(240.0, o.villageLootSeconds, 0);
        assertEquals(48, o.villageBedRadius);
        assertEquals(16, o.villageBedEvidenceRadius);
        assertEquals(120.0, o.villageBedSeconds, 0);
        assertEquals(20.0, o.villageBedEachSeconds, 0);
        assertEquals(32, o.portalBuildBlocks);
        assertEquals(56, o.netherEntryBlocks);
        assertEquals(40, o.netherEntryBlocksLow);
        assertEquals(32, o.golemHuntRadius);
        assertEquals(5, o.golemMinBlocks);
        assertEquals(14.0, o.golemMinHealth, 0);
        assertEquals(0.3, o.golemSafeMargin, 0);
        assertEquals(2, o.golemMaxAttempts);
        // the margin is what puts our feet on a 3.0 level against a 2.7 golem
        assertTrue(o.golemSafeMargin > 0 && o.golemSafeMargin < 1);
        assertTrue(o.golemAbortHealth < o.golemMinHealth);
        assertTrue(o.noLavaSeconds < o.castGiveUpMinutes * 60);
        assertEquals(OverworldConfig.ArmorPlan.FULL_IRON, o.armorPlan);
        assertEquals(7, o.ironKit.size());
        // axe, two stone picks (one spare), sword, furnace
        assertEquals(4, o.starterKit.size());
    }

    @Test
    public void needKinds() {
        assertTrue(new KitNeed("iron_ingot", 3).isGathering());
        assertTrue(new KitNeed(KitNeed.FOOD, 70).isGathering());
        assertFalse(new KitNeed("iron_pickaxe", 1).isGathering());
        assertTrue(new KitNeed(KitNeed.EQUIP_ARMOR, 1).isSpecial());
        assertFalse(new KitNeed("wool", 24).isSpecial());
    }

    @Test
    public void hudWordsAreHumanReadable() {
        FakeFacts f = new FakeFacts();
        assertEquals("Getting food", KitRunner.words(new KitNeed(KitNeed.FOOD, 70), f));
        assertEquals("Looking for iron", KitRunner.words(new KitNeed("iron_ingot", 39), f));
        f.give(Items.RAW_IRON, 3);
        assertEquals("Smelting iron", KitRunner.words(new KitNeed("iron_ingot", 39), f));
        // a batch in the furnace means the iron need is the dig for the next one
        FakeFacts cooking = new FakeFacts().cooking("iron_ingot", 3, 30);
        cooking.give(Items.RAW_IRON, 3);
        assertEquals("Mining more iron", KitRunner.words(new KitNeed("iron_ingot", 39), cooking));
        assertEquals("Chopping wood", KitRunner.words(new KitNeed("stone_pickaxe", 1), f));
        f.give(Items.WOODEN_PICKAXE, 1);
        assertEquals("Mining stone", KitRunner.words(new KitNeed("stone_pickaxe", 1), f));
        assertEquals("Collecting wool", KitRunner.words(new KitNeed("wool", 24), f));
        f.give(Items.SHEARS, 1);
        assertEquals("Shearing sheep", KitRunner.words(new KitNeed("wool", 24), f));
        assertEquals("Making iron chestplate", KitRunner.words(new KitNeed("iron_chestplate", 1), f));
    }

    @Test
    public void portalChestHeuristic() {
        assertTrue(RuinedPortalLoot.plausible(70, false, true));
        assertFalse("no netherrack, a village chest", RuinedPortalLoot.plausible(70, false, false));
        assertFalse("underwater", RuinedPortalLoot.plausible(70, true, true));
        assertFalse("buried", RuinedPortalLoot.plausible(30, false, true));
    }

    @Test
    public void pillagerRadius() {
        List<Vec3> pillagers = List.of(new Vec3(100, 70, 100));
        assertTrue(DangerFilter.nearAny(new BlockPos(120, 70, 100), pillagers, 40));
        assertFalse(DangerFilter.nearAny(new BlockPos(150, 70, 100), pillagers, 40));
        assertFalse(DangerFilter.nearAny(new BlockPos(0, 0, 0), List.of(), 40));
    }
}
