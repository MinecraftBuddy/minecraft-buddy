package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class AuraPickTest {

    // {id, squared distance}
    private static List<int[]> two() {
        List<int[]> mobs = new ArrayList<>();
        mobs.add(new int[]{1, 4});
        mobs.add(new int[]{2, 9});
        return mobs;
    }

    @Test
    public void theFightTargetInReachBeatsTheNearest() {
        List<int[]> mobs = two();
        assertSame(mobs.get(1), AuraPick.pick(mobs, m -> m[0], 2, m -> true, m -> m[1]));
    }

    @Test
    public void outOfReachNoFightOrAMissingTargetIsTheNearest() {
        List<int[]> mobs = two();
        assertSame(mobs.get(0), AuraPick.pick(mobs, m -> m[0], 2, m -> m[0] != 2, m -> m[1]));
        assertSame(mobs.get(0), AuraPick.pick(mobs, m -> m[0], -1, m -> true, m -> m[1]));
        assertSame(mobs.get(0), AuraPick.pick(mobs, m -> m[0], 7, m -> true, m -> m[1]));
    }

    @Test
    public void noTargetsIsNull() {
        assertNull(AuraPick.pick(new ArrayList<int[]>(), m -> m[0], 2, m -> true, m -> m[1]));
    }
}
