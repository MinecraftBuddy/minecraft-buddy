package adris.altoclef.tasks.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class AsyncSmeltingTest {
    @After
    public void forget() {
        AsyncSmelting.clear();
    }

    @Test
    public void ironNeedsTheSmeltingSwitchOnly() {
        assertTrue(AsyncSmelting.wantsName("iron_ingot", true, false));
        assertTrue(AsyncSmelting.wantsName("iron_ingot", true, true));
        assertFalse(AsyncSmelting.wantsName("iron_ingot", false, true));
    }

    // an adopted load is not food unless its output is cooked food, and a name that is not asks nothing of the item registry
    @Test
    public void onlyCookedFoodHasNutritionForAnAdoptedLoad() {
        assertEquals(0, AsyncSmelting.unitsOfOutput("iron_ingot"));
        assertEquals(0, AsyncSmelting.unitsOfOutput("gold_ingot"));
        assertEquals(0, AsyncSmelting.unitsOfOutput("dried_kelp"));
        assertEquals(0, AsyncSmelting.unitsOfOutput("cobblestone"));
        assertEquals(0, AsyncSmelting.unitsOfOutput(""));
    }

    @Test
    public void foodNeedsBothSwitches() {
        assertTrue(AsyncSmelting.wantsName("cooked_mutton", true, true));
        assertFalse("user said stand there for the meat", AsyncSmelting.wantsName("cooked_mutton", true, false));
        // a plain altoclef run never turns smelting on, so it keeps standing at the smoker like it always did
        assertFalse(AsyncSmelting.wantsName("cooked_mutton", false, true));
    }

    @Test
    public void everythingElseStaysBlocking() {
        assertFalse(AsyncSmelting.wantsName("gold_ingot", true, true));
        assertFalse(AsyncSmelting.wantsName("glass", true, true));
        assertFalse(AsyncSmelting.wantsName("dried_kelp", true, true));
    }

    @Test
    public void theSmeltTasksStampTheTickTheyHadTheScreenInHand() {
        assertEquals(-1, AsyncSmelting.lastWork());
        AsyncSmelting.working(1234);
        assertEquals(1234, AsyncSmelting.lastWork());
        // a new run does not inherit it
        AsyncSmelting.clear();
        assertEquals(-1, AsyncSmelting.lastWork());
    }

    @Test
    public void coldMeatLeftInAStationIsAPickupNotACook() {
        RunState.FurnaceJob job = AsyncSmelting.strandedJob(new RunState.Pos(1, 64, 1), "OVERWORLD", "smoker", "porkchop", 6,
                "cooked_porkchop", 8, 5000, false);
        // due the moment it is made, so the next visit goes and gets it (or lights it)
        assertTrue(FurnacePlan.anyDue(List.of(job), 5000));
        assertTrue(job.stranded);
        // and it is not dinner on the way: the 5 unlit beef read as 77 units held and the stand-by waited "0 s" for them
        assertEquals(0, FurnaceJobs.pendingUnits(List.of(job)));
        assertEquals(0, FurnaceJobs.pending(List.of(job), "cooked_porkchop"));
    }

    @Test
    public void litMeatLeftInAStationIsACookWithATimer() {
        RunState.FurnaceJob job = AsyncSmelting.strandedJob(new RunState.Pos(1, 64, 1), "OVERWORLD", "smoker", "porkchop", 6,
                "cooked_porkchop", 8, 5000, true);
        assertFalse(job.stranded);
        // 6 items at 5 s each in a smoker
        assertEquals(5000 + 6 * 100, job.doneTick);
        assertEquals(48, FurnaceJobs.pendingUnits(List.of(job)));
    }

    @Test
    public void theFoodTaskSeesNothingUntilTheGamerHandsOverItsJobs() {
        assertEquals(0, AsyncSmelting.pendingFoodUnits());
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        AsyncSmelting.watchJobs(() -> jobs);
        assertEquals(0, AsyncSmelting.pendingFoodUnits());
        RunState.FurnaceJob meat = new RunState.FurnaceJob(new RunState.Pos(1, 64, 1), "OVERWORLD", "smoker", "mutton", 7,
                "cooked_mutton", 0, 700);
        meat.unitsEach = 6;
        jobs.add(meat);
        assertEquals(42, AsyncSmelting.pendingFoodUnits());
        // the run ending lets go of the list
        AsyncSmelting.clear();
        assertEquals(0, AsyncSmelting.pendingFoodUnits());
    }
}
