package adris.altoclef.tasks.construction.compound;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// a lake far above or below us is not one the cast works from
public class LakeYBandTest {

    @Test
    public void aLakeWithinSixteenBlocksCounts() {
        assertTrue(ConstructNetherPortalBucketTask.inYBand(64, 64));
        assertTrue(ConstructNetherPortalBucketTask.inYBand(48, 64));
        assertTrue(ConstructNetherPortalBucketTask.inYBand(80, 64));
    }

    @Test
    public void aLakeSixtyBlocksDownDoesNot() {
        assertFalse(ConstructNetherPortalBucketTask.inYBand(4, 64));
        assertFalse(ConstructNetherPortalBucketTask.inYBand(47, 64));
        assertFalse(ConstructNetherPortalBucketTask.inYBand(81, 64));
    }
}
