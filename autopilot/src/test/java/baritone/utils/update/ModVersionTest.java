package baritone.utils.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModVersionTest {

    @Test
    public void parsesBothChannels() {
        ModVersion base = ModVersion.parse("1.1.6");
        ModVersion any = ModVersion.parse("1.1.6-anyversion");
        assertNotNull(base);
        assertNotNull(any);
        assertEquals("", base.channel());
        assertEquals("anyversion", any.channel());
        assertEquals("1.1.6-anyversion", any.raw());
        assertFalse(base.sameChannel(any));
    }

    @Test
    public void unknownSuffixesAreNotTheBaseChannel() {
        // a beta must never be offered the base channel's update because its numbers parse
        assertNull(ModVersion.parse("1.1.6-beta"));
        assertNull(ModVersion.parse("1.1.6-12-gabc123"));
        assertNull(ModVersion.parse("1.1.6-dirty"));
        assertNull(ModVersion.parse("1.1.6-anyversion-beta"));
        assertNull(ModVersion.parse("v1.1.6"));
        assertNull(ModVersion.parse("unknown"));
        assertNull(ModVersion.parse(""));
        assertNull(ModVersion.parse(null));
        assertNull(ModVersion.parse("99999999999.0"));
    }

    @Test
    public void comparesNumbersNotStrings() {
        assertTrue(ModVersion.parse("1.1.10").newerThan(ModVersion.parse("1.1.9")));
        assertTrue(ModVersion.parse("1.2.0").newerThan(ModVersion.parse("1.1.6")));
        assertFalse(ModVersion.parse("1.1.6").newerThan(ModVersion.parse("1.1.6")));
        assertEquals(0, ModVersion.parse("1.1").compareTo(ModVersion.parse("1.1.0")));
        assertEquals(ModVersion.parse("1.1"), ModVersion.parse("1.1.0"));
        assertEquals(ModVersion.parse("1.1").hashCode(), ModVersion.parse("1.1.0").hashCode());
        assertTrue(ModVersion.parse("1.1.6.1").newerThan(ModVersion.parse("1.1.6")));
    }

    @Test
    public void nightliesParse() {
        ModVersion n = ModVersion.parse("1.1.7-nightly.1");
        ModVersion na = ModVersion.parse("1.1.7-nightly.12-anyversion");
        assertNotNull(n);
        assertNotNull(na);
        assertTrue(n.isNightly());
        assertEquals("", n.channel());
        assertTrue(na.isNightly());
        assertEquals("anyversion", na.channel());
        assertEquals("1.1.7-nightly.12-anyversion", na.raw());
        assertFalse(ModVersion.parse("1.1.7").isNightly());
        assertFalse(ModVersion.parse("1.1.7-anyversion").isNightly());
        assertTrue(n.sameTrack(ModVersion.parse("1.1.6-nightly.4")));
        assertFalse(n.sameTrack(ModVersion.parse("1.1.7")));
        assertFalse(n.sameChannel(na));
    }

    @Test
    public void brokenNightliesAreNull() {
        // anyversion goes last, release.yml strips it off the end
        assertNull(ModVersion.parse("1.1.7-anyversion-nightly.1"));
        assertNull(ModVersion.parse("1.1.7-nightly"));
        assertNull(ModVersion.parse("1.1.7-nightly."));
        assertNull(ModVersion.parse("1.1.7-nightly.x"));
        assertNull(ModVersion.parse("1.1.7-nightly.1.2"));
        assertNull(ModVersion.parse("1.1.7-nightly-1"));
        assertNull(ModVersion.parse("1.1.7-nightly.0"));
        assertNull(ModVersion.parse("1.1.7-Nightly.1"));
        assertNull(ModVersion.parse("1.1.7-nightly.99999999999"));
        assertNull(ModVersion.parse("1.1.7-nightly.1-beta"));
    }

    @Test
    public void stableBeatsItsNightlies() {
        ModVersion stable = ModVersion.parse("1.1.7");
        ModVersion n3 = ModVersion.parse("1.1.7-nightly.3");
        ModVersion n2 = ModVersion.parse("1.1.7-nightly.2");
        ModVersion old = ModVersion.parse("1.1.6");
        assertTrue(stable.newerThan(n3));
        assertTrue(n3.newerThan(n2));
        assertTrue(n2.newerThan(old));
        assertTrue(ModVersion.parse("1.1.7-nightly.10").newerThan(ModVersion.parse("1.1.7-nightly.9")));
        assertTrue(ModVersion.parse("1.1.8-nightly.1").newerThan(stable));
        assertFalse(n2.newerThan(n2));
        // channel still isn't part of the order
        assertEquals(0, n2.compareTo(ModVersion.parse("1.1.7-nightly.2-anyversion")));
    }

    @Test
    public void aNightlyIsNotItsStable() {
        assertNotEquals(ModVersion.parse("1.1.7"), ModVersion.parse("1.1.7-nightly.1"));
        assertNotEquals(ModVersion.parse("1.1.7-nightly.1"), ModVersion.parse("1.1.7-nightly.2"));
        assertNotEquals(ModVersion.parse("1.1.7-nightly.1"), ModVersion.parse("1.1.7-nightly.1-anyversion"));
        assertEquals(ModVersion.parse("1.1.7-nightly.1"), ModVersion.parse("1.1.7.0-nightly.1"));
        assertEquals(ModVersion.parse("1.1.7-nightly.1").hashCode(), ModVersion.parse("1.1.7.0-nightly.1").hashCode());
        assertNotEquals(ModVersion.parse("1.1.7").hashCode(), ModVersion.parse("1.1.7-nightly.1").hashCode());
    }
}
