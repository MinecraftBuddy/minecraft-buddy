package baritone.behavior;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the guess in pathStart that says we left the block under us and are about to land a block lower
public class MidJumpTest {

    @Test
    public void offTheGroundAboveSomethingSolidIsAJump() {
        assertTrue(PathingBehavior.midJump(false, false, true));
    }

    // sneaking on a ladder rests with onGround false, so this one would fire every tick and start the path a block down
    @Test
    public void aLadderIsNotAJump() {
        assertFalse(PathingBehavior.midJump(false, true, true));
    }

    @Test
    public void onTheGroundOrWithNothingToLandOnItIsNot() {
        assertFalse(PathingBehavior.midJump(true, false, true));
        assertFalse(PathingBehavior.midJump(false, false, false));
    }
}
