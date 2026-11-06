package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;

import adris.altoclef.tasks.movement.WanderGiveUp.Stage;
import org.junit.Test;

public class WanderGiveUpTest {
    @Test
    public void exactlyFortyFiveSecondsIsStillOk() {
        assertEquals(Stage.OK, WanderGiveUp.decide(45, 0));
    }

    @Test
    public void justOverFortyFiveSecondsFails() {
        assertEquals(Stage.FAILING, WanderGiveUp.decide(45.05, 0));
    }

    @Test
    public void youngWanderIsOkNoMatterHowLittleItMoved() {
        assertEquals(Stage.OK, WanderGiveUp.decide(0, 0));
        assertEquals(Stage.OK, WanderGiveUp.decide(10, 0.5));
    }

    @Test
    public void threeBlocksOutIsOkForGood() {
        assertEquals(Stage.OK, WanderGiveUp.decide(500, 3));
        assertEquals(Stage.OK, WanderGiveUp.decide(500, 40));
    }

    @Test
    public void justUnderThreeBlocksStillFails() {
        assertEquals(Stage.FAILING, WanderGiveUp.decide(46, 2.99));
    }

    @Test
    public void anExcursionMakesItOkAfterItWasFailing() {
        // the caller keeps a running max, so the second call is what a wander sees after one trip out
        assertEquals(Stage.FAILING, WanderGiveUp.decide(60, 1));
        assertEquals(Stage.OK, WanderGiveUp.decide(61, 3.1));
        assertEquals(Stage.OK, WanderGiveUp.decide(600, 3.1));
    }
}
