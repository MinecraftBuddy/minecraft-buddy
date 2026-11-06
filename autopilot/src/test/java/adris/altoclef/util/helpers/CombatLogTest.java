package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import org.junit.Test;

// one `combat:` line per transition, and the dimension is in it. the rest of the line reads the same in all three
public class CombatLogTest {

    private static String line(Dimension d, Event event, Why why) {
        return CombatLog.line(d, event, why, false, "zombie", "zombie", "2 zombies, 1 skeleton", 14, 52);
    }

    @Test
    public void everyTransitionSaysWhichDimensionItWasIn() {
        for (Dimension d : Dimension.values()) {
            for (Event event : Event.values()) {
                if (event == Event.NONE) continue;
                String text = line(d, event, Why.HIT);
                assertTrue(event + " in " + d + ": " + text, text.startsWith("combat: [" + d.name().toLowerCase() + "] "));
            }
        }
    }

    @Test
    public void noTransitionNoLine() {
        for (Dimension d : Dimension.values()) {
            assertNull(line(d, Event.NONE, Why.NONE));
        }
    }

    @Test
    public void everyLineIsOneLine() {
        for (Dimension d : Dimension.values()) {
            for (Event event : Event.values()) {
                if (event == Event.NONE) continue;
                for (Why why : Why.values()) {
                    String text = line(d, event, why);
                    assertFalse(text, text.contains("\n"));
                    assertFalse(text, text.contains("null"));
                }
            }
        }
    }

    @Test
    public void theFightLinesNameTheTargetAndTheHp() {
        assertEquals("combat: [nether] FIGHT zombie (hit us, hp 14)", line(Dimension.NETHER, Event.FIGHT_START, Why.HIT));
        assertEquals("combat: [end] target dead, next zombie (hit us, hp 14)", line(Dimension.END, Event.FIGHT_NEXT, Why.HIT));
        assertEquals("combat: [end] cornered, next zombie",
                CombatLog.line(Dimension.END, Event.FIGHT_NEXT, Why.CORNERED, true, "zombie", "zombie", "", 14, 0));
        assertEquals("combat: [nether] cornered, fighting zombie", line(Dimension.NETHER, Event.RUN_TO_FIGHT, Why.CORNERED));
    }

    @Test
    public void theRunLinesNameTheCrowdAndTheReason() {
        assertEquals("combat: [nether] RUN from 2 zombies, 1 skeleton (hp 14) [LOW_HP]", line(Dimension.NETHER, Event.RUN_START, Why.LOW_HP));
        assertEquals("combat: [nether] RUN from 2 zombies, 1 skeleton (something nasty close, hp 14) [HEAVY]", line(Dimension.NETHER, Event.RUN_START, Why.HEAVY));
        assertEquals("combat: [overworld] FIGHT -> RUN from 2 zombies, 1 skeleton (hp 14) [LOW_HP]", line(Dimension.OVERWORLD, Event.FIGHT_TO_RUN, Why.LOW_HP));
    }

    @Test
    public void theNewRunReasonsSayWhy() {
        assertEquals("combat: [overworld] RUN from 2 zombies, 1 skeleton (a crowd on us, hp 14) [CROWD]", line(Dimension.OVERWORLD, Event.RUN_START, Why.CROWD));
        assertEquals("combat: [overworld] RUN from 2 zombies, 1 skeleton (it hit us and we have nothing to hit back with, hp 14) [UNARMED]",
                line(Dimension.OVERWORLD, Event.RUN_START, Why.UNARMED));
        assertEquals("combat: [overworld] FIGHT -> RUN from 2 zombies, 1 skeleton (a crowd on us, hp 14) [CROWD]",
                line(Dimension.OVERWORLD, Event.FIGHT_TO_RUN, Why.CROWD));
    }

    @Test
    public void aStanceChangeSaysFromToAndWhy() {
        assertEquals("combat: [overworld] stance CALM -> FLEE (running, nearest 6 blocks, too close to eat, hp 7)",
                CombatLog.stance(Dimension.OVERWORLD, CombatRules.Stance.CALM, CombatRules.Stance.FLEE,
                        "running, nearest 6 blocks, too close to eat", 7));
    }

    @Test
    public void aMagmaHitSaysWhatShiftWasDoing() {
        assertEquals("combat: [nether] hurt 0.4 (hotFloor) shift=false onGround=true y=83.00 input=KeyboardInput",
                CombatLog.hurt(Dimension.NETHER, 0.4f, null, "hotFloor") + CombatLog.hotFloor(false, true, 83, "KeyboardInput"));
    }

    @Test
    public void aHitSaysWhoAndHowWhenTheClientKnows() {
        assertEquals("combat: [overworld] hurt 4.0 by skeleton (arrow)", CombatLog.hurt(Dimension.OVERWORLD, 4f, "skeleton", "arrow"));
        assertEquals("combat: [nether] hurt 2.5 (fall)", CombatLog.hurt(Dimension.NETHER, 2.5f, null, "fall"));
        assertEquals("combat: [end] hurt 1.0 by zombie", CombatLog.hurt(Dimension.END, 1f, "zombie", null));
        assertEquals("combat: [end] hurt 1.0 (source unknown)", CombatLog.hurt(Dimension.END, 1f, null, null));
    }

    @Test
    public void theEndingLinesSayHowItEnded() {
        assertEquals("combat: [overworld] fight over, zombie dead", line(Dimension.OVERWORLD, Event.FIGHT_DEAD, Why.NONE));
        assertEquals("combat: [overworld] fight over, lost track of zombie", line(Dimension.OVERWORLD, Event.FIGHT_LOST, Why.NONE));
        assertEquals("combat: [overworld] fight over, can't get to zombie, ignoring it until it hits us again",
                line(Dimension.OVERWORLD, Event.FIGHT_STALLED, Why.NONE));
        assertEquals("combat: [nether] run over, 52 blocks, clear", line(Dimension.NETHER, Event.RUN_CLEAR, Why.NONE));
        assertEquals("combat: [nether] run over, 25 s cap, 52 blocks", line(Dimension.NETHER, Event.RUN_CAP, Why.NONE));
        assertEquals("combat: [end] run over, stuck 52 blocks from where it started", line(Dimension.END, Event.RUN_STUCK, Why.NONE));
    }

    @Test
    public void aChasedRunSaysItKeptGoingAndThenWhoItTurnedOn() {
        assertEquals("combat: [overworld] chased too long, turning to fight zombie [CHASED]",
                line(Dimension.OVERWORLD, Event.RUN_CHASED_FIGHT, Why.CHASED));
        assertEquals("combat: [overworld] still chased, next zombie",
                CombatLog.line(Dimension.OVERWORLD, Event.FIGHT_NEXT, Why.CHASED, true, "zombie", "zombie", "", 14, 0));
    }

    @Test
    public void theCornerLinesSayWhichCorner() {
        assertEquals("combat: [nether] cornered, next magma_cube [fast]",
                CombatLog.line(Dimension.NETHER, Event.FIGHT_NEXT, Why.CORNERED, true, false, "fast", "magma_cube", "magma_cube", "", 14, 0));
        assertEquals("combat: [nether] cornered, fighting zombie [box]",
                CombatLog.line(Dimension.NETHER, Event.RUN_TO_FIGHT, Why.CORNERED, true, false, "box", "zombie", "zombie", "", 14, 0));
        assertEquals("combat: [nether] cornered, next zombie",
                CombatLog.line(Dimension.NETHER, Event.FIGHT_NEXT, Why.CORNERED, true, false, null, "zombie", "zombie", "", 14, 0));
    }
}
