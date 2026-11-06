package adris.altoclef.trace;

import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.UserTaskChain;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinesTest {

    @Test
    public void theChainSwitchLine() {
        assertEquals("switch chain from=MobDefenseChain:9.0 to=UserTaskChain:12.5 next=FoodChain:8.0",
                Lines.chainSwitch("MobDefenseChain", 9.0f, "UserTaskChain", 12.5f, "FoodChain", 8.0f));
    }

    @Test
    public void nobodyIsNoneAndAnInactiveLoserIsOff() {
        assertEquals("switch chain from=none to=UserTaskChain:50.0 next=none",
                Lines.chainSwitch(null, Float.NaN, "UserTaskChain", 50f, null, Float.NEGATIVE_INFINITY));
        assertEquals("switch chain from=FoodChain:off to=none next=none",
                Lines.chainSwitch("FoodChain", Float.NaN, null, Float.NEGATIVE_INFINITY, null, 0f));
    }

    @Test
    public void realChainClassNamesHaveNoSpacesInThem() {
        // getName() on these says "User Tasks" and "Mob Defense", which would split the key=value
        assertFalse(UserTaskChain.class.getSimpleName().contains(" "));
        assertFalse(MobDefenseChain.class.getSimpleName().contains(" "));
        assertEquals("UserTaskChain", UserTaskChain.class.getSimpleName());
    }

    @Test
    public void theRepeatLine() {
        assertEquals("repeat: CollectIronTask \"mining\" x5 in 60 s", Lines.repeat("CollectIronTask", "mining", 5));
    }

    @Test
    public void theStormLine() {
        assertEquals("MONITOR: start storm ClickSlotTask x21 in 60 s", Lines.startStorm("ClickSlotTask"));
    }

    @Test
    public void theStormLineNamesWhoKeptCuttingItOff() {
        assertEquals("MONITOR: start storm DestroyBlockTask x21 in 60 s, mostly cut off by PickupDroppedItemTask",
                Lines.startStorm("DestroyBlockTask", "PickupDroppedItemTask", false));
        assertEquals("MONITOR: start storm GetToEntityTask x21 in 60 s, mostly dropped by parent KillEntityTask",
                Lines.startStorm("GetToEntityTask", "KillEntityTask", true));
        // nobody to blame is the plain line
        assertEquals(Lines.startStorm("ClickSlotTask"), Lines.startStorm("ClickSlotTask", null, false));
    }

    @Test
    public void theCursorLine() {
        assertEquals("MONITOR: cursor stuck cobblestone x12 for 5 s, no container screen open", Lines.cursorStuck("cobblestone", 12, 5));
    }

    private static Lines.Stand stand(String move, String goal, String task, String last) {
        return new Lines.Stand(41, 10.25, 64, -3.5, move, 3, 17, goal, false, "SET_GOAL_AND_PATH", "Custom goal", false,
                "MOVE_FORWARD,SPRINT", "FORWARD", false, null, null, true, false, "none", "UserTaskChain", task, last);
    }

    @Test
    public void theStandLineIsOneRowWithEveryGroup() {
        String move = Lines.move("MovementTraverse", "1,64,2", "2,64,2", "RUNNING", "12/153", "movement");
        String line = Lines.standing(stand(move, "GoalXZ{x=1,z=2}", "<GetToXZTask> walking", Lines.lastDebug("Pausing", 7)));
        assertEquals("MONITOR: standing on a live path 41t at 10.3 64.0 -3.5"
                + " | move=MovementTraverse 1,64,2->2,64,2 state=RUNNING onCurrent=12/153 owner=movement"
                + " | pathPos=3/17 goal=GoalXZ{x=1,z=2}"
                + " | paused=false lastCmd=SET_GOAL_AND_PATH by=Custom goal"
                + " | interactionPaused=false | keys=MOVE_FORWARD,SPRINT held=FORWARD sneak=false | screen=none"
                + " | using=none onGround=true inWater=false | calc=none"
                + " | chain=UserTaskChain task=<GetToXZTask> walking | last=Pausing (7t ago)", line);
    }

    @Test
    public void theStandLineNeverPrintsNullOrBreaksTheRow() {
        Lines.Stand blank = new Lines.Stand(40, 0, 0, 0, null, 0, 0, null, true, null, null, true, null, null, false, null, null, false,
                true, null, null, null, null);
        String line = Lines.standing(blank);
        assertFalse(line.contains("null"));
        assertTrue(line.contains("goal=none"));
        assertTrue(line.endsWith("last=none"));
        String messy = Lines.standing(stand("m", "Goal\nwith\r\nlines", "task\nx", "line one\nline two"));
        assertFalse(messy.contains("\n"));
        assertFalse(messy.contains("\r"));
    }

    @Test
    public void aCrouchShowsUpWhoeverCausedIt() {
        Lines.Stand crouched = new Lines.Stand(41, 0, 64, 0, null, 0, 0, null, false, null, null, false, null, "SNEAK,BACK", true,
                null, null, true, false, null, null, null, null);
        String line = Lines.standing(crouched);
        assertTrue(line.contains(" | keys=none held=SNEAK,BACK sneak=true | "));
        assertTrue(Lines.standing(stand("m", "g", "t", "l")).contains("held=FORWARD sneak=false"));
    }

    @Test
    public void aLongGoalAndTaskAreTrimmed() {
        String longGoal = "G".repeat(200);
        String line = Lines.standing(stand("m", longGoal, "T".repeat(300), "L"));
        assertTrue(line.contains("goal=" + "G".repeat(57) + "..."));
        assertFalse(line.contains("G".repeat(58)));
        assertTrue(line.contains("task=" + "T".repeat(97) + "..."));
        assertFalse(line.contains("T".repeat(98)));
    }

    @Test
    public void theOverLineSaysWhyItEnded() {
        assertEquals("MONITOR: stand over after 130t (moved)", Lines.standOver(130, true));
        assertEquals("MONITOR: stand over after 60t (path gone)", Lines.standOver(60, false));
    }

    @Test
    public void lastDebugCarriesTheAge() {
        assertEquals("Pausing since x (12t ago)", Lines.lastDebug("Pausing since x", 12));
        assertEquals("none", Lines.lastDebug(null, 5));
        assertEquals("none", Lines.lastDebug("", 5));
        assertEquals("a (0t ago)", Lines.lastDebug("a", -4));
    }

    @Test
    public void theStandLineNamesWhoPausedAndWhatPreparedIsStuckOn() {
        Lines.Stand stand = new Lines.Stand(41, 0, 64, 0, "m", 0, 3, null, true, null, null, false, null, null, false, null, null, true, false,
                null, null, null, null, "KillAura.startShielding", "break 1,64,2 Block{minecraft:air} avoid=true reach=true look=false");
        String line = Lines.standing(stand);
        assertTrue(line.contains(" | move=m prepared=break 1,64,2 Block{minecraft:air} avoid=true reach=true look=false | "));
        assertTrue(line.contains(" by=none pausedBy=KillAura.startShielding | "));
    }
}
