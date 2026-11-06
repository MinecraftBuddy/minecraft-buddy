package adris.altoclef.tasks.construction.schematic;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NetherPromptTest {

    private static final Set<String> NETHER = Set.of("minecraft:brewing_stand", "minecraft:quartz_block");

    @Test
    public void asksAboutWhatIsShortAndFromTheNether() {
        Map<String, Integer> need = new LinkedHashMap<>();
        need.put("minecraft:stone", 300);
        need.put("minecraft:brewing_stand", 1);
        need.put("minecraft:quartz_block", 20);
        Set<String> out = NetherPrompt.shortfall(need, Map.of("minecraft:quartz_block", 4), NETHER::contains);
        // in the bill's order, so the chat line reads like the tab
        assertEquals(List.of("minecraft:brewing_stand", "minecraft:quartz_block"), List.copyOf(out));
    }

    @Test
    public void aBagThatAlreadyHasThemHasNothingToAsk() {
        Map<String, Integer> need = Map.of("minecraft:brewing_stand", 1, "minecraft:stone", 10);
        assertTrue(NetherPrompt.shortfall(need, Map.of("minecraft:brewing_stand", 1), NETHER::contains).isEmpty());
        assertTrue(NetherPrompt.shortfall(Map.of("minecraft:stone", 10), Map.of(), NETHER::contains).isEmpty());
    }

    @Test
    public void theQuestionNamesTheItems() {
        assertEquals("This build requires things in the nether (brewing stand, quartz). Would you like me to go there and get them?",
            NetherPrompt.question("brewing stand, quartz"));
    }

    @Test
    public void theInMemoryOneKeepsTheAnswerAndTheLeftOut() {
        BuildMemory m = BuildMemory.inMemory(Set.of("minecraft:glass"));
        assertEquals(null, m.nether());
        m.netherAnswered(false, Set.of("minecraft:brewing_stand"));
        assertEquals(Boolean.FALSE, m.nether());
        assertEquals(Set.of("minecraft:glass", "minecraft:brewing_stand"), m.excluded());
        m.finished();
        assertEquals(null, m.nether());
    }
}
