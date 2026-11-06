package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.tasks.construction.schematic.CountStep.Next;
import org.junit.Test;

import static adris.altoclef.tasks.construction.schematic.CountStep.next;
import static org.junit.Assert.assertEquals;

public class CountStepTest {

    // args: ended, restEmpty, stuck, bagFull, nothingToGet, fromPause

    @Test
    public void theFirstCountGathersOrBuilds() {
        assertEquals(Next.GATHER, next(false, false, false, false, false, false));
        assertEquals(Next.BUILD, next(false, false, false, false, true, false));
        // the file is already built: the builder gets it, says done, and the count after that ends it
        assertEquals(Next.BUILD, next(false, true, false, false, true, false));
    }

    @Test
    public void doneWhenTheBuilderLetGoAndOnlySkippedIsLeft() {
        assertEquals(Next.DONE, next(true, true, false, false, true, false));
        // done beats stuck and a full bag
        assertEquals(Next.DONE, next(true, true, true, true, true, false));
    }

    @Test
    public void letGoWithWorkWeCanFetchIsARound() {
        assertEquals(Next.GATHER, next(true, false, false, false, false, false));
        assertEquals(Next.STUCK, next(true, false, true, false, false, false));
    }

    @Test
    public void letGoWithNothingToFetchTrustsTheBuilder() {
        // the count sees work the builder doesn't: its settings call those cells done. the builder wins, not "stuck"
        assertEquals(Next.DONE_BUT_DIFFERS, next(true, false, false, false, true, false));
        assertEquals(Next.DONE_BUT_DIFFERS, next(true, false, true, false, true, false));
        // a full bag is something to fetch that doesn't fit, that's still a full bag
        assertEquals(Next.BAG_FULL, next(true, false, false, true, true, false));
    }

    @Test
    public void stuckBeatsEverythingButDone() {
        assertEquals(Next.STUCK, next(false, false, true, true, false, true));
        assertEquals(Next.STUCK, next(false, false, true, false, true, false));
    }

    @Test
    public void aFullBag() {
        assertEquals(Next.BAG_FULL, next(false, false, false, true, true, true));
    }

    @Test
    public void aPauseForThingsWeHave() {
        assertEquals(Next.CANT_PLACE, next(false, false, false, false, true, true));
        assertEquals(Next.GATHER, next(false, false, false, false, false, true));
    }
}
