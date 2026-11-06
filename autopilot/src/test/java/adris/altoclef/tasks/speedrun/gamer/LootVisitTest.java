package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// the log line for a chest visit, so "why did it leave" has an answer
public class LootVisitTest {
    @Test
    public void whyCoversEveryWayOut() {
        assertEquals("emptied", LootVisit.why(true, false, true));
        assertEquals("nothing wanted", LootVisit.why(true, false, false));
        assertEquals("timer", LootVisit.why(false, true, true));
        assertEquals("stopped", LootVisit.why(false, false, false));
    }

    @Test
    public void summaryIsSortedAndCounted() {
        Map<String, Integer> taken = new HashMap<>();
        taken.put("obsidian", 4);
        taken.put("iron_ingot", 3);
        assertEquals("3x iron_ingot, 4x obsidian", LootVisit.summary(taken));
        assertEquals("nothing", LootVisit.summary(new HashMap<>()));
    }

    @Test
    public void lineHasPosAndReason() {
        Map<String, Integer> taken = new HashMap<>();
        taken.put("flint", 1);
        assertEquals("ruined portal chest 225 65 114 done (emptied), took 1x flint",
                LootVisit.line("ruined portal", 225, 65, 114, "emptied", taken));
    }
}
