package adris.altoclef.util.helpers;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

// test slijmpost (slime_post): the post 28 across from the nearest sighting within 128, at its height, toward the buddy
public class SlimePostTest {

    @Test
    public void thePostStandsTwentyEightAcrossTowardTheBuddy() {
        assertArrayEquals(new int[]{28, -20, 0}, SlimePost.post(new int[]{0, -20, 0}, 100, 0));
        assertArrayEquals(new int[]{0, 5, -28}, SlimePost.post(new int[]{0, 5, 0}, 0, -60));
        // the buddy on top of the sighting: east of it
        assertArrayEquals(new int[]{28, 12, 0}, SlimePost.post(new int[]{0, 12, 0}, 0.2, 0.3));
    }

    @Test
    public void theNearestSightingWithinReachIsTaken() {
        List<int[]> seen = List.of(new int[]{100, -10, 0}, new int[]{0, -30, 60}, new int[]{300, 0, 0});
        assertArrayEquals(new int[]{0, -30, 60}, SlimePost.nearest(seen, 0, 0));
        assertNull(SlimePost.nearest(List.of(new int[]{200, -10, 0}), 0, 0));
        assertNull(SlimePost.nearest(List.of(), 0, 0));
    }
}
