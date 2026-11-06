package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.IronActivity.Kind;
import adris.altoclef.tasks.speedrun.gamer.IronActivity.Scene;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class IronActivityTest {
    // plain surface mining, nothing cooking
    private static final Scene PLAIN = new Scene(false, false, true, true, false);
    // something cooking, no stand-by
    private static final Scene COOKING = new Scene(true, false, true, true, false);
    // standing by a quick smoker
    private static final Scene STANDING = new Scene(true, true, true, true, false);

    // something cooking that is not food, and the bag is under the floor (FoodPlan.starving)
    private static final Scene STARVING = new Scene(true, false, true, true, false, true);
    // the same with nothing cooking at all
    private static final Scene STARVING_PLAIN = new Scene(false, false, true, true, false, true);

    // the driver loop of IronPhase.arbiterTick with the jobs swapped for "does this one hand back a task this tick"
    private static final class Sim {
        final IronActivity a = new IronActivity();
        final List<String> lines = new ArrayList<>();
        final List<Kind> asked = new ArrayList<>();
        long now;

        Kind tick(Scene s, Set<Kind> wanting) {
            asked.clear();
            for (Kind k : a.ask(s, now)) {
                asked.add(k);
                if (wanting.contains(k)) {
                    String line = a.chose(k, now);
                    if (line != null) {
                        lines.add(line);
                    }
                    now++;
                    return k;
                }
                if (k == a.current()) {
                    a.ended(k, now, k == Kind.COAL ? "cluster mined" : null);
                }
            }
            String line = a.idle(now);
            if (line != null) {
                lines.add(line);
            }
            now++;
            return null;
        }

        Kind tick(Scene s, Kind... wanting) {
            return tick(s, wanting.length == 0 ? EnumSet.noneOf(Kind.class) : EnumSet.of(wanting[0], wanting));
        }

        void run(Scene s, int ticks, Kind... wanting) {
            for (int i = 0; i < ticks; i++) {
                tick(s, wanting);
            }
        }
    }

    // ---- the order and the eligibility

    @Test
    public void theOrderIsTheDocumentedOne() {
        assertEquals(List.of(Kind.FINISHING, Kind.GOLEM_FIGHT, Kind.STAND_BY, Kind.STATION, Kind.RUINED_PORTAL, Kind.VILLAGE_CHEST,
                Kind.BED, Kind.GOLEM_START, Kind.DIAMOND, Kind.GOLD, Kind.COAL, Kind.GRAVEL, Kind.FURNACE, Kind.PACK_UP, Kind.SURFACE, Kind.KIT),
                List.of(Kind.values()));
    }

    @Test
    public void diamondsAreAskedBeforeCoalAndGravelAndInsideTheSideJobRanges() {
        assertEquals(Kind.GOLD.ordinal(), Kind.DIAMOND.ordinal() + 1);
        assertEquals(Kind.COAL.ordinal(), Kind.GOLD.ordinal() + 1);
        assertTrue(Kind.DIAMOND.ordinal() > Kind.GOLEM_START.ordinal());
        // the quick smoker's cut-in range runs STATION..GRAVEL, and IronPhase.sideJob GOLEM_FIGHT..GRAVEL: both hold it
        assertTrue(Kind.DIAMOND.ordinal() > Kind.STATION.ordinal() && Kind.DIAMOND.ordinal() < Kind.GRAVEL.ordinal());
        assertTrue(IronActivity.preempts(Kind.STAND_BY, Kind.DIAMOND, false));
        assertEquals("diamond detour", Kind.DIAMOND.words);
        // with all three eligible and nothing held, the diamonds come out first
        List<Kind> asked = new IronActivity().ask(PLAIN, 0);
        assertTrue(asked.indexOf(Kind.DIAMOND) < asked.indexOf(Kind.COAL));
        assertTrue(asked.indexOf(Kind.COAL) < asked.indexOf(Kind.GRAVEL));
    }

    @Test
    public void aSideJobThatIsGoingKeepsTheWheelFromADiamondAndAHeldDiamondFromCoal() {
        Sim sim = new Sim();
        // nobody preempts a coal detour that is going, a diamond ore showing up halfway waits for it
        assertEquals(Kind.COAL, sim.tick(PLAIN, Kind.COAL, Kind.KIT));
        assertEquals(Kind.COAL, sim.tick(PLAIN, Kind.DIAMOND, Kind.COAL, Kind.KIT));
        Sim other = new Sim();
        assertEquals(Kind.DIAMOND, other.tick(PLAIN, Kind.COAL, Kind.DIAMOND, Kind.KIT));
        assertEquals(Kind.DIAMOND, other.tick(PLAIN, Kind.DIAMOND, Kind.COAL, Kind.KIT));
        assertEquals(Kind.DIAMOND, other.tick(PLAIN, Kind.COAL, Kind.DIAMOND, Kind.KIT));
    }

    @Test
    public void aDiamondDetourWaitsForAFullBagAndAQuietSmokerLikeCoal() {
        assertTrue(IronActivity.yields(Kind.DIAMOND));
        assertTrue(IronActivity.eligible(Kind.DIAMOND, PLAIN));
        assertTrue(IronActivity.eligible(Kind.DIAMOND, COOKING));
        assertFalse(IronActivity.eligible(Kind.DIAMOND, STARVING_PLAIN));
        assertFalse(IronActivity.eligible(Kind.DIAMOND, STANDING));
        Sim sim = new Sim();
        assertEquals(Kind.DIAMOND, sim.tick(PLAIN, Kind.DIAMOND, Kind.KIT));
        // the bag runs dry mid-detour: it lets go, the kit's head is the food
        assertEquals(Kind.KIT, sim.tick(STARVING_PLAIN, Kind.DIAMOND, Kind.KIT));
        assertEquals(Kind.DIAMOND, IronActivity.gave(Kind.DIAMOND, true));
    }

    @Test
    public void onlyFinishingIsAMustAndOnlyTheKitDoesNotCommit() {
        for (Kind k : Kind.values()) {
            assertEquals(k.name(), k == Kind.FINISHING, IronActivity.must(k));
            assertEquals(k.name(), k != Kind.KIT, IronActivity.commits(k));
        }
    }

    @Test
    public void finishingAndTheKitAreAlwaysEligible() {
        for (Scene s : List.of(PLAIN, COOKING, STANDING, new Scene(false, false, false, false, false))) {
            assertTrue(IronActivity.eligible(Kind.FINISHING, s));
            assertTrue(IronActivity.eligible(Kind.KIT, s));
        }
    }

    @Test
    public void furnaceKindsNeedSomethingCooking() {
        assertFalse(IronActivity.eligible(Kind.FURNACE, PLAIN));
        assertFalse(IronActivity.eligible(Kind.STAND_BY, PLAIN));
        assertFalse(IronActivity.eligible(Kind.PACK_UP, PLAIN));
        assertTrue(IronActivity.eligible(Kind.FURNACE, COOKING));
        assertTrue(IronActivity.eligible(Kind.PACK_UP, COOKING));
        assertFalse(IronActivity.eligible(Kind.STAND_BY, COOKING));
    }

    @Test
    public void standingByPausesEverythingThatWalksOff() {
        assertTrue(IronActivity.eligible(Kind.STAND_BY, STANDING));
        for (Kind k : List.of(Kind.STATION, Kind.RUINED_PORTAL, Kind.VILLAGE_CHEST, Kind.BED, Kind.GOLEM_START, Kind.DIAMOND, Kind.COAL, Kind.GRAVEL, Kind.FURNACE)) {
            assertFalse(k.name(), IronActivity.eligible(k, STANDING));
            assertTrue(k.name(), IronActivity.eligible(k, COOKING));
        }
        // the climb, the pack-up and the kit are what happens after the stand-by, they stay on the list
        assertTrue(IronActivity.eligible(Kind.PACK_UP, STANDING));
        assertTrue(IronActivity.eligible(Kind.SURFACE, STANDING));
    }

    @Test
    public void lootJobsOnlyWhereThePhaseLoots() {
        Scene gather = new Scene(false, false, true, false, false);
        for (Kind k : List.of(Kind.RUINED_PORTAL, Kind.VILLAGE_CHEST, Kind.GOLEM_START, Kind.GOLEM_FIGHT)) {
            assertFalse(k.name(), IronActivity.eligible(k, gather));
        }
        assertTrue(IronActivity.eligible(Kind.BED, gather));
        assertTrue(IronActivity.eligible(Kind.STATION, gather));
    }

    @Test
    public void aGolemFightIsAskedAsAFightNotAStart() {
        Scene fighting = new Scene(false, false, true, true, true);
        assertTrue(IronActivity.eligible(Kind.GOLEM_FIGHT, fighting));
        assertFalse(IronActivity.eligible(Kind.GOLEM_START, fighting));
        assertFalse(IronActivity.eligible(Kind.GOLEM_FIGHT, PLAIN));
        assertTrue(IronActivity.eligible(Kind.GOLEM_START, PLAIN));
    }

    @Test
    public void surfaceAndPackUpNeedAHead() {
        Scene noHead = new Scene(true, false, false, true, false);
        assertFalse(IronActivity.eligible(Kind.SURFACE, noHead));
        assertFalse(IronActivity.eligible(Kind.PACK_UP, noHead));
    }

    @Test
    public void freeChoiceAsksTopFirst() {
        Sim sim = new Sim();
        assertEquals(Kind.STATION, sim.tick(COOKING, Kind.STATION, Kind.COAL, Kind.FURNACE, Kind.KIT));
        assertEquals(List.of(Kind.FINISHING, Kind.STATION), sim.asked);
    }

    @Test
    public void nobodyIsAskedTwiceInATick() {
        IronActivity a = new IronActivity();
        a.chose(Kind.COAL, 0);
        List<Kind> ask = a.ask(COOKING, 1);
        assertEquals(ask.size(), EnumSet.copyOf(ask).size());
        // held first: the must above it, then it, then the open choice
        assertEquals(Kind.FINISHING, ask.get(0));
        assertEquals(Kind.COAL, ask.get(1));
    }

    // ---- commitment

    @Test
    public void aSideJobHoldsUntilItsJobSaysDone() {
        Sim sim = new Sim();
        sim.tick(PLAIN, Kind.COAL, Kind.KIT);
        // a village chest and a station show up mid detour: not asked while coal holds
        for (int i = 0; i < 50; i++) {
            assertEquals(Kind.COAL, sim.tick(PLAIN, Kind.COAL, Kind.VILLAGE_CHEST, Kind.STATION, Kind.KIT));
            assertFalse(sim.asked.contains(Kind.VILLAGE_CHEST));
            assertFalse(sim.asked.contains(Kind.STATION));
        }
        // coal hands back null: the open choice goes the same tick, top first
        assertEquals(Kind.STATION, sim.tick(PLAIN, Kind.VILLAGE_CHEST, Kind.STATION, Kind.KIT));
        assertEquals("activity: coal detour -> station pickup (coal detour done: cluster mined)", sim.lines.get(sim.lines.size() - 1));
    }

    @Test
    public void theKitGivesWayToAnythingEligible() {
        Sim sim = new Sim();
        sim.run(PLAIN, 5, Kind.KIT);
        assertEquals(Kind.BED, sim.tick(PLAIN, Kind.BED, Kind.KIT));
        assertEquals("activity: kit -> village bed (the kit gives way)", sim.lines.get(sim.lines.size() - 1));
    }

    @Test
    public void finishingPreemptsAnyHeldActivityAndTheOtherOneDoesNotRest() {
        Sim sim = new Sim();
        sim.tick(PLAIN, Kind.SURFACE);
        assertEquals(Kind.FINISHING, sim.tick(PLAIN, Kind.FINISHING, Kind.SURFACE));
        assertTrue(sim.lines.get(sim.lines.size() - 1).contains("must"));
        // the pickup is done: the climb comes straight back, it was cut, it did not end
        assertEquals(Kind.SURFACE, sim.tick(PLAIN, Kind.SURFACE, Kind.KIT));
    }

    @Test
    public void oneLinePerChangeAndNoneWhileItHolds() {
        Sim sim = new Sim();
        sim.run(PLAIN, 30, Kind.KIT);
        assertEquals(1, sim.lines.size());
        sim.run(PLAIN, 30, Kind.COAL, Kind.KIT);
        assertEquals(2, sim.lines.size());
    }

    @Test
    public void noFlipAtTheBoundaryOfAFlickeringJob() {
        // a job whose own start test wobbles every other tick: it gets one start, then sits out the rest after each end
        Sim sim = new Sim();
        int starts = 0;
        Kind last = null;
        for (int i = 0; i < 400; i++) {
            Kind got = sim.tick(PLAIN, i % 2 == 0 ? EnumSet.of(Kind.STATION, Kind.KIT) : EnumSet.of(Kind.KIT));
            if (got == Kind.STATION && last != Kind.STATION) {
                starts++;
            }
            last = got;
        }
        // 400 ticks, a start at most every REST_TICKS + 1, instead of 200
        assertTrue("starts " + starts, starts <= 400 / IronActivity.REST_TICKS + 1);
    }

    @Test
    public void restIsOnlyForWhatEndedOnItsOwn() {
        IronActivity a = new IronActivity();
        a.chose(Kind.COAL, 0);
        a.ended(Kind.COAL, 10, "cluster mined");
        assertTrue(a.resting(Kind.COAL, 10 + IronActivity.REST_TICKS - 1));
        assertFalse(a.resting(Kind.COAL, 10 + IronActivity.REST_TICKS));
        assertFalse(a.ask(COOKING, 20).contains(Kind.COAL));
        assertTrue(a.ask(COOKING, 10 + IronActivity.REST_TICKS).contains(Kind.COAL));
        // the kit and the must never rest
        a.chose(Kind.KIT, 60);
        a.ended(Kind.KIT, 61, null);
        assertFalse(a.resting(Kind.KIT, 62));
    }

    @Test
    public void theSameJobStraightBackIsNotALine() {
        IronActivity a = new IronActivity();
        assertEquals("activity: nothing -> furnace trip (first pick)", a.chose(Kind.FURNACE, 0));
        assertNull(a.chose(Kind.FURNACE, 1));
    }

    @Test
    public void idleIsOneLine() {
        Sim sim = new Sim();
        sim.tick(PLAIN, Kind.KIT);
        sim.run(PLAIN, 10);
        assertEquals(2, sim.lines.size());
        assertEquals("activity: kit -> nothing (kit done)", sim.lines.get(1));
        assertNull(sim.a.current());
    }

    @Test
    public void aGolemFightTheActivityForgotIsHeld() {
        IronActivity a = new IronActivity();
        a.chose(Kind.SURFACE, 0);
        List<Kind> ask = a.ask(new Scene(false, false, true, true, true), 1);
        assertEquals(List.of(Kind.FINISHING, Kind.GOLEM_FIGHT, Kind.SURFACE), ask.subList(0, 3));
    }

    // ---- the furnace collect preempts only when it must

    @Test
    public void aDueCollectTakesTheWheelFromTheKit() {
        Sim sim = new Sim();
        sim.run(COOKING, 5, Kind.KIT);
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
    }

    @Test
    public void aDueCollectWaitsForAHeldSideJob() {
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.VILLAGE_CHEST);
        for (int i = 0; i < 100; i++) {
            assertEquals(Kind.VILLAGE_CHEST, sim.tick(COOKING, Kind.VILLAGE_CHEST, Kind.FURNACE, Kind.KIT));
        }
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
    }

    @Test
    public void aFurnaceTripHoldsAgainstSideJobs() {
        // the old cooking tick asked the side jobs before the trip under way, so a station or a chest could pull us off mid walk
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.FURNACE);
        for (int i = 0; i < 60; i++) {
            assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.STATION, Kind.VILLAGE_CHEST, Kind.KIT));
        }
    }

    @Test
    public void aClimbHoldsAgainstADueCollect() {
        // turning round mid climb to walk back down to the furnace is what PackUp exists to avoid
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.SURFACE);
        for (int i = 0; i < 60; i++) {
            assertEquals(Kind.SURFACE, sim.tick(COOKING, Kind.SURFACE, Kind.FURNACE, Kind.KIT));
        }
    }

    @Test
    public void packUpGoesBeforeTheClimb() {
        Sim sim = new Sim();
        sim.run(COOKING, 3, Kind.KIT);
        assertEquals(Kind.PACK_UP, sim.tick(COOKING, Kind.PACK_UP, Kind.SURFACE, Kind.KIT));
        for (int i = 0; i < 20; i++) {
            assertEquals(Kind.PACK_UP, sim.tick(COOKING, Kind.PACK_UP, Kind.SURFACE, Kind.KIT));
        }
        assertEquals(Kind.SURFACE, sim.tick(COOKING, Kind.SURFACE, Kind.KIT));
    }

    // ---- the loops from before

    @Test
    public void coalDetourVsTheFurnaceDue() {
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.COAL);
        // the furnace goes due: the trip wants the wheel, but coal holds until its own never rule (furnace due) ends it
        assertEquals(Kind.COAL, sim.tick(COOKING, Kind.COAL, Kind.FURNACE, Kind.KIT));
        // coal said done: straight to the trip on the same tick, no kit tick in between to start the mining task again
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
        // coal's start test says yes again while the trip runs: not asked
        for (int i = 0; i < 30; i++) {
            assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.COAL, Kind.FURNACE, Kind.KIT));
        }
        sim.tick(COOKING, Kind.KIT);
        assertTrue(sim.lines.contains("activity: coal detour -> furnace trip (coal detour done: cluster mined)"));
    }

    @Test
    public void standByVsTheEarlyPick() {
        // a quick smoker is stood at; the early pick's interrupt makes the iron furnace a collect. the stand-by keeps the wheel
        // to its own end (FurnacePlan's budget), then the collect goes
        Sim sim = new Sim();
        sim.tick(STANDING, Kind.STAND_BY);
        for (int i = 0; i < 80; i++) {
            assertEquals(Kind.STAND_BY, sim.tick(STANDING, Kind.STAND_BY, Kind.FURNACE, Kind.KIT));
            assertFalse(sim.asked.contains(Kind.FURNACE));
        }
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
    }

    @Test
    public void theEarlyPickCutsTheMiningNeedShort() {
        // the mining need is the kit, the floor: the interrupt's collect gets the wheel the tick it shows up
        Sim sim = new Sim();
        sim.run(COOKING, 200, Kind.KIT);
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
    }

    @Test
    public void smokerStandByThatWantsBackRightAwayRests() {
        // the smoker/iron loop shape: the stand-by ends, the bot is a tick away from the iron, and the smoker wants
        // the wheel back. it sits out the rest instead of trading the head every tick
        Sim sim = new Sim();
        sim.tick(STANDING, Kind.STAND_BY);
        sim.tick(STANDING, Kind.KIT);
        Kind got;
        int standBys = 0;
        for (int i = 0; i < IronActivity.REST_TICKS - 2; i++) {
            got = sim.tick(STANDING, i % 2 == 0 ? EnumSet.of(Kind.STAND_BY, Kind.KIT) : EnumSet.of(Kind.KIT));
            if (got == Kind.STAND_BY) {
                standBys++;
            }
        }
        assertEquals(0, standBys);
    }

    @Test
    public void foodTopUpVsTheKit() {
        // the food top-up runs as the kit's head (FoodPlan decides that). a smoker that gets picked up and placed again while the
        // top-up runs shows up here as the station pickup wanting the wheel every other tick: it gets it at most once per rest, and a pickup that
        // started holds against the kit instead of handing back on the next tick
        Sim sim = new Sim();
        sim.run(PLAIN, 10, Kind.KIT);
        int switches = 0;
        Kind last = Kind.KIT;
        for (int i = 0; i < 200; i++) {
            Kind got = sim.tick(PLAIN, i % 2 == 0 ? EnumSet.of(Kind.STATION, Kind.KIT) : EnumSet.of(Kind.KIT));
            if (got != last) {
                switches++;
            }
            last = got;
        }
        assertTrue("switches " + switches, switches <= 2 * (200 / IronActivity.REST_TICKS + 1));
        // and a pickup that keeps wanting the wheel keeps it, the top-up waits
        sim.now += IronActivity.REST_TICKS;
        assertEquals(Kind.STATION, sim.tick(PLAIN, Kind.STATION, Kind.KIT));
        for (int i = 0; i < 40; i++) {
            assertEquals(Kind.STATION, sim.tick(PLAIN, Kind.STATION, Kind.KIT));
        }
    }

    // ---- an empty bag eats first

    @Test
    public void aDueIronTripDoesNotTakeTheWheelFromAnEmptyBag() {
        Sim sim = new Sim();
        sim.run(STARVING, 5, Kind.KIT);
        assertEquals(Kind.KIT, sim.tick(STARVING, Kind.FURNACE, Kind.KIT));
        assertFalse(sim.asked.contains(Kind.FURNACE));
        // nothing was asked of the furnace and nothing was said about a change either: the kit just kept going
        assertTrue(sim.lines.toString(), sim.lines.size() == 1);
        assertFalse(IronActivity.eligible(Kind.FURNACE, STARVING));
        // the climb's pack-up is not a trip that gives way, it has its own once per furnace book
        assertTrue(IronActivity.eligible(Kind.PACK_UP, STARVING));
    }

    @Test
    public void aFedBagKeepsTheIronTripOverTheKit() {
        // (and a trip for food in a station is a scene that is not starving either, FoodPlanTest pins that half)
        Sim sim = new Sim();
        sim.run(COOKING, 5, Kind.KIT);
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
        assertTrue(IronActivity.eligible(Kind.FURNACE, COOKING));
        assertTrue(IronActivity.eligible(Kind.PACK_UP, COOKING));
    }

    @Test
    public void aTripOnItsWayLetsGoWhenTheBagRunsDry() {
        Sim sim = new Sim();
        sim.run(COOKING, 3, Kind.KIT);
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
        // the bag goes under the floor mid-walk: the trip is not asked any more, the kit has the wheel
        assertEquals(Kind.KIT, sim.tick(STARVING, Kind.FURNACE, Kind.KIT));
        assertEquals(Kind.KIT, sim.tick(STARVING, Kind.FURNACE, Kind.KIT));
        assertFalse(sim.asked.contains(Kind.FURNACE));
        // and when the food is back the trip takes it again, no rest owed: it never ended on its own
        assertEquals(Kind.FURNACE, sim.tick(COOKING, Kind.FURNACE, Kind.KIT));
    }

    @Test
    public void theDetoursThatAreNotFoodWaitForAnEmptyBagToo() {
        for (Kind k : List.of(Kind.DIAMOND, Kind.COAL, Kind.GRAVEL, Kind.GOLEM_START)) {
            assertTrue(k.name(), IronActivity.eligible(k, PLAIN));
            assertFalse(k.name(), IronActivity.eligible(k, STARVING_PLAIN));
        }
        // a coal detour that is going lets go, a golem hunt is only kept from starting
        Sim sim = new Sim();
        assertEquals(Kind.COAL, sim.tick(PLAIN, Kind.COAL, Kind.KIT));
        assertEquals(Kind.KIT, sim.tick(STARVING_PLAIN, Kind.COAL, Kind.KIT));
        assertTrue(IronActivity.yields(Kind.COAL));
        assertFalse(IronActivity.yields(Kind.GOLEM_START));
    }

    @Test
    public void lootBedsAndStationPickupsStayOnForAnEmptyBag() {
        // a chest can be the food, a bed is seconds, and the station pickup is the smoker coming down (or the table the food needs)
        for (Kind k : List.of(Kind.STATION, Kind.RUINED_PORTAL, Kind.VILLAGE_CHEST, Kind.BED)) {
            assertTrue(k.name(), IronActivity.eligible(k, STARVING_PLAIN));
            assertFalse(k.name(), IronActivity.yields(k));
        }
        assertEquals(Kind.FINISHING, new Sim().tick(STARVING, Kind.FINISHING, Kind.KIT));
    }

    @Test
    public void aGolemFightThatIsGoingIsNeverDroppedForTheBag() {
        Scene fight = new Scene(false, false, true, true, true, true);
        Sim sim = new Sim();
        assertEquals(Kind.GOLEM_FIGHT, sim.tick(fight, Kind.GOLEM_FIGHT, Kind.KIT));
        assertEquals(Kind.GOLEM_FIGHT, sim.tick(fight, Kind.GOLEM_FIGHT, Kind.KIT));
    }

    @Test
    public void theHungerLineIsAboutTheWalkThatLostTheWheel() {
        // the one that held it, else the trip that was due, else nobody lost anything and there is nothing to say
        assertEquals(Kind.COAL, IronActivity.gave(Kind.COAL, true));
        assertEquals(Kind.FURNACE, IronActivity.gave(Kind.FURNACE, false));
        assertEquals(Kind.FURNACE, IronActivity.gave(Kind.KIT, true));
        assertEquals(Kind.FURNACE, IronActivity.gave(null, true));
        assertNull(IronActivity.gave(null, false));
        assertNull(IronActivity.gave(Kind.KIT, false));
        assertNull(IronActivity.gave(Kind.BED, false));
    }

    @Test
    public void theHungerLineSaysWhatWaits() {
        assertEquals("activity: food first, 0 units held under the floor of 24, the furnace trip waits",
                IronActivity.starvingLine(Kind.FURNACE, 0, 24));
    }

    @Test
    public void aQuickSmokerCutsInOnAHeldSideJob() {
        // a coal detour that started before the smoker went quick would eat the stand-by otherwise
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.COAL);
        assertEquals(Kind.STAND_BY, sim.tick(STANDING, Kind.COAL, Kind.STAND_BY, Kind.KIT));
        assertEquals("activity: coal detour -> smoker stand-by (a quick smoker comes before the coal detour)", sim.lines.get(1));
        for (Kind side : List.of(Kind.STATION, Kind.RUINED_PORTAL, Kind.VILLAGE_CHEST, Kind.BED, Kind.GOLEM_START, Kind.DIAMOND, Kind.COAL, Kind.GRAVEL)) {
            assertTrue(side.name(), IronActivity.preempts(Kind.STAND_BY, side, false));
        }
    }

    @Test
    public void aQuickSmokerDoesNotCutInOnTripsClimbsOrFights() {
        for (Kind held : List.of(Kind.FINISHING, Kind.GOLEM_FIGHT, Kind.FURNACE, Kind.PACK_UP, Kind.SURFACE)) {
            assertFalse(held.name(), IronActivity.preempts(Kind.STAND_BY, held, false));
        }
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.SURFACE);
        assertEquals(Kind.SURFACE, sim.tick(STANDING, Kind.SURFACE, Kind.STAND_BY, Kind.KIT));
        // and nothing else cuts in on anything, finishing aside
        for (Kind k : Kind.values()) {
            for (Kind held : Kind.values()) {
                if (k != Kind.FINISHING && k != Kind.STAND_BY) {
                    assertFalse(k + " on " + held, IronActivity.preempts(k, held, false));
                }
            }
        }
    }

    @Test
    public void aQuickSmokerNeverPullsUsOffAGolemPillar() {
        assertFalse(IronActivity.preempts(Kind.STAND_BY, Kind.GOLEM_START, true));
        Scene fightingStanding = new Scene(true, true, true, true, true);
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.GOLEM_START);
        for (int i = 0; i < 40; i++) {
            assertEquals(Kind.GOLEM_START, sim.tick(fightingStanding, Kind.GOLEM_START, Kind.STAND_BY, Kind.KIT));
            assertFalse(sim.asked.contains(Kind.STAND_BY));
        }
    }

    @Test
    public void aPackUpCutByTheFinishingComesStraightBack() {
        // the visit emptied furnace A and handed over its pickup: finishing takes it like a must, no rest for the pack-up, so a
        // second furnace down here is fetched before the climb
        Sim sim = new Sim();
        sim.tick(COOKING, Kind.PACK_UP);
        assertEquals(Kind.FINISHING, sim.tick(COOKING, Kind.FINISHING, Kind.PACK_UP, Kind.SURFACE, Kind.KIT));
        assertEquals(Kind.PACK_UP, sim.tick(COOKING, Kind.PACK_UP, Kind.SURFACE, Kind.KIT));
    }

    @Test
    public void afterNothingTheNextPickIsARestart() {
        Sim sim = new Sim();
        sim.tick(PLAIN, Kind.KIT);
        sim.tick(PLAIN);
        sim.tick(PLAIN, Kind.KIT);
        assertEquals("activity: nothing -> kit (something to do again)", sim.lines.get(2));
    }

    @Test
    public void resetForgetsEverything() {
        IronActivity a = new IronActivity();
        a.chose(Kind.COAL, 0);
        a.ended(Kind.COAL, 1, "x");
        a.reset();
        assertNull(a.current());
        assertFalse(a.resting(Kind.COAL, 2));
        assertEquals("activity: nothing -> kit (first pick)", a.chose(Kind.KIT, 3));
    }

    @Test
    public void theDetailNamesWhoWasAskedWhoDeclinedAndTheScene() {
        List<Kind> asked = List.of(Kind.COAL, Kind.FURNACE, Kind.KIT);
        Scene s = new Scene(true, false, true, false, false, true);
        assertEquals("cands=[coal,furnace,kit] declined=[coal,furnace] scene=jobs:1,standby:0,head:1,loots:0,golem:0,starving:1",
                IronActivity.detail(asked, 2, s));
    }

    @Test
    public void theDetailIsAppendedAndTheOldLineIsLeftAlone() {
        IronActivity a = new IronActivity();
        String line = a.chose(Kind.KIT, 0);
        assertEquals("activity: nothing -> kit (first pick)", line);
        String full = IronActivity.withDetail(line, List.of(Kind.KIT), 0, PLAIN);
        assertTrue(full.startsWith(line + " cands=[kit] declined=[] scene="));
    }

    @Test
    public void noLineMeansNoDetail() {
        assertNull(IronActivity.withDetail(null, List.of(Kind.KIT), 0, PLAIN));
    }

    @Test
    public void everyoneDecliningListsEveryone() {
        assertTrue(IronActivity.detail(List.of(Kind.COAL, Kind.KIT), 2, PLAIN).contains("declined=[coal,kit]"));
    }

    @Test
    public void armorInTheBagGoesOnBeforeAFurnaceTripStarts() {
        Scene wear = new Scene(true, false, true, true, false, false, true);
        assertTrue(IronActivity.eligible(Kind.FURNACE, COOKING));
        assertFalse(IronActivity.eligible(Kind.FURNACE, wear));
        // only the trip start is held back, the kit is still the floor and the side jobs are untouched
        assertTrue(IronActivity.eligible(Kind.KIT, wear));
        assertTrue(IronActivity.eligible(Kind.COAL, wear));
        List<Kind> order = new IronActivity().ask(wear, 0);
        assertFalse(order.contains(Kind.FURNACE));
        assertEquals(Kind.KIT, order.get(order.size() - 1));
    }

    @Test
    public void wearFirstNeedsAHeldPieceAndNoHostileClose() {
        assertTrue(IronActivity.wearFirst(true, true, false));
        assertFalse(IronActivity.wearFirst(true, false, false));
        assertFalse(IronActivity.wearFirst(false, true, false));
        assertFalse(IronActivity.wearFirst(true, true, true));
    }

}
