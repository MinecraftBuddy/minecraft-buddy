package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KitRunnerTest {
    private static class StubTask extends Task {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return false;
        }

        @Override
        protected String toDebugString() {
            return "stub";
        }
    }

    private final List<Integer> foodTargets = new ArrayList<>();
    private int built;
    private boolean unknown;
    private KitRunner runner;
    private StubContext ctx;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        ctx = new StubContext();
        built = 0;
        unknown = false;
        foodTargets.clear();
        runner = new KitRunner((need, equip, food) -> {
            built++;
            if (KitNeed.FOOD.equals(need.catalogueName())) {
                foodTargets.add(food);
            }
            return unknown ? null : new StubTask();
        });
    }

    @Test
    public void sameNeedKeepsTheSameTaskObject() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39), new KitNeed("iron_pickaxe", 1));
        Task first = runner.run(ctx, needs);
        for (int i = 0; i < 50; i++) {
            assertSame(first, runner.run(ctx, new ArrayList<>(needs)));
        }
        assertEquals(1, built);
    }

    @Test
    public void aChangedNeedBuildsANewTask() {
        Task first = runner.run(ctx, List.of(new KitNeed("iron_ingot", 39)));
        Task second = runner.run(ctx, List.of(new KitNeed("iron_ingot", 36)));
        assertNotSame(first, second);
        Task third = runner.run(ctx, List.of(new KitNeed("iron_pickaxe", 1)));
        assertNotSame(second, third);
        assertEquals(3, built);
    }

    @Test
    public void aFreshTaskSurvivesTheHeadSwappingUnderItForAFewSeconds() {
        KitNeed iron = new KitNeed("iron_ingot", 39);
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        ctx.facts.give(Items.STONE_PICKAXE, 1);
        Task first = runner.run(ctx, List.of(iron));
        // the planner puts food first from here on: held for the dwell, then it takes over
        assertSame(first, runner.run(ctx, List.of(food, iron)));
        ctx.facts.seconds(2);
        assertSame(first, runner.run(ctx, List.of(food, iron)));
        assertEquals(1, built);
        ctx.facts.seconds(4);
        assertNotSame(first, runner.run(ctx, List.of(food, iron)));
        assertEquals(2, built);
        // and that one is the fresh one now, the order flipping back does not rip it out again
        Task second = runner.run(ctx, List.of(iron, food));
        assertSame(second, runner.run(ctx, List.of(iron, food)));
        assertEquals("Getting food", runner.hud());
    }

    @Test
    public void aNeedThatBlinksOutOfThePlanKeepsItsTask() {
        KitNeed logs = new KitNeed("log", 29);
        KitNeed cobble = new KitNeed("cobblestone", 19);
        ctx.facts.give(Items.STONE_PICKAXE, 1).give(Items.OAK_LOG, 3);
        Task first = runner.run(ctx, List.of(logs, cobble));
        ctx.facts.seconds(30);
        // gone for a tick and back: same task, nothing rebuilt
        assertSame(first, runner.run(ctx, List.of(cobble)));
        ctx.facts.seconds(0.05);
        assertSame(first, runner.run(ctx, List.of(logs, cobble)));
        assertEquals(1, built);
        // gone for good: kept through the dwell, then the cobble takes over
        ctx.facts.seconds(1);
        assertSame(first, runner.run(ctx, List.of(cobble)));
        ctx.facts.seconds(4.95);
        assertSame(first, runner.run(ctx, List.of(cobble)));
        ctx.facts.seconds(0.05);
        assertNotSame(first, runner.run(ctx, List.of(cobble)));
        assertEquals(2, built);
    }

    @Test
    public void aNeedWeHoldNowHandsOverAtOnce() {
        KitNeed logs = new KitNeed("log", 29);
        KitNeed cobble = new KitNeed("cobblestone", 19);
        ctx.facts.give(Items.STONE_PICKAXE, 1);
        Task first = runner.run(ctx, List.of(logs, cobble));
        ctx.facts.give(Items.OAK_LOG, 29);
        assertNotSame(first, runner.run(ctx, List.of(cobble)));
    }

    @Test
    public void aMissingPickaxeIsNotMadeToWait() {
        Task first = runner.run(ctx, List.of(new KitNeed("iron_ingot", 39)));
        assertNotSame(first, runner.run(ctx, List.of(new KitNeed("stone_pickaxe", 1), new KitNeed("iron_ingot", 39))));
    }

    @Test
    public void onlyTheFirstNeedRuns() {
        runner.run(ctx, List.of(new KitNeed("iron_ingot", 39), new KitNeed("iron_pickaxe", 1)));
        assertEquals(1, built);
        assertEquals("Looking for iron", runner.hud());
    }

    @Test
    public void nothingToDoIsNoTask() {
        assertNull(runner.run(ctx, List.of()));
        assertNull(runner.hud());
    }

    @Test
    public void resetForgetsTheTask() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39));
        Task first = runner.run(ctx, needs);
        runner.reset();
        assertNotSame(first, runner.run(ctx, needs));
    }

    @Test
    public void anUnknownNameFailsOnceAndRunsNothing() {
        unknown = true;
        List<KitNeed> needs = List.of(new KitNeed("not_an_item", 1));
        assertNull(runner.run(ctx, needs));
        assertNull(runner.run(ctx, needs));
        assertNull(runner.run(ctx, needs));
        assertEquals(1, ctx.fails.size());
        assertTrue(ctx.fails.get(0).contains("not_an_item"));
    }

    @Test
    public void growingCountsTellTheWatchdog() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39));
        runner.run(ctx, needs);
        assertTrue(ctx.progress.isEmpty());
        ctx.facts.give(Items.IRON_INGOT, 1);
        runner.run(ctx, needs);
        assertEquals(1, ctx.progress.size());
        runner.run(ctx, needs);
        assertEquals("no change, no ping", 1, ctx.progress.size());
    }

    @Test
    public void twoNeedsTradingTheHeadAreNotProgress() {
        List<KitNeed> a = List.of(new KitNeed("iron_ingot", 39));
        List<KitNeed> b = List.of(new KitNeed("cobblestone", 20));
        for (int i = 0; i < 10; i++) {
            runner.run(ctx, a);
            runner.run(ctx, b);
        }
        assertTrue(ctx.progress.isEmpty());
        // the iron is kept through a blink (HeadLatch), so the cobble only takes over once the iron has been gone a dwell
        ctx.facts.seconds(6);
        runner.run(ctx, b);
        assertEquals("Mining stone", runner.hud());
        // a real gain on one of them still counts
        ctx.facts.give(Items.COBBLESTONE, 3);
        runner.run(ctx, b);
        assertEquals(List.of("cobblestone"), ctx.progress);
        // and the other one turning up again is not a gain either
        runner.run(ctx, a);
        assertEquals(1, ctx.progress.size());
    }

    @Test
    public void aBagOfRottenFleshDoesNotSatisfyTheFoodNeed() {
        // 20 rotten flesh is 80 nutrition for CollectFoodTask, and 0 for what we would actually eat
        ctx.facts.give(Items.ROTTEN_FLESH, 20);
        ctx.facts.junkFoodUnits = 80;
        List<KitNeed> plan = KitPlanner.gather(ctx.facts, ctx.cfg.overworld, 8);
        KitNeed food = plan.stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).findFirst().orElseThrow();
        assertEquals(130, food.count());
        assertEquals(210, KitRunner.foodTarget(food, ctx.food()));
        runner.run(ctx, List.of(food));
        assertEquals(List.of(210), foodTargets);
    }

    @Test
    public void junkPickedUpLaterRebuildsTheFoodTask() {
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        Task first = runner.run(ctx, List.of(food));
        ctx.facts.junkFoodUnits = 8;
        Task second = runner.run(ctx, List.of(food));
        assertNotSame(first, second);
        assertEquals(List.of(70, 78), foodTargets);
    }

    @Test
    public void theFoodNeedComingAndGoingIsLoggedOncePerChange() {
        PrintStream real = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            ctx.facts.foodUnits = 60;
            KitNeed iron = new KitNeed("iron_ingot", 39);
            runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, 70), iron));
            runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, 70), iron));
            // the minimum becoming the target is the same need still being owed
            runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, 100), iron));
            runner.run(ctx, List.of(iron));
            runner.run(ctx, List.of(iron));
            runner.run(ctx, List.of());
            runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, 70)));
        } finally {
            System.setOut(real);
        }
        List<String> lines = captured.toString().lines().filter(l -> l.contains("food: need")).toList();
        assertEquals(lines.toString(), 3, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("food: need on, held 60 of 70 (station 0, pending 0, raw left out 0), running food"));
        // food is held for a dwell once it drops out of the plan while the bag is short, so it is still the one running
        assertTrue(lines.get(1), lines.get(1).endsWith("food: need off, held 60 of 70 (station 0, pending 0, raw left out 0), running food"));
        assertTrue(lines.get(2), lines.get(2).endsWith("food: need on, held 60 of 70 (station 0, pending 0, raw left out 0), running food"));
    }

    @Test
    public void theDiamondCraftsComingAndGoingAreLoggedOncePerChange() {
        PrintStream real = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            KitNeed iron = new KitNeed("iron_ingot", 39);
            ctx.facts.give(Items.DIAMOND, 3);
            runner.run(ctx, List.of(iron));
            runner.run(ctx, List.of(new KitNeed("diamond_pickaxe", 1), iron));
            runner.run(ctx, List.of(new KitNeed("diamond_pickaxe", 1), iron));
            ctx.facts.give(Items.DIAMOND, 4);
            runner.run(ctx, List.of(new KitNeed("diamond_pickaxe", 1), new KitNeed("diamond_boots", 1), iron));
            runner.run(ctx, List.of(iron));
            runner.run(ctx, List.of(iron));
        } finally {
            System.setOut(real);
        }
        List<String> lines = captured.toString().lines().filter(l -> l.contains("diamonds:")).toList();
        assertEquals(lines.toString(), 3, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("diamonds: 3 held -> [diamond_pickaxe]"));
        assertTrue(lines.get(1), lines.get(1).endsWith("diamonds: 7 held -> [diamond_pickaxe, diamond_boots]"));
        assertTrue(lines.get(2), lines.get(2).endsWith("diamonds: 7 held -> []"));
    }

    @Test
    public void nonFoodNeedsIgnoreJunk() {
        ctx.facts.junkFoodUnits = 80;
        assertEquals(32, KitRunner.foodTarget(new KitNeed(KitNeed.BUILD_BLOCKS, 32), ctx.food()));
    }

    @Test
    public void equipNeedUsesTheItemsWeCarry() {
        ctx.facts.give(Items.IRON_HELMET, 1);
        List<List<Item>> seen = new ArrayList<>();
        KitRunner r = new KitRunner((need, equip, food) -> {
            seen.add(equip);
            return new StubTask();
        });
        r.run(ctx, List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)));
        assertEquals(List.of(List.of(Items.IRON_HELMET)), seen);
    }

    @Test
    public void armorOnGoesFirstAndGivesUpOnAPieceItCannotReach() {
        ctx.facts.give(Items.IRON_HELMET, 1);
        KitNeed iron = new KitNeed("iron_ingot", 39);
        KitNeed pick = new KitNeed("iron_pickaxe", 1);
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        List<KitNeed> plan = List.of(iron, pick, armor);
        runner.run(ctx, plan);
        assertEquals(armor, runner.current());
        // the hud's list is still the plan as handed in
        assertEquals(plan, runner.needs());
        // the helmet never goes on (a cursed piece over the slot, say): the runner lets go and the iron carries on
        for (int t = 1; t <= HeadLatch.ARMOR_STUCK_TICKS; t++) {
            ctx.facts.gameTime = t;
            runner.run(ctx, plan);
        }
        ctx.facts.gameTime++;
        runner.run(ctx, plan);
        assertEquals(iron, runner.current());
        ctx.facts.gameTime += 2000;
        runner.run(ctx, plan);
        assertEquals(iron, runner.current());
        // a new piece in the bag is a new list: armor on first again
        ctx.facts.give(Items.IRON_BOOTS, 1);
        KitNeed two = new KitNeed(KitNeed.EQUIP_ARMOR, 2);
        runner.run(ctx, List.of(iron, pick, two));
        assertEquals(two, runner.current());
    }

    @Test
    public void ticksTheRunnerWasNotRunDoNotCountAsStuckArmor() {
        ctx.facts.give(Items.IRON_HELMET, 1);
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        List<KitNeed> plan = List.of(new KitNeed("iron_ingot", 39), armor);
        runner.run(ctx, plan);
        // a fight had the wheel for a long while: the first tick back is the equip's second tick, not its 1000th
        ctx.facts.gameTime = 1000;
        runner.run(ctx, plan);
        ctx.facts.gameTime = 1001;
        runner.run(ctx, plan);
        assertEquals(armor, runner.current());
    }

    @Test
    public void givenUpArmorAtThePlanHeadLetsTheRestGoFirst() {
        ctx.facts.give(Items.IRON_HELMET, 1);
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        KitNeed flint = new KitNeed("flint_and_steel", 1);
        // the portal gate's order once the food and buckets are done
        List<KitNeed> plan = List.of(armor, flint);
        for (int t = 0; t <= HeadLatch.ARMOR_STUCK_TICKS + 1; t++) {
            ctx.facts.gameTime = t;
            runner.run(ctx, plan);
        }
        assertEquals(flint, runner.current());
        // nothing else left: it is all there is to try
        assertEquals(List.of(armor), HeadLatch.armorLast(List.of(armor)));
    }

    @Test
    public void theHeadLineNamesOldNewAndTheFirstFourNeeds() {
        List<KitNeed> plan = List.of(new KitNeed("iron_ingot", 4), new KitNeed("food", 12), new KitNeed("log", 3),
                new KitNeed("coal", 2), new KitNeed("flint", 1));
        assertEquals("switch kit head from=none to=iron_ingot x4 plan=[iron_ingot x4, food x12, log x3, coal x2]",
                KitRunner.headLine(null, plan.get(0), plan));
        assertEquals("switch kit head from=food x12 to=log x3 plan=[iron_ingot x4, food x12, log x3, coal x2]",
                KitRunner.headLine(plan.get(1), plan.get(2), plan));
    }

    @Test
    public void aShortPlanIsListedWhole() {
        List<KitNeed> plan = List.of(new KitNeed("flint", 1));
        assertEquals("switch kit head from=none to=flint x1 plan=[flint x1]", KitRunner.headLine(null, plan.get(0), plan));
    }
}
