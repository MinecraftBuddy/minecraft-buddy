package adris.altoclef.tasks.resources;

import static adris.altoclef.tasks.resources.CropPhase.BREAK;
import static adris.altoclef.tasks.resources.CropPhase.REPLANT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public class CropPhaseTest {
    @Test
    public void breaksWhenThereIsNothingToReplant() {
        assertEquals(BREAK, CropPhase.next(BREAK, true, true, 0, false));
        assertEquals(BREAK, CropPhase.next(REPLANT, true, true, 0, false));
    }

    @Test
    public void breaksWithoutSeedsOrWithReplantingOff() {
        assertEquals(BREAK, CropPhase.next(REPLANT, true, false, 3, true));
        assertEquals(BREAK, CropPhase.next(REPLANT, false, true, 3, true));
    }

    @Test
    public void aHoleInRangeStartsReplanting() {
        assertEquals(REPLANT, CropPhase.next(BREAK, true, true, 1, false));
    }

    @Test
    public void aCommittedHoleKeepsUsReplantingEvenWhenItIsOutOfRange() {
        assertEquals(REPLANT, CropPhase.next(REPLANT, true, true, 0, true));
        // but nothing out of range pulls us out of breaking
        assertEquals(BREAK, CropPhase.next(BREAK, true, true, 0, true));
    }

    // a field of crops, the bot breaks one, the cell opens a few ticks later, replant fills it, then the next crop.
    // the old code changed its mind every tick here
    @Test
    public void harvestReplantNextCropDoesNotFlipFlop() {
        Set<Integer> crops = new TreeSet<>(List.of(1, 2, 3));
        ReplantBook<Integer> book = new ReplantBook<>(200, c -> { });
        CropPhase phase = BREAK;
        List<String> trace = new ArrayList<>();
        Integer breaking = null;
        int breakTicksLeft = 0;
        int plantTicksLeft = 0;
        Set<Integer> air = new TreeSet<>();
        for (int tick = 0; tick < 200 && (!crops.isEmpty() || !book.any()); tick++) {
            // a swing that lands opens the cell
            if (breaking != null && --breakTicksLeft == 0) {
                crops.remove(breaking);
                air.add(breaking);
                book.add(breaking);
                breaking = null;
            }
            book.refresh(tick, air::contains);
            phase = CropPhase.next(phase, true, true, book.near(c -> 0, 100), book.committed());
            Integer cell = phase == REPLANT ? book.pick(tick, c -> 0, 100) : null;
            if (phase == REPLANT && cell == null) {
                phase = BREAK;
            }
            if (phase == REPLANT) {
                trace.add("P" + cell);
                if (plantTicksLeft == 0) {
                    plantTicksLeft = 4;
                }
                if (--plantTicksLeft == 0) {
                    air.remove(cell);
                }
            } else if (!crops.isEmpty()) {
                if (breaking == null) {
                    breaking = crops.iterator().next();
                    breakTicksLeft = 3;
                }
                trace.add("B" + breaking);
            } else {
                break;
            }
            if (crops.isEmpty() && !book.any() && breaking == null) {
                break;
            }
        }
        // every plant run is one unbroken stretch per cell: no cell is revisited after we moved on
        String joined = String.join(",", trace);
        assertFalse(joined, joined.matches(".*P1.*B.*P1.*"));
        assertTrue(joined, joined.contains("P1"));
        assertTrue(joined, joined.contains("P2"));
        assertTrue(joined, joined.contains("P3"));
        // each cell: some breaking, then planting. never back and forth inside a cell's own turn
        for (int cell = 1; cell <= 3; cell++) {
            assertFalse(joined, joined.contains("P" + cell + ",B" + cell + ",P" + cell));
        }
    }
}
