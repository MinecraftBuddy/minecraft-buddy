package adris.altoclef.butler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReplyCooldownTest {

    @Test
    public void oneAnswerPerNamePerWindowWhateverTheCase() {
        ReplyCooldown cooldown = new ReplyCooldown(1000, 8);
        assertTrue(cooldown.tryReply("Stranger", 0));
        assertFalse(cooldown.tryReply("Stranger", 10));
        assertFalse(cooldown.tryReply("STRANGER", 999));
        assertTrue(cooldown.tryReply("Stranger", 1000));
        // somebody else is not held back by them
        assertTrue(cooldown.tryReply("other", 1001));
    }

    @Test
    public void aNameSpammerCannotGrowItWithoutEnd() {
        ReplyCooldown cooldown = new ReplyCooldown(1000, 4);
        for (int i = 0; i < 100; i++) {
            assertTrue(cooldown.tryReply("n" + i, i));
        }
        // it forgot the old ones to stay small, which only ever means an extra answer, never a missing one
        assertTrue(cooldown.tryReply("n0", 100));
    }
}
