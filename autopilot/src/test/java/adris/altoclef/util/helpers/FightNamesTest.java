package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FightNamesTest {

    @Test
    public void aMobNobodyLookedAtIsIt() {
        assertEquals("it", new FightNames().of(7));
    }

    // the bug: one name for the last fight, so a skeleton that died unseen was a zombie
    @Test
    public void eachFightKeepsItsOwnName() {
        FightNames n = new FightNames();
        n.note(7, "zombie");
        n.note(8, "skeleton");
        assertEquals("zombie", n.of(7));
        assertEquals("skeleton", n.of(8));
        assertEquals("it", n.of(9));
    }

    @Test
    public void aMobThatIsAlreadyGoneNeverOverwritesItsName() {
        FightNames n = new FightNames();
        n.note(7, "skeleton");
        n.note(7, "it");
        assertEquals("skeleton", n.of(7));
        n.note(-1, "zombie");
        assertEquals("it", n.of(-1));
    }

    @Test
    public void onlyTheLatestFewAreKept() {
        FightNames n = new FightNames();
        for (int i = 0; i < 50; i++) n.note(i, "mob" + i);
        assertEquals("mob49", n.of(49));
        assertEquals("it", n.of(0));
    }

    @Test
    public void clearForgetsThem() {
        FightNames n = new FightNames();
        n.note(7, "zombie");
        n.clear();
        assertEquals("it", n.of(7));
    }
}
