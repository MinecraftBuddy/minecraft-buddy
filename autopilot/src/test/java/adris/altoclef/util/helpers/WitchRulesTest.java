package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// (witch_cover, witch_melee_reach, poison_eat) the witch's rules: when the cover is wanted, when a witch in reach is a melee foe,
// when a witch keeps a meal shut, the poisoned golden apple; and the meter's witch row, a record that leaves StandModel's rows
public class WitchRulesTest {

    @Test
    public void aWitchsHitOrThePoisonWithAWitchWithinSixteenWantsTheCover() {
        assertTrue(WitchRules.coverWanted(true, 20, true, false, 9));
        assertTrue(WitchRules.coverWanted(true, 20, false, true, 16));
        // neither a hit nor the poison: a witch about is no reason
        assertFalse(WitchRules.coverWanted(true, 12, false, false, 6));
        // the witch too far, the nether, dead
        assertFalse(WitchRules.coverWanted(true, 12, true, true, 16.5));
        assertFalse(WitchRules.coverWanted(false, 12, true, true, 6));
        assertFalse(WitchRules.coverWanted(true, 0, true, true, 6));
    }

    @Test
    public void aWitchInReachIsAMeleeFoeWithABladeAndNoOtherShootersLine() {
        assertTrue(WitchRules.melee(true, true, true, false, false));
        // another shooter with a line: the 13 Nov fight among skeletons, no melee
        assertFalse(WitchRules.melee(true, true, true, true, false));
        // no sword or axe: no fight picked
        assertFalse(WitchRules.melee(true, true, false, false, false));
        // out of reach, or the key off: never
        assertFalse(WitchRules.melee(true, false, true, false, true));
        assertFalse(WitchRules.melee(false, true, true, false, true));
    }

    @Test
    public void stuckAnyHandSwingsAtAWitchInReach() {
        assertTrue(WitchRules.melee(true, true, false, false, true));
        assertTrue(WitchRules.melee(true, true, false, true, true));
    }

    @Test
    public void poisonedOnlyAWitchCloseWithALineKeepsAMealShut() {
        assertFalse(WitchRules.keepsMealShut(true, true, 12, true));
        assertFalse(WitchRules.keepsMealShut(true, true, WitchRules.EAT_CLOSE, true));
        assertFalse(WitchRules.keepsMealShut(true, true, 3, false));
        assertTrue(WitchRules.keepsMealShut(true, true, 4.9, true));
        // no poison on, or the key off: every witch as before
        assertTrue(WitchRules.keepsMealShut(true, false, 12, true));
        assertTrue(WitchRules.keepsMealShut(false, true, 12, true));
    }

    @Test
    public void theGoldenAppleUnderTenHpWhilePoisoned() {
        assertTrue(WitchRules.gapple(true, true, 9.9f, true, true));
        assertFalse(WitchRules.gapple(true, true, 10, true, true));
        assertFalse(WitchRules.gapple(true, false, 4, true, true));
        assertFalse(WitchRules.gapple(true, true, 4, false, true));
        assertFalse(WitchRules.gapple(true, true, 4, true, false));
        assertFalse(WitchRules.gapple(false, true, 4, true, true));
    }

    // the meter's row: a shooter within 10 with a line, never in melee; kept out of the rows StandModel and StandPick read
    @Test
    public void theMeterHasAWitchRowApartFromStandModels() {
        MobThreat row = MobThreat.forMeter("witch", 1);
        assertNotNull(row);
        assertTrue(row.shot() > 0);
        assertEquals(10, row.range(), 1e-9);
        assertEquals(0, row.melee(), 1e-9);
        assertFalse(row.flies());
        assertNull(MobThreat.of("witch", 1));
        assertFalse(MobThreat.known("witch"));
    }

    // a witch 6 off that sees the buddy is no "body that hurts nobody": it counts in the next three seconds' loss
    @Test
    public void aWitchWithALineCountsInTheMetersLoss() {
        DangerMeter.Us us = new DangerMeter.Us(20, 9.6, 15, 0, 2, 0, false);
        MobThreat row = MobThreat.forMeter("witch", 1);
        DangerMeter.Mob seeing = new DangerMeter.Mob("witch", row, 6, 0, 0, 1, true, true, 26);
        DangerMeter.Mob blind = new DangerMeter.Mob("witch", row, 6, 0, 0, 1, true, false, 26);
        assertTrue(DangerMeter.damage3(seeing, us, 6) > 0);
        assertEquals(0, DangerMeter.damage3(blind, us, 6), 1e-9);
        assertTrue(DangerMeter.read(us, List.of(seeing), List.of()).t() > 0);
    }
}
