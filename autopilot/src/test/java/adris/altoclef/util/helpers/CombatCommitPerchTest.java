package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

// the perch: stack up where we stand and hit the melee chasers from the top. the rules are pure, so this is all numbers
public class CombatCommitPerchTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final double FEET = 64;
    private static final double PIGLIN = 1.95;
    private static final double HOGLIN = 1.4;

    // an angry zombified piglin, flagged fast
    private static Foe piglin(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER, true);
    }

    private static Foe hoglin(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.HEAVY);
    }

    private static Foe plain(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit);
    }

    private static Map<Integer, Double> heights(double height, int... ids) {
        Map<Integer, Double> out = new HashMap<>();
        for (int id : ids) out.put(id, height);
        return out;
    }

    private static PerchRules.Facts facts(Map<Integer, Double> heights) {
        return new PerchRules.Facts(true, FEET, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights);
    }

    private static PerchRules.Facts on(double feetY, Map<Integer, Double> heights) {
        return new PerchRules.Facts(true, feetY, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights);
    }

    private static Tick tick(long now, float health, Foe... foes) {
        return new Tick(now, health, 0.5, 0.5, List.of(foes), null);
    }

    private static Foe[] pack() {
        return new Foe[]{piglin(1, 2, NEVER), piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER)};
    }

    // the same three with the room the pillar needs to start
    private static Foe[] farPack() {
        return new Foe[]{piglin(1, 4, NEVER), piglin(2, 4.4, NEVER), piglin(3, 4.8, NEVER)};
    }

    // the crowd run starts with them on top of us (the old rules), the perch comes once we have a few blocks on them
    private static Event perchStart(CombatCommit c, long now) {
        assertEquals(Event.RUN_START, c.step(tick(now - 1, 20, pack())));
        return c.step(tick(now, 20, farPack()));
    }

    // ---- the numbers

    @Test
    public void heightsFollowTheTallestChaserAndTheStepBeside() {
        assertEquals(3, PerchRules.height(PIGLIN, 0));
        assertEquals(2, PerchRules.height(HOGLIN, 0));
        // a block step beside us lifts the piglin's head to 3.1 and the pillar past what we stack
        assertEquals(4, PerchRules.height(PIGLIN, 1));
        // never under 2, a pillar of one is not over a baby
        assertEquals(2, PerchRules.height(0.9, 0));
        assertEquals(3, PerchRules.height(2.4, 0));
    }

    @Test
    public void reachFromTheTopCoversTheShortestFoeAtTheFoot() {
        assertTrue(PerchRules.reaches(3, PIGLIN));
        assertTrue(PerchRules.reaches(2, HOGLIN));
        // a hoglin at the foot of a pillar made for a piglin is out of arm's reach
        assertTrue(!PerchRules.reaches(3, HOGLIN));
    }

    @Test
    public void perchHeightSaysNoWhenTheSpotOrTheCrowdIsWrong() {
        List<Foe> piglins = List.of(piglin(1, 2, NEVER), piglin(2, 3, NEVER));
        Map<Integer, Double> h = heights(PIGLIN, 1, 2);
        assertEquals(3, PerchRules.perchHeight(facts(h), piglins));
        // a step beside us
        assertEquals(0, PerchRules.perchHeight(new PerchRules.Facts(true, FEET, 1, 8, 6, true, 0, PerchRules.Facts.NEVER, false, h), piglins));
        // not standing on anything, too few blocks, no room above
        assertEquals(0, PerchRules.perchHeight(new PerchRules.Facts(false, FEET, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, h), piglins));
        assertEquals(0, PerchRules.perchHeight(new PerchRules.Facts(true, FEET, 0, 2, 6, true, 0, PerchRules.Facts.NEVER, false, h), piglins));
        assertEquals(0, PerchRules.perchHeight(new PerchRules.Facts(true, FEET, 0, 8, 4, true, 0, PerchRules.Facts.NEVER, false, h), piglins));
        // a creeper or a mob we have no height for within 8
        assertEquals(0, PerchRules.perchHeight(facts(h), List.of(piglin(1, 2, NEVER), creeper(9, 6))));
        assertEquals(0, PerchRules.perchHeight(facts(h), List.of(piglin(1, 2, NEVER), skeleton(9, 7, NEVER))));
        // one past 8 is not a chaser
        assertEquals(3, PerchRules.perchHeight(facts(h), List.of(piglin(1, 2, NEVER), skeleton(9, 12, NEVER))));
        // a hoglin and a piglin together: the pillar for the piglin leaves the hoglin out of reach
        Map<Integer, Double> mixed = new HashMap<>();
        mixed.put(1, PIGLIN);
        mixed.put(2, HOGLIN);
        assertEquals(0, PerchRules.perchHeight(facts(mixed), List.of(piglin(1, 2, NEVER), hoglin(2, 3))));
        // nobody at all
        assertEquals(0, PerchRules.perchHeight(facts(h), List.of()));
    }

    // ---- the triggers

    @Test
    public void aRunThatStartsFromAFastPackPerchesInsteadOfJogging() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.PERCH_START, perchStart(c, NOW));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.PERCH, c.why());
        assertEquals(3, c.perchHeight());
        assertTrue(c.perching());
        String note = c.takePerchNote();
        assertNotNull(note);
        assertTrue(note, note.startsWith("perch start:"));
        assertEquals(-1, c.targetId());
    }

    @Test
    public void withoutFactsItIsTheOldRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, pack())));
        assertEquals(Mode.RUN, c.mode());
    }

    // a pack of fast piglins hitting at +20, +220 and +240 ticks, hp 8.7 at +280: the perch is never given up for the hp
    @Test
    public void thePiglinChaseStaysAPerchThroughTheHits() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.PERCH_START, perchStart(c, NOW));
        long[] hits = {0, 20, 220, 240};
        float hp = 20;
        for (long dt = 1; dt <= 280; dt++) {
            for (long hit : hits) {
                if (dt == hit) hp -= 3.9f;
            }
            if (dt == 100) hp = 15;
            if (dt == 270) hp = 8.7f;
            long now = NOW + dt;
            Foe one = piglin(1, 2, lastHit(hits, dt));
            assertEquals("tick " + dt, Event.NONE, c.step(tick(now, hp, one, piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER))));
            assertEquals(Mode.FIGHT, c.mode());
            assertEquals(Why.PERCH, c.why());
        }
    }

    private static long lastHit(long[] hits, long dt) {
        long last = -1;
        for (long hit : hits) {
            if (hit <= dt) last = hit;
        }
        return last < 0 ? NEVER : dt - last;
    }

    // the same chase with no perch (no blocks): hits after the grace are counted, and the worst of them stays in mind, so at 8.7 hp the
    // run does not turn round on a pack it cannot win against
    @Test
    public void theWorstHitOfTheChaseIsRememberedPastTheHitWindow() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, pack())));
        long[] hits = {0, 20, 220, 240};
        float hp = 20;
        Event last = Event.NONE;
        for (long dt = 1; dt <= 290; dt++) {
            for (long hit : hits) {
                if (dt == hit) hp -= hit == 220 ? 3.9f : 2.4f;
            }
            if (dt == 100) hp = 15;
            if (dt == 219) hp = 15;
            if (dt == 220) hp = 11.1f;
            if (dt == 240) hp = 8.7f;
            // moving, so never cornered
            double x = (dt / 10) % 2 == 0 ? 0 : 2;
            Foe one = piglin(1, 2, lastHit(hits, dt));
            last = c.step(new Tick(NOW + dt, hp, x, 0, List.of(one, piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER)), null));
        }
        assertEquals(Event.NONE, last);
        assertEquals(Mode.RUN, c.mode());
    }

    // hoglins are not a perch species any more (the brain gives them no height), so the old rules take over
    @Test
    public void aHoglinInTheWayAtFullHpDoesNotPerch() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(HOGLIN)));
        Foe hog = hoglin(1, 5);
        Tick t = new Tick(NOW, 20, 0.5, 0.5, List.of(hog), null, new Way(Set.of(1), true, FEET), true, false);
        assertTrue(c.step(t) != Event.PERCH_START);
        assertTrue(c.why() != Why.PERCH);
    }

    // the cap with a hoglin 6 away at full hp: no perch for a hoglin, the old cap rules
    @Test
    public void theRunCapWithAHoglinWithinEightDoesNotPerch() {
        CombatCommit c = new CombatCommit();
        Foe hog = hoglin(1, 5);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, hog)));
        for (long dt = 1; dt < CombatCommit.RUN_CAP; dt++) {
            double x = (dt / 10) % 2 == 0 ? 0 : 2;
            assertEquals(Event.NONE, c.step(new Tick(NOW + dt, 7, x, 0, List.of(hog), null)));
        }
        c.perchFacts(facts(heights(HOGLIN)));
        assertTrue(c.step(tick(NOW + CombatCommit.RUN_CAP, 20, hoglin(1, 6))) != Event.PERCH_START);
        assertTrue(c.why() != Why.PERCH);
    }

    @Test
    public void aChaserAtOurElbowOrAFreshHitIsNoRoomToBuild() {
        assertTrue(PerchRules.roomToStart(List.of(piglin(1, 4, NEVER), piglin(2, 7, NEVER))));
        assertTrue(!PerchRules.roomToStart(List.of(piglin(1, 3.9, NEVER))));
        assertTrue(!PerchRules.roomToStart(List.of(piglin(1, 5, PerchRules.START_QUIET))));
        assertTrue(PerchRules.roomToStart(List.of(piglin(1, 5, PerchRules.START_QUIET + 1))));
        // far ones do not count, and a shooter's hit is the shot rule's business
        assertTrue(PerchRules.roomToStart(List.of(piglin(1, 12, 0), skeleton(9, 7, 0))));
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, piglin(1, 2, NEVER), piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER))));
        // room but a swing landed ten ticks ago, then room and a quiet second
        CombatCommit d = new CombatCommit();
        d.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.RUN_START, d.step(tick(NOW - 1, 20, pack())));
        assertTrue(d.step(tick(NOW, 20, piglin(1, 5, 10), piglin(2, 4.4, NEVER), piglin(3, 4.8, NEVER))) != Event.PERCH_START);
        CombatCommit e = new CombatCommit();
        e.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.PERCH_START, perchStart(e, NOW));
    }

    @Test
    public void hoglinsZoglinsAndWitherSkeletonsAreNotPerchSpecies() {
        for (String type : List.of("hoglin", "zoglin", "wither_skeleton")) assertTrue(type, !PerchRules.species(type));
        for (String type : List.of("zombified_piglin", "zombie", "zombie_villager", "husk")) assertTrue(type, PerchRules.species(type));
    }

    @Test
    public void aShooterThatHitUsLatelyIsNoPerch() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(new PerchRules.Facts(true, FEET, 0, 8, 6, true, 1, NOW - 10, false, heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, pack())));
        CombatCommit d = new CombatCommit();
        d.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        // a shooter past the chasers' range that hit us just now
        assertEquals(Event.RUN_START, d.step(tick(NOW, 20, piglin(1, 2, NEVER), piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER), skeleton(9, 14, 5))));
    }

    @Test
    public void aSlowCrowdWithNoHitsYetIsStillTheOldRun() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(1.95, 1, 2, 3)));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, plain(1, 2), plain(2, 2.4), plain(3, 2.8))));
    }

    // ---- while perched

    private static CombatCommit perched() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.PERCH_START, perchStart(c, NOW));
        return c;
    }

    // the pillar is built: feet 3 up
    private static void top(CombatCommit c, long now) {
        c.perchFacts(on(FEET + 3, heights(PIGLIN, 1, 2, 3)));
        assertEquals(Event.NONE, c.step(tick(now, 20, pack())));
    }

    @Test
    public void nobodyWithinSixForAHundredTicksEndsIt() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        long start = NOW + 2;
        c.perchFacts(on(FEET + 3, heights(PIGLIN, 1, 2, 3)));
        for (long now = start; now < start + PerchRules.QUIET_TICKS; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20)));
        }
        assertEquals(Event.PERCH_END, c.step(tick(start + PerchRules.QUIET_TICKS, 20)));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.takePerchNote().startsWith("perch leave:"));
    }

    @Test
    public void somebodyWithinSixKeepsItUp() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        for (long now = NOW + 2; now < NOW + 400; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 20, piglin(1, 5.5, NEVER))));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theCapLeavesOnlyWithNobodyWithinFourAndOtherwiseStartsOver() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        long cap = NOW + PerchRules.CAP_TICKS;
        // somebody at the foot at the cap: the cap starts over
        for (long now = NOW + 2; now < cap; now += 5) {
            assertEquals(Event.NONE, c.step(tick(now, 20, piglin(1, 2, NEVER))));
        }
        assertEquals(Event.NONE, c.step(tick(cap, 20, piglin(1, 2, NEVER))));
        assertEquals(Mode.FIGHT, c.mode());
        // a cap later with them at five blocks (not quiet, not at the foot): over
        long second = cap + PerchRules.CAP_TICKS;
        for (long now = cap + 5; now < second; now += 5) {
            assertEquals(Event.NONE, c.step(tick(now, 20, piglin(1, 5, NEVER))));
        }
        assertEquals(Event.PERCH_END, c.step(tick(second, 20, piglin(1, 5, NEVER))));
    }

    @Test
    public void aMeleeHitOnTopMeansItFailedAndTheOldRulesCome() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        assertEquals(Event.NONE, c.step(tick(NOW + 10, 20, pack())));
        // a swing that landed 3 ticks ago, well after we got to the top
        Event e = c.step(tick(NOW + 20, 16, piglin(1, 2, 3), piglin(2, 2.4, NEVER), piglin(3, 2.8, NEVER)));
        assertEquals(Event.PERCH_END, e == Event.PERCH_END ? e : c.endedFirst());
        assertTrue(c.takePerchNote().startsWith("perch fail:"));
    }

    @Test
    public void aShooterHittingUsTwiceEndsIt() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        c.perchFacts(new PerchRules.Facts(true, FEET + 3, 0, 8, 6, true, 2, NOW + 5, false, heights(PIGLIN, 1, 2, 3)));
        Event e = c.step(tick(NOW + 6, 20, pack()));
        assertEquals(Event.PERCH_END, e == Event.PERCH_END ? e : c.endedFirst());
        assertTrue(c.takePerchNote().startsWith("perch fail:"));
    }

    @Test
    public void aPillarThatWentNowhereIsAFailedPerch() {
        CombatCommit c = perched();
        c.perchFacts(new PerchRules.Facts(true, FEET, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, true, heights(PIGLIN, 1, 2, 3)));
        Event e = c.step(tick(NOW + 31, 20, pack()));
        assertEquals(Event.PERCH_END, e == Event.PERCH_END ? e : c.endedFirst());
        assertTrue(c.takePerchNote().startsWith("perch fail:"));
    }

    @Test
    public void knockedOffTheColumnIsAFailedPerch() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        c.perchFacts(on(FEET, heights(PIGLIN, 1, 2, 3)));
        Event e = c.step(tick(NOW + 5, 20, pack()));
        assertEquals(Event.PERCH_END, e == Event.PERCH_END ? e : c.endedFirst());
        assertTrue(c.takePerchNote().startsWith("perch fail:"));
    }

    @Test
    public void aFailedPerchIsNotTriedAgainRightAway() {
        CombatCommit c = perched();
        c.perchFacts(new PerchRules.Facts(true, FEET, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, true, heights(PIGLIN, 1, 2, 3)));
        c.step(tick(NOW + 31, 20, pack()));
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        for (long now = NOW + 140; now < NOW + 400; now += 10) {
            c.step(tick(now, 20, pack()));
            assertTrue(!c.perching());
        }
    }

    @Test
    public void oneRePerchNearTheLastOneThenTheOldRules() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
        long now = NOW;
        for (int round = 0; round < 3; round++) {
            Event start = perchStart(c, now);
            if (round < 2) {
                assertEquals("round " + round, Event.PERCH_START, start);
                c.perchFacts(on(FEET + 3, heights(PIGLIN, 1, 2, 3)));
                // everybody leaves and stays gone until the perch ends
                long t = now + 1;
                while (c.mode() != Mode.NONE) c.step(tick(t++, 20));
                c.perchFacts(facts(heights(PIGLIN, 1, 2, 3)));
                now = t + 200;
            } else {
                assertTrue(!c.perching());
                assertTrue(start != Event.PERCH_START);
            }
        }
    }

    // ---- hit memory across a chase

    // three on us at 11.5 hp after a 3.9 hit. at the cap the line sees 11.5 alone, so the hit has to be remembered
    private static CombatCommit hitRunAtTheCap(Foe... foes) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 15.4f, foes)));
        for (long dt = 1; dt < CombatCommit.RUN_CAP; dt++) {
            double x = (dt / 10) % 2 == 0 ? 0 : 2;
            float hp = dt >= 50 ? 11.5f : 15.4f;
            assertEquals("tick " + dt, Event.NONE, c.step(new Tick(NOW + dt, hp, x, 0, List.of(foes), null)));
        }
        return c;
    }

    @Test
    public void theCapDoesNotTurnRoundOnTheSameChasersAfterAHeavyHit() {
        Foe[] crowd = {plain(1, 2), plain(2, 2.4), plain(3, 2.8)};
        CombatCommit c = hitRunAtTheCap(crowd);
        assertEquals(Event.RUN_START, c.step(tick(NOW + CombatCommit.RUN_CAP, 11.5f, crowd)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aFreshBunchOfChasersHasNotHurtUsYet() {
        Foe[] crowd = {plain(1, 2), plain(2, 2.4), plain(3, 2.8)};
        CombatCommit c = hitRunAtTheCap(crowd);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CombatCommit.RUN_CAP, 11.5f, plain(7, 2), plain(8, 2.4), plain(9, 2.8))));
    }

    @Test
    public void nobodyInTheFoeListButAnAngryOneBelowKeepsItUp() {
        CombatCommit c = perched();
        top(c, NOW + 1);
        // the foe list lost it (it cannot reach us), the world still counts it within six
        c.perchFacts(new PerchRules.Facts(true, FEET + 3, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights(PIGLIN, 1, 2, 3), 1, 1, false, false));
        for (long now = NOW + 2; now < NOW + 600; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20)));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aCreeperNearWillNotPerchAndEndsOneInProgress() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(new PerchRules.Facts(true, FEET, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights(PIGLIN, 1, 2, 3), -1, -1, true, false));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, pack())));
        CombatCommit d = perched();
        top(d, NOW + 1);
        d.perchFacts(new PerchRules.Facts(true, FEET + 3, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights(PIGLIN, 1, 2, 3), -1, -1, true, true));
        Event e = d.step(tick(NOW + 5, 20, pack()));
        assertEquals(Event.PERCH_END, e == Event.PERCH_END ? e : d.endedFirst());
        assertTrue(d.takePerchNote().startsWith("perch fail:"));
    }

    // a fast piglin on us that just hit us is no room to build a pillar
    @Test
    public void aFightThatWouldBailWithAFastMobOnUsHasNoRoomToPerch() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(facts(heights(PIGLIN, 1)));
        Foe hit = piglin(1, 2, 5);
        assertEquals(Event.FIGHT_START, c.step(new Tick(NOW, 20, 0.5, 0.5, List.of(hit), hit)));
        // it is on us and just hit us: no room to build, the old rules
        assertTrue(c.step(new Tick(NOW + 1, 7, 0.5, 0.5, List.of(piglin(1, 2, 6)), piglin(1, 2, 6))) != Event.PERCH_START);
        assertTrue(c.why() != Why.PERCH);
    }

    @Test
    public void chasersThatJoinMidRunShareTheHitMemory() {
        CombatCommit c = new CombatCommit();
        Foe[] first = {plain(1, 2), plain(2, 2.4), plain(3, 2.8)};
        Foe[] second = {plain(4, 2), plain(5, 2.4), plain(6, 2.8)};
        assertEquals(Event.RUN_START, c.step(tick(NOW, 15.4f, first)));
        for (long dt = 1; dt < CombatCommit.RUN_CAP; dt++) {
            double x = (dt / 10) % 2 == 0 ? 0 : 2;
            float hp = dt >= 50 ? 11.5f : 15.4f;
            Foe[] foes = dt < 10 ? first : second;
            assertEquals("tick " + dt, Event.NONE, c.step(new Tick(NOW + dt, hp, x, 0, List.of(foes), null)));
        }
        assertEquals(Event.RUN_START, c.step(tick(NOW + CombatCommit.RUN_CAP, 11.5f, second)));
        assertEquals(Why.LOW_HP, c.why());
    }
}
