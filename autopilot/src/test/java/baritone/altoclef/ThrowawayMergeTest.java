package baritone.altoclef;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ThrowawayMergeTest {
    @Test
    public void mergeKeepsFirstOrderAndDropsDuplicates() {
        List<String> merged = BaritoneSettingsScope.mergeUnique(List.of("dirt", "cobble", "netherrack"),
                List.of("netherrack", "dirt", "soul", "cobble", "end"));
        assertEquals(List.of("dirt", "cobble", "netherrack", "soul", "end"), merged);
    }
}
