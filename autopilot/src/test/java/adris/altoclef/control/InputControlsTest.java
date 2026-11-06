package adris.altoclef.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.utils.input.Input;
import org.junit.Before;
import org.junit.Test;

public class InputControlsTest {
    private FakeKeys keys;
    private InputControls controls;

    @Before
    public void setUp() {
        keys = new FakeKeys();
        controls = new InputControls(keys);
    }

    @Test
    public void aHoldStaysUntilReleased() {
        controls.hold(Input.SNEAK);
        controls.onTickPre();
        controls.onTickPost();
        assertTrue(controls.isHeldDown(Input.SNEAK));
        controls.release(Input.SNEAK);
        assertFalse(controls.isHeldDown(Input.SNEAK));
    }

    @Test
    public void aFreshHoldClicksOnceAndAKeptHoldDoesNot() {
        controls.hold(Input.MOVE_FORWARD);
        controls.hold(Input.MOVE_FORWARD);
        assertEquals(1, keys.clicks);
    }

    @Test
    public void aTryPressLastsOneTick() {
        controls.tryPress(Input.JUMP);
        assertTrue(controls.isHeldDown(Input.JUMP));
        // a second press in the same tick is swallowed
        controls.tryPress(Input.JUMP);
        assertEquals(1, keys.clicks);
        controls.onTickPre();
        assertFalse(controls.isHeldDown(Input.JUMP));
    }

    @Test
    public void aHeldLeftClickWaitsWhileAnEntityIsUnderTheCrosshair() {
        keys.entityUnderCrosshair = true;
        controls.hold(Input.CLICK_LEFT);
        assertFalse(controls.isHeldDown(Input.CLICK_LEFT));
        keys.entityUnderCrosshair = false;
        controls.hold(Input.CLICK_LEFT);
        assertTrue(controls.isHeldDown(Input.CLICK_LEFT));
    }

    // the aura's shield and the chain's shield on one use key: one letting go must not lift the other's, a shield only
    // blocks after 5 ticks of use and a lift starts that over
    @Test
    public void anOwnedKeyStaysDownWhileAnotherOwnerHoldsIt() {
        Object aura = new Object();
        Object chain = new Object();
        controls.hold(Input.CLICK_RIGHT, aura);
        controls.hold(Input.CLICK_RIGHT, chain);
        controls.releaseOwnedBy(chain);
        assertTrue(controls.isHeldDown(Input.CLICK_RIGHT));
        // and no second click, the use under way just carries on
        assertEquals(1, keys.clicks);
        controls.releaseOwnedBy(aura);
        assertFalse(controls.isHeldDown(Input.CLICK_RIGHT));
    }

    // the food chain's bite is a plain hold: a shield letting go leaves it be
    @Test
    public void anOwnerLettingGoLeavesAPlainHoldDown() {
        Object aura = new Object();
        controls.hold(Input.CLICK_RIGHT, aura);
        controls.hold(Input.CLICK_RIGHT, null);
        controls.releaseOwnedBy(aura);
        assertTrue(controls.isHeldDown(Input.CLICK_RIGHT));
    }

    // the magma sneak lets go on its own name and the shield's sneak stays down
    @Test
    public void aSneakOwnerLettingGoLeavesTheShieldsSneak() {
        Object magma = new Object();
        Object shield = new Object();
        controls.hold(Input.SNEAK, shield);
        controls.hold(Input.SNEAK, magma);
        assertTrue(controls.isHeldBy(Input.SNEAK, magma));
        controls.releaseOwnedBy(magma);
        assertFalse(controls.isHeldBy(Input.SNEAK, magma));
        assertTrue(controls.isHeldBy(Input.SNEAK, shield));
        assertTrue(controls.isHeldDown(Input.SNEAK));
    }

    // mining tasks release sneak by hand every tick: a pinned sneak rides it out and only the unpin lifts it
    @Test
    public void aPinnedKeySurvivesAPlainRelease() {
        Object magma = new Object();
        controls.pin(Input.SNEAK, magma);
        controls.release(Input.SNEAK);
        assertTrue(controls.isHeldDown(Input.SNEAK));
        assertTrue(controls.isHeldBy(Input.SNEAK, magma));
        controls.unpin(Input.SNEAK, magma);
        assertFalse(controls.isHeldDown(Input.SNEAK));
        // and a plain release works as it always did again
        controls.hold(Input.SNEAK);
        controls.release(Input.SNEAK);
        assertFalse(controls.isHeldDown(Input.SNEAK));
    }

    @Test
    public void unpinningLeavesAnotherOwnersSneak() {
        Object magma = new Object();
        Object shield = new Object();
        controls.hold(Input.SNEAK, shield);
        controls.pin(Input.SNEAK, magma);
        controls.unpin(Input.SNEAK, magma);
        assertTrue(controls.isHeldDown(Input.SNEAK));
        assertTrue(controls.isHeldBy(Input.SNEAK, shield));
    }

    // and the food chain's plain release still wins over every shield, eating needs the use key
    @Test
    public void aPlainReleaseLiftsEveryOwner() {
        controls.hold(Input.CLICK_RIGHT, new Object());
        controls.hold(Input.CLICK_RIGHT, new Object());
        controls.release(Input.CLICK_RIGHT);
        assertFalse(controls.isHeldDown(Input.CLICK_RIGHT));
    }

    // the edge brake pins sneak on its own name: letting go must not lift the magma sneak's pin
    @Test
    public void twoPinnersShareASneakAndTheLastOneLiftsIt() {
        Object magma = new Object();
        Object brake = new Object();
        controls.pin(Input.SNEAK, magma);
        controls.pin(Input.SNEAK, brake);
        controls.unpin(Input.SNEAK, brake);
        assertFalse(controls.isPinnedBy(Input.SNEAK, brake));
        assertTrue(controls.isPinnedBy(Input.SNEAK, magma));
        controls.release(Input.SNEAK);
        assertTrue(controls.isHeldDown(Input.SNEAK));
        controls.unpin(Input.SNEAK, magma);
        assertFalse(controls.isHeldDown(Input.SNEAK));
    }

    // a screen lets every key up, the next pin's fresh hold forgets the magma claim. the magma pin still has to hold it
    @Test
    public void aPinSurvivesAnotherPinnerAfterTheGameLetTheKeyUp() {
        Object magma = new Object();
        Object brake = new Object();
        controls.pin(Input.SNEAK, magma);
        keys.setDown(Input.SNEAK, false);
        controls.pin(Input.SNEAK, brake);
        controls.unpin(Input.SNEAK, brake);
        assertTrue(controls.isHeldDown(Input.SNEAK));
        controls.unpin(Input.SNEAK, magma);
        assertFalse(controls.isHeldDown(Input.SNEAK));
    }
}
